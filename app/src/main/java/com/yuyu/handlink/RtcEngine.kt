package com.yuyu.handlink

import android.content.Context
import android.util.Log
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.SoftwareVideoDecoderFactory
import org.webrtc.SoftwareVideoEncoderFactory
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * WebRTC 核心封装。
 *
 * ============================ 为什么换成 WebRTC ============================
 *
 * 拆 ColorOS 的 ShareScreen 后确认：它用的是 com.oplus.ortc（OPPO 自研的 WebRTC 封装）。
 * 里面这些字符串是关键证据：
 *     ScreenVideo MaxBitrateKbps / MinBitrateKbps
 *     availableOutgoingBitrate
 *     BitrateAllocation
 *     DynamicFrameRateManager.getSuggestFrameRate
 *
 * 这套东西解决的是同一个问题：**网络状况随时在变，固定参数必然翻车。**
 *   · 带宽好的时候：应该自动提画质
 *   · 带宽差的时候：应该自动降码率而不是死撑丢帧
 *
 * 我们之前的做法是"拍一个固定值"，这也是"卡"和"糊"反复调不好的根源。
 *
 * ============================ 角色说明 ============================
 *
 * 被控端（host）：把 MediaProjection 的画面喂进 VideoSource，编码后发出
 * 控制端（viewer）：接收 VideoTrack 渲染到 SurfaceViewRenderer
 *
 * 信令复用现有中继的文本通道（rtc-offer / rtc-answer / rtc-ice），
 * 不需要改服务器。
 */
