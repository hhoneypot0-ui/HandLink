package com.yuyu.handlink

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 控制端：连接被控端（局域网 WS 或异地中继）+ H264 解码到 Surface + 音频播放 + 发送输入事件。
 * 连接对象由 AppState 持有，切后台不断线；断开后按原参数自动重连。
 */
class RemoteLink {

    /** WebRTC 接收通道 */
    var rtcViewer: RtcViewer? = null

    /**
     * 局域网/中继统一客户端。
     *
     * 踩坑记录：
     *   · proxy(NO_PROXY) 是必须的，ColorOS 的系统代理会劫持局域网连接
     *   · pingInterval 交给业务层的心跳，不要在 HTTP 层开 ——
     *     它会让 OkHttp 在握手阶段就尝试发送控制帧，某些服务端实现会直接断开
     *   · readTimeout 必须给个有限值，0（无限）在某些网络栈上会读不到握手响应
     */
    private val http = OkHttpClient.Builder()
        .proxy(java.net.Proxy.NO_PROXY)
        .readTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val mainH = Handler(Looper.getMainLooper())

    private var ws: WebSocket? = null
    private var decoder: RemoteDecoder? = null
    @Volatile private var player: RemoteAudioPlayer? = null
    private var relayMode = false
    private var relayRoom = ""
    private var pendingCode = ""
    private var pendingName = ""
    private var pendingSurface: Surface? = null
    private var lastReq: okhttp3.Request? = null

    /** 当前编码尺寸（来自被控端 size 消息） */
    @Volatile private var vw = 0
    @Volatile private var vh = 0

    /** 当前渲染目标 Surface；Activity 切后台会被销毁，回来要换新的 */
    @Volatile private var target: Surface? = null

    /** 解码器当前绑定的 Surface，用来避免尺寸变化引发的重复重建 */
    @Volatile private var boundSurface: Surface? = null

    /** 最近一次收到的视频 SPS/PPS（带 9 字节帧头），解码器重建时要补喂 */
    @Volatile private var lastCodecConfig: ByteArray? = null

    /** 解码器还没建好时先攒着的帧 */
    private val pendingFrames = ConcurrentLinkedQueue<ByteArray>()

    @Volatile var connected = false

    // 帧格式诊断日志计数（只打前 2 条）
    private var cfgLogCount = 0
    private var keyLogCount = 0
    // 硬件解码卡死后的软解回退开关（一次会话内保持）
    @Volatile private var swFallback = false
    /** 本机解码能力，连上时上报给被控端，让它决定编码尺寸 */
    private val decCap by lazy { DecoderProfile.detect() }

    /** 连接参数，用于切后台/断线后的自动重连 */
    @Volatile private var appCtx: android.content.Context? = null
    @Volatile private var lastIp = ""
    @Volatile private var lastCode = ""
    @Volatile private var lastName = ""

    /** 用户是否希望保持连接（点了「断开连接」才是 false） */
    @Volatile var wantConnected = false
        private set

    /** 回到前台：Surface 换新 + 如果连接断了就按原参数重连 */
    fun onForeground(surface: Surface) {
        attachSurface(surface)
        if (!wantConnected) return
        if (ws == null && lastReq != null) {
            Log.i("RemoteLink", "auto reconnect")
            open(lastReq!!, relayMode)
        }
    }

    /** 控制端上线/回到前台时把新的 Surface 交进来，必要时重建解码器 */
    @Synchronized
    /**
     * 用 UI 侧计算出的精确像素尺寸覆盖视频尺寸。
     *
     * 绿屏根因：SurfaceView 的 BufferQueue 尺寸必须和视频帧尺寸严格一致，
     * 差 1px 硬件解码器就输出绿屏。视频流本身是 720x1600，但屏幕上
     * 按比例缩放后是比如 605x1345，两者不等 —— 这里统一成屏幕上的显示尺寸。
     */
    fun overrideDisplaySize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        if (w == vw && h == vh) return
        Log.i("RemoteLink", "overrideDisplaySize ${vw}x${vh} -> ${w}x${h}")
        vw = w; vh = h
        val s = target
        if (s != null && s.isValid) attachSurface(s)
    }

    fun attachSurface(surface: Surface) {
        target = surface
        if (!surface.isValid) { Log.i("RemoteLink", "attachSurface: invalid surface"); return }
        val w = vw; val h = vh
        if (w <= 0 || h <= 0) { Log.i("RemoteLink", "attachSurface: no size yet (${w}x${h})"); return }
        if (boundSurface === surface && decoder != null) return  // 已经挂在这个 Surface 上
        Log.i("RemoteLink", "attachSurface -> rebuild ${w}x${h}, surfaceFrame=${surface.toString()}")
        rebuildDecoder(w, h, surface)
    }

    /**
     * Surface 被系统销毁：只停解码器，不动 WS 连接。
     * 必须带上被销毁的那个 Surface 做校验：表单切全屏时，新 SurfaceView 的
     * surfaceCreated 可能先于旧 surfaceDestroyed 到达，旧回调若无条件清
     * target/decoder，会把刚在新 Surface 上建好的解码器杀掉，之后永远黑屏。
     */
    @Synchronized
    fun detachSurface(destroyed: Surface) {
        if (target !== destroyed && boundSurface !== destroyed) return
        target = null
        boundSurface = null
        decoder?.stop(); decoder = null
        AppState.decodedFps.value = 0
    }

    /** 每次新建解码器都重新尝试硬件解码：软解只作为本次会话的降级手段，
     *  不能因为上一次会话失败就永久软解 —— 那会让本来能硬解的手机全程卡顿。 */
    @Synchronized
    private fun rebuildDecoder(w: Int, h: Int, surface: Surface) {
        decoder?.stop()
        decoder = try {
            RemoteDecoder(w, h, surface, preferSw = swFallback).also { dec ->
                // 把已缓存的 SPS/PPS 交给解码器，硬件解码器必须在
                // configure 之前拿到 csd-0，否则永远不出帧
                val cfg = lastCodecConfig
                Log.i("RemoteLink", "decoder create: lastCodecConfig=${cfg?.size ?: "NULL"}")
                if (cfg != null && cfg.size > 9) {
                    dec.setCsd(cfg.copyOfRange(9, cfg.size))
                }
                dec.start()
            }
        } catch (e: Exception) {
            Log.w("RemoteLink", "decoder create failed", e)
            null
        }
        boundSurface = if (decoder != null) surface else null
        val d = decoder
        if (d != null) {
            // 硬件解码器对某些厂商码流（实测 A93s MTK 编码 → 一加15 硬解）会"出一帧后哑掉"，
            // 检测喂帧多却长时间无输出 → 自动重建为软解（c2.android.avc.decoder）。
            d.onStall = {
                mainH.post {
                    if (!swFallback) {
                        Log.i("RemoteLink", "hw decoder stalled -> fallback to software decoder")
                        swFallback = true
                        val s = target
                        if (s != null && s.isValid) rebuildDecoder(vw, vh, s)
                    }
                }
            }
            // 先把缓存下来的 SPS/PPS 喂进去，再补发攒着的帧
            lastCodecConfig?.let { d.feed(it) }
            while (true) {
                val f = pendingFrames.poll() ?: break
                d.feed(f)
            }
        }
        pendingFrames.clear()
    }

    fun connect(ip: String, code: String, myName: String, surface: Surface, ctx: android.content.Context? = null) {
        start(code, myName, surface, ctx)
        lastIp = ip.trim()
        AppState.remoteIp.value = ip.trim()
        val req = okhttp3.Request.Builder().url("ws://$ip:$WS_PORT").build()
        open(req, relay = false)
    }

    fun connectRelay(relayUrl: String, room: String, code: String, myName: String, surface: Surface, ctx: android.content.Context? = null) {
        start(code, myName, surface, ctx)
        relayRoom = room
        AppState.remoteIp.value = relayUrl
        val req = okhttp3.Request.Builder().url(relayUrl).build()
        open(req, relay = true)
    }

    private fun start(code: String, myName: String, surface: Surface, ctx: android.content.Context?) {
        disconnect(keepWish = true)
        pendingCode = code.trim(); pendingName = myName.trim(); pendingSurface = surface
        lastCode = code.trim(); lastName = myName.trim()
        wantConnected = true
        // 连接期间跑前台服务保活，避免切后台被冻结导致 ping 停发、对端判超时断开
        appCtx = ctx?.applicationContext ?: appCtx
        appCtx?.let { ControlKeepAliveService.start(it) }
        AppState.remoteName.value = code
        AppState.connState.value = "connecting"
    }

    private fun open(req: okhttp3.Request, relay: Boolean) {
        relayMode = relay
        lastReq = req
        AppState.connState.value = "connecting"
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (relayMode) {
                    webSocket.send(JSONObject().put("t", "join").put("room", relayRoom).toString())
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val j = try { JSONObject(text) } catch (e: Exception) { return }
                Log.i("RemoteLink", "recv t=${j.optString("t")}")
                when (j.optString("t")) {
                    "joined" -> { webSocket.send(Proto.pair(pendingCode, pendingName, decCap.maxW, decCap.maxH, decCap.hasHw, decCap.score).toString()); AppState.connState.value = "waiting" }
                    "hello" -> { webSocket.send(Proto.pair(pendingCode, pendingName, decCap.maxW, decCap.maxH, decCap.hasHw, decCap.score).toString()); AppState.connState.value = "waiting" }
                    "hosted" -> { /* 被控端消息，观众忽略 */ }
                    "note" -> { /* 中间提示 */ }
                    "deny" -> {
                        AppState.connState.value = "denied:" + j.optString("reason")
                        connected = false
                        decoder?.stop(); decoder = null
                    }
                    "accept" -> {
                        Log.i("RemoteLink", "ACCEPT received, useRtc=${AppState.useRtc.value} rtcViewer=$rtcViewer appCtx=${appCtx != null}")
                        AppState.peerName.value = j.optString("peer")
                        // 连接确认：如果启用 RTC，主动通知被控端发起 offer
                        val c = appCtx
                        if (AppState.useRtc.value && rtcViewer == null && c != null) {
                            val v = RtcViewer(c) { sig -> rawSend(sig) }
                            v.onState = { st -> Log.i("RemoteLink", "rtc state=$st") }
                            v.start()
                            rtcViewer = v
                            // 告知被控端：我准备好了，请发 offer
                            rawSend(rtcReady())
                            Log.i("RemoteLink", "rtc viewer started, sent rtc-ready")
                        }
                        AppState.connState.value = "connected"
                        connected = true
                        // 声音：控制端听到的＝被控端此刻外放的声音；
                        // 音量走本机媒体音量，被控端不受影响
                        if (player == null) player = RemoteAudioPlayer().also { it.start() }
                        // Surface 可能已就绪（重连场景），挂上解码器
                        val s = target
                        if (s != null && s.isValid) attachSurface(s)
                    }
                    // ---- WebRTC 信令 ----
                    "rtc-offer" -> {
                        Log.i("RemoteLink", "got rtc offer")
                        rtcViewer?.handleOffer(j.optString("sdp"))
                    }
                    "rtc-ice" -> {
                        rtcViewer?.handleIce(
                            j.optString("mid"),
                            j.optInt("mline", 0),
                            j.optString("cand")
                        )
                    }
                    "size" -> {
                        val w = j.optInt("w"); val h = j.optInt("h")
                        Log.i("RemoteLink", "size ${w}x${h}")
                        vw = w; vh = h
                        AppState.remoteW.value = w; AppState.remoteH.value = h
                        // Surface 可能还不可用（切后台中），等 attachSurface 时再建
                        val s = target
                        if (s != null && s.isValid) attachSurface(s)
                        else Log.i("RemoteLink", "size: no target surface yet")
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                val arr = bytes.toByteArray()
                if (arr.size < 9) return
                val fl = decodeFrameFlags(arr)

                // 音频帧（bit2）：交给播放器，不进视频解码器
                if (fl and 0x04 != 0) {
                    val p = player
                    if (p != null) p.feed(arr.copyOfRange(9, arr.size), fl and 0x02 != 0)
                    return
                }

                // 视频 SPS/PPS 缓存：解码器可能还没建好或中途重建，
                // 没有 config 的话收到关键帧也解不出画面。
                if (fl and 0x02 != 0) {
                    lastCodecConfig = arr
                    if (cfgLogCount++ < 2) {
                        // 诊断 MTK 等厂商编码器输出格式：AnnexB(00 00 00 01) vs avcc(长度前缀)
                        Log.i("RemoteLink", "video cfg ${arr.size - 9}B: ${arr.copyOfRange(9, minOf(25, arr.size)).joinToString(",") { (it.toInt() and 0xFF).toString(16) }}")
                    }
                    return
                }
                if (keyLogCount++ < 2) {
                    Log.i("RemoteLink", "video frame flags=$fl size=${arr.size} head=${arr.copyOfRange(9, minOf(25, arr.size)).joinToString(",") { (it.toInt() and 0xFF).toString(16) }}")
                }
                val d = decoder
                if (d == null) {
                    if (pendingFrames.size < 30) pendingFrames.offer(arr)
                    return
                }
                d.feed(arr)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w("RemoteLink", "ws failure", t)
                connected = false
                if (ws === webSocket) ws = null
                if (!AppState.connState.value.startsWith("denied")) {
                    AppState.connState.value =
                        if (AppState.connState.value == "connected") "closed:连接中断，正在重连…"
                        else "error:连不上，正在重试…"
                }
                cleanupDecoder()
                // 用户没主动断开就按原参数自动重连（2 秒退避）
                if (wantConnected && lastReq != null) {
                    mainH.postDelayed({
                        if (wantConnected && ws == null) {
                            Log.i("RemoteLink", "retry open")
                            open(lastReq!!, relayMode)
                        }
                    }, 2000L)
                }
            }
        })
    }

    private fun cleanupDecoder() {
        decoder?.stop(); decoder = null
        vw = 0; vh = 0
        boundSurface = null
        try { player?.stop() } catch (e: Exception) { }
        player = null
        AppState.remoteW.value = 0; AppState.remoteH.value = 0
        AppState.decodedFps.value = 0
    }

    fun tap(nx: Float, ny: Float) = rawSend(Proto.tap(nx, ny))
    fun longPress(nx: Float, ny: Float) = rawSend(Proto.longPress(nx, ny))
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) =
        rawSend(Proto.swipe(x1, y1, x2, y2, ms))

    /** 中断当前拖动（比如控制端手指抬起异常） */
    fun cancelTouch() = rawSend(JSONObject().put("t", "cancel"))

    fun down(nx: Float, ny: Float) = rawSend(Proto.down(nx, ny))
    fun move(nx: Float, ny: Float) = rawSend(Proto.move(nx, ny))
    fun up(nx: Float, ny: Float) = rawSend(Proto.up(nx, ny))
    fun cancel() = rawSend(Proto.cancel())
    fun key(k: String) = rawSend(Proto.key(k))
    fun sendText(s: String) = rawSend(Proto.text(s))

    /**
     * 统一出口。
     *
     * ============================ 为什么必须分流 ============================
     *
     * 触摸事件以前全走 WebSocket，而 WebSocket 是 TCP：
     *   · TCP 保证有序，一个包丢了后面的全等着（队头阻塞）
     *   · 网络稍有抖动，延迟就会累积到几百毫秒
     *   · 表现就是用户说的"超级不跟手"
     *
     * WebRTC 的 DataChannel 默认走 SCTP over DTLS over **UDP**，
     * 可选不可靠不有序模式，天然适合触摸这种"过期就没意义"的数据。
     *
     * 所以触摸类消息优先走 DataChannel，信令类（pair/rtc-*）仍然走 WebSocket
     * —— 信令必须可靠有序。
     */
    private fun rawSend(msg: JSONObject) {
        val tag = msg.optString("t")

        // 触摸类：优先 DataChannel
        val isTouch = tag in TOUCH_TAGS
        if (isTouch) {
            val v = rtcViewer
            if (v != null && v.sendTouch(msg.toString())) return
        }

        val w = ws ?: return
        if (connected) try { w.send(msg.toString()) } catch (e: Exception) { }
    }

    companion object {
        /** 这些是触摸事件，走 DataChannel（不可靠、低延迟） */
        private val TOUCH_TAGS = setOf("down", "move", "up", "tap", "lp", "swipe", "cancel")
    }

    fun disconnect() {
        swFallback = false   // 新会话重新尝试硬解
 disconnect(keepWish = false) }

    /** 只由内部调用：connect() 重建连接时不要把「用户想保持连接」这个意图清掉 */
    private fun disconnect(keepWish: Boolean) {
        connected = false
        target = null
        if (!keepWish) {
            wantConnected = false
            lastIp = ""; lastCode = ""; lastName = ""
            lastReq = null
        }
        val old = ws
        ws = null
        if (old != null) {
            // 先告诉被控端主动结束，再关连接；避免对端把「新控制端接管」当成异常
            try { if (AppState.connState.value == "connected") old.send(Proto.stop().toString()) } catch (e: Exception) { }
            try { old.close(1000, null) } catch (e: Exception) { }
        }
        cleanupDecoder()
        AppState.peerName.value = ""
        AppState.connState.value = "idle"
        // 用户主动断开才停保活；connect() 内部重建时 keepWish=true 会跳过
        if (!keepWish) appCtx?.let { ControlKeepAliveService.stop(it) }
    }

    /** 进程是否还有活动连接（Activity 销毁时不销毁它，交给用户手动断开） */
    val isActive: Boolean get() = ws != null
}

internal data class Frame(val data: ByteArray, val pts: Long, val flags: Int)

/** H264 解码器：feed 二进制帧（带自定义头），输出到 Surface */
class RemoteDecoder(val w: Int, val h: Int, val surface: Surface, val preferSw: Boolean = false) {

    /** 喂帧充足却长时间无输出（硬件解码器吃不进某些厂商码流）时回调一次 */
    var onStall: (() -> Unit)? = null

    /** SPS/PPS（csd-0），必须在 configure 之前设置，否则硬件解码器不出帧 */
    @Volatile private var csdData: ByteArray? = null

    /** 上层收到 CODEC_CONFIG 帧时调用 */
    fun setCsd(bytes: ByteArray) {
        if (csdData == null) {
            csdData = bytes.copyOf()
            Log.i("RemoteDecoder", "csd stored ${bytes.size}B")
        }
    }

    private var codec: MediaCodec? = null
    @Volatile private var running = false
    private val info = MediaCodec.BufferInfo()
    private val queue = ConcurrentLinkedQueue<Frame>()
    /** 因延迟保护丢掉的帧数 */
    @Volatile var droppedForLatency = 0L
        private set

    /** 按优先级挑硬件解码器，找不到返回系统默认 */
    private fun pickHwDecoder(): MediaCodec {
        val prefer = listOf(
            "c2.qti.avc.decoder.low_latency",   // 高通低延迟：投屏首选
            "c2.qti.avc.decoder",               // 高通常规
            "c2.mtk.avc.decoder",               // 联发科
            "c2.hisi.avc.decoder",              // 麒麟
            "OMX.qcom.video.decoder.avc",       // 高通旧命名
            "OMX.MTK.VIDEO.DECODER.AVC"         // 联发科旧命名
        )
        for (name in prefer) {
            try {
                val c = MediaCodec.createByCodecName(name)
                Log.i("RemoteDecoder", "picked hw decoder: $name")
                return c
            } catch (e: Exception) {
                // 这台机器没有这个解码器，继续试下一个
            }
        }
        Log.w("RemoteDecoder", "no named hw decoder, fallback to createDecoderByType")
        return MediaCodec.createDecoderByType("video/avc")
    }

