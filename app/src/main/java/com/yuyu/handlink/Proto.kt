package com.yuyu.handlink

import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface

/** 局域网直连端口（被控端监听） */
const val WS_PORT = 8723

/**
 * 消息约定：
 *  - 文本帧 = JSON，字段 t 为类型
 *  - 二进制帧 = [1B flags][8B pts BE][payload]
 *    flags bit0=关键帧 bit1=CODEC_CONFIG(SPS/PPS 或 AudioSpecificConfig) bit2=AUDIO
 */
object Proto {
    // 控制端 -> 被控端
    /**
 * 控制端发起配对，**同时上报自己的解码能力**。
 *
 * 被控端拿到 maxW/maxH 后会据此决定编码尺寸 —— 这是保证不卡的关键：
 * 编码尺寸必须迁就控制端解码能力，而不是被控端编码能力。
 * 实测：A93s 当控制端解 816x1792 时硬解卡死、软解丢 351 帧；
 *      但解 528x1200 就正常。所以必须让被控端知道控制端能吃多大。
 */
fun pair(
    code: String,
    name: String,
    maxW: Int = 0,
    maxH: Int = 0,
    decHw: Boolean = false,
    score: Int = 0
) = JSONObject().apply {
    put("t", "pair")
    put("code", code)
    put("name", name)
    put("maxW", maxW)
    put("maxH", maxH)
    put("decHw", decHw)
    put("score", score)
}
    fun tap(x: Float, y: Float) = JSONObject().put("t", "tap").put("x", x).put("y", y)
    fun longPress(x: Float, y: Float) = JSONObject().put("t", "lp").put("x", x).put("y", y)
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) =
        JSONObject().put("t", "swipe").put("x1", x1).put("y1", y1).put("x2", x2).put("y2", y2).put("ms", ms)
    // 运动流：逐点描述手指轨迹，比分段 swipe 更接近真实触摸
    fun down(x: Float, y: Float) = JSONObject().put("t", "down").put("x", x).put("y", y)
    fun move(x: Float, y: Float) = JSONObject().put("t", "move").put("x", x).put("y", y)
    fun up(x: Float, y: Float) = JSONObject().put("t", "up").put("x", x).put("y", y)
    fun cancel() = JSONObject().put("t", "cancel")
    fun key(k: String) = JSONObject().put("t", "key").put("k", k)
    fun text(s: String) = JSONObject().put("t", "text").put("s", s)
    fun stop() = JSONObject().put("t", "stop")

    // 被控端 -> 控制端
    /**
 * 握手时把自己的**解码能力**告诉对方。
 *
 * 这是保证不卡的关键：实时投屏的编码尺寸必须迁就**控制端**的解码能力，
 * 而不是被控端的编码能力。
 * 实测教训：一加15 编码 816x1792 毫无压力（95fps），
 * 但 A93s（天玑700）当控制端时硬解直接卡死、软解丢帧 351 帧，
 * 整个画面没法看。反过来 A93s 编码 528x1200 时，一加15 解码又轻松又清晰。
 *
 * 字段：
 *  maxW/maxH  控制端能稳定解码的尺寸上限
 *  decHw      是否有硬件解码器
 *  score      综合性能分
 */
fun hello(
    maxW: Int = 0,
    maxH: Int = 0,
    decHw: Boolean = false,
    score: Int = 0
) = JSONObject().apply {
    put("t", "hello")
    put("app", "HandLink")
    put("maxW", maxW)
    put("maxH", maxH)
    put("decHw", decHw)
    put("score", score)
}
    fun size(w: Int, h: Int) = JSONObject().put("t", "size").put("w", w).put("h", h)
    fun accept(name: String) = JSONObject().put("t", "accept").put("peer", name)
    fun deny(reason: String) = JSONObject().put("t", "deny").put("reason", reason)
    fun note(msg: String) = JSONObject().put("t", "note").put("msg", msg)
}

/** 本机 IPv4（优先 wlan） */
/**
 * 取本机局域网 IP，给控制端填地址用。
 *
 * 注意：不能只认 "wlan0"。
 * 实测一加15 的 WiFi 网卡叫 wlan2，另外还有 rmnet_data3（移动数据）和 tun0（VPN）。
 * 原来的 sortedByDescending { startsWith("wlan") } 只区分 true/false，
 * 不保证同一类里按优先级排，结果可能选中移动数据网段的地址，对方根本连不上。
 */
/**
 * 取本机局域网 IP（给控制端填地址用）。
 *
 * 踩过的坑：
 *  1) 不能只认 wlan0 —— 一加15 的 WiFi 网卡叫 wlan2，另有 wlan0 存在但无地址
 *  2) 不能按"是否 10.x 网段"过滤 —— 移动数据 rmnet 也常是 10.x
 *  3) 必须排除 rmnet(移动数据)、tun(VPN)、ppp 等，对方在局域网连不上
 *
 * 最终策略：先按网卡名挑出候选（wlan/eth 优先），再取第一个有效私有 IPv4。
 */
