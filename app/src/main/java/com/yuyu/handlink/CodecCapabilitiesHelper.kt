package com.yuyu.handlink

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log

/**
 * 编解码能力探测与降级。
 *
 * 不同厂商（OPPO/小米/华为/三星/…)的硬件编解码器支持的分辨率、帧率、
 * 对齐要求、profile 都不一样。直接 configure 失败，或者 configure 成功但
 * 实际不出帧，是"某些机型黑屏"的主要原因。
 *
 * 这里的策略：
 *  1. configure 前先用 CodecCapabilities 检查分辨率是否在支持范围内
 *  2. 超出就按最大支持尺寸等比缩下来
 *  3. 宽高必须是编码器要求的对齐值（常见 16/32）
 */
object CodecCapabilitiesHelper {

    private const val TAG = "HandLinkCodec"
    private const val MIME_AVC = MediaFormat.MIMETYPE_VIDEO_AVC

    /**
     * 找出这台机器上可用的 H.264 编码器，返回它支持的最大宽高。
     * 拿不到就返回一个保守值。
     */
    fun maxEncoderSize(): Pair<Int, Int> {
        var best = 1280 to 1280  // 保守默认
        try {
            val list = MediaCodecList(MediaCodecList.ALL_CODECS)
            for (info in list.codecInfos) {
                if (!info.isEncoder) continue
                for (type in info.supportedTypes) {
                    if (!type.equals(MIME_AVC, ignoreCase = true)) continue
                    try {
                        val caps = info.getCapabilitiesForType(MIME_AVC)
                        val vc = caps.videoCapabilities ?: continue
                        val mw = vc.supportedWidths.upper
                        val mh = vc.supportedHeights.upper
                        // 用长边/短边表达，取本机最大能力
                        val maxLong = maxOf(mw, mh)
                        val maxShort = minOf(mw, mh)
                        if (maxLong > best.first || maxShort > best.second) {
                            best = maxLong to maxShort
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "probe ${info.name} failed", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "probe encoders failed", e)
        }
        Log.i(TAG, "max encoder size: ${best.first}x${best.second}")
        return best
    }

    /** H.264 编码器普通要求宽高 2 对齐，部分要求 16 对齐，这里取 16 */
    fun align16(v: Int): Int = (v / 16) * 16

    /**
     * 把期望尺寸收敛到编码器真正能编的范围。
     * 返回 (w, h)，都已做 16 对齐。
     */
    fun fitEncoder(w: Int, h: Int, maxLong: Int, maxShort: Int): Pair<Int, Int> {
        var cw = w
        var ch = h
        val longSide = maxOf(cw, ch)
        val shortSide = minOf(cw, ch)
        if (longSide > maxLong || shortSide > maxShort) {
            val ratio = minOf(
                maxLong.toDouble() / longSide,
                maxShort.toDouble() / shortSide
            )
            cw = (cw * ratio).toInt()
            ch = (ch * ratio).toInt()
            Log.i(TAG, "downscale to ${cw}x${ch} (max ${maxLong}x${maxShort})")
        }
        cw = align16(cw).coerceAtLeast(160)
        ch = align16(ch).coerceAtLeast(160)
        return cw to ch
    }

    /**
     * 按优先级挑一个真的能 configure 的编码器。
     * 返回 (codecName, format)；都失败返回 null。
     */
    /**
 * 按优先级挑编码器。**必须传入完整的 format**，
 * 否则会出现"测试通过、真实 configure 失败 → 悄悄回退软编"的坑：
 * 实测 A93s（天玑700）就是这样，硬编拿着带 profile/b-frame 的参数 configure 失败，
 * 系统退回 media.swcodec 软编，CPU 直接跑满，画面又卡又糊。
 */
fun createBestEncoder(
    w: Int, h: Int, fmt: MediaFormat
): Pair<String, MediaFormat>? {
    val list = MediaCodecList(MediaCodecList.ALL_CODECS)
    val want = w to h

    data class Cand(val name: String, val hw: Boolean, val prefer: Int)

    val cands = ArrayList<Cand>()
    for (info in list.codecInfos) {
        if (!info.isEncoder) continue
        if (!info.supportedTypes.any { it.equals(MIME_AVC, true) }) continue
        // 分辨率能力检查：不支持的直接排除
        val ok = try {
            val caps = info.getCapabilitiesForType(MIME_AVC)
            caps.videoCapabilities?.isSizeSupported(w, h) ?: true
        } catch (e: Exception) {
            false
        }
        if (!ok) continue

        val hw = if (android.os.Build.VERSION.SDK_INT >= 29) {
            info.isHardwareAccelerated && !info.isSoftwareOnly
        } else {
            // API<29 没有这个字段，用名字猜测
            val n = info.name.lowercase()
            !(n.startsWith("c2.android") || n.contains("sw.") || n.contains("google"))
        }
        // 硬件编码器绝对优先；同类里 OMX 的 MTK/QCOM 前缀再往前排
        val prefer = when {
            hw -> 100
            else -> 0
        }
        cands.add(Cand(info.name, hw, prefer))
    }

    // 硬件优先，其次名字排序保证稳定
    cands.sortWith(compareByDescending<Cand> { it.prefer }.thenBy { it.name })

    for (c in cands) {
        try {
            val codec = android.media.MediaCodec.createByCodecName(c.name)
            // 用**真实 format** 试，确保参数被接受
            codec.configure(fmt, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            codec.stop()
            codec.release()
            Log.i(TAG, "chosen encoder: ${c.name} hw=${c.hw} for ${w}x${h}")
            return c.name to fmt
        } catch (e: Exception) {
            Log.w(TAG, "encoder ${c.name} rejected: ${e.message}")
        }
    }

    // 全部失败：最后尝试用最小参数集再试一轮硬件编码器
    Log.w(TAG, "all encoders rejected full format, retry with minimal")
    val minimal = MediaFormat.createVideoFormat(MIME_AVC, w, h).apply {
        setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )
    }
    for (c in cands.filter { it.hw }) {
        try {
            val codec = android.media.MediaCodec.createByCodecName(c.name)
            codec.configure(minimal, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            codec.stop()
            codec.release()
            Log.i(TAG, "fallback minimal: ${c.name}")
            return c.name to minimal
        } catch (e: Exception) {
            Log.w(TAG, "minimal ${c.name} rejected: ${e.message}")
        }
    }
    return null
}

    /**
     * 挑一个真能 configure 的解码器。
     */
    fun createBestDecoder(w: Int, h: Int): Pair<String, MediaFormat>? {
        val list = MediaCodecList(MediaCodecList.ALL_CODECS)
        val candidates = list.codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(MIME_AVC, true) } }

        for (info in candidates) {
            try {
                val fmt = MediaFormat.createVideoFormat(MIME_AVC, w, h)
                val codec = android.media.MediaCodec.createByCodecName(info.name)
                codec.configure(fmt, null, null, 0)
                codec.start()
                codec.stop()
                codec.release()
                Log.i(TAG, "chosen decoder: ${info.name} for ${w}x${h}")
                return info.name to fmt
            } catch (e: Exception) {
                Log.w(TAG, "decoder ${info.name} unusable: ${e.message}")
            }
        }
        return null
    }
}