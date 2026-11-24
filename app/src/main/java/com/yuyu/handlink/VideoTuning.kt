package com.yuyu.handlink

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import kotlin.math.max
import kotlin.math.min

/**
 * 自适应画质引擎
 *
 * 目标：在【延迟 / 帧率 / 画质】三者间自动找最优平衡，用户完全不用管。
 *
 * ---- 设计思路 ----
 *
 * 一、起点：按设备能力定初始档
 *     - 内存、CPU 核心数、屏幕分辨率
 *     - 但只作为**起点**，不作为最终答案
 *
 * 二、运行时：三类实时指标驱动调整
 *     - 网络：发送队列水位（最准，直接反映对方跟不跟得上）
 *     - 性能：编码耗时、是否掉帧
 *     - 延迟：端到端估算
 *
 * 三、策略：优先保延迟，其次保帧率，最后才花预算在画质上
 *     网络差 → 先降分辨率，再降码率，最差时降帧率
 *     网络好 → 反过来逐级回升，但绝不越过设备能力上限
 *
 * 四、稳定性：调整有冷却时间 + 滞回区间，避免来回抖
 */
object VideoTuning {

    private const val TAG = "HandLinkTune"

    const val QUALITY_AUTO = "auto"
    const val QUALITY_SMOOTH = "smooth"
    const val QUALITY_BALANCED = "balanced"
    const val QUALITY_HD = "hd"
    const val QUALITY_MAX = "max"

    private const val PREF = "handlink_video"
    private const val KEY_QUALITY = "quality"

    fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun quality(ctx: Context): String =
        prefs(ctx).getString(KEY_QUALITY, QUALITY_AUTO) ?: QUALITY_AUTO

    fun setQuality(ctx: Context, q: String) {
        prefs(ctx).edit().putString(KEY_QUALITY, q).apply()
    }

    // ============================================================
    //  设备能力评估（只在启动时算一次）
    // ============================================================

    data class DeviceProfile(
        /** 总内存 GB */
        val ramGb: Double,
        /** CPU 核心数 */
        val cores: Int,
        /** 屏幕长边像素 */
        val screenLong: Int,
        /** 屏幕短边像素 */
        val screenShort: Int,
        /** 屏幕密度 */
        val densityDpi: Int,
        /** 综合分数 0~100，越高越强 */
        val score: Int,
        /** 推荐的初始档位 */
        val startTier: String
    )

    fun profile(ctx: Context): DeviceProfile {
        val ram = totalRamGb(ctx)
        val cores = Runtime.getRuntime().availableProcessors()

        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val dm = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        val longSide = max(dm.widthPixels, dm.heightPixels)
        val shortSide = min(dm.widthPixels, dm.heightPixels)

        // 评分：内存 50 分 + 核心 30 分 + 分辨率压力 20 分
        var s = 0.0
        s += when {
            ram >= 16 -> 50.0
            ram >= 12 -> 46.0
            ram >= 8 -> 38.0
            ram >= 6 -> 28.0
            ram >= 4 -> 18.0
            else -> 8.0
        }
        s += when {
            cores >= 8 -> 30.0
            cores >= 6 -> 22.0
            cores >= 4 -> 14.0
            else -> 6.0
        }
        // 分辨率越高，编码压力越大，分数相应回落
        s += when {
            longSide >= 3200 -> 8.0    // 2K+ 屏，编码负担重
            longSide >= 2400 -> 14.0
            longSide >= 2000 -> 18.0
            else -> 20.0
        }

        val score = s.toInt().coerceIn(0, 100)
        val tier = when {
            score >= 82 -> QUALITY_HD
            score >= 62 -> QUALITY_BALANCED
            else -> QUALITY_SMOOTH
        }

        val p = DeviceProfile(ram, cores, longSide, shortSide, dm.densityDpi, score, tier)
        Log.i(TAG, "device: ram=${"%.1f".format(ram)}G cores=$cores " +
            "screen=${longSide}x$shortSide score=$score startTier=$tier")
        return p
    }

