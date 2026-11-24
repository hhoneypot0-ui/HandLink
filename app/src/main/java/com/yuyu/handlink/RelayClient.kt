package com.yuyu.handlink

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 被控端的中继客户端：作为 WS client 连 VPS relay，以配对码为房间号注册。
 * 观众（控制端）经 relay 转发来的 pair/input 消息走与局域网模式完全相同的同意/注入流程。
 */
class RelayClient(
    private val relayUrl: String,          // ws://ip:8724
    private val room: String,              // = 配对码
    private val onPair: (name: String) -> Unit,      // 收到观众配对请求
    private val onInput: (JSONObject) -> Unit,
    /** WebRTC 信令回调 */
    private val onRtc: ((JSONObject) -> Unit)? = null,
    private val onViewerLeft: () -> Unit,
    private val onReady: () -> Unit,
) {
    private val http = OkHttpClient.Builder()
        .proxy(java.net.Proxy.NO_PROXY)
        .pingInterval(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var ws: WebSocket? = null
    @Volatile var viewerReady = false
        private set

    fun markViewerReady() { viewerReady = true }

    // 简易 pacing：两次发送间隔过短的 P 帧直接丢弃，防 relay 排队放大延迟
    private var lastSendMs = 0L
    private var droppingUntilKey = false

    fun start() {
        ws?.close(1000, null)
        val req = okhttp3.Request.Builder().url(relayUrl).build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("t", "host").put("room", room).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val j = try { JSONObject(text) } catch (e: Exception) { return }
                when (j.optString("t")) {
                    "hosted" -> { viewerReady = false; onReady() }
                    "pair" -> onPair(j.optString("name", "TA"))
                // WebRTC 信令：走同一条文本通道，不额外改服务器
                "rtc-offer", "rtc-answer", "rtc-ice", "rtc-ready" -> onRtc?.invoke(j)
                    "note" -> { /* 观众离开等提示 */ }
                    "deny" -> { viewerReady = false; onViewerLeft() }
                    else -> if (viewerReady) onInput(j) // tap/lp/swipe/key/text
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w("RelayClient", "relay down, retry in 5s", t)
                viewerReady = false
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ start() }, 5000)
            }
        })
    }

    fun sendText(json: JSONObject) {
        try { ws?.send(json.toString()) } catch (e: Exception) { }
    }

    /** 音频帧：量小（AAC 96kbps），不走视频那套 GOP/pacing 丢弃逻辑 */
    fun sendAudio(flags: Int, ptsUs: Long, payload: ByteArray) {
        if (!viewerReady) return
        val frame = encodeFrame(flags, ptsUs, payload)
        try { ws?.send(frame.toByteString()) } catch (e: Exception) { }
    }

    fun sendVideo(flags: Int, ptsUs: Long, payload: ByteArray) {
        if (!viewerReady) return
        val startsGop = flags and 0x03 != 0
        val now = System.currentTimeMillis()
        // 阈值 15ms：30fps(33ms) 及以上帧距都放行；只掐真正挤在同一瞬间的突发。
        // 之前 30ms 会把 45fps 档的帧当积压丢掉一半，画面直接变慢动作。
        if (!startsGop && now - lastSendMs < 15) { droppingUntilKey = true }
        if (droppingUntilKey) {
            if (startsGop) droppingUntilKey = false else return
        }
        lastSendMs = now
        val frame = encodeFrame(flags, ptsUs, payload)
        try { ws?.send(frame.toByteString()) } catch (e: Exception) { }
    }

    fun stop() {
        try { ws?.close(1000, null) } catch (e: Exception) { }
        ws = null
        viewerReady = false
    }
}
