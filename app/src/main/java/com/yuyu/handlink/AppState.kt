package com.yuyu.handlink

import kotlinx.coroutines.flow.MutableStateFlow

/** 知情同意请求（被控端收到配对后等待本机用户决定），决定只生效一次 */
class ConsentRequest(val name: String, private val onDecide: (Boolean) -> Unit) {
    private var done = false
    fun decided(ok: Boolean) {
        if (done) return
        done = true
        onDecide(ok)
    }
}

/** UI 与服务之间的共享状态（同进程单例） */
object AppState {
    /** 当前角色：null=选角色页 / "controlled"=被控端 / "controller"=控制端 */
    val role = MutableStateFlow<String?>(null)
    // ---- 被控端 ----
    val serviceRunning = MutableStateFlow(false)
    val pairCode = MutableStateFlow("")
    val activeController = MutableStateFlow<String?>(null)
    val fps = MutableStateFlow(0)
    val consentRequest = MutableStateFlow<ConsentRequest?>(null)
    val uiVisible = MutableStateFlow(false)
    /** 对端（控制端）上报的解码能力：决定本机编码尺寸
     *  0 表示未上报（旧版对端），此时沿用本机默认 */
    /** Android 16 起，锁屏/来电会中断 MediaProjection。
     *  true 表示"因系统原因中断，需要用户点一下重新授权"，而不是用户主动结束 */
    /** 异地中继时的码率上限（bps）。0=不限（局域网直连）。
     *  云服务器出口带宽通常只有 1~5Mbps，直连的高码率会把它撑爆。 */
    val relayQualityCap = MutableStateFlow(0)
    val projectionInterrupted = MutableStateFlow(false)
    /** 是否使用 WebRTC 通道（true=自适应码率，false=旧的固定码率 WebSocket）
     *  0.4.21 默认切回 false：A93s 实测 WebRTC 只有 VP8 软编可用（M74 AAR 无
     *  OpenH264、MTK 硬编探测失败），软编 CPU 打爆 → 超卡超模糊不跟手。
     *  旧管线走 MediaCodec 硬编 H264 6.8Mbps，MTK 毫无压力。
     *  WebRTC 硬编攻关见 HANDOFF 待办。 */
    val useRtc = MutableStateFlow(false)
    val peerDecW = MutableStateFlow(0)
    val peerDecH = MutableStateFlow(0)
    val peerDecHw = MutableStateFlow(false)

    // ---- 中继 ----
    val relayUrl = MutableStateFlow("ws://YOUR_RELAY_HOST:8724") /* TODO: 换成你自己的中继服务器地址 */
    val relayStatus = MutableStateFlow("off") // off / online / connecting

    // ---- 控制端 ----
    // idle / connecting / waiting(等待对方确认) / denied / connected / closed / error
    val connState = MutableStateFlow("idle")
    val remoteW = MutableStateFlow(0)
    val remoteH = MutableStateFlow(0)
    val decodedFps = MutableStateFlow(0)
    val peerName = MutableStateFlow("")
    val remoteIp = MutableStateFlow("")
    val remoteName = MutableStateFlow("")

    /**
     * 控制端连接对象提升到进程级：Activity 切后台/被回收时不要断开。
     * 原来放在 Composable 的 remember 里，onDispose 就 disconnect()，
     * 结果一切后台连接就没了、解码器也释放了，回来只能看到黑屏。
     */
    val link: RemoteLink by lazy { RemoteLink() }

    /** 当前有效的视频 Surface（Activity 切后台会被销毁并置空） */
    @Volatile var currentSurface: android.view.Surface? = null

    /** 是否处于全屏观看模式（连上后被控端画面铺满整屏） */
    val immersive = MutableStateFlow(false)

    /** 全屏观看时控件是否显示（连上后显示 3 秒自动隐藏） */
    val controlsVisible = MutableStateFlow(true)

    /** 控件自动隐藏的时间点（uptimeMillis 风格，用 System.currentTimeMillis 比较） */
    @Volatile var controlsAutoHideAt: Long = 0L

    /** 画面是否铺满（true=裁剪填满整屏，false=完整显示留黑边） */
    val fillScreen = MutableStateFlow(true)

    /** 是否横屏观看（看电影/电视用） */
    val landscape = MutableStateFlow(false)

    fun randomCode(): String = (100000..999999).random().toString()
}