    private fun totalRamGb(ctx: Context): Double = try {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        mi.totalMem / 1024.0 / 1024.0 / 1024.0
    } catch (e: Exception) {
        6.0
    }

    // ============================================================
    //  参数计算
    // ============================================================

    /** 用户是否手动指定了档位；未指定则走自动 */
    fun isAuto(ctx: Context): Boolean = quality(ctx) == QUALITY_AUTO

    fun effectiveQuality(ctx: Context): String {
        val q = quality(ctx)
        if (q != QUALITY_AUTO) return q
        return profile(ctx).startTier
    }

    /**
     * 编码长边上限。
     *
     * 核心认识：屏幕镜像不是录屏成片。
     * 控制端屏幕宽度只有 1000~1300px，编码尺寸超过它纯属浪费算力，
     * 只会在编码、传输、解码三处同时增加负担，延迟直接翻倍。
     */
    /**
     * 编码长边上限。
     *
     * 平衡点的取法：
     *  — 控制端屏幕宽度约 1200~1300px，编码宽度低于它的 60% 时
     *    放大倍数超过 1.7x，肉眼能明显看出糊。
     *  — A93s（天玑700）实测硬编 720x1600 可以跑 20fps+，
     *    528x1200 虽然更快但放大 2.4 倍，糊到没法用。
     *  — 所以「流畅」档也要保证宽度不低于 600，长边 1600 左右。
     */
    /**
     * 最终编码长边：取「本机能力」与「控制端解码能力」的较小者。
     *
     * 这是整个项目最容易踩的坑：
     *  - 一加15 编码 816x1792 时滑动 95fps，流畅得很；
     *  - 但 A93s（天玑700）当控制端去解这个尺寸，硬解 34 秒零输出、
     *    软解 3 秒丢 351 帧，画面完全没法看。
     *  - 反过来 A93s 编码 528x1200、一加15 解码，两边都舒服。
     *
     * 结论：尺寸上限必须听控制端的，不能被控端一厢情愿。
     */
    fun maxDim(ctx: Context): Int {
        // 本机能力：按设备评分决定能推到多高
        // 一加15(骁龙8至尊)/Reno14(天玑8350) 都属于能跑高画质的一档
        val p = profile(ctx)
        val own = when (effectiveQuality(ctx)) {
            QUALITY_SMOOTH -> 1200
            QUALITY_BALANCED -> 1600
            QUALITY_HD -> if (p.score >= 80) 2000 else 1800
            QUALITY_MAX -> if (p.score >= 80) 2400 else 2000
            else -> 1600
        }
        // 中继模式：码率被限到 3Mbps，分辨率继续拉高只会变成大块马赛克。
        // 1280 长边配合 3Mbps（约 720x1280）是公网上画质/流畅的平衡点。
        val relayCap = AppState.relayQualityCap.value
        if (relayCap in 1..9_000_000) {
            // 6Mbps 配 1600 长边（约 720x1600）是公网投屏的画质/流畅平衡点，
            // 3Mbps 时只能给 1280，画面在翻页瞬间会明显糊。
            return minOf(own, 1600)
        }

        val pw = AppState.peerDecW.value
        val ph = AppState.peerDecH.value
        if (pw <= 0 || ph <= 0) return own
        // 控制端给的是"宽x高"，取它的长边作为约束
        val peerLong = maxOf(pw, ph)
        return minOf(own, peerLong)
    }

    private fun ownMaxDim(ctx: Context): Int = when (effectiveQuality(ctx)) {
        // A93s（天玑700）实测：
        //   800x1792 → 13fps，画面清晰但滑动发涩
        //   528x1200 → 21fps，跟手但糊
        // 取中间 1440 长边（约 640x1440），帧率与清晰度的平衡点。
        // 记住：投屏的第一体验指标是"跟手"，画面稍糊可以忍，卡顿没法用。
        QUALITY_SMOOTH -> 1200
        QUALITY_BALANCED -> 1440
        QUALITY_HD -> 1800
        QUALITY_MAX -> 2000
        else -> 1600
    }

