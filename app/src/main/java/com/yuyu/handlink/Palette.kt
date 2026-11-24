package com.yuyu.handlink

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 牵手远程 配色系统
 *
 * 主色：粉嫩粉 #FF98BE（与桌面图标同色）
 */
object Palette {
    val Pink        = Color(0xFFFF98BE)   // 主色，与图标同色
    val PinkLight   = Color(0xFFFFC4DA)   // 浅粉，次级强调
    val PinkDeep    = Color(0xFFE96A99)   // 深粉，按下态/容器
    val Surface     = Color(0xFF141218)   // 页面底色
    val SurfaceHigh = Color(0xFF232029)   // 卡片/输入框底色
    val OnSurface   = Color(0xFFF0E8ED)   // 主文字

    // 状态色
    val Online      = Color(0xFF81C784)   // 已连接
    val Pending     = Color(0xFFFFB74D)   // 未连接 / 处理中
    val Error       = Color(0xFFFF8A9B)   // 错误
}

fun handLinkScheme() = darkColorScheme(
    primary = Palette.Pink,
    onPrimary = Color(0xFF3D1024),
    primaryContainer = Palette.PinkDeep,
    onPrimaryContainer = Palette.PinkLight,
    secondary = Palette.PinkLight,
    onSecondary = Color(0xFF3D1024),
    secondaryContainer = Palette.PinkDeep,
    onSecondaryContainer = Color(0xFFFFE4EE),
    tertiary = Palette.Online,
    background = Palette.Surface,
    onBackground = Palette.OnSurface,
    surface = Palette.Surface,
    onSurface = Palette.OnSurface,
    surfaceVariant = Palette.SurfaceHigh,
    onSurfaceVariant = Palette.PinkLight,
    outline = Color(0xFF6B5C66),
)