package com.yuyu.handlink

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * App 不在前台时，用悬浮窗向本机用户要“允许控制”的决定。
 * 需要 SYSTEM_ALERT_WINDOW（悬浮窗）权限；没有该权限时走全屏意图通知兜底。
 */
object OverlayConsent {

    private var view: LinearLayout? = null
    private var wm: WindowManager? = null

    fun show(ctx: Context, req: ConsentRequest) {
        dismiss()
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F2202028"))
            setPadding(dp(20f), dp(20f), dp(20f), dp(16f))
        }
        root.addView(TextView(ctx).apply {
            text = "牵手远程"
            setTextColor(Color.parseColor("#9AA0AA"))
            textSize = 13f
        })
        root.addView(TextView(ctx).apply {
            text = "「${req.name}」正在请求实时查看并控制\n你的手机屏幕。"
            setTextColor(Color.WHITE)
            textSize = 17f
            setPadding(0, dp(10f), 0, dp(18f))
        })
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val deny = Button(ctx).apply {
            text = "拒绝"; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#40464F"))
        }
        val accept = Button(ctx).apply {
            text = "允许 30 秒"; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#C24444"))
        }
        row.addView(deny, LinearLayout.LayoutParams(0, dp(46f), 1f).apply { marginEnd = dp(8f) })
        row.addView(accept, LinearLayout.LayoutParams(0, dp(46f), 1f))
        root.addView(row)

        val window = WindowManager.LayoutParams(
            dp(320f), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(80f) }

        wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        deny.setOnClickListener { dismiss(); req.decided(false) }
        accept.setOnClickListener { dismiss(); req.decided(true) }
        wm?.addView(root, window)
        view = root
    }

    fun dismiss() {
        view?.let { try { wm?.removeView(it) } catch (e: Exception) { } }
        view = null
    }
}
