package com.yuyu.handlink

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * WebRTC 通道（控制端侧）。
 *
 * 收到 offer → 回 answer → 拿到远端 VideoTrack → 渲染到 SurfaceViewRenderer。
 * 触摸通过被控端建的 DataChannel 发回去。
 */
class RtcViewer(
    private val ctx: Context,
    private val sendSignal: (JSONObject) -> Unit
) {

    companion object {
        private const val TAG = "HandLinkRtcViewer"
    }

    private var engine: RtcEngine? = null
    private var renderer: SurfaceViewRenderer? = null

    @Volatile var started = false
        private set

    var onState: ((String) -> Unit)? = null
    var onVideoTrack: ((VideoTrack) -> Unit)? = null

    // ---------------- 渲染器 ----------------

    /**
     * 绑定渲染器。Activity 里创建 SurfaceViewRenderer 后调用。
     * 如果视频轨还没到，[RtcEngine] 会在 onVideoTrackReady 时补上 sink。
     */
    fun attachRenderer(r: SurfaceViewRenderer) {
        renderer = r
        val e = engine ?: return
        // 幂等：factory+update 会各调一次，重复 init 抛异常会吞掉后面的挂接
        try { r.init(e.eglBase.eglBaseContext, null) } catch (t: Throwable) { }
        e.onVideoTrackReady = { track ->
            Log.i(TAG, "video track ready, adding sink")
            track.addSink(r)
            onVideoTrack?.invoke(track)
        }
        // 关键补挂：offer 常在 Compose 挂渲染器之前就 apply 完，
        // track 已存进 engine —— 只设回调不补挂，sink 永远加不上，
        // 表现：framesDecoded 在涨但 EglRenderer Frames received: 0（0.4.16 实测）
        e.attachRenderer(r)
    }

    fun detachRenderer() {
        try {
            renderer?.release()
        } catch (e: Throwable) { }
        renderer = null
    }

    // ---------------- 连接 ----------------

    fun start() {
        if (started) return
        try {
            val e = RtcEngine(
                ctx = ctx,
                isHost = false,
                onSignal = { json -> sendSignal(json) },
                onState = { st ->
                    Log.i(TAG, "state=$st")
                    onState?.invoke(st)
                },
                // 控制端不接收触摸事件（方向是控制端→被控端）
                onTouch = null
            )
            e.init()
            e.start()
            engine = e
            started = true
            // 渲染器可能已经先 attach 了
            renderer?.let { attachRenderer(it) }
            Log.i(TAG, "RTC viewer started")
        } catch (e: Throwable) {
            Log.e(TAG, "start failed", e)
            stop()
        }
    }

    fun handleOffer(sdp: String) {
        engine?.handleOffer(sdp)
    }

    fun handleIce(mid: String, mline: Int, cand: String) {
        engine?.addIce(mid, mline, cand)
    }

    /** 发送触摸指令（JSON 字符串），走 DataChannel */
    fun sendTouch(json: String): Boolean = engine?.sendTouch(json) ?: false

    fun stop() {
        started = false
        try {
            renderer?.let { r -> engine?.detachRenderer(r) }
        } catch (e: Throwable) { }
        try { engine?.dispose() } catch (e: Throwable) { }
        engine = null
        Log.i(TAG, "stopped")
    }
}