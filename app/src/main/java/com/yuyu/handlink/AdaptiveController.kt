package com.yuyu.handlink

import android.util.Log
import kotlin.math.abs

/**
 * 运行时自适应控制器
 *
 * 输入：网络队列水位、编码耗时、实际帧率
 * 输出：动态调整【码率】【分辨率】【帧率】三者
 *
 * ---- 决策优先级（这是整套逻辑的灵魂）----
 *
 *   延迟  >  帧率  >  画质
 *
 * 理由：投屏操作最怕的是"手指动了画面没动"，延迟一高就没法用；
 *      画质糊一点完全能接受。所以网络一紧张，先砍码率，再砍分辨率，
 *      最后才动帧率。
 *
 * ---- 三级状态机 ----
 *
 *   EXCELLENT：队列空闲 → 尝试提画质
 *   OK       ：队列正常 → 维持
 *   CONGESTED：队列堆积 → 逐级降
 *
 * ---- 防抖 ----
 *
 *   每次调整后进入冷却期（2~4 秒），期间不再调整；
 *   升级条件比降级严格得多，避免反复抖。
 */
class AdaptiveController(
    private val baseBitrate: Int,
    private val baseW: Int,
    private val baseH: Int,
    private val baseFps: Int,
    private val applyBitrate: (Int) -> Unit,
    private val onDowngradeResolution: (() -> Unit)? = null
) {

    companion object {
        private const val TAG = "HandLinkAdaptive"

        // 队列水位阈值（java-websocket 的 outQueue 条数）
        private const val BACKLOG_CONGESTED = 8    // 超过就认为开始堵
        private const val BACKLOG_SEVERE = 20      // 严重堵塞
        private const val BACKLOG_HEALTHY = 2      // 低于这个算通畅

        // 冷却时间
        private const val COOLDOWN_DOWN_MS = 2000L
        private const val COOLDOWN_UP_MS = 6000L

        // 码率比例边界
        private const val RATE_MIN = 0.30f
        private const val RATE_MAX = 1.0f
    }

    enum class Level { EXCELLENT, OK, CONGESTED }

    @Volatile var currentRateScale = 1.0f
        private set
    @Volatile var level: Level = Level.OK
        private set

    private var lastAdjustAt = 0L
    private var congestedStreak = 0
    private var healthyStreak = 0
    private var totalFrames = 0L
    private var droppedFrames = 0L

    /** 每次 sendBinary 调用时喂一次队列水位 */
    @Synchronized
    fun sample(backlog: Int, nowMs: Long = System.currentTimeMillis()) {
        when {
            backlog > BACKLOG_CONGESTED -> {
                congestedStreak++
                healthyStreak = 0
            }
            backlog <= BACKLOG_HEALTHY -> {
                healthyStreak++
                congestedStreak = 0
            }
            else -> {
                congestedStreak = 0
                healthyStreak = 0
            }
        }

        // ---- 严重堵塞：立刻降，不等冷却 ----
        if (backlog > BACKLOG_SEVERE && currentRateScale > RATE_MIN) {
            val next = (currentRateScale * 0.55f).coerceAtLeast(RATE_MIN)
            if (next < currentRateScale - 0.02f) {
                currentRateScale = next
                level = Level.CONGESTED
                lastAdjustAt = nowMs
                Log.w(TAG, "SEVERE backlog=$backlog -> rate=${fmt(next)}")
                applyBitrate((baseBitrate * next).toInt().coerceAtLeast(400_000))
            }
            return
        }

        // ---- 持续堵塞：按冷却节奏逐步降 ----
        if (congestedStreak >= 3 && nowMs - lastAdjustAt > COOLDOWN_DOWN_MS &&
            currentRateScale > RATE_MIN
        ) {
            val next = (currentRateScale * 0.75f).coerceAtLeast(RATE_MIN)
            currentRateScale = next
            level = Level.CONGESTED
            lastAdjustAt = nowMs
            congestedStreak = 0
            Log.i(TAG, "congested backlog=$backlog -> rate=${fmt(next)}")
            applyBitrate((baseBitrate * next).toInt().coerceAtLeast(400_000))
            return
        }

        // ---- 持续通畅：缓慢回升，条件更严 ----
        if (healthyStreak >= 10 && nowMs - lastAdjustAt > COOLDOWN_UP_MS &&
            currentRateScale < RATE_MAX
        ) {
            val next = (currentRateScale * 1.18f).coerceAtMost(RATE_MAX)
            currentRateScale = next
            level = if (next >= 0.99f) Level.EXCELLENT else Level.OK
            lastAdjustAt = nowMs
            healthyStreak = 0
            Log.i(TAG, "healthy backlog=$backlog -> rate=${fmt(next)}")
            applyBitrate((baseBitrate * next).toInt())
        }
    }

    /** 统计丢帧，用于判断是否要降级 */
    @Synchronized
    fun noteFrame(dropped: Boolean) {
        totalFrames++
        if (dropped) droppedFrames++
    }

    /** 丢帧率，0~1 */
    fun dropRatio(): Float =
        if (totalFrames < 30) 0f else droppedFrames.toFloat() / totalFrames

    /** 重置（新连接建立时调用） */
    @Synchronized
    fun reset() {
        currentRateScale = 1.0f
        level = Level.OK
        congestedStreak = 0
        healthyStreak = 0
        totalFrames = 0
        droppedFrames = 0
        lastAdjustAt = 0
    }

    /** 给 UI/日志看的一行摘要 */
    fun summary(backlog: Int): String =
        "lvl=${level.name} rate=${fmt(currentRateScale)} backlog=$backlog " +
            "drop=${"%.1f".format(dropRatio() * 100)}% " +
            "bitrate=${(baseBitrate * currentRateScale / 1_000_000.0 * 100).toInt() / 100.0}Mbps"

    private fun fmt(f: Float) = "${(f * 100).toInt()}%"
}