    fun frameRate(ctx: Context): Int = frameRateFor(effectiveQuality(ctx))

    /**
     * 编码帧率。
     *
     * 关键教训：一加15/Reno14 都是 120Hz 屏，屏幕本身在 120fps 地刷新。
     * 如果编码只有 30fps，就是"每 4 帧取 1 帧"，
     * 快速滑动时画面看着就是一顿一顿的（用户说的"卡"很大程度来自这里）。
     *
     * 45fps 是公网中继下的平衡点：
     *   · 比 30fps 明显顺滑
     *   · 又不像 60fps 那样把码率翻倍
     */
    private fun frameRateFor(quality: String): Int = when (quality) {
        QUALITY_SMOOTH -> 30
        QUALITY_BALANCED -> 45
        QUALITY_HD -> 45
        else -> 60
    }

    /**
     * 码率预算。
     *
     * 手机 WiFi 直连实测：4~6Mbps 是 720p/1080p 实时投屏的甜点区间。
     * 超过 8Mbps 后，路由器和手机无线模块开始排队，延迟暴涨。
     */
    fun bitrateFor(w: Int, h: Int, quality: String): Int {
        val pixels = w.toLong() * h.toLong()
        val fps = frameRateFor(quality)
        // bpp 取 0.12~0.18：实时投屏要在"清晰"和"不涨延迟"之间取平衡。
        // 低于 0.10 会出现明显块噪和马赛克，尤其大片纯色背景的 App 界面。
        val bpp = when (quality) {
            QUALITY_SMOOTH -> 0.120
            QUALITY_BALANCED -> 0.150
            QUALITY_HD -> 0.175
            else -> 0.210
        }
        val raw = pixels * bpp * fps
        // 参考 scrcpy 默认 8Mbps（1080p 全屏镜像）：
        // 实测它的 35~70ms 延迟正是在这个码率下达到的，
        // 说明带宽不是瓶颈，缓冲策略才是。所以码率上限可以放宽。
        // 手机 WiFi 直连的实测上限：12Mbps 以内基本能稳住，
        // 再高就会遇到路由器排队导致延迟抖动。
        var cap = when (quality) {
            QUALITY_SMOOTH -> 6_000_000
            QUALITY_BALANCED -> 9_000_000
            QUALITY_HD -> 12_000_000
            else -> 16_000_000
        }

        // ---- 异地中继模式必须单独限流 ----
        // 走公网 VPS 中转时，瓶颈不是手机，是云服务器的出口带宽。
        // 按带宽计费的机器常见配置只有 1~5Mbps，
        // 发 12Mbps 进去只会堆在服务器缓冲区里，延迟一路涨到几秒，
        // 表现为"又卡又糊"——卡是排队造成的，糊是丢帧后编码器降质造成的。
        // 宁可分辨率低一点，也要保证不排队。
        val relayCap = AppState.relayQualityCap.value
        if (relayCap > 0) cap = minOf(cap, relayCap)

        return raw.toInt().coerceIn(1_500_000, cap)
    }

    /** 编码器能力上限（由硬件决定，不能被运行时调整突破） */
    fun respectCodecCap(w: Int, h: Int, ctx: Context): Pair<Int, Int> {
        val (maxLong, maxShort) = CodecCapabilitiesHelper.maxEncoderSize()
        return CodecCapabilitiesHelper.fitEncoder(w, h, maxLong, maxShort)
    }

    fun describe(ctx: Context): String {
        val eff = effectiveQuality(ctx)
        val name = when (eff) {
            QUALITY_SMOOTH -> "流畅"
            QUALITY_BALANCED -> "均衡"
            QUALITY_HD -> "高清"
            else -> "原画"
        }
        val p = profile(ctx)
        return if (isAuto(ctx)) "自动（$name · 性能分 ${p.score}）" else name
    }
}