/**
 * 取本机局域网 IPv4（给控制端填地址用）。
 *
 * 为什么不用 NetworkInterface：
 *   Android 10+ 对 NetworkInterface.getNetworkInterfaces() 有 SELinux 限制，
 *   部分 ROM（实测一加15 安卓17）会返回空列表。必须走 ConnectivityManager。
 *
 * 优先级：WiFi > 以太网 > 其他非虚拟网络。排除移动数据(rmnet)和 VPN(tun)。
 */
fun localIpv4(ctx: android.content.Context): String? {
    // 首选：ConnectivityManager 的链路属性（Android 官方推荐做法）
    try {
        val cm = ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager
        if (cm != null) {
            for (net in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(net) ?: continue
                // 只要 WiFi / 以太网，排除移动数据与 VPN
                val isLan = caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
                if (!isLan) continue
                val lp = cm.getLinkProperties(net) ?: continue
                val v4 = lp.linkAddresses.map { it.address }
                    .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                val h = (v4 as? Inet4Address)?.hostAddress
                if (!h.isNullOrBlank()) {
                    android.util.Log.i("HandLinkNet", "ip via ConnectivityManager: $h")
                    return h
                }
            }
        }
    } catch (e: Exception) {
        android.util.Log.w("HandLinkNet", "ConnectivityManager failed", e)
    }

    // 兜底：NetworkInterface（老系统或特殊 ROM 上仍可用）
    return try {
        val all = NetworkInterface.getNetworkInterfaces()?.toList() ?: return null
        val cands = ArrayList<Pair<String, String>>()
        for (n in all) {
            if (!n.isUp || n.isLoopback) continue
            val nm = n.name.lowercase()
            if (nm.startsWith("rmnet") || nm.startsWith("r_rmnet") ||
                nm.startsWith("tun") || nm.startsWith("ppp") ||
                nm.startsWith("dummy") || nm.startsWith("ip6tnl") ||
                nm.startsWith("tunl") || nm == "lo"
            ) continue
            val isLan = nm.startsWith("wlan") || nm.startsWith("eth") || nm.startsWith("ap")
            for (a in n.inetAddresses) {
                if (a is Inet4Address && !a.isLoopbackAddress) {
                    a.hostAddress?.let { cands.add(nm to it) }
                }
            }
        }
        cands.sortedWith(
            compareByDescending<Pair<String, String>> {
                it.first.startsWith("wlan") || it.first.startsWith("eth")
            }.thenByDescending { it.first }
        ).firstOrNull()?.second
    } catch (e: Exception) {
        null
    }
}

fun encodeFrame(flags: Int, ptsUs: Long, payload: ByteArray): ByteArray {
    val out = ByteArray(9 + payload.size)
    out[0] = flags.toByte()
    var pts = ptsUs
    for (i in 8 downTo 1) { out[i] = (pts and 0xFF).toByte(); pts = pts shr 8 }
    System.arraycopy(payload, 0, out, 9, payload.size)
    return out
}

fun decodeFrameFlags(bytes: ByteArray): Int = bytes[0].toInt() and 0xFF
fun decodeFramePts(bytes: ByteArray): Long {
    var pts = 0L
    for (i in 1..8) pts = (pts shl 8) or (bytes[i].toLong() and 0xFF)
    return pts
}

// ==========================================================================
//  WebRTC 信令
//
//  为什么上 WebRTC：
//    拆 ColorOS 的 ShareScreen 发现它用的是 com.oplus.ortc（基于 WebRTC），
//    核心能力是 availableOutgoingBitrate / BitrateAllocation ——
//    实时探测当前带宽并动态调整码率/分辨率/帧率。
//    我们用固定码率是开环控制，网络一抖就只能丢帧，画面又卡又糊。
//
//  信令怎么走：
//    不改服务器，复用现有中继的文本通道。
//    被控端 createOffer → 通过 relay 发给控制端 → 控制端 createAnswer 回传，
//    ICE candidate 同样双向透传。
// ==========================================================================

/** 被控端发起：SDP offer */
fun rtcOffer(sdp: String) = JSONObject().put("t", "rtc-offer").put("sdp", sdp)

/** 控制端应答：SDP answer */
fun rtcAnswer(sdp: String) = JSONObject().put("t", "rtc-answer").put("sdp", sdp)

/** 双向：ICE candidate */
fun rtcIce(mid: String, mline: Int, candidate: String) =
    JSONObject().put("t", "rtc-ice").put("mid", mid).put("mline", mline).put("cand", candidate)

/** 控制端通知被控端：WebRTC 已就绪 */
fun rtcReady() = JSONObject().put("t", "rtc-ready")
