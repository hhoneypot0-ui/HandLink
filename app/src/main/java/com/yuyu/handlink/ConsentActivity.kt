package com.yuyu.handlink

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle

/** 无悬浮窗权限时，由全屏意图通知拉起，代替前台对话框做同意决定 */
class ConsentActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val req = AppState.consentRequest.value
        if (req == null) { finish(); return }
        AlertDialog.Builder(this)
            .setTitle("牵手远程")
            .setMessage("「${req.name}」正在请求实时查看并控制你的手机屏幕。\n\n只有你点“允许”后对方才能看到和操作。")
            .setCancelable(false)
            .setNegativeButton("拒绝") { d, _ -> d.dismiss(); req.decided(false); finish() }
            .setPositiveButton("允许") { d, _ -> d.dismiss(); req.decided(true); finish() }
            .show()
    }
}
