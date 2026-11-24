package com.yuyu.handlink.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuyu.handlink.data.settings.ThemeMode

object AppColors {
    val Brand = Color(0xFFFF98BE)
    val BrandDark = Color(0xFFFFB8D4)
    val Online = Color(0xFF22C55E)
    val Offline = Color(0xFF9CA3AF)
    val Busy = Color(0xFFF59E0B)
    val Danger = Color(0xFFEF4444)
    val Warning = Color(0xFFF59E0B)
}

private val Light = lightColorScheme(
    primary = AppColors.Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE4EE),
    onPrimaryContainer = Color(0xFF5C1A33),
    secondary = Color(0xFF5B6478),
    secondaryContainer = Color(0xFFFCE8F0),
    background = Color(0xFFFFF9FB),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFFDF0F5),
    surfaceContainer = Color(0xFFFFF5F9),
    surfaceContainerHigh = Color(0xFFFDEFF5),
    outline = Color(0xFFC7CCD8),
    outlineVariant = Color(0xFFE3E6EE),
    error = AppColors.Danger,
)

/** 「蓝色」主题：白底 + 蓝色点缀（和粉色一样是亮色，只换主色） */
private val Blue = lightColorScheme(
    primary = Color(0xFF3B7DD8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE9FB),
    onPrimaryContainer = Color(0xFF17335C),
    secondary = Color(0xFF546A85),
    secondaryContainer = Color(0xFFE3ECF7),
    background = Color(0xFFF7FAFD),
    surface = Color.White,
    surfaceVariant = Color(0xFFEDF3FA),
    surfaceContainer = Color(0xFFF2F7FC),
    surfaceContainerHigh = Color(0xFFEAF1F9),
    outline = Color(0xFFC3CFDD),
    outlineVariant = Color(0xFFDDE6F0),
    error = Color(0xFFEF4444),
)

/** 系统深色模式用的深色主题 */
private val Dark = darkColorScheme(
    primary = Color(0xFF7FB3FF),
    onPrimary = Color(0xFF04203F),
    primaryContainer = Color(0xFF1B3A63),
    onPrimaryContainer = Color(0xFFD6E6FF),
    secondary = Color(0xFFA8C4E8),
    secondaryContainer = Color(0xFF22344D),
    background = Color(0xFF0A1524),
    surface = Color(0xFF101E31),
    surfaceVariant = Color(0xFF16273D),
    surfaceContainer = Color(0xFF0E1B2C),
    surfaceContainerHigh = Color(0xFF17293F),
    outline = Color(0xFF33506F),
    outlineVariant = Color(0xFF22364D),
    error = Color(0xFFFF7B7B),
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun HandLinkTheme(mode: ThemeMode = ThemeMode.SYSTEM, dynamic: Boolean = false, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = when (mode) {
        // 粉色：白底粉字
        ThemeMode.LIGHT -> Light
        // 蓝色：白底蓝字
        ThemeMode.DARK -> Blue
        // 跟随系统：系统深色才用深色
        ThemeMode.SYSTEM -> {
            val dark = isSystemInDarkTheme()
            when {
                dynamic && Build.VERSION.SDK_INT >= 31 ->
                    if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
                dark -> Dark
                else -> Light
            }
        }
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
}
