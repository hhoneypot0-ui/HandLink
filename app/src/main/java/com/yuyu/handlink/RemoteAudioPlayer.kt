package com.yuyu.handlink

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 控制端音频播放：解码 AAC 并播放。
 *
 * 音量跟随本机媒体音量，用户可以自己用音量键调整（不改被控端）。
 * 走 STREAM_MUSIC，所以系统音量条、静音键都生效。
 */
class RemoteAudioPlayer(
    private val sampleRate: Int = AudioCapturer.SAMPLE_RATE,
    private val channels: Int = AudioCapturer.CHANNEL_COUNT,
) {
    companion object {
        private const val TAG = "HandLinkAudio"
    }

    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile private var worker: Thread? = null
    private val queue = ConcurrentLinkedQueue<ByteArray>()
    @Volatile private var config: ByteArray? = null
    // config 到达后置位，由解码线程注入 CODEC_CONFIG 缓冲。
    // MediaCodec 非线程安全，不能在 feed()（WS 线程）里直接操作编码器。
    @Volatile private var pendingConfig = false

    // 预缓冲：攒够约 120ms PCM 才开播，避免全程 underrun
    private val prebufferBytes = sampleRate.toLong() * channels * 2 * 12 / 100
    private var playing = false
    private var writtenBytes = 0L
    private var outCount = 0

    // 输出流方案轮转：部分 ColorOS/MTK 机型 USAGE_MEDIA 的 AudioTrack 全局假死
    // （playState=1 但 HAL 永不消费，write 持续返回 0，重建也无效）。
    // 候选 usage 按序降级：MEDIA → VOICE_COMMUNICATION（通话路由绕开 Dirac 媒体链）→ NOTIFICATION。
    private val attrPlans = listOf(
        intArrayOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_MOVIE),
        intArrayOf(AudioAttributes.USAGE_VOICE_COMMUNICATION, AudioAttributes.CONTENT_TYPE_SPEECH),
        intArrayOf(AudioAttributes.USAGE_NOTIFICATION, AudioAttributes.CONTENT_TYPE_SPEECH),
    )
    private var planIdx = 0
    private var planStartMs = 0L

    /** 当前输出方案彻底死亡时轮转到下一个候选 usage。
     *  单独用 planStartMs 计时——重建 AudioTrack 会重置 lastGoodWrite，
     *  若拿它当方案切换依据，2 秒一次的重建会让 3 秒门槛永远达不到（实测踩过）。 */
    private fun advancePlanIfNeeded(now: Long): Boolean {
        if (now - planStartMs < 4000 || planIdx >= attrPlans.size - 1) return false
        planIdx++
        planStartMs = now
        Log.w(TAG, "audio output plan ${planIdx - 1} dead -> switch to usage=${attrPlans[planIdx][0]}")
        return true
    }

    fun start() {
        if (running) return
        playing = false; writtenBytes = 0; outCount = 0; dropCount = 0; ptsUs = 0; writeErr = 0; partial = 0
        planIdx = 0; planStartMs = System.currentTimeMillis()
        try {
            val fmt = MediaFormat.createAudioFormat(AudioCapturer.MIME, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                // 重连场景：上一段会话已拿到 AudioSpecificConfig，直接作为 csd-0 起步
                config?.let { setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(it)) }
            }
            val c = try {
                // 音频统一软解：96kbps AAC 的 CPU 占用可忽略，但厂商硬解（实测 MTK）
                // 对喂帧节奏/缓冲数挑剔，会"出几帧后永久冻结"。软解跨 ROM 行为一致。
                MediaCodec.createByCodecName("c2.android.aac.decoder")
            } catch (e: Exception) {
                MediaCodec.createDecoderByType(AudioCapturer.MIME)
            }
            c.configure(fmt, null, null, 0)
            c.start()
            codec = c
            Log.i(TAG, "audio decoder: ${c.name}")

            track = newTrack()

            running = true
            thread = Thread { loop(c) }.apply {
                name = "audio-play"
                worker = this
                start()
            }
            Log.i(TAG, "audio player started ${sampleRate}Hz x$channels")
        } catch (e: Exception) {
            Log.w(TAG, "player start failed", e)
            stop()
        }
    }

    /** 建 AudioTrack：ColorOS7/MTK 的输出 HAL 会假死（playState=1 但 write 永远 0），
     *  pause/play 救不回来，只能整体重建。抽成函数供自愈调用。 */
    private fun newTrack(): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val plan = attrPlans[planIdx]
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(plan[0])
                    .setContentType(plan[1])
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(
                        if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO
                        else AudioFormat.CHANNEL_OUT_MONO
                    )
                    .build()
            )
            .setBufferSizeInBytes((minBuf * 2).coerceAtLeast(8192))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun loop(c: MediaCodec) {
        var t = track ?: return
        val info = MediaCodec.BufferInfo()
        var lastGoodWrite = System.currentTimeMillis()
        while (running) {
            try {
                // 把 AudioSpecificConfig 作为 CODEC_CONFIG 喂给解码器：
                // 裸 AAC（无 ADTS）没有它一个样本都解不出来，表现为控制端没声音。
                if (pendingConfig) {
                    pendingConfig = false
                    val cfg = config
                    if (cfg != null) {
                        val idx = c.dequeueInputBuffer(4_000)
                        if (idx >= 0) {
                            val buf = c.getInputBuffer(idx)!!
                            buf.clear(); buf.put(cfg)
                            c.queueInputBuffer(
                                idx, 0, cfg.size, 0,
                                MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                            )
                            Log.i(TAG, "audio config injected ${cfg.size}B")
                        }
                    }
                }
                val data = queue.poll()
                if (data != null) {
                    val idx = c.dequeueInputBuffer(4_000)
                    if (idx >= 0) {
                        val buf = c.getInputBuffer(idx)!!
                        buf.clear(); buf.put(data)
                        // pts 必须给真实递增时间戳：MTK 的 AAC 解码器遇到恒为 0 的 pts，
                        // 出几帧缓冲就再也不吐数据（实测 A93s decodedOut 涨、written 冻结在 15KB）。
                        c.queueInputBuffer(idx, 0, data.size, ptsUs, 0)
                        ptsUs += 1024L * 1_000_000L / sampleRate
                    } else {
                        queue.offer(data)
                    }
                }
                var outIdx = c.dequeueOutputBuffer(info, 4_000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.i(TAG, "audio decoder format: ${c.outputFormat}")
                }
                while (outIdx >= 0) {
                    val out = c.getOutputBuffer(outIdx)
                    if (out != null && info.size > 0) {
                        val pcm = ByteArray(info.size)
                        out.position(info.offset)
                        out.get(pcm)
                        // 阻塞写：WRITE_NON_BLOCKING 缓冲满时是部分写入，返回值被丢弃
                        // 就等于丢音频数据。阻塞写天然把解码节奏钳在消费速率上。
                        @Suppress("DEPRECATION")
                        val written = t.write(pcm, 0, pcm.size)
                        if (written <= 0) {
                            writeErr++
                            // ColorOS7/MTK 输出 HAL 假死：write 持续返回 0，pause/play 与重建都无效。
                            // 注意不能要求 playing==true：假死常发生在预缓冲填满之前，
                            // playing 永远到不了 true，自愈就永远不触发（实测踩过）。
                            val now = System.currentTimeMillis()
                            if (now - lastGoodWrite > 2000) {
                                val switched = advancePlanIfNeeded(now)
                                if (switched) {
                                    Log.w(TAG, "rebuild with switched plan")
                                } else {
                                    Log.w(TAG, "AudioTrack write=$written err=$writeErr -> rebuild track")
                                }
                                try { t.stop() } catch (e: Exception) { }
                                try { t.release() } catch (e: Exception) { }
                                t = newTrack()
                                track = t
                                playing = false
                                writtenBytes = 0
                                lastGoodWrite = System.currentTimeMillis()
                            }
                        } else {
                            lastGoodWrite = System.currentTimeMillis()
                            if (written < pcm.size) partial++
                            writtenBytes += written
                            if (!playing && writtenBytes >= prebufferBytes) {
                                playing = true
                                t.play()
                                Log.i(TAG, "prebuffer filled, playback started (${writtenBytes}B)")
                            }
                        }
                        outCount++
                        if (outCount <= 3 || outCount % 200 == 0) {
                            Log.i(TAG, "player stats: decodedOut=$outCount written=${writtenBytes}B q=${queue.size} playState=${t.playState} err=$writeErr partial=$partial")
                        }
                    }
                    c.releaseOutputBuffer(outIdx, false)
                    outIdx = c.dequeueOutputBuffer(info, 0)
                }
            } catch (e: IllegalStateException) {
                break
            } catch (e: Exception) {
                Log.w(TAG, "loop", e)
            }
        }
    }

    /** 收到一个音频帧（去掉 9 字节头后的 AAC 数据） */
    fun feed(aac: ByteArray, isConfig: Boolean) {
        if (!running) return
        if (isConfig) {
            config = aac
            pendingConfig = true
            Log.i(TAG, "recv audio config ${aac.size}B: ${aac.joinToString(",") { (it.toInt() and 0xFF).toString(16) }}")
            return
        }
        feedCount++
        if (feedCount == 1) {
            Log.i(TAG, "recv first audio frame ${aac.size}B: ${aac.take(8).joinToString(",") { (it.toInt() and 0xFF).toString(16) }}")
        }
        // 队列上限 24 帧（约550ms）：超过就丢最老的，把音频总延迟摁在
        // 视频量级附近（之前 64 帧上限，实测队列爬到 40+，口型差近 1 秒）。
        while (queue.size > 24) { queue.poll(); dropCount++ }
        if (dropCount > 0 && dropCount % 200 == 1) Log.i(TAG, "audio dropping old frames: $dropCount")
        queue.offer(aac)
    }

    private var feedCount = 0
    private var dropCount = 0
    private var ptsUs = 0L
    private var writeErr = 0L
    private var partial = 0L

    fun stop() {
        running = false
        try { worker?.join(300) } catch (e: InterruptedException) { }
        worker = null
        thread = null
        queue.clear()
        try { track?.pause(); track?.flush(); track?.release() } catch (e: Exception) { }
        track = null
        try { codec?.stop(); codec?.release() } catch (e: Exception) { }
        codec = null
    }
}