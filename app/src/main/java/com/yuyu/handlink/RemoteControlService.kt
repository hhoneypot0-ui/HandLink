package com.yuyu.handlink

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/**
 * 被控端输入注入（无障碍方案，作为 Shizuku 的降级路径）。
 *
 * ============================ 定位说明 ============================
 *
 * 首选是 [ShizukuTouch]：注入真实 MotionEvent，真正跟手。
 * 这个服务只在 Shizuku 不可用时兜底。
 *
 * 无障碍的 GestureDescription 有几个绕不过去的限制：
 *   · 一个服务同时只能执行一个手势，新 dispatch 会取消旧的
 *   · 必须提前给定路径和时长，做不到实时跟随
 *   · 分段续接时若没在系统窗口内接上就 cancelled
 *
 * 所以这里的分段策略是：**收到 move 就立即发，完全不等回调**。
 * 每段都是一个 30ms 左右的独立短手势，从上一个位置画到新位置。
 * 理论上不如 Shizuku 顺手，但比"攒到 up 才发"好得多。
 */
class RemoteControlService : AccessibilityService() {

    companion object {
        private const val TAG = "HandLinkInput"
        @Volatile var instance: RemoteControlService? = null
        fun isReady(): Boolean = instance != null
    }

    // ---------------- 运动流状态 ----------------
    private var curX = 0f
    private var curY = 0f
    private var touching = false
    private var motionDownAt = 0L
    private var motionMoved = false
    private var lastFlushAt = 0L

    /** 两段之间的最小间隔 */
    private val MIN_FLUSH_GAP = 12L
    /** 单段手势时长 */
    private val SEG_MS = 32L

    override fun onServiceConnected() {
        instance = this
        Log.w(TAG, "SERVICE CONNECTED (a11y bound)")
        try {
            serviceInfo = serviceInfo.apply {
                flags = flags and android.accessibilityservice.AccessibilityServiceInfo
                    .FLAG_REQUEST_TOUCH_EXPLORATION_MODE.inv()
            }
        } catch (e: Exception) {
            Log.w(TAG, "clear touch exploration flag", e)
        }
        Log.i(TAG, "connected, real screen = ${screenWidth()}x${screenHeight()}")
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* 不处理事件流 */ }

    override fun onDestroy() {
        if (instance === this) instance = null
        Log.w(TAG, "SERVICE DESTROYED (a11y unbound)")
        super.onDestroy()
    }

    // ---------------- 坐标 ----------------

    private fun screenWidth(): Int = screenSize().first
    private fun screenHeight(): Int = screenSize().second

    private fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(m)
        return if (m.widthPixels > 0 && m.heightPixels > 0) Pair(m.widthPixels, m.heightPixels)
        else Pair(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
    }

    private fun px(frac: Float, isX: Boolean): Float {
        val f = frac.coerceIn(0f, 1f)
        return if (isX) f * (screenWidth() - 1) else f * (screenHeight() - 1)
    }

    private fun JSONObject.getFloatFrac(key: String): Float = optDouble(key, 0.0).toFloat()

    // ---------------- 指令分发 ----------------

    fun handleInput(json: JSONObject): Boolean {
        val type = json.optString("t")
        return when (type) {
            "tap" -> gesture(dot(json.getFloatFrac("x"), json.getFloatFrac("y")), 40L)
            "lp" -> gesture(dot(json.getFloatFrac("x"), json.getFloatFrac("y")), 650L)
            "swipe" -> {
                val p = Path().apply {
                    moveTo(px(json.getFloatFrac("x1"), true), px(json.getFloatFrac("y1"), false))
                    lineTo(px(json.getFloatFrac("x2"), true), px(json.getFloatFrac("y2"), false))
                }
                val ms = json.optLong("ms", 300L).coerceIn(40L, 2000L)
                gesture(p, ms)
            }
            "down" -> beginMotion(json.getFloatFrac("x"), json.getFloatFrac("y"))
            "move" -> stepMotion(json.getFloatFrac("x"), json.getFloatFrac("y"))
            "up" -> endMotion(json.getFloatFrac("x"), json.getFloatFrac("y"))
            "cancel" -> { resetMotion(); true }
            "key" -> when (json.optString("k")) {
                "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                else -> false
            }
            "text" -> insertText(json.optString("s"))
            else -> false
        }
    }

    private fun dot(nx: Float, ny: Float): Path {
        val x = px(nx, true)
        val y = px(ny, false)
        return Path().apply { moveTo(x, y); lineTo(x + 1f, y + 1f) }
    }

    private fun gesture(path: Path, durationMs: Long): Boolean {
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        return try {
            val ok = dispatchGesture(
                GestureDescription.Builder().addStroke(stroke).build(), null, null
            )
            Log.i(TAG, "dispatchGesture dur=${durationMs}ms -> $ok")
            ok
        } catch (e: Exception) {
            Log.w(TAG, "dispatchGesture failed", e)
            false
        }
    }

    // ---------------- 运动流：收到就发，不等回调 ----------------

    private fun beginMotion(nx: Float, ny: Float): Boolean {
        curX = px(nx, true)
        curY = px(ny, false)
        touching = true
        motionMoved = false
        motionDownAt = SystemClock.uptimeMillis()
        lastFlushAt = 0L
        return true
    }

    private fun stepMotion(nx: Float, ny: Float): Boolean {
        if (!touching) return false
        val x = px(nx, true)
        val y = px(ny, false)

        val now = SystemClock.uptimeMillis()
        if (now - lastFlushAt < MIN_FLUSH_GAP) return true

        if (Math.hypot((x - curX).toDouble(), (y - curY).toDouble()) < 1.5) return true

        lastFlushAt = now
        motionMoved = true
        dispatchSeg(curX, curY, x, y)
        curX = x
        curY = y
        return true
    }

    private fun endMotion(nx: Float, ny: Float): Boolean {
        val x = px(nx, true)
        val y = px(ny, false)

        if (!touching) {
            return gesture(dot(nx, ny), 40L)
        }
        touching = false

        if (Math.hypot((x - curX).toDouble(), (y - curY).toDouble()) >= 1.0) {
            dispatchSeg(curX, curY, x, y)
            curX = x
            curY = y
        }

        if (!motionMoved) {
            val held = SystemClock.uptimeMillis() - motionDownAt
            return gesture(dot(nx, ny), if (held > 500) 650L else 40L)
        }
        return true
    }

    private fun dispatchSeg(x1: Float, y1: Float, x2: Float, y2: Float) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        try {
            dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0L, SEG_MS))
                    .build(),
                null,
                null
            )
        } catch (e: Exception) {
            Log.w(TAG, "dispatchSeg failed", e)
        }
    }

    private fun resetMotion() {
        touching = false
        motionMoved = false
    }

    private fun insertText(text: String): Boolean {
        if (text.isEmpty()) return false
        val root = rootInActiveWindow ?: return false
        val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }
}