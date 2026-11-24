package com.yuyu.handlink

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer

/**
 * 被控端音频采集 + 编码（AAC）。
 *
 * 目标：控制端听到的声音＝被控端此刻外放的声音。
 *  - Android 10+ 用 AudioPlaybackCapture 直接抓"本机正在播放"的音频（不占麦克风，不录环境音）
 *  - 更低版本退回麦克风
 *  - 两条路都编码成 AAC-LC 推给控制端
 */
class AudioCapturer(
    private val ctx: Context,
    private val projection: MediaProjection?,
    private val onFrame: (flags: Int, ptsUs: Long, payload: ByteArray) -> Unit,
) {
    companion object {
        private const val TAG = "HandLinkAudio"
        const val SAMPLE_RATE = 44100
        const val CHANNEL_COUNT = 2
        const val BIT_RATE = 96_000
        const val MIME = MediaFormat.MIMETYPE_AUDIO_AAC
    }

    @Volatile private var running = false
    @Volatile private var micMode = false
    private var record: AudioRecord? = null
    private var encoder: MediaCodec? = null
    private var thread: Thread? = null

    fun start() {
        if (running) return
        try {
            val ar = createRecord() ?: run {
                Log.w(TAG, "no audio source available")
                return
            }
            record = ar

            val fmt = MediaFormat.createAudioFormat(MIME, SAMPLE_RATE, CHANNEL_COUNT).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            val enc = MediaCodec.createEncoderByType(MIME)
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            encoder = enc

            ar.startRecording()
            running = true
            thread = Thread { loop(ar, enc) }.apply { name = "audio-capture"; start() }
            Log.i(TAG, "audio started: ${if (usePlaybackCapture()) "playback-capture" else "mic"}")
        } catch (e: Exception) {
            Log.w(TAG, "start failed", e)
            stop()
        }
    }

    private fun usePlaybackCapture(): Boolean =
        Build.VERSION.SDK_INT >= 29 && projection != null && !micMode

    /** 静音看门狗判定"系统正在放声音"：只有媒体流活跃时的持续零能量才算采集失效 */
    private fun mediaActive(): Boolean = try {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        am.isMusicActive
    } catch (e: Exception) { true }

    /** 麦克风兜底开关：默认关（不传环境音）。调试/坏机型用 adb touch 该文件打开 */
    private fun micFallbackEnabledByFlag(): Boolean = try {
        java.io.File("/data/local/tmp/handlink_mic_fallback").exists()
    } catch (e: Throwable) { false }

    /** playback capture → 麦克风 热切换：沿用同一编码器，重启采集循环 */
    private fun swapTo(mic: AudioRecord, enc: MediaCodec) {
        micMode = true
        thread = Thread { loop(mic, enc) }.apply { name = "audio-capture-mic"; start() }
    }

    @SuppressLint("MissingPermission")
    private fun createRecord(): AudioRecord? {
        if (Build.VERSION.SDK_INT >= 29 && projection != null) {
            // AudioPlaybackCapture 的 AudioRecord 必须真正初始化成功才算可用；
            // 构造出来 state 不是 INITIALIZED 时说明系统拒绝了，退回麦克风。
            val captured = try {
                val builder = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .addMatchingUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .addMatchingUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .addMatchingUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .addMatchingUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .addMatchingUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .addMatchingUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .addMatchingUsage(AudioAttributes.USAGE_ALARM)
                // 把当前装着的应用都显式加进来：有些 ROM 只按 usage 匹配会漏掉，
                // 按 uid 显式声明更稳，覆盖"被控端在看视频/听歌"这类其他 App 发声的场景。
                try {
                    ctx.packageManager.getInstalledApplications(0).forEach { ai ->
                        if (ai.uid > 10000) builder.addMatchingUid(ai.uid)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "enumerate packages failed", e)
                }
                val cfg = builder.build()
                var rec: AudioRecord? = null
                // 个别机型要求先有投影画面再建音频，重试几轮
                var attempt = 0
                while (attempt < 3 && rec == null) {
                    val r = AudioRecord.Builder()
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                                .build()
                        )
                        .setBufferSizeInBytes(bufferSize())
                        .setAudioPlaybackCaptureConfig(cfg)
                        .build()
                    if (r.state == AudioRecord.STATE_INITIALIZED) {
                        rec = r
                    } else {
                        Log.w(TAG, "playback capture not initialized (attempt=$attempt state=${r.state})")
                        try { r.release() } catch (e: Exception) { }
                        Thread.sleep(200)
                    }
                    attempt++
                }
                rec
            } catch (e: Exception) {
                Log.w(TAG, "playback capture unavailable", e)
                null
            }
            if (captured != null) return captured
            Log.w(TAG, "fallback to microphone (needs RECORD_AUDIO)")
        }
        return micRecord()
    }

    @SuppressLint("MissingPermission")
    private fun micRecord(): AudioRecord? {
        return try {
            val rec = AudioRecord(
                android.media.MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize()
            )
            if (rec.state == AudioRecord.STATE_INITIALIZED) rec else null
        } catch (e: Exception) {
            Log.w(TAG, "mic unavailable", e)
            null
        }
    }

    private fun bufferSize(): Int {
        val min = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        return (min * 2).coerceAtLeast(16384)
    }

    private fun loop(ar: AudioRecord, enc: MediaCodec) {
        val pcm = ByteArray(8192)
        val info = MediaCodec.BufferInfo()
        var outCount = 0
        var cfgLogged = false
        var readCount = 0
        var capturedBytes = 0L
        var lastStatAt = System.currentTimeMillis()
        var peakRecent = 0
        var silentSince = if (usePlaybackCapture()) System.currentTimeMillis() else Long.MAX_VALUE
        var micSwapped = false
        Log.i(TAG, "record format: rate=${ar.sampleRate} ch=${ar.channelCount} enc=${ar.audioFormat} buf=${ar.bufferSizeInFrames}")
        while (running) {
            try {
                val n = ar.read(pcm, 0, pcm.size)
                if (n > 0) {
                    readCount++
                    capturedBytes += n
                    // 能量探针：区分"采到静音"和"没采到"——被控端有声音但控制端听不到时，
                    // 看这个值就知道断点在采集还是传输/播放。
                    var peak = 0
                    var i = 0
                    while (i + 1 < n) {
                        val lo = pcm[i].toInt() and 0xFF
                        val hi = pcm[i + 1].toInt()
                        val s = (hi shl 8) or lo
                        val a = if (s < 0) -s else s
                        if (a > peak) peak = a
                        i += 2
                    }
                    if (peak > peakRecent) peakRecent = peak
                    // 静音看门狗（默认关闭）：用户明确不要"传环境音/讲话声"，
                    // 麦克风兜底会把被控端周围声音全传过去，等于打电话——隐私差。
                    // 真实目标机（Reno14/Android16）playback capture 正常，不需要兜底。
                    // 仅当手动打开 /data/local/tmp/handlink_mic_fallback 文件时才启用
                    // （给 playback capture 彻底坏掉的机型留的后门）。
                    if (peak > 100) silentSince = 0L
                    if (!micSwapped && silentSince > 0L &&
                        System.currentTimeMillis() - silentSince > 6000 && mediaActive() &&
                        micFallbackEnabledByFlag()
                    ) {
                        micSwapped = true
                        Log.w(TAG, "playback capture silent 6s+ while media playing -> switch to MIC")
                        val mic = micRecord()
                        if (mic != null) {
                            try { ar.stop() } catch (e: Exception) { }
                            try { ar.release() } catch (e: Exception) { }
                            record = mic
                            mic.startRecording()
                            swapTo(mic, enc)
                            return
                        }
                    }
                    val nowMs = System.currentTimeMillis()
                    if (nowMs - lastStatAt >= 3000) {
                        lastStatAt = nowMs
                        Log.i(TAG, "captured ${capturedBytes}B in ${readCount} reads, peak=${peakRecent}/32767")
                        peakRecent = 0
                    }
                    val inIdx = enc.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf: ByteBuffer = enc.getInputBuffer(inIdx) ?: continue
                        buf.clear()
                        buf.put(pcm, 0, n)
                        enc.queueInputBuffer(inIdx, 0, n, System.nanoTime() / 1000, 0)
                    }
                }
                while (true) {
                    val outIdx = enc.dequeueOutputBuffer(info, 0)
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.i(TAG, "encoder format: ${enc.outputFormat}")
                    }
                    if (outIdx >= 0) {
                        val out = enc.getOutputBuffer(outIdx)
                        if (out != null && info.size > 0) {
                            val payload = ByteArray(info.size)
                            out.position(info.offset)
                            out.get(payload)
                            var flags = 0
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) flags = flags or 0x02
                            if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) flags = flags or 0x01
                            if (flags and 0x02 != 0 && !cfgLogged) {
                                cfgLogged = true
                                Log.i(TAG, "audio CODEC_CONFIG ${payload.size}B: ${payload.joinToString(",") { (it.toInt() and 0xFF).toString(16) }}")
                            }
                            outCount++
                            if (outCount == 1) Log.i(TAG, "first audio frame flags=$flags size=${payload.size}")
                            onFrame(flags, info.presentationTimeUs, payload)
                        }
                        enc.releaseOutputBuffer(outIdx, false)
                    } else {
                        break
                    }
                }
            } catch (e: IllegalStateException) {
                break
            } catch (e: Exception) {
                Log.w(TAG, "loop", e)
            }
        }
    }

    fun stop() {
        running = false
        try { thread?.join(300) } catch (e: InterruptedException) { }
        thread = null
        try { record?.stop() } catch (e: Exception) { }
        try { record?.release() } catch (e: Exception) { }
        record = null
        try { encoder?.stop(); encoder?.release() } catch (e: Exception) { }
        encoder = null
    }
}