class RtcEngine(
    private val ctx: Context,
    private val isHost: Boolean,
    private val onSignal: (org.json.JSONObject) -> Unit,
    private val onState: (String) -> Unit,
    private val onTouch: ((String) -> Unit)? = null
) {

    companion object {
        private const val TAG = "HandLinkRtc"
        const val VIDEO_ID = "handlink-video"
        const val TOUCH_CHANNEL = "handlink-touch"

        /** 公共 STUN。真正跨运营商/严格 NAT 还需要 TURN，先用它打通大部分场景 */
        private const val STUN = "stun:YOUR_RELAY_HOST:3478"
        private const val TURN_SECRET = "REPLACE_WITH_YOUR_TURN_SECRET"

        @Volatile private var factoryReady = false
    }

    val eglBase: EglBase = EglBase.create()
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null

    private var videoSource: VideoSource? = null
    /** 供被控端把 VideoCapturer 接上来 */
    val videoSourceRef: VideoSource? get() = videoSource
    /** 被控端：把相机/虚拟屏的帧喂进来 */
    var videoCapturer: VideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null

    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null

    private var videoTrack: VideoTrack? = null
    private var touchChannel: DataChannel? = null
    @Volatile private var watchdogRunning = false
    @Volatile private var swFallback = false
    @Volatile private var running = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 是否局域网直连（由 RtcHost/RtcViewer 在 start 前设置） */
    @Volatile var isLanMode = true

    private var pendingCandidates = ArrayList<IceCandidate>()
    private var remoteSet = false

    // ---------------- 初始化 ----------------

    fun init() {
        if (!factoryReady) {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(ctx)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            )
            factoryReady = true
        }
        // 被控端（推流方）强制软件编码工厂：
        // 实测 A93s MTK 上硬件 H264 编码器探测失败 → offer 里根本没有 H264 →
        // 协商只剩 VP8 → MTK VP8 硬编对屏幕源静默停产 → viewer 0 帧（黑屏总根因）。
        // OpenH264 软编保证 H264 一定在 offer 里，接收端（全旗舰机）硬解毫无压力。
        // 控制端仍用默认工厂（硬解）。
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(
                if (isHost) SoftwareVideoEncoderFactory()
                else DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
            )
            .setVideoDecoderFactory(
                DefaultVideoDecoderFactory(eglBase.eglBaseContext)
            )
            .createPeerConnectionFactory()
        rtcDiag("factory created host=$isHost sw=$swFallback")
        Log.i(TAG, "factory created, host=$isHost")
    }

    private fun iceServers(): List<PeerConnection.IceServer> {
        // RESTful 凭证：username=过期时间戳:名字，credential=HMAC-SHA1(secret, username)
        val ttl = System.currentTimeMillis() / 1000 + 86400
        val user = "$ttl:handlink"
        val cred = try {
            val mac = javax.crypto.Mac.getInstance("HmacSHA1")
            mac.init(javax.crypto.spec.SecretKeySpec(TURN_SECRET.toByteArray(), "HmacSHA1"))
            java.util.Base64.getEncoder().encodeToString(mac.doFinal(user.toByteArray()))
        } catch (e: Exception) { "" }
        return listOf(
            // 自建 STUN（Google 的国内连不通，还会拖慢 ICE 收集）
            PeerConnection.IceServer.builder("stun://YOUR_RELAY_HOST:3478")
                .createIceServer(),
            // coturn TURN：打洞失败时的中继兜底（UDP+TCP 双Transport）
            PeerConnection.IceServer.builder("turn://YOUR_RELAY_HOST:3478?transport=udp")
                .setUsername(user).setPassword(cred)
                .setTlsCertPolicy(PeerConnection.TlsCertPolicy.TLS_CERT_POLICY_INSECURE_NO_CHECK)
                .createIceServer(),
            PeerConnection.IceServer.builder("turn://YOUR_RELAY_HOST:3478?transport=tcp")
                .setUsername(user).setPassword(cred)
                .setTlsCertPolicy(PeerConnection.TlsCertPolicy.TLS_CERT_POLICY_INSECURE_NO_CHECK)
                .createIceServer(),
        )
    }

    private fun rtcConfig(): PeerConnection.RTCConfiguration {
        return PeerConnection.RTCConfiguration(iceServers()).apply {
            // 实时投屏优先低延迟
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            // TCP 候选在 UDP 被封时能救急
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            // 投屏最小码率托底，避免 BWE 冷启动阶段把屏幕流压到几乎不出帧
            screencastMinBitrate = 1_000_000
            // CPU 过载探测必须开着：0.4.19 实测关掉后 VP8 软编全速狂飙，
            // 80 秒灌出 236MB（≈24Mbps），热点上行被打爆 → ICE DISCONNECTED
            enableCpuOveruseDetection = true
        }
    }

    // ---------------- 建立连接 ----------------

    fun start() {
        val f = factory ?: return
        running = true
        engineAlive = true
        synchronized(pcGate) {
            try { pc?.close() } catch (e: Exception) { }
            pc = null
        }

        val newPc = f.createPeerConnection(rtcConfig(), object : PeerConnection.Observer {
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {
                Log.i(TAG, "signaling: $s")
            }

            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
                Log.i(TAG, "ice: $s")
                rtcDiag("ice $s")
                onState(s?.name ?: "UNKNOWN")
            }

            override fun onIceConnectionReceivingChange(b: Boolean) { }
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {
                Log.i(TAG, "iceGather: $s")
            }

            override fun onIceCandidate(c: IceCandidate?) {
                c ?: return
                onSignal(rtcIce(c.sdpMid ?: "", c.sdpMLineIndex, c.sdp))
            }

            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) { }

            override fun onAddStream(stream: MediaStream?) {
                Log.i(TAG, "addStream: ${stream?.videoTracks?.size} video tracks")
            }

            override fun onRemoveStream(stream: MediaStream?) { }

            override fun onDataChannel(ch: DataChannel?) {
                // 触摸方向是 控制端 → 被控端，
                // 所以**被控端**必须监听收到的 DataChannel 消息。
                // 之前只在 !isHost（控制端）注册，方向反了，触摸根本收不到。
                if (ch == null) return
                touchChannel = ch
                ch.registerObserver(object : DataChannel.Observer {
                    override fun onBufferedAmountChange(l: Long) { }
                    override fun onStateChange() {
                        Log.i(TAG, "touch channel state: ${ch.state()}")
                    }
                    override fun onMessage(buf: DataChannel.Buffer?) {
                        val b = buf ?: return
                        val bytes = ByteArray(b.data.remaining())
                        b.data.get(bytes)
                        onTouch?.invoke(String(bytes))
                    }
                })
                Log.i(TAG, "data channel ready, isHost=$isHost")
            }

            override fun onRenegotiationNeeded() {
                if (isHost) {
                    if (autoOfferEnabled) {
                        Log.i(TAG, "renegotiation needed -> createOffer")
                        createOffer()
                    } else {
                        // start() 建轨阶段的自动 offer 必须压住：它和配对后的显式 offer
                        // 同飞 → viewer 收两个 offer 建两条接收轨，渲染器挂在死轨上 0 帧
                        rtcDiag("auto offer suppressed (pre-pairing)")
                    }
                }
            }

            override fun onAddTrack(r: RtpReceiver?, streams: Array<out MediaStream>?) {
                val track = r?.track()
                if (track is VideoTrack) {
                    Log.i(TAG, "got video track")
                    videoTrack = track
                    onVideoTrackReady?.invoke(track)
                }
            }
        })
        synchronized(pcGate) { pc = newPc }

        if (isHost) {
            setupHostTracks(f)
        }
        Log.i(TAG, "peerConnection ready")
    }

    /** 控制端拿到远端视频轨后的回调 */
    var onVideoTrackReady: ((VideoTrack) -> Unit)? = null

    // ---------------- 被控端：推流 ----------------

    private fun setupHostTracks(f: PeerConnectionFactory) {
        val pc = pc ?: return

        // 视频轨：由 CaptureService 把 MediaProjection 的帧喂给 videoCapturer
        // 关键：screencast=true —— 之前传 false（摄像头模式），屏幕帧走相机管线
        // 的旋转/裁剪/自适应逻辑，是 viewer 0 帧的候选根因之一
        val src = f.createVideoSource(true)
        videoSource = src
        val track = f.createVideoTrack(VIDEO_ID, src)
        track.setEnabled(true)
        pc.addTrack(track, listOf("handlink"))

        // 音频：故意不建 WebRTC 音频轨！
        // f.createAudioSource(MediaConstraints()) 默认抓的是**麦克风**——
        // 之前用户反馈"不需要打电话功能，为什么能听见那边讲话"，根因就是这条轨
        // 把被控端周围人声环境音全传了过去。
        // 我们要的声音只有一种：被控端外放的媒体声，它走自己的
        // AudioPlaybackCapture → AAC → WebSocket 通道，不经 WebRTC。
        // 触摸通道：被控端建房，控制端连。
        //
        // 关键：设成**不可靠 + 不有序**（maxRetransmits=0, ordered=false）。
        // 触摸坐标是"过期即作废"的数据 —— 第 10 个点迟到了也没用，
        // 重传只会让后面的点排队等，反而更不跟手。
        // UDP 语义下直接丢，永远发最新的那个，延迟最低。
        val init = DataChannel.Init()
        init.ordered = false
        init.maxRetransmits = 0
        touchChannel = pc.createDataChannel(TOUCH_CHANNEL, init)
        rtcDiag("host tracks ready (swFactory=$swFallback)")
        Log.i(TAG, "host tracks ready")
    }

    /** 被控端发起 offer */
    private val negLock = Any()
    @Volatile private var negotiating = false
    /** 配对允许后才放开自动 offer（RtcHost.start 显式 createOffer 前置 true） */
    @Volatile var autoOfferEnabled = false
    fun createOffer() {
        if (!engineAlive) return
        val pc = pc ?: return
        // 去重：addTrack 会触发 onRenegotiationNeeded 自动 offer 一次，
        // 配对允许后又显式调一次 → 双 offer 双 answer，host 只 apply 其一，
        // SSRC/payload 对不上 → viewer ICE CONNECTED 但 0 帧（实测 0.4.13）。
        synchronized(negLock) {
            if (negotiating) { rtcDiag("createOffer skipped (negotiating in progress)"); return }
            negotiating = true
            // 非 STABLE（start() 时 addTrack 自动发过没人应答的 offer，卡在 HAVE_LOCAL_OFFER）
            // 先回滚再重新发，保证配对后的 offer 一定基于最新轨道状态
            if (pc.signalingState() != PeerConnection.SignalingState.STABLE) {
                rtcDiag("createOffer rollback from ${pc.signalingState()}")
                pc.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() { doCreateOffer(pc) }
                    override fun onSetFailure(err: String?) {
                        rtcDiag("rollback failed: $err")
                        synchronized(negLock) { negotiating = false }
                    }
                }, SessionDescription(SessionDescription.Type.OFFER, ""))
                return
            }
        }
        doCreateOffer(pc)
    }

    private fun doCreateOffer(pc: PeerConnection) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        pc.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp ?: return
                pc.setLocalDescription(SdpObserverAdapter(), sdp)
                onSignal(rtcOffer(sdp.description))
                rtcDiag("offer sent len=${sdp.description.length} h264=${sdp.description.contains("H264")}")
                synchronized(negLock) { negotiating = false }
                Log.i(TAG, "offer sent, len=${sdp.description.length}")
            }

            override fun onCreateFailure(err: String?) {
                rtcDiag("createOffer failed: $err")
                synchronized(negLock) { negotiating = false }
                Log.e(TAG, "createOffer failed: $err")
            }
        }, constraints)
    }

    /** 控制端处理 offer 并回 answer */
    fun handleOffer(sdp: String) {
        if (!engineAlive) return
        val pc = pc ?: return
        if (pc.signalingState() != PeerConnection.SignalingState.STABLE) {
            rtcDiag("handleOffer skipped state=${pc.signalingState()}")
            return
        }
        val desc = SessionDescription(SessionDescription.Type.OFFER, sdp)
        pc.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                remoteSet = true
                flushCandidates()
                rtcDiag("offer applied (viewer)")
                startOutboundWatchdog(pc)
                // 控制端主动开一个接收用的 DataChannel 接收触摸?
                // 不：触摸是被控端发指令给控制端？不是。
                // 触摸方向是 控制端 -> 被控端，所以控制端要能向被控端的 channel 发消息。
                // UNIFIED_PLAN 下，控制端 onDataChannel 拿到的是被控端建的通道，可直接 send。
                val c = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                }
                pc.createAnswer(object : SdpObserverAdapter() {
                    override fun onCreateSuccess(ans: SessionDescription?) {
                        ans ?: return
                        pc.setLocalDescription(SdpObserverAdapter(), ans)
                        onSignal(rtcAnswer(ans.description))
                        rtcDiag("answer sent len=${ans.description.length}")
                        Log.i(TAG, "answer sent, len=${ans.description.length}")
                    }

                    override fun onCreateFailure(err: String?) {
                        Log.e(TAG, "createAnswer failed: $err")
                    }
                }, c)
            }

            override fun onSetFailure(err: String?) {
                Log.e(TAG, "setRemote(offer) failed: $err")
            }
        }, desc)
    }

    fun handleAnswer(sdp: String) {
        if (!engineAlive) return
        val pc = pc ?: return
        if (pc.signalingState() != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
            rtcDiag("handleAnswer skipped state=${pc.signalingState()}")
            return
        }
        val desc = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        pc.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                remoteSet = true
                flushCandidates()
                rtcDiag("answer applied")
                Log.i(TAG, "answer applied")
                forceScreencastCodec(pc)
                startOutboundWatchdog(pc)
            }

            override fun onSetFailure(err: String?) {
                Log.e(TAG, "setRemote(answer) failed: $err")
            }
        }, desc)
    }

    /**
     * 关键修复（实测链路：captured 有帧率、viewer Frames received: 0、ICE CONNECTED）：
     * M74 的 VideoStreamEncoder 默认从 SDP 编解码器表取第一个 payload，被控端 offer 表头是
     * VP8 —— MTK 硬编在屏幕源 VP8 上静默不产出 RTP，一帧都不发。
     * 屏幕投屏必须钉死 H264：negotiated codecs 重排 + setParameters 显式生效。
     */
    private fun forceScreencastCodec(pc: PeerConnection) {
        try {
            val sender = pc.senders?.firstOrNull { it.track()?.kind() == "video" }
                ?: run { rtcDiag("forceCodec: NO video sender (senders=${pc.senders?.size})"); return }
            val params = sender.getParameters()
                ?: run { rtcDiag("forceCodec: params null"); return }
            val codecs = params.codecs
                ?: run { rtcDiag("forceCodec: codecs null"); return }
            val h264 = codecs.filter { it.name.equals("H264", ignoreCase = true) }
            if (h264.isEmpty()) {
                rtcDiag("forceCodec: no H264 in ${codecs.map { it.name }}")
                Log.w(TAG, "no H264 in negotiated codecs"); return
            }
            val rest = codecs.filter { !it.name.equals("H264", ignoreCase = true) }
            // 优先 packetization-mode=1
            val preferred = h264.firstOrNull {
                it.parameters?.any { p -> p.key == "packetization-mode" && p.value == "1" } == true
            } ?: h264.first()
            // M74 库 RtpParameters 构造 package-private、字段 final：反射改原对象
            params.javaClass.getField("codecs").set(
                params, ArrayList(listOf(preferred) + h264.filter { it !== preferred } + rest)
            )
            params.javaClass.getField("transactionId").set(params, "hl-h264-${System.currentTimeMillis()}")
            // 码率封顶：实测 0.4.19 VP8 软编在 BWE 爬坡下冲到 24Mbps，
            // 热点上行被打爆 → ICE DISCONNECTED → 触摸通道死亡。
            // 4Mbps 足够 720p 屏幕流，且给信令/RTCP 留活路。
            // 码率与降级策略。
            //
            // 之前封 4Mbps + 30fps，实测三个抱怨全中：
            //   · 糊：2K 屏只给 720p，放大必糊
            //   · 卡：30fps 对 120Hz 屏只有四分之一
            //   · 不跟手：WebRTC 默认 MAINTAIN_FRAMERATE，带宽一紧就降分辨率，
            //     画面先糊再恢复，观感就是"抖"
            //
            // 现在：局域网给足 16Mbps/60fps，中继保守 8Mbps/45fps。
            params.encodings?.forEach { enc ->
                try {
                    val lan = isLanMode
                    // ============================================================
                    // 码率策略：和 ColorOS 反着来。
                    //
                    // ColorOS 用 FramerateBitrateAdjuster：带宽一紧就降帧率，
                    // 用户感受就是"卡"。
                    //
                    // 我们的做法：
                    //   · 局域网：16Mbps / 60fps —— 热点/局域网带宽充足，给满
                    //   · 中继：8Mbps / 45fps —— 公网留余量但保证流畅
                    //   · degradationPreference = MAINTAIN_RESOLUTION
                    //     带宽不足时**优先降码率**，而不是降帧率/分辨率
                    //   · 起步码率直接给到 60%，避免 BWE 从很低开始爬坡，
                    //     头几秒的画面糊（这是 ColorOS 也有的毛病）
                    val maxBr = if (lan) 16_000_000 else 8_000_000
                    val minBr = if (lan) 4_000_000 else 2_000_000
                    val maxFps = if (lan) 60.0 else 45.0

                    enc.javaClass.getField("maxBitrate").set(enc, maxBr)
                    enc.javaClass.getField("minBitrate").set(enc, minBr)
                    enc.javaClass.getField("maxFramerate").set(enc, maxFps)

                    try {
                        enc.javaClass.getField("degradationPreference").set(
                            enc, org.webrtc.RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
                        )
                    } catch (e2: Throwable) { }

                    rtcDiag("enc: max=${maxBr/1e6}M min=${minBr/1e6}M fps=$maxFps")
                } catch (e: Throwable) { rtcDiag("enc cap fail ${e.message}") }
            }
            val ok = sender.setParameters(params)
            Log.i(TAG, "force H264 screencast codec: $ok (payload=${preferred.payloadType})")
            rtcDiag("force H264 codec ok=$ok payload=${preferred.payloadType}")
            startOutboundWatchdog(pc)
        } catch (e: Throwable) {
            Log.w(TAG, "forceScreencastCodec failed", e)
            rtcDiag("forceScreencastCodec EXC ${e.message}")
        }
    }

    /**
     * 出站看门狗：每 4 秒读一次 video 发送流的 bytesSent。
     * ICE CONNECTED 但 12 秒内 bytesSent 不涨 = 编码器没产出 RTP（实测 MTK 硬编
     * 对屏幕源静默停产）→ 用软件编解码器工厂重建整个 PeerConnection。
     */
    private fun startOutboundWatchdog(pc: PeerConnection) {
        if (watchdogRunning) return
        watchdogRunning = true
        Thread {
            var lastBytes = -1L
            var stalledRounds = 0
            while (watchdogRunning && running) {
                try {
                    Thread.sleep(4000)
                    if (!watchdogRunning) break
                    val latch = java.util.concurrent.CountDownLatch(1)
                    var sent = 0L
                    var recv = 0L
                    var framesR = 0L
                    var framesD = 0L
                    var decImpl = "?"
                    if (!synchronized(pcGate) { pc === this@RtcEngine.pc }) continue
                    pc.getStats { report: org.webrtc.RTCStatsReport? ->
                        report?.getStatsMap()?.values?.forEach { s ->
                            if (s.type == "outbound-rtp") {
                                val m = s.members["bytesSent"]
                                if (m is Number) sent += m.toLong()
                            }
                            if (s.type == "inbound-rtp") {
                                val m = s.members["bytesReceived"]
                                if (m is Number) recv += m.toLong()
                                val fr = s.members["framesReceived"]
                                if (fr is Number) framesR += fr.toLong()
                                val fd = s.members["framesDecoded"]
                                if (fd is Number) framesD += fd.toLong()
                            }
                            if (s.type == "video-decoder") {
                                s.members["decoderImplementation"]?.let { decImpl = "$it" }
                            }
                        }
                        latch.countDown()
                    }
                    latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
                    if (isHost) rtcDiag("outbound bytesSent=$sent (last=$lastBytes)")
                    else rtcDiag("inbound bytesRecv=$recv framesRecv=$framesR framesDecoded=$framesD decoder=$decImpl")
                    if (!isHost) continue
                    if (lastBytes >= 0 && sent == lastBytes) {
                        stalledRounds++
                        if (stalledRounds >= 3) {
                            rtcDiag("encoder stalled -> rebuild with SOFTWARE codecs")
                            watchdogRunning = false
                            swFallback = true
                            // viewer 的 PeerConnection 由信令线程（WebSocket onMessage）
                            // 驱动，从看门狗线程 close+重建会跨线程竞争 nativeAddIceCandidate
                            // 的 pc_-> 指针 → SIGSEGV（实测一加 20:10/20:14 两次闪退）。
                            // viewer 侧不做自动重建，留给重连按钮；host 侧信令本就在主线程。
                            if (isHost) mainHandler.post { restartConnection() }
                            return@Thread
                        }
                    } else stalledRounds = 0
                    lastBytes = sent
                } catch (e: Throwable) {
                    rtcDiag("watchdog exc ${e.message}")
                    break
                }
            }
        }.apply { name = "rtc-watchdog"; isDaemon = true; start() }
    }

    /** 用当前（可能是软件）工厂重建 PeerConnection，SDP 重新协商 */
    private fun restartConnection() {
        synchronized(pcGate) {
            try { pc?.close() } catch (e: Exception) { }
            pc = null
        }
        videoSource?.dispose(); videoSource = null
        touchChannel = null
        remoteSet = false
        pendingCandidates.clear()
        // 换纯软件编解码工厂（libvpx VP8 + OpenH264，全平台必然可用）
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(SoftwareVideoEncoderFactory())
            .setVideoDecoderFactory(SoftwareVideoDecoderFactory())
            .createPeerConnectionFactory()
        start()
        if (isHost) createOffer()
    }

    private fun rtcDiag(msg: String) {
        try {
            val dir = ctx.getExternalFilesDir(null) ?: return
            java.io.File(dir, "rtc_diag.txt").appendText("${System.currentTimeMillis()} RTC:$msg\n")
        } catch (e: Exception) { }
    }

    // 所有碰 pc_ 的操作过 pcGate：dispose/restart 持锁关 pc，
    // 拿不到锁的调用方直接跳过 —— 根治 nativeAddIceCandidate SIGSEGV
    // （viewer 实测 20:10/20:14/20:36 三次闪退，OkHttp 线程 vs watchdog 线程竞争 pc_）
    private val pcGate = Any()

    // 铁律（M74 native 崩溃教训）：Java 对象活着 ≠ native pc_ 活着。
    // factory.dispose 后任何 PeerConnection 方法都是野指针读——
    // 崩溃栈 fault addr 尾缀恒定 0x...713817422c = 同一失效基址，4 次闪退同源。
    // 唯一安全做法：engineAlive 标志 + 所有信令入口先检查。
    @Volatile private var engineAlive = false

    fun addIce(mid: String, mline: Int, cand: String) {
        if (!engineAlive) return
        val c = IceCandidate(mid, mline, cand)
        if (remoteSet) {
            synchronized(pcGate) { if (engineAlive) pc?.addIceCandidate(c) }
        } else {
            pendingCandidates.add(c)
        }
    }

    private fun flushCandidates() {
        val pc = pc ?: return
        if (!engineAlive) return
        synchronized(pcGate) {
            if (!engineAlive) return@synchronized
            for (c in pendingCandidates) pc.addIceCandidate(c)
            pendingCandidates.clear()
        }
    }

    // ---------------- 触摸：控制端 → 被控端 ----------------

    fun sendTouch(json: String): Boolean {
        val ch = touchChannel ?: return false
        if (ch.state() != DataChannel.State.OPEN) return false
        return try {
            ch.send(DataChannel.Buffer(java.nio.ByteBuffer.wrap(json.toByteArray()), false))
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---------------- 被控端：绑定采集源 ----------------

    /**
     * 被控端把 VideoCapturer 接到 VideoSource 上。
     * CaptureService 需要自己实现一个喂帧的 VideoCapturer。
     */
    /**
     * 被控端：创建 SurfaceTextureHelper，并把 capturer 以 VideoSource 为 observer 初始化。
     *
     * 这一步必须由 RtcEngine 做，因为只有它持有 VideoSource。
     * VideoCapturer 收到的每一帧都会回调给这个 observer，进而进入 WebRTC 编码。
     */
    fun createCaptureHelper(cap: VideoCapturer): SurfaceTextureHelper {
        val h = SurfaceTextureHelper.create("hl-capture", eglBase.eglBaseContext)
        surfaceHelper = h
        val src = videoSource
        if (src != null) {
            cap.initialize(h, ctx, src.capturerObserver)
        } else {
            Log.e(TAG, "videoSource is null, capture will not start")
        }
        return h
    }


    // ---------------- 控制端：渲染 ----------------

    fun attachRenderer(renderer: SurfaceViewRenderer) {
        val track = videoTrack ?: return
        renderer.setMirror(false)
        track.removeSink(renderer)  // 幂等：重复 attach 不双投递
        track.addSink(renderer)
        rtcDiag("sink attached to existing track")
    }

    fun detachRenderer(renderer: SurfaceViewRenderer) {
        videoTrack?.removeSink(renderer)
    }

    // ---------------- 释放 ----------------

    fun dispose() {
        engineAlive = false
        // 顺序=一切 native 崩溃的根源修复：先 stop 看门狗并等它退出，
        // 再动 pc_/factory。否则 watchdog 的 getStats 与 pc.close/factory.dispose
        // 并发 → native 指针失效 → SIGSEGV（一加 20:10/20:14 两次实测）。
        watchdogRunning = false
        running = false
        // 最多等 4 秒让 rtc-watchdog 退出（它每 4 秒醒一次）；
        // 若 dispose 本身跑在信令线程上，等待要短，避免卡死 WebSocket 回调
        val onSignalThread = Thread.currentThread().name.contains("OkHttp")
        var waited = 0
        while (waited < (if (onSignalThread) 500 else 4000)) {
            val alive = Thread.getAllStackTraces().keys.any {
                it.name == "rtc-watchdog" && it.state != Thread.State.TERMINATED
            }
            if (!alive) break
            Thread.sleep(100); waited += 100
        }
        try { touchChannel?.close() } catch (e: Exception) { }
        touchChannel = null
        try { videoCapturer?.stopCapture() } catch (e: Exception) { }
        try { videoCapturer?.dispose() } catch (e: Exception) { }
        videoCapturer = null
        try { surfaceHelper?.dispose() } catch (e: Exception) { }
        surfaceHelper = null
        try { videoSource?.dispose() } catch (e: Exception) { }
        videoSource = null
        try { audioSource?.dispose() } catch (e: Exception) { }
        audioSource = null
        synchronized(pcGate) {
            try { pc?.close() } catch (e: Exception) { }
            pc = null
        }
        try { factory?.dispose() } catch (e: Exception) { }
        factory = null
        try { eglBase.release() } catch (e: Exception) { }
        Log.i(TAG, "disposed")
    }

    /** 统一的 SdpObserver：只关心需要的那几个回调 */
    private open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) { }
        override fun onSetSuccess() { }
        override fun onCreateFailure(p0: String?) { }
        override fun onSetFailure(p0: String?) { }
    }
}