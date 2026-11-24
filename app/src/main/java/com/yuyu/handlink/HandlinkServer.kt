package com.yuyu.handlink

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import android.util.Log
import org.json.JSONObject
import java.net.InetSocketAddress
import java.nio.ByteBuffer

/**
 * 被控端内置的 WebSocket 服务器。
 * 协议时序：
 *  client 连接 -> server 发 hello -> client 发 pair(code,name)
 *  -> server 校验 + 本机用户确认 -> accept / deny
 *  确认后 client 可发输入事件，server 广播视频二进制帧。
 */
class HandlinkServer(
    private val onPair: (conn: WebSocket, name: String) -> Unit,   // 校验通过后进入确认流程
    private val onInput: (JSONObject) -> Unit,
    /** WebRTC 信令 */
    private val onRtc: (JSONObject) -> Unit = {},
    private val onDisconnect: (conn: WebSocket) -> Unit,
) : WebSocketServer(java.net.InetSocketAddress(java.net.InetAddress.getByName("0.0.0.0"), WS_PORT)) {

    /** 已通过确认、正在接收输入的会话连接 */
    @Volatile var activeConn: WebSocket? = null

    init {
        isReuseAddr = true
        // connectionLostTimeout = 30 会在后台起一个定时 ping 线程，
        // 在 Android 上这个线程和 selector 抢锁，实测会导致
        // 新连接的握手永远得不到处理（TCP 连上但服务器不响应）。
        // 我们靠业务层的 stall 检测保活，不需要它。
        connectionLostTimeout = 0
        setDaemon(false)
        Log.i("HandlinkServer", "init: connectionLostTimeout=0 (disabled)")
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        Log.i("HandlinkServer", "WS OPEN from ${conn.remoteSocketAddress}")
        conn.send(Proto.hello().toString())
    }

    override fun onMessage(conn: WebSocket, message: String) {
        val json = try { JSONObject(message) } catch (e: Exception) { return }
        val tt = json.optString("t")
        // 全量打点：排查"信令到底有没有到"这种问题时，没有这条寸步难行
        Log.i("HandlinkServer", "recv t=$tt from=${conn.remoteSocketAddress}")
        when (tt) {
            "pair" -> {
                if (json.optString("code") != AppState.pairCode.value) {
                    conn.send(Proto.deny("配对码错误").toString())
                    try { conn.close(1000) } catch (e: Exception) { }
                    return
                }
                // 已有活动连接：先踢掉旧的（本机用户重新确认新的）
                activeConn?.let { old ->
                    old.send(Proto.deny("已有新的控制端连接").toString())
                    old.close(1000)
                }
                activeConn = null
                // 记录控制端的解码能力：被控端据此决定编码尺寸。
                // maxW/maxH 为 0 表示对方是旧版、没上报，保持当前设置。
                val cw = json.optInt("maxW", 0)
                val ch = json.optInt("maxH", 0)
                if (cw > 0 && ch > 0) {
                    AppState.peerDecW.value = cw
                    AppState.peerDecH.value = ch
                    AppState.peerDecHw.value = json.optBoolean("decHw", false)
                    android.util.Log.i(
                        "HandlinkServer",
                        "peer decoder cap: ${cw}x$ch hw=${json.optBoolean("decHw", false)} " +
                            "score=${json.optInt("score", 0)}"
                    )
                }
                onPair(conn, json.optString("name", "TA"))
            }
            // WebRTC 信令：局域网模式下同样要路由，
            // 否则控制端发了 rtc-ready 被控端收不到，永远起不来 RTC → 黑屏
            "rtc-offer", "rtc-answer", "rtc-ice", "rtc-ready" -> {
                if (conn === activeConn) onRtc(json)
            }
            "input" -> {
                if (conn === activeConn) onInput(json)
            }
            "stop" -> {
                if (conn === activeConn) {
                    activeConn = null
                    onDisconnect(conn)
                    conn.close(1000)
                }
            }
            else -> {
                // tap/lp/swipe/key/text 等输入事件：只接受已确认会话
                if (conn === activeConn) onInput(json)
            }
        }
    }

    override fun onMessage(conn: WebSocket, message: ByteBuffer) { /* 控制端不发二进制 */ }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        if (conn === activeConn) activeConn = null
        onDisconnect(conn)
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.w("HandlinkServer", "onError: ${ex?.message}")
        android.util.Log.w("HandlinkServer", "ws error", ex)
    }

    override fun onStart() {
        Log.i("HandlinkServer", "onStart: server thread alive")
        android.util.Log.i("HandlinkServer", "listening on $WS_PORT")
    }

    /** GOP 对齐丢弃：积压时丢弃整个 GOP 直到下一个关键帧，绝不单独丢 P 帧（会掐断参考链产生幻影） */
    @Volatile private var droppingUntilKey = false

    // ---- 运行时自适应 ----
    /** 由 CaptureService 注入，负责根据队列水位调码率/画质 */
    @Volatile var adaptive: AdaptiveController? = null

    private var lastStatLog = 0L

    fun sendBinary(data: ByteArray) {
        val conn = activeConn ?: return
        if (!conn.isOpen) return
        val flags = data[0].toInt() and 0xFF
        val startsGop = flags and 0x01 != 0 || flags and 0x02 != 0
        val impl = conn as? org.java_websocket.WebSocketImpl
        val backlog = try { impl?.outQueue?.size ?: 0 } catch (e: Exception) { 0 }

        val ad = adaptive
        ad?.sample(backlog)

        // 丢帧阈值随码率状态变化：已经降过码还堵，说明降得不够
        val scale = ad?.currentRateScale ?: 1.0f
        val dropAt = when {
            scale <= 0.45f -> 26
            scale <= 0.70f -> 18
            else -> 12
        }
        var dropped = false
        if (backlog > dropAt && !startsGop) {
            droppingUntilKey = true
        }
        if (droppingUntilKey) {
            if (startsGop) droppingUntilKey = false
            else dropped = true
        }
        ad?.noteFrame(dropped)
        if (dropped) return

        try { conn.send(java.nio.ByteBuffer.wrap(data)) } catch (e: Exception) { }

        // 每 10 秒打一行状态，方便现场排查
        val now = System.currentTimeMillis()
        if (ad != null && now - lastStatLog > 10_000) {
            lastStatLog = now
            android.util.Log.i("HandlinkServer", ad.summary(backlog))
        }
    }

    /** 新连接建立时重置自适应状态 */
    fun resetAdaptive() {
        adaptive?.reset()
        droppingUntilKey = false
    }

    fun sendText(json: JSONObject) {
        val conn = activeConn
        val tt = json.optString("t")
        if (conn == null) {
            android.util.Log.w("HandlinkServer", "sendText t=$tt DROPPED: activeConn is null")
            return
        }
        try {
            conn.send(json.toString())
            if (tt.startsWith("rtc")) android.util.Log.i("HandlinkServer", "sendText t=$tt ok")
        } catch (e: Exception) {
            android.util.Log.w("HandlinkServer", "sendText t=$tt failed", e)
        }
    }
}