    fun start() {
        // ============================================================
        // 硬解优先。
        //
        // 踩坑：MediaCodec.createDecoderByType("video/avc") 在 ColorOS/OnePlus 上
        // 返回的是 c2.android.avc.decoder（**软件解码器**），实测 720x1600 只有 2fps，
        // 再好的手机也白搭 —— 用户说"我手机这么好"就是这个原因。
        //
        // 必须显式按名字挑硬件解码器。各厂商命名不同：
        //   高通：c2.qti.avc.decoder / c2.qti.avc.decoder.low_latency
        //   联发科：c2.mtk.avc.decoder
        //   麒麟：c2.hisi.avc.decoder
        // 都找不到再退回 createDecoderByType。
        // ============================================================
        val c = try {
            if (preferSw) {
                MediaCodec.createByCodecName("c2.android.avc.decoder")
            } else {
                pickHwDecoder()
            }
        } catch (e: Exception) {
            Log.w("RemoteDecoder", "hw decoder failed, try default", e)
            try {
                MediaCodec.createDecoderByType("video/avc")
            } catch (e2: Exception) {
                Log.e("RemoteDecoder", "default failed, try sw", e2)
                try { MediaCodec.createByCodecName("c2.android.avc.decoder") }
                catch (e3: Exception) { Log.e("RemoteDecoder", "sw too", e3); return }
            }
        }
        Log.i("RemoteDecoder", "decoder = ${c.name}")
        val fmt = MediaFormat.createVideoFormat("video/avc", w, h)
        // 关键：把 SPS/PPS 作为 csd-0 写进 format。
        //
        // 踩坑：之前只靠 BUFFER_FLAG_CODEC_CONFIG 把配置帧喂进去，
        // 软件解码器(c2.android)认这种约定，但高通硬件解码器
        // (c2.qti.avc.decoder.low_latency) 只认 configure 时的 csd-0，
        // 结果就是 fed 一直涨、dequeueOutputBuffer 永远返回 TRY_AGAIN，黑屏。
        //
        // csd 数据由上层在收到 CODEC_CONFIG 帧时通过 setCsd() 传进来。
        val csd = csdData
        if (csd != null && csd.isNotEmpty()) {
            try {
                fmt.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(csd))
                Log.i("RemoteDecoder", "csd-0 set ${csd.size}B")
            } catch (e: Exception) {
                Log.w("RemoteDecoder", "csd-0 set failed", e)
            }
        } else {
            Log.w("RemoteDecoder", "no csd available at configure time")
        }
        // 解码器低延迟提示（不支持的机型静默忽略）；软解不需要
        if (!preferSw) {
            try { fmt.setInteger(MediaFormat.KEY_PRIORITY, 0) } catch (e: Exception) { }
            try {
                if (android.os.Build.VERSION.SDK_INT >= 30) fmt.setLong(MediaFormat.KEY_LATENCY, 0L)
            } catch (e: Exception) { }
        }
        Log.i("RemoteDecoder", "start ${w}x${h} codec=${c.name} surface.valid=${surface.isValid}")
        if (!surface.isValid) {
            Log.e("RemoteDecoder", "surface already invalid at start, abort")
            return
        }
        c.configure(fmt, surface, null, 0)
        c.start()
        codec = c
        running = true
        var firstOutLogged = false
        var fedCount = 0
        var noOutCount = 0
        var lastLog = System.currentTimeMillis()
        val startMs = System.currentTimeMillis()
        var lastOutMs = startMs
        var stallFired = false
        Thread {
            while (running) {
                try {
                    val f = queue.peek()
                    if (f != null) {
                        val idx = c.dequeueInputBuffer(4_000)
                        if (idx >= 0) {
                            queue.poll()   // 真正拿到输入缓冲后才出队，保证帧序不乱
                            val buf = c.getInputBuffer(idx)!!
                            buf.clear(); buf.put(f.data)
                            c.queueInputBuffer(idx, 0, f.data.size, f.pts, f.flags)
                            fedCount++
                            if (fedCount == 1) Log.i("RemoteDecoder", "fed first ${f.data.size}B flags=${f.flags}")
                        }
                        // 拿不到输入缓冲就保留在队头，下一轮再试。
                        // 原写法 queue.offer(f) 会把当前帧塞到队尾，
                        // 后续帧全部排到它前面 → 码流顺序错乱 → 解码器永远出不了帧。
                    }
                    val out = c.dequeueOutputBuffer(info, 4_000)
                    if (out >= 0) {
                        c.releaseOutputBuffer(out, true) // 立即渲染，低延迟优先
                        lastOutMs = System.currentTimeMillis()
                        if (!firstOutLogged) {
                            firstOutLogged = true
                            Log.i("RemoteDecoder", "first output frame rendered")
                        }
                        tickFps()
                    } else {
                        noOutCount++
                        val now = System.currentTimeMillis()
                        if (now - lastLog > 3000) {
                            lastLog = now
                            Log.w("RemoteDecoder", "no output yet (code=$out fed=$fedCount queued=${queue.size} dropped=$droppedForLatency surfaceValid=${surface.isValid})")
                        }
                        // 卡死检测：本次解码器实例内，喂够帧且持续 6 秒没有输出才判死。
                        // 教训：原来用 now-lastOutMs>5s 判定，但 lastOutMs 在
                        // 实例创建时就初始化，加上 fedCount 从 0 重新计，
                        // 实际出现了"刚启动 29ms 就被判死并回退软解"的误杀，
                        // 结果骁龙8 硬解明明能用，却全程跑软解 → 画面极卡。
                        //
                        // 现在要求：
                        //   1) 本次实例内真的喂进 >= 45 帧
                        //   2) 从该实例创建起至少过了 6 秒
                        //   3) 期间一帧都没出
                        if (!stallFired && !preferSw && fedCount >= 45 &&
                            now - startMs > 6_000 && now - lastOutMs > 6_000
                        ) {
                            stallFired = true
                            Log.w("RemoteDecoder", "hw stalled: fed=$fedCount no output for ${now - startMs}ms")
                            onStall?.invoke()
                        }
                    }
                } catch (e: IllegalStateException) {
                    Log.w("RemoteDecoder", "illegal state", e)
                    break
                } catch (e: Exception) {
                    Log.w("RemoteDecoder", "loop", e)
                }
            }
        }.apply { name = "decoder"; start() }
    }

    fun feed(frame: ByteArray) {
        val c = codec ?: return
        if (!running || frame.size < 9) return
        val flags = decodeFrameFlags(frame)
        val pts = decodeFramePts(frame)
        val payload = frame.copyOfRange(9, frame.size)
        val codecFlags = when {
            flags and 0x02 != 0 -> MediaCodec.BUFFER_FLAG_CODEC_CONFIG
            flags and 0x01 != 0 -> MediaCodec.BUFFER_FLAG_KEY_FRAME
            else -> 0
        }
        queue.offer(Frame(payload, pts, codecFlags))
        skipToFresh()
    }

    /** 端到端延迟保护：队列积压说明网络跟不上，丢掉最新关键帧之前的所有旧帧，
     *  宁可画面跳一下，也不要操作延迟越滚越大 */
    private fun skipToFresh() {
        // 端到端延迟保护。
        //
        // 教训：一开始照搬 scrcpy 的"队列 >2 就丢"过于激进 ——
        // 实测丢帧率高达 19 帧，画面反而出不来。
        // scrcpy 能用这招，是因为它走 ADB 隧道、帧到达极其均匀；
        // 我们走 WiFi，帧是突发到达的，队列短暂到 3~4 帧属正常现象，
        // 这时候丢帧只会白白损失画面。
        //
        // 调整为分档：
        //   队列 <= 6      ：不动（正常波动范围）
        //   队列 7~12      ：跳到最新关键帧（保留解码起点）
        //   队列 > 12      ：真的堆积了，丢到只剩 1 帧
        if (queue.size <= 6) return

        val snap = queue.toList()
        var lastKey = -1
        for (i in snap.indices) {
            if (snap[i].flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) lastKey = i
        }

        if (lastKey > 0) {
            repeat(lastKey) { queue.poll() }
            droppedForLatency += lastKey
        } else if (queue.size > 12) {
            val keep = 2
            repeat(maxOf(0, queue.size - keep)) { queue.poll(); droppedForLatency++ }
        }
    }

    private fun tickFps() {
        fpsCount++
        val now = System.currentTimeMillis()
        if (now - fpsLastMs >= 1000) {
            AppState.decodedFps.value = fpsCount
            fpsCount = 0; fpsLastMs = now
        }
    }

    private var fpsCount = 0
    private var fpsLastMs = 0L

    fun stop() {
        running = false
        queue.clear()
        try { codec?.let { it.stop(); it.release() } } catch (e: Exception) { }
        codec = null
    }
}
