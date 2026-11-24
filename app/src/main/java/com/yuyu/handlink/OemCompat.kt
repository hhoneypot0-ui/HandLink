package com.yuyu.handlink

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * ColorOS / OxygenOS 后台保活引导。
 *
 * 为什么需要：
 *   ColorOS 的后台管理非常激进。实测和大量反馈都指向同一现象 ——
 *   切后台几分钟、或者锁屏一段时间，后台进程被冻结，于是：
 *     · 无障碍服务被系统关掉
 *     · 前台服务被杀
 *     · 屏幕共享自然断掉
 *
 *   系统不会给普通 App 静默授权的接口，只能引导用户手动开几个开关：
 *     1) 允许完全后台行为（耗电管理）
 *     2) 允许自启动
 *     3) 关闭"智能省电"对该应用的限制
 *     4) 最近任务里给卡片加锁
 *
 *   这个类做两件事：检测哪几项还没开；一键跳到对应设置页。
 */
object OemCompat {

    private const val TAG = "HandLinkOem"

    data class Status(
        /** 是否已忽略电池优化（AOSP 标准项） */
        val ignoringBattery: Boolean,
        /** 是否 ColorOS 系（OPPO/一加/realme） */
        val isColorOs: Boolean,
        /** 品牌名 */
        val brand: String
    )

    fun detect(ctx: Context): Status {
        val ignoring = try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(ctx.packageName)
        } catch (e: Exception) {
            false
        }
        val brand = Build.BRAND.orEmpty()
        val mfr = Build.MANUFACTURER.orEmpty()
        val isColorOs = mfr.equals("OPPO", true) || mfr.equals("OnePlus", true) ||
            mfr.equals("realme", true) || brand.equals("OPPO", true) ||
            brand.equals("OnePlus", true) || brand.equals("realme", true)
        Log.i(TAG, "oem: mfr=$mfr brand=$brand colorOs=$isColorOs ignoringBattery=$ignoring")
        return Status(ignoring, isColorOs, if (mfr.isNotBlank()) mfr else brand)
    }

    /** 请求忽略电池优化（弹 AOSP 标准对话框） */
    fun requestIgnoreBattery(ctx: Context): Boolean {
        return try {
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            i.data = Uri.parse("package:${ctx.packageName}")
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            true
        } catch (e: Exception) {
            Log.w(TAG, "requestIgnoreBattery failed", e)
            // 退化到电池优化列表页
            try {
                val i2 = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                i2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i2)
                true
            } catch (e2: Exception) {
                false
            }
        }
    }

    /**
     * 打开本应用的详情页 —— ColorOS 里"耗电管理/自启动"都在这里。
     * 用户进去后能一次把几项都开掉。
     */
    fun openAppDetail(ctx: Context): Boolean {
        val candidates = listOf(
            // OPPO / 一加 / realme 的应用详情
            Intent().apply {
                component = ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                )
            },
            Intent().apply {
                component = ComponentName(
                    "com.oplus.safecenter",
                    "com.oplus.safecenter.permission.startup.StartupAppListActivity"
                )
            },
            Intent().apply {
                component = ComponentName(
                    "com.coloros.oppoguardelf",
                    "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"
                )
            },
            Intent().apply {
                component = ComponentName(
                    "com.oplus.battery",
                    "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"
                )
            }
        )
        for (i in candidates) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                ctx.startActivity(i)
                Log.i(TAG, "opened oem page: ${i.component}")
                return true
            } catch (e: Exception) {
                // 下一个
            }
        }
        // 兜底：应用详情页
        return try {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            Log.i(TAG, "fallback to app detail")
            true
        } catch (e: Exception) {
            Log.w(TAG, "openAppDetail all failed", e)
            false
        }
    }

    /** 打开无障碍设置页 */
    fun openAccessibility(ctx: Context): Boolean = try {
        val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        true
    } catch (e: Exception) {
        false
    }

    /**
     * 生成给用户看的保活清单（纯文本，UI 直接显示）。
     * 只在 ColorOS 系设备上给出额外项。
     */
    fun keepAliveChecklist(ctx: Context): List<String> {
        val st = detect(ctx)
        val list = ArrayList<String>()
        if (st.isColorOs) {
            list.add("设置 › 应用 › 应用管理 › 牵手远程 › 耗电管理")
            list.add("开启「允许完全后台行为」")
            list.add("开启「允许自启动」")
            list.add("关闭「闲置时暂停应用活动」")
            list.add("最近任务里下拉卡片加锁（防止被划掉）")
        } else {
            list.add("设置 › 电池 › 电池优化 › 牵手远程 › 不优化")
            list.add("最近任务里给卡片加锁")
        }
        if (!st.ignoringBattery) {
            list.add("当前尚未加入电池优化白名单，建议点下面按钮开启")
        }
        return list
    }
}