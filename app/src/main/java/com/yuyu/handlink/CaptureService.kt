package com.yuyu.handlink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import org.java_websocket.WebSocket
import org.json.JSONObject

/**
 * 被控端前台服务：MediaProjection 截屏 -> H264 编码 -> 局域网 WS / 异地中继推给已确认的控制端；
 * 同时把控制端输入转发给无障碍服务注入。音频（被控端外放声）单独编码 AAC 同路推送。
 * 知情同意：新连接必须由本机用户点“允许”才开始推流；进行中有常驻通知 + 一键停止。
 */
class CaptureService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_STOP_CONTROL = "stop_control"
        const val ACTION_NEW_CODE = "new_code"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        const val NOTI_CHANNEL_CAP = "cap"
        const val NOTI_CHANNEL_CONSENT = "consent"
        const val NOTI_CAP = 1
        const val NOTI_FS_CONSENT = 3

        @Volatile var projectionToken: Pair<Int, Intent>? = null
    }

    private lateinit var nm: NotificationManager
    private var server: HandlinkServer? = null
    private var relayClient: RelayClient? = null
    @Volatile private var relayActive = false
    private var encoder: MediaCodec? = null
    /** RTC 接管后置 true，让旧的 drain 循环退出 */
    @Volatile private var encoderStopped = false
    private var adaptiveCtrl: AdaptiveController? = null
    /** WebRTC 推流通道（自适应码率） */
    private var rtcHost: RtcHost? = null
    /** 本机真实屏幕尺寸（getRealMetrics），Shizuku 注入坐标按它换算 */
    @Volatile private var encRealW = 1080
    @Volatile private var encRealH = 2400
    /** 编码器累计输出帧数，用于检测长时间无输出 */
    @Volatile private var encoderOutCount = 0L
    @Volatile private var surfaceFrameCount = 0L
    private var mediaProjection: MediaProjection? = null
    /** 投屏授权 Intent：官方 ScreenCapturerAndroid 需要它自己建 VirtualDisplay */
    private var projectionData: Intent? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var captureThread: Thread? = null
    private var audio: AudioCapturer? = null
    @Volatile private var running = false
    private var encW = 0
    private var encH = 0
    private val main = Handler(Looper.getMainLooper())

    private var frameCount = 0
    private var lastFpsMs = 0L
    /** 缓存最近的 SPS/PPS 配置帧：新会话建立时要补发，否则解码器解不出任何画面 */
    @Volatile private var lastConfig: ByteArray? = null
    @Volatile private var lastConfigPts = 0L
    /** 同理缓存音频 AudioSpecificConfig：编码器启动只发一次，晚接入的控制端必须补发才解得出声音 */
    @Volatile private var lastAudioConfig: ByteArray? = null
    @Volatile private var lastAudioConfigPts = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopEverything(); return START_NOT_STICKY }
            ACTION_STOP_CONTROL -> { releaseActiveSession("本机已停止控制"); updateNotif(); return START_STICKY }
            ACTION_NEW_CODE -> { restartRelay(); return START_STICKY }
        }
        if (running) return START_STICKY

        nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannels()
        startForegroundCompat()

        val rc = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_RESULT_DATA)

        if (rc == 0 || data == null) { stopSelf(); return START_NOT_STICKY }
        projectionToken = rc to data
        running = true

        try {
            startProjection(rc, data)
        } catch (e: Exception) {
            Log.e("CaptureService", "start failed", e)
            stopEverything(); return START_NOT_STICKY
        }
        // 必须先生成配对码再启动服务器：中继以当时的 pairCode 作为房间号注册，
        // 顺序反了会用旧码/空码注册，界面显示的新码进不了房（"房间不存在或被控端不在线"）。
        AppState.pairCode.value = AppState.randomCode()
        startServer()
        AppState.serviceRunning.value = true
        updateNotif()
        return START_STICKY
    }

    // ---------- MediaProjection + 编码 ----------

    private fun startProjection(resultCode: Int, data: Intent) {
        projectionData = data
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mgr.getMediaProjection(resultCode, data) ?: error("no projection")
        mediaProjection = projection
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // Android 16 起，锁屏 / 来电挂断 / 系统回收 都会主动停掉 MediaProjection。
                // 老代码在这里直接 stopEverything()，表现为"锁一下屏共享就没了，得重开"。
                //
                // 现在的处理：标记为"被系统中断"，保持服务与房间存活，
                // 由 UI 引导用户点一下重新授权，回来后自动恢复共享。
                Log.w("CaptureService", "MediaProjection stopped by system (lock screen / call / recycle)")
                main.post {
                    AppState.projectionInterrupted.value = true
                    AppState.serviceRunning.value = false
                    AppState.fps.value = 0
                    try { server?.stop() } catch (e: Exception) { }
                    server = null
                    try { encoder?.let { it.stop(); it.release() } } catch (e: Exception) { }
                    encoder = null
                    try { virtualDisplay?.release() } catch (e: Exception) { }
                    virtualDisplay = null
                    mediaProjection = null
                    // 注意：这里**不**调用 stopEverything()，
                    // 否则会把无障碍、前台服务、配对码全部清掉，
                    // 用户就得从头再来一遍。
                    updateNotif()
                }
            }
        }, null)

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
        encRealW = metrics.widthPixels
        encRealH = metrics.heightPixels

        // 清晰度：默认按原尺寸编码。长边上限只用来兜底超大屏（平板/折叠屏），
        // 手机上不会再缩。原来是固定 1280，A93 的 1080x2400 被压成 576x1280，
        // 再看电视/视频就是糊的。
        // 异地中继是主场景：必须在**任何尺寸/码率计算之前**设置上限。
        // 踩过的坑：这个设置原来放在 startRelayIfNeeded() 里，
        // 而分辨率在第 156 行、码率在第 178 行就算完了，
        // 结果编码器仍按 912x1984 @ 9.5Mbps 启动，中继直接撑爆 → 又卡又糊。
        AppState.relayQualityCap.value = 8_000_000
        val maxDim = VideoTuning.maxDim(this)
        val scale = if (maxDim <= 0) 1f
        else minOf(1f, maxDim.toFloat() / maxOf(metrics.widthPixels, metrics.heightPixels))
        var wantW = roundEven(metrics.widthPixels * scale)
        var wantH = roundEven(metrics.heightPixels * scale)

        // 收敛到本机编码器真正支持的范围：不同芯片差别很大，
        // 这是"某些机型黑屏"的主因之一。
        val (maxLong, maxShort) = CodecCapabilitiesHelper.maxEncoderSize()
        val (fitW, fitH) = CodecCapabilitiesHelper.fitEncoder(wantW, wantH, maxLong, maxShort)
        val realScale = fitW.toFloat() / metrics.widthPixels
        encW = fitW
        encH = fitH

        // 关键：dpi 必须跟着尺寸一起缩放。
        // 原来把真实 480dpi 配 576x1280 的虚拟屏，系统算出的逻辑宽度只有 192dp
        // （真实是 360dp），虚拟屏的布局与合成出错，编码器拿不到正常画面，
        // 控制端就只能看到 0fps 的黑屏。
        val encDpi = (metrics.densityDpi * realScale).toInt().coerceAtLeast(72)

        // 码率按分辨率算，不再写死 4Mbps。
        val fps = VideoTuning.frameRate(this)
        val bitrate = VideoTuning.bitrateFor(encW, encH, VideoTuning.effectiveQuality(this))

        val fmt = MediaFormat.createVideoFormat("video/avc", encW, encH).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            // I 帧间隔 1 秒：太长会让中途重连的控制端等太久才能出画面
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)

            // ===== 以下全部按 scrcpy 的低延迟实践配置 =====
            // scrcpy 能做到 35~70ms，靠的就是这几项，缺一项都达不到。

            // 1) 禁止 B 帧。B 帧要参考前后帧，编码器和解码器都必须多缓几帧，
            //    这是实时投屏延迟最大来源之一。部分编码器不支持该 key，try 掉。
            try {
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            } catch (e: Exception) {
                Log.d("CaptureService", "no KEY_MAX_B_FRAMES on this encoder")
            }

                // 2) Profile 选择：**低码率必须用 High，不能照搬 scrcpy 的 Baseline**
            //
            //    Baseline 只支持 CAVLC 熵编码，压缩效率比 CABAC 低 30~40%。
            //    scrcpy 能用 Baseline 是因为它跑 8Mbps 以上，码率够挥霍；
            //    我们异地中继只有 3Mbps，用 Baseline 的结果就是"静止画面也糊"。
            //
            //    High Profile = CABAC + 更灵活的运动补偿，
            //    同等画质省 30~40% 码率；代价是解码稍重，但现代手机毫无压力
            //    （Reno14 天玑8350 / 一加15 骁龙8 都能解 4K60）。
            try {
                setInteger(
                    MediaFormat.KEY_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
                )
                // Level 4.0 覆盖到 1080p@30，足够
                setInteger(
                    MediaFormat.KEY_LEVEL,
                    MediaCodecInfo.CodecProfileLevel.AVCLevel4
                )
            } catch (e: Exception) {
                // 个别编码器不认 High，退回 Main（仍有 CABAC）
                try {
                    setInteger(
                        MediaFormat.KEY_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AVCProfileMain
                    )
                } catch (e2: Exception) {
                    Log.d("CaptureService", "profile setting unsupported")
                }
            }

            // 3) CBR 固定码率：VBR 会攒多帧再算码率，CBR 每帧即时出，延迟更稳
            try {
                setInteger(MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            } catch (e: Exception) {
                try {
                    setInteger(MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                } catch (e2: Exception) { }
            }

            // 4) 实时优先级
            try { setInteger(MediaFormat.KEY_PRIORITY, 0) } catch (e: Exception) { }

            // 5) 编码器低延迟模式
            try {
                if (Build.VERSION.SDK_INT >= 30) setLong(MediaFormat.KEY_LATENCY, 0L)
                else if (Build.VERSION.SDK_INT == 29) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            } catch (e: Exception) { }

            // 6) 关闭预编码，编码器拿到帧立刻处理
            try { setInteger("prepend-sps-pps-to-idr-frames", 0) } catch (e: Exception) { }
        }
        // 挑一个真正能用的编码器：各厂商实现差异很大，createEncoderByType
        // 可能拿到一个 configure 就失败的编码器，直接表现为黑屏。
        val picked = CodecCapabilitiesHelper.createBestEncoder(encW, encH, fmt)
            ?: run {
                Log.e("CaptureService", "no usable H.264 encoder for ${encW}x${encH}")
                stopEverything()
                return
            }
        val codec = MediaCodec.createByCodecName(picked.first)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = codec.createInputSurface()
        codec.start()
        encoder = codec

        // 把实际生效的编码参数打出来，方便确认低延迟配置有没有被采纳
        try {
            val of = codec.outputFormat
            Log.i("CaptureService", "encoder effective: " +
                "profile=" + runCatching { of.getInteger("profile") }.getOrNull() +
                " level=" + runCatching { of.getInteger("level") }.getOrNull() +
                " bitrate=" + runCatching { of.getInteger(MediaFormat.KEY_BIT_RATE) }.getOrNull() +
                " max-bframes=" + runCatching { of.getInteger("max-bframes") }.getOrNull())
        } catch (e: Exception) { }

        Log.i("CaptureService", "capture real=${metrics.widthPixels}x${metrics.heightPixels}" +
            "@${metrics.densityDpi}  encode=${encW}x${encH}@${encDpi} ${fps}fps ${bitrate / 1_000_000.0}Mbps")

        // 只用 AUTO_MIRROR：虚拟屏镜像主屏，内容随主屏变化持续推帧。
        // 加 PUBLIC 会让虚拟屏变成"自己的内容"，首帧后就不再合成，编码器静默。
        virtualDisplay = projection.createVirtualDisplay(
            "handlink", encW, encH, encDpi,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface, null, null
        )
        startDrainLoop(codec)
        startRefreshTick(codec)
        startAudio(projection)
    }

    /**
     * 刷新机制。
     *
     * 问题：MediaProjection 的镜像物理屏只在"画面有变化"时才向虚拟屏提交新帧。
     * 画面静止时输入 Surface 长时间拿不到 buffer，编码器就一帧不出，
     * 控制端看到的是完全卡住的画面（帧率跌到 1~3fps），看电视/视频尤其明显。
     *
     * 两条互补的手段：
     *  1) KEY_REPEAT_PREVIOUS_FRAME_AFTER：编码器在指定时长内没收到新帧就重复上一帧，
     *     保证码流不断。这是 MediaCodec 的标准参数，不依赖厂商实现。
     *  2) 给虚拟屏周期性 setSurface（先置 null 再置回）：强制 SurfaceFlinger 重新提交一帧，
     *     让镜像真正刷新，从而拿到新的 PTS 和真实画面。
     */
    private fun startRefreshTick(codec: MediaCodec) {
        // ===== 静止画面保活：用 scrcpy 同款做法 =====
        //
        // 问题：镜像只在屏幕内容变化时才产生新帧，
        //       静止时控制端会看到"卡住不动"。
        //
        // 错误做法（我之前用的）：每秒 PARAMETER_KEY_REQUEST_SYNC_FRAME
        //   → 强制编码关键帧，数据量大、打乱 GOP、画面质量反而变差。
        //
        // 正确做法（scrcpy 官方做法）：
        //   设置 KEY_REPEAT_PREVIOUS_FRAME_AFTER = 100ms，
        //   编码器在指定时长内没收到新帧，就自己重复上一帧。
        //   这个参数设一次即长期生效，不需要轮询。
        //
        // 文档原文："这两个问题通过标志位 KEY_REPEAT_PREVIOUS_FRAME_AFTER 得到解决"
        // PARAMETER_KEY_REPEAT_PREVIOUS_FRAME_AFTER 的真实字符串常量。
        //
        // 之前用 MediaCodec::class.java.getField() 反射拿，在 Android 13+ 上
        // 因为 hidden API 限制拿不到，返回 null → 保活机制完全没生效 →
        // 静止画面时编码器 1.3fps → 控制端解码器喂不够帧判定卡死 → 黑屏。
        //
        // 这个 key 的字符串值从 API 19 至今没变过，直接写死。
        val repeatKey: String = "repeat-previous-frame-after"

        if (repeatKey != null) {
            try {
                val p = Bundle()
                // 100ms：静止时保持 ~10fps 的刷新，肉眼看起来是"冻结但活着"，
                // 不会误以为是掉线
                p.putLong(repeatKey, 100_000L)
                codec.setParameters(p)
                Log.i("CaptureService", "repeat-previous-frame enabled (100ms)")
            } catch (e: Exception) {
                Log.w("CaptureService", "repeat-previous-frame unsupported", e)
            }
        } else {
            Log.i("CaptureService", "repeat-previous-frame not available, no idle keepalive")
        }

        // 兜底：仅在极长时间无输出时补一个关键帧，避免控制端彻底卡死。
        // 间隔 5 秒，且只有真的 5 秒没出帧才触发，不影响正常播放。
        val t = Thread {
            var lastOutCount = -1L
            var stallTicks = 0
            while (running) {
                try {
                    Thread.sleep(1000L)
                    val now = encoderOutCount
                    if (now == lastOutCount) {
                        stallTicks++
                        if (stallTicks >= 5) {
                            stallTicks = 0
                            requestSyncFrame()
                            Log.i("CaptureService", "5s no output -> force keyframe")
                        }
                    } else {
                        stallTicks = 0
                        lastOutCount = now
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    break
                }
            }
        }
        t.name = "refresh-tick"
        t.isDaemon = true
        t.start()
    }

    /** 请求编码器立刻输出一个关键帧（只在兜底场景用） */
    private fun requestSyncFrame() {
        try {
            val c = encoder ?: return
            val p = Bundle()
            p.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            c.setParameters(p)
        } catch (e: Exception) {
            // 个别编码器不支持，忽略
        }
    }

    private fun roundEven(v: Float): Int {
        val i = v.toInt()
        return if (i % 2 == 0) i else i - 1
    }

    /** 编码输出循环：有已确认会话时才推帧 */
    private fun startDrainLoop(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        val t = Thread {
            var firstFrameLogged = false
            var cfgLogged = false
            var outCount = 0L
            var lastOutCount = 0L
            var lastStat = System.currentTimeMillis()
            while (running) {
                if (encoderStopped) break   // RTC 接管后退出，避免刷屏
                try {
                    val idx = codec.dequeueOutputBuffer(info, 10_000)
                    if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.i("CaptureService", "encoder format changed: ${codec.outputFormat}")
                    }
                    if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        val now = System.currentTimeMillis()
                        if (now - lastStat >= 3000) {
                            val dt = now - lastStat
                            val fps3 = (outCount - lastOutCount) * 1000.0 / dt
                            Log.w(
                                "CaptureService",
                                "idle ${dt}ms fps=${"%.1f".format(fps3)} out=$outCount"
                            )
                            lastStat = now
                            lastOutCount = outCount
                        }
                    }
                    if (idx >= 0) {
                        val buf = codec.getOutputBuffer(idx)
                        if (buf != null && info.size > 0) {
                            outCount++; encoderOutCount++
                            val payload = ByteArray(info.size)
                            buf.position(info.offset)
                            buf.get(payload)
                            var flags = 0
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) flags = flags or 0x02
                            if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) flags = flags or 0x01
                            if (flags and 0x02 != 0) { lastConfig = payload; lastConfigPts = info.presentationTimeUs }
                            if (!cfgLogged && flags and 0x02 != 0) {
                                cfgLogged = true
                                Log.i("CaptureService", "got CODEC_CONFIG ${payload.size}B")
                            }
                            if (!firstFrameLogged) {
                                firstFrameLogged = true
                                Log.i("CaptureService", "first frame flags=$flags size=${payload.size} pts=${info.presentationTimeUs}")
                            }
                            if (server?.activeConn != null || relayActive) {
                                server?.sendBinary(encodeFrame(flags, info.presentationTimeUs, payload))
                                relayClient?.sendVideo(flags, info.presentationTimeUs, payload)
                            }
                        }
                        codec.releaseOutputBuffer(idx, false)
                        countFps()
                        val n2 = System.currentTimeMillis()
                        if (n2 - lastStat >= 3000) {
                            val fps3 = (outCount - lastOutCount) * 1000.0 / (n2 - lastStat)
                            Log.i("CaptureService", "encode fps=${"%.1f".format(fps3)} out=$outCount")
                            lastStat = n2
                            lastOutCount = outCount
                        }
                    }
                } catch (e: IllegalStateException) {
                    break // codec 已释放
                } catch (e: Exception) {
                    Log.w("CaptureService", "drain", e)
                }
            }
        }
        t.name = "encoder-drain"
        t.start()
        captureThread = t
    }

    private fun countFps() {
        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastFpsMs >= 1000) {
            AppState.fps.value = frameCount
            frameCount = 0
            lastFpsMs = now
        }
    }

    /**
     * 音频：把被控端此刻外放的声音编码成 AAC 推给控制端（局域网 + 中继两路）。
     * Android 10+ 走 AudioPlaybackCapture；更低版本退回麦克风。
     */
    private fun startAudio(projection: MediaProjection) {
        // 等投影完全起来再建 AudioRecord，否则个别机型初始化失败
        main.postDelayed({
            if (!running) return@postDelayed
            try {
                Log.i("CaptureService", "audio: starting capture")
                audio?.stop()
                lastAudioConfig = null
                audio = AudioCapturer(this, projection) { flags, pts, payload ->
                    if (flags and 0x02 != 0) { lastAudioConfig = payload; lastAudioConfigPts = pts }
                    if (server?.activeConn != null) {
                        server?.sendBinary(encodeFrame(flags or 0x04, pts, payload))
                    }
                    if (relayActive) {
                        relayClient?.sendAudio(flags or 0x04, pts, payload)
                    }
                }.also { it.start() }
                Log.i("CaptureService", "audio: capture request done, obj=${audio != null}")
            } catch (e: Exception) {
                Log.w("CaptureService", "audio start failed", e)
            }
        }, 800L)
    }

    // ---------- WebSocket 服务器 + 同意流程 ----------

    private fun startServer() {
        val srv = HandlinkServer(
            onPair = { conn, name -> requestConsent(conn, name) },
            onInput = { json -> handleInputJson(json) },
            onRtc = { json -> handleRtcSignal(json) },
            onDisconnect = {
                if (server?.activeConn == null && !relayActive) {
                    AppState.activeController.value = null
                    updateNotif()
                }
            },
        )
        // 创建运行时自适应控制器：
        // 按发送队列水位动态调整码率，保延迟优先。
        val bRate = VideoTuning.bitrateFor(
            encW, encH, VideoTuning.effectiveQuality(this)
        )
        val ctrl = AdaptiveController(
            baseBitrate = bRate,
            baseW = encW,
            baseH = encH,
            baseFps = VideoTuning.frameRate(this),
            applyBitrate = { newRate ->
                try {
                    val c = encoder
                    if (c != null) {
                        val p = Bundle()
                        p.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, newRate)
                        c.setParameters(p)
                    }
                } catch (e: Exception) {
                    Log.w("CaptureService", "apply bitrate failed", e)
                }
            }
        )
        srv.adaptive = ctrl
        adaptiveCtrl = ctrl
        Log.i("CaptureService", "adaptive ready: base=${bRate / 1_000_000.0}Mbps " +
            "encode=${encW}x${encH}@${VideoTuning.frameRate(this)}fps")

        try { srv.start() } catch (e: Exception) { Log.e("CaptureService", "server start", e) }
        server = srv
        startRelayIfNeeded()
    }

    private fun handleInputJson(json: JSONObject) {
        val t = json.optString("t")

        // ---- 优先走 Shizuku：注入的是真实 MotionEvent，真正跟手 ----
        if (ShizukuTouch.available) {
            val ok = try {
                val nx = json.optDouble("x", 0.0).toFloat().coerceIn(0f, 1f)
                val ny = json.optDouble("y", 0.0).toFloat().coerceIn(0f, 1f)
                val px = nx * (encRealW - 1)
                val py = ny * (encRealH - 1)
                when (t) {
                    "down" -> ShizukuTouch.down(px, py)
                    "move" -> ShizukuTouch.move(px, py)
                    "up" -> ShizukuTouch.up(px, py)
                    "tap" -> ShizukuTouch.tap(px, py, 40L)
                    "lp" -> ShizukuTouch.tap(px, py, 650L)
                    else -> false
                }
            } catch (e: Exception) {
                Log.w("CaptureService", "shizuku inject failed", e)
                false
            }
            if (ok) return
        }

        // ---- 降级：无障碍手势 ----
        val svc = RemoteControlService.instance
        if (svc == null) {
            // 无障碍服务实例为 null：settings 里显示"已启用"也可能没真正绑定上，
            // 重装 APK 后尤其常见。这里打明确日志，并对非 move 类指令回一次提示，
            // 避免每个 move 都刷屏。
            if (t != "move") {
                Log.w("CaptureService", "input t=$t REJECTED: a11y service not bound")
                server?.sendText(Proto.note("无障碍服务未绑定，请到设置里关闭再开启一次"))
                relayClient?.sendText(Proto.note("无障碍服务未绑定，请到设置里关闭再开启一次"))
            }
        } else {
            Log.i("CaptureService", "input t=$t -> injecting")
            try {
                svc.handleInput(json)
            } catch (e: Exception) {
                Log.w("CaptureService", "inject failed", e)
            }
        }
    }

    // ---------- 中继模式 ----------

    /**
     * 处理来自控制端的 WebRTC 信令。
     * 信令走现有 relay 的文本通道，不额外改服务器。
     */
    fun handleRtcSignal(json: JSONObject) {
        when (json.optString("t")) {
            "rtc-answer" -> {
                rtcHost?.handleAnswer(json.optString("sdp"))
            }
            "rtc-ice" -> {
                rtcHost?.handleIce(
                    json.optString("mid"),
                    json.optInt("mline", 0),
                    json.optString("cand")
                )
            }
            "rtc-ready" -> {
                // 必须检查 useRtc！
                // 之前这里无条件 startRtcHost()，导致 useRtc=false（默认）时，
                // 只要控制端发来 rtc-ready，被控端仍会启动 RTC，
                // 和旧的 MediaCodec 管线抢资源 → 画面异常、连接不稳。
                if (!AppState.useRtc.value) {
                    Log.i("CaptureService", "rtc-ready ignored (useRtc=false)")
                } else {
                    Log.i("CaptureService", "viewer rtc ready -> start host")
                    startRtcHost()
                }
            }
        }
    }

    /** 启动 WebRTC 推流（在 offer 之前调用） */
    /**
     * 停掉旧的 MediaCodec 编码链。
     *
     * RTC 用官方 ScreenCapturerAndroid 之后，它自己会建一个 VirtualDisplay。
     * 旧这条链（CaptureService 里的 MediaCodec + 自己的 VirtualDisplay）必须完全退出，
     * 否则两个 VirtualDisplay 同时镜像同一块屏幕，既浪费又可能互相干扰。
     */
    private fun stopLegacyEncoder() {
        val enc = encoder
        if (enc == null) return
        encoderStopped = true
        try { captureThread?.interrupt() } catch (e: Exception) { }
        captureThread = null
        try { enc.stop() } catch (e: Exception) { }
        try { enc.release() } catch (e: Exception) { }
        encoder = null
        try { virtualDisplay?.release() } catch (e: Exception) { }
        virtualDisplay = null
        Log.w("CaptureService", ">>> legacy encoder + virtualDisplay released")
    }

    private fun startRtcHost() {
        Log.w("CaptureService", ">>> startRtcHost ENTER")
        if (rtcHost?.started == true) {
            Log.w("CaptureService", ">>> already started")
            return
        }
        val rc = relayClient
        val srv = server
        if (rc == null && srv == null) {
            Log.w("CaptureService", ">>> ABORT: no signaling channel")
            return
        }
        val data = projectionData
        if (data == null) {
            Log.w("CaptureService", ">>> ABORT: projection intent is null")
            return
        }

        // 按真实屏幕比例出流。
        // ColorOS 用的是 FramerateBitrateAdjuster（带宽紧就降帧率）→ 卡。
        // 我们反过来：分辨率帧率给满，只让码率自适应。
        val isLan = rc?.viewerReady != true
        val longSide = if (isLan) 2000 else 1600
        val sw = encRealW
        val sh = encRealH
        val scale = longSide.toFloat() / maxOf(sw, sh)
        val w = ((sw * scale).toInt() / 2 * 2).coerceAtLeast(480)
        val h = ((sh * scale).toInt() / 2 * 2).coerceAtLeast(480)
        val fps = if (isLan) 60 else 45

        Log.w("CaptureService", ">>> RTC target ${w}x${h}@${fps} lan=$isLan (screen=${sw}x${sh})")

        val host = RtcHost(this, data) { sig ->
            if (srv?.activeConn != null) srv.sendText(sig)
            if (rc?.viewerReady == true) rc.sendText(sig)
        }
        host.onState = { st -> Log.i("CaptureService", "rtc state=$st") }
        host.onTouch = { msg ->
            try { handleInputJson(org.json.JSONObject(msg)) } catch (e: Exception) { }
        }
        host.start(w, h, fps, isLan)
        rtcHost = host

        // 官方采集器自己管 VirtualDisplay，我们只负责停掉旧的 MediaCodec 链路，
        // 避免两套东西同时抓着 MediaProjection
        main.postDelayed({
            stopLegacyEncoder()
            Log.w("CaptureService", ">>> legacy encoder stopped, RTC owns capture")
        }, 800L)
    }

    private fun rtcDiag(msg: String) {
        Log.i("CaptureService", msg)
        try {
            val dir = getExternalFilesDir(null) ?: return
            java.io.File(dir, "rtc_diag.txt").appendText("${System.currentTimeMillis()} CS:$msg\n")
        } catch (e: Exception) { }
    }

    private fun startRelayIfNeeded() {
        // 异地是主场景：开共享即连中继，局域网 WS 服务器只是备用通道
        val rc = RelayClient(
            relayUrl = AppState.relayUrl.value,
            room = AppState.pairCode.value,
            onPair = { name -> requestConsentRelay(name) },
            onInput = { json -> handleInputJson(json) },
            onRtc = { json -> handleRtcSignal(json) },
            onViewerLeft = {
                relayActive = false
                AppState.activeController.value = null
                updateNotif()
            },
            onReady = { AppState.relayStatus.value = "online" },
        )
        relayClient = rc
        AppState.relayStatus.value = "connecting"
        rc.start()
    }

    private fun restartRelay() {
        releaseRelay("房间号已更换")
        relayClient?.stop()
        relayClient = null
        startRelayIfNeeded()
    }

    private fun releaseRelay(reason: String) {
        if (relayActive) {
            relayClient?.sendText(Proto.deny(reason))
        }
        relayActive = false
    }

    private fun requestConsentRelay(name: String) {
        relayClient?.sendText(Proto.note("等待对方在手机上的确认…"))

        val decide: (Boolean) -> Unit = { ok ->
            main.post {
                AppState.consentRequest.value = null
                val rc = relayClient
                if (ok && AppState.serviceRunning.value && rc != null) {
                    relayActive = true
                    AppState.activeController.value = name
                    rc.markViewerReady()
                    rc.sendText(Proto.accept("本机"))
                    rc.sendText(Proto.size(encW, encH))
                    lastConfig?.let { rc.sendVideo(0x02, lastConfigPts, it) }
                    lastAudioConfig?.let { rc.sendAudio(0x02 or 0x04, lastAudioConfigPts, it) }
                    requestSyncFrame()
                    updateNotif()
                    // 中继启动 WebRTC
                    if (AppState.useRtc.value) {
                        Log.i("CaptureService", "relay consent ok -> start RTC host")
                        Log.w("CaptureService", ">>> CALL startRtcHost from L798")
                        startRtcHost()
                    }
                } else {
                    rc?.sendText(Proto.deny("对方拒绝或超时"))
                }
            }
        }
        val req = ConsentRequest(name, decide)
        if (AppState.uiVisible.value) {
            AppState.consentRequest.value = req
        } else if (Settings.canDrawOverlays(this)) {
            main.post { OverlayConsent.show(this, req) }
        } else {
            AppState.consentRequest.value = req
            main.post { showConsentNotification(name) }
        }
        main.postDelayed({
            if (AppState.consentRequest.value === req) req.decided(false)
        }, 60_000) // 30s 太赶：用户切个窗口就超时拒绝了，放宽到 60s
    }

    private fun requestConsent(conn: WebSocket, name: String) {
        // 先通知控制端等待
        try { conn.send(Proto.note("等待对方在手机上的确认…").toString()) } catch (e: Exception) { }

        val decide: (Boolean) -> Unit = { ok ->
            main.post {
                AppState.consentRequest.value = null
                if (ok && AppState.serviceRunning.value && conn.isOpen) {
                    server?.activeConn = conn
                    AppState.activeController.value = name
                    server?.sendText(Proto.accept("本机"))
                    server?.sendText(Proto.size(encW, encH))
                    // 补发序列参数集，让新解码器能立刻起步
                    lastConfig?.let { server?.sendBinary(encodeFrame(0x02, lastConfigPts, it)) }
                    // 补发音频配置，否则控制端 AAC 解码器起不来、没声音
                    lastAudioConfig?.let {
                        server?.sendBinary(encodeFrame(0x02 or 0x04, lastAudioConfigPts, it))
                    }
                    // 再立刻要一个关键帧：静止画面下编码器可能很久不出 I 帧，
                    // 解码器拿不到 I 帧就一直不出画面（控制端表现为 0fps 黑屏）。
                    requestSyncFrame()
                    updateNotif()

                    // ---- 局域网模式启动 WebRTC ----
                    // 这里必须主动拉起 RTC：控制端收到 accept 后会发 rtc-ready，
                    // 但被控端如果没先建好 RtcHost，那条信令就无处可去。
                    // 以前的实现漏了这一步，导致局域网永远走旧通道 → 黑屏。
                    if (AppState.useRtc.value) {
                        Log.i("CaptureService", "LAN consent ok -> start RTC host")
                        Log.w("CaptureService", ">>> CALL startRtcHost from L848")
                        startRtcHost()
                    }
                } else {
                    try {
                        conn.send(Proto.deny("对方拒绝或超时").toString())
                        conn.close(1000)
                    } catch (e: Exception) { }
                }
            }
        }
        val req = ConsentRequest(name, decide)

        if (AppState.uiVisible.value) {
            // App 在前台：由 Compose 对话框决定（挂在 AppRoot，任何页面都能弹）
            AppState.consentRequest.value = req
        } else if (Settings.canDrawOverlays(this)) {
            main.post { OverlayConsent.show(this, req) }
        } else {
            // 兜底：全屏意图通知拉起 ConsentActivity
            AppState.consentRequest.value = req
            main.post { showConsentNotification(name) }
        }
        // 60 秒超时自动拒绝（30 秒实测太赶）
        main.postDelayed({
            if (AppState.consentRequest.value === req) req.decided(false)
        }, 60_000)
    }

    private fun showConsentNotification(name: String) {
        val pi = PendingIntent.getActivity(
            this, 1, Intent(this, ConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, NOTI_CHANNEL_CONSENT)
            .setContentTitle("牵手远程：$name 请求控制你的手机")
            .setContentText("点按查看并决定")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTI_FS_CONSENT, n)
    }

    private fun releaseActiveSession(reason: String) {
        val conn = server?.activeConn
        if (server != null) server?.activeConn = null
        releaseRelay(reason)
        AppState.activeController.value = null
        if (conn != null) {
            try {
                conn.send(Proto.deny(reason).toString())
                conn.close(1000)
            } catch (e: Exception) { }
        }
    }

    // ---------- 通知 ----------

    private fun ensureChannels() {
        val cap = NotificationChannel(NOTI_CHANNEL_CAP, "远程会话", NotificationManager.IMPORTANCE_LOW)
        val consent = NotificationChannel(NOTI_CHANNEL_CONSENT, "连接请求", NotificationManager.IMPORTANCE_HIGH)
        nm.createNotificationChannel(cap)
        nm.createNotificationChannel(consent)
    }

    private fun startForegroundCompat() {
        val n = Notification.Builder(this, NOTI_CHANNEL_CAP)
            .setContentTitle("牵手远程服务运行中")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTI_CAP, n,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTI_CAP, n)
        }
    }

    private fun updateNotif() {
        val who = AppState.activeController.value
        val sb = StringBuilder(
            if (who == null) "牵手远程：等待连接（对方需配对码并经你确认）"
            else "正在被「$who」控制"
        )
        if (AppState.fps.value > 0 && who != null) sb.append(" · ${AppState.fps.value}fps")
        val actions = ArrayList<Notification.Action>()
        if (who != null) {
            actions.add(
                Notification.Action.Builder(
                    android.R.drawable.stat_notify_chat, "停止控制",
                    PendingIntent.getService(this, 2,
                        Intent(this, CaptureService::class.java).setAction(ACTION_STOP_CONTROL),
                        PendingIntent.FLAG_IMMUTABLE)
                ).build()
            )
        }
        actions.add(
            Notification.Action.Builder(
                android.R.drawable.stat_notify_chat, "结束共享",
                PendingIntent.getService(this, 3,
                    Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE)
                ).build()
        )
        val n = Notification.Builder(this, NOTI_CHANNEL_CAP)
            .setContentTitle(sb)
            .setContentText("点通知可快速关闭")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(this, 0,
                    Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            )
            .setActions(*actions.toTypedArray())
            .build()
        nm.notify(NOTI_CAP, n)
    }

    private fun stopEverything() {
        running = false
        AppState.consentRequest.value?.let {
            AppState.consentRequest.value = null
            try { it.decided(false) } catch (e: Exception) { }
        }
        AppState.activeController.value = null
        AppState.serviceRunning.value = false
        AppState.pairCode.value = ""
        AppState.fps.value = 0
        OverlayConsent.dismiss()
        try { audio?.stop() } catch (e: Exception) { }
        audio = null
        try { server?.stop() } catch (e: Exception) { }
        server = null
        relayActive = false
        try { relayClient?.stop() } catch (e: Exception) { }
        relayClient = null
        AppState.relayStatus.value = "off"
        try { captureThread?.join(500) } catch (e: Exception) { }
        captureThread = null
        try { virtualDisplay?.release() } catch (e: Exception) { }
        virtualDisplay = null
        try { encoder?.let { it.stop(); it.release() } } catch (e: Exception) { }
        encoder = null
        try { mediaProjection?.stop() } catch (e: Exception) { }
        mediaProjection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }
}
