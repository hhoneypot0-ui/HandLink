package com.yuyu.handlink

import android.util.Log

/**
 * 控制端解码能力探测。
 *
 * 为什么需要它：
 *   实时投屏的编码尺寸必须迁就**控制端**的解码能力。
 *   实测教训 —— 一加15 编码 816x1792 毫无压力（滑动时 95fps），
 *   但 A93s（天玑700）当控制端时：
 *     · 硬解 34.5 秒零输出（直接卡死）
 *     · 退回软解后 3 秒丢 351 帧
 *   反过来 A93s 编码 528x1200 时，一加15 解码又轻松又清晰。
 *
 *   所以控制端连上时要主动告诉被控端："我最多能吃多大"，
 *   被控端据此调整编码尺寸。
 */
object DecoderProfile {

    private const val TAG = "HandLinkDec"

    data class Cap(
        /** 能稳定解码的最大宽度 */
        val maxW: Int,
        /** 能稳定解码的最大高度 */
        val maxH: Int,
        /** 是否有硬件 AVC 解码器 */
        val hasHw: Boolean,
        /** 综合性能分 0~100 */
        val score: Int,
        /** 芯片档位 0~3 */
        val tier: Int
    )

    fun detect(): Cap {
        val hw = hasHardwareDecoder()
        val ram = totalRamGb()
        val cores = Runtime.getRuntime().availableProcessors()
        val tier = chipTier()
        val codecMax = maxDecoderSize()

        // 性能分：只用于日志展示，尺寸决策主要看芯片档位
        var s = 0.0
        s += when {
            ram >= 16 -> 40.0
            ram >= 12 -> 36.0
            ram >= 8 -> 30.0
            ram >= 6 -> 20.0
            else -> 10.0
        }
        s += when {
            cores >= 8 -> 30.0
            cores >= 6 -> 22.0
            cores >= 4 -> 14.0
            else -> 6.0
        }
        s += if (hw) 30.0 else 0.0
        val score = s.toInt().coerceIn(0, 100)

        // 尺寸上限：按芯片档位，而不是内存/核心数。
        // 血泪教训：A93s 8核7.5G 有硬解，按硬件看起来像高端，
        // 实际解 816x1792 直接卡死。天玑700 就是不行。
        val (w, h) = when {
            tier >= 3 && hw -> 1080 to 2400   // 旗舰：骁龙8系/天玑9系
            // 中高：天玑8系(如 Reno14 的天玑8350，本身能解 4K60)、骁龙7系
            // 给到 1080 宽度，因为这类芯片解码 1080p 是基本盘
            tier >= 2 && hw -> 1080 to 2240
            hw -> 560 to 1280                 // 中低有硬解：A93s 在这档
            score >= 60 -> 480 to 1280        // 纯软解，性能还行
            else -> 400 to 960                // 纯软解，性能弱
        }

        val finalW = minOf(w, codecMax.first.coerceAtLeast(400))
        val finalH = minOf(h, codecMax.second.coerceAtLeast(960))

        val cap = Cap(finalW, finalH, hw, score, tier)
        Log.i(
            TAG,
            "decoder cap: ${finalW}x${finalH} hw=$hw tier=$tier score=$score " +
                "ram=${"%.1f".format(ram)}G cores=$cores codecMax=${codecMax.first}x${codecMax.second}"
        )
        return cap
    }

    /** 是否有硬件 H.264 解码器 */
    private fun hasHardwareDecoder(): Boolean {
        return try {
            val list = android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS)
            for (info in list.codecInfos) {
                if (info.isEncoder) continue
                if (!info.supportedTypes.any { it.equals("video/avc", true) }) continue
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    if (info.isHardwareAccelerated && !info.isSoftwareOnly) return true
                } else {
                    val n = info.name.lowercase()
                    if (!n.startsWith("c2.android") && !n.contains("sw.") &&
                        !n.contains("google")
                    ) return true
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    /** 解码器声明的最大分辨率 */
    private fun maxDecoderSize(): Pair<Int, Int> {
        var bestW = 480
        var bestH = 960
        try {
            val list = android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS)
            for (info in list.codecInfos) {
                if (info.isEncoder) continue
                if (!info.supportedTypes.any { it.equals("video/avc", true) }) continue
                val caps = try {
                    info.getCapabilitiesForType("video/avc")
                } catch (e: Exception) {
                    continue
                } ?: continue
                val vc = caps.videoCapabilities ?: continue
                val w = vc.supportedWidths.upper
                val h = vc.supportedHeights.upper
                // 未声明上限时会返回 Integer.MAX_VALUE，要过滤掉
                if (w in 1..16384 && h in 1..16384) {
                    if (w.toLong() * h.toLong() > bestW.toLong() * bestH.toLong()) {
                        bestW = w
                        bestH = h
                    }
                }
            }
        } catch (e: Exception) { }
        return bestW to bestH
    }

    private fun totalRamGb(): Double = try {
        val txt = java.io.File("/proc/meminfo").readText()
        val kb = Regex("MemTotal:\\s+(\\d+)").find(txt)?.groupValues?.get(1)?.toLong() ?: 0L
        kb / 1024.0 / 1024.0
    } catch (e: Exception) {
        6.0
    }

    /**
     * 芯片档位（1=中低, 2=中高, 3=旗舰）。
     *
     * 比内存/核心数更可靠：同一颗 U 的内存可能配成 6G 或 8G，
     * 但解码能力由芯片本身决定。
     *
     * 取型号顺序：ro.soc.model > ro.board.platform > cpuinfo Hardware
     */
    private fun chipTier(): Int {
        val hint = StringBuilder()
        for (prop in listOf("ro.soc.model", "ro.board.platform", "ro.hardware")) {
            try {
                val c = Class.forName("android.os.SystemProperties")
                val g = c.getMethod("get", String::class.java)
                val v = g.invoke(null, prop) as? String
                if (!v.isNullOrBlank()) hint.append(v.lowercase()).append(' ')
            } catch (e: Throwable) { }
        }
        try {
            val t = java.io.File("/proc/cpuinfo").readText().lowercase()
            Regex("hardware\\s*:\\s*(.+)").find(t)?.groupValues?.get(1)?.let {
                hint.append(it.trim()).append(' ')
            }
        } catch (e: Exception) { }

        val hw = hint.toString()
        Log.i(TAG, "soc hint: $hw")

        return when {
            // 旗舰：骁龙8系/9系(SM8xxx/SM9xxx)、天玑9系(MT698x/MT699x)、麒麟9系、Tensor
            hw.contains("sm8") || hw.contains("sm9") ||
                hw.contains("mt698") || hw.contains("mt699") ||
                hw.contains("kirin9") || hw.contains("tensor") -> 3
            // 中高：天玑8系(MT6877/MT6895/MT6897 等，含 Reno14 的天玑8350)、骁龙7系
            hw.contains("mt689") || hw.contains("mt688") || hw.contains("mt687") ||
                hw.contains("sm7") || hw.contains("sdm7") ||
                hw.contains("mt68") || hw.contains("mt67") ||
                hw.contains("kirin8") -> 2
            // 中低：天玑700/720/600、骁龙6系/4系
            hw.contains("mt6833") || hw.contains("mt6769") ||
                hw.contains("mt6768") || hw.contains("mt6765") ||
                hw.contains("sm6") || hw.contains("sm4") ||
                hw.contains("sdm6") || hw.contains("sdm4") ||
                hw.contains("kirin7") -> 1
            else -> 1
        }
    }
}