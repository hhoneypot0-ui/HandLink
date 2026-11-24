package yunyun.xiangxiang.handlink.host

import android.content.Context
import android.os.Build

/**
 * 手机「看得懂的名字」。
 * Build.MODEL 是 PLK110、PFGM00 这种代号，用户看不懂；
 * 这里优先取厂商写死的市场名（一加 15 / OPPO A93s 5G），再退回系统设备名。
 */
object DeviceNames {

    fun marketingName(context: Context): String {
        systemProp("ro.product.marketname")?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        systemProp("ro.oplus.product.marketname")?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        systemProp("ro.vendor.oplus.market.name")?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        systemProp("ro.config.marketing_name")?.takeIf { it.isNotBlank() }?.let { return it.trim() }

        // 系统设置里的设备名，如「冷桉意的一加15」「OPPO A93s 5G」
        runCatching {
            val n = android.provider.Settings.Global.getString(context.contentResolver, "device_name")
            if (!n.isNullOrBlank()) return n.trim()
        }

        val brand = Build.MANUFACTURER?.trim().orEmpty()
        val model = Build.MODEL?.trim().orEmpty()
        return listOf(brand, model).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "我的手机" }
    }

    private fun systemProp(key: String): String? = runCatching {
        val cls = Class.forName("android.os.SystemProperties")
        val get = cls.getMethod("get", String::class.java)
        get.invoke(null, key) as? String
    }.getOrNull()
}
