package com.yuyu.handlink

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
import org.json.JSONObject
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource

/**
 * WebRTC 推流通道（被控端）。
 *
 * ============================ 为什么改用 ScreenshotAndroid ============================
 *
 * 反编译 ColorOS 的 ShareScreen 发现：它的采集用的是 WebRTC 官方自带的
 *     com.oplus.ortc.ScreenCapturerAndroid
 * 也就是 org.webrtc.ScreenCapturerAndroid，OPPO 只是改包名重打包。
 *
 * 它内部**自己管理 VirtualDisplay 和 MediaProjection 的完整生命周期**：
 *   · createVirtualDisplay 在 startCapture 里做
 *   · resize / rotation 自己处理
 *   · stop() 时自己 release
 *
 * 我之前的写法是自己建 SurfaceTextureHelper + 自己拿 VirtualDisplay 挂 surface，
 * 结果和 CaptureService 里那套 MediaCodec 链路抢同一块缓冲，出现绿屏、
 * 帧率掉到 1fps、RTC 不接管等一系列问题 —— 全是因为在重写本该由官方封装的东西。
 *
 * ============================ 但参数要比 ColorOS 激进 ============================
 *
 * ColorOS 用了 FramerateBitrateAdjuster，带宽一紧就**降帧率**，
 * 这就是用户说的"卡"。
 *
 * 我们的策略相反：
 *   · 分辨率给满（按真实屏幕比例，长边 2000）
 *   · 帧率给满（局域网 60fps）
 *   · 只让码率自适应（保帧率保分辨率，宁可码率降）
 *   · degradationPreference = MAINTAIN_RESOLUTION
 */
class RtcHost(
    private val ctx: Context,
    private val mediaProjectionData: Intent,
    private val sendSignal: (JSONObject) -> Unit
) {

    companion object {
        private const val TAG = "HandLinkRtcHost"
    }

    private var engine: RtcEngine? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var helper: SurfaceTextureHelper? = null

    @Volatile var started = false
        private set

    var onState: ((String) -> Unit)? = null
    var onTouch: ((String) -> Unit)? = null

    /**
     * 启动推流。
     *
     * @param width/height 编码尺寸
     * @param fps 目标帧率
     */
    fun start(width: Int, height: Int, fps: Int, isLan: Boolean = true) {
        if (started) return
        try {
            val e = RtcEngine(
                ctx = ctx,
                isHost = true,
                onSignal = { json -> sendSignal(json) },
                onState = { st ->
                    Log.i(TAG, "state=$st")
                    onState?.invoke(st)
                },
                onTouch = { msg -> onTouch?.invoke(msg) }
            )
            e.isLanMode = isLan
            e.init()
            e.start()
            engine = e

            // 官方采集器：把授权 Intent 交给它，它自己建 VirtualDisplay
            val projectionCallback = object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "MediaProjection stopped by user/system")
                    AppState.projectionInterrupted.value = true
                }
            }
            val cap = ScreenCapturerAndroid(mediaProjectionData, projectionCallback)
            capturer = cap

            val vs: VideoSource = e.videoSourceRef ?: run {
                Log.e(TAG, "videoSource is null")
                stop()
                return
            }
            val h = SurfaceTextureHelper.create("hl-rtc-capture", e.eglBase.eglBaseContext)
            helper = h
            cap.initialize(h, ctx, vs.capturerObserver)
            cap.startCapture(width, height, fps)

            started = true

            // 主动发起协商，不依赖 onRenegotiationNeeded
            e.createOffer()

            Log.i(TAG, "RTC host started ${width}x${height}@$fps lan=$isLan")
        } catch (e: Throwable) {
            Log.e(TAG, "start failed", e)
            stop()
        }
    }

    fun handleAnswer(sdp: String) {
        engine?.handleAnswer(sdp)
    }

    fun handleIce(mid: String, mline: Int, cand: String) {
        engine?.addIce(mid, mline, cand)
    }

    fun stop() {
        started = false
        try { capturer?.stopCapture() } catch (e: Throwable) { }
        try { capturer?.dispose() } catch (e: Throwable) { }
        capturer = null
        try { helper?.dispose() } catch (e: Throwable) { }
        helper = null
        try { engine?.dispose() } catch (e: Throwable) { }
        engine = null
        Log.i(TAG, "stopped")
    }
}