package yunyun.xiangxiang.handlink.ui.theme

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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yunyun.xiangxiang.handlink.data.settings.ThemeMode

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

/** 站酷快乐体：圆润可爱，内置在 APK 里，不依赖系统字体 */
private val CuteFont = androidx.compose.ui.text.font.FontFamily(
    androidx.compose.ui.text.font.Font(yunyun.xiangxiang.handlink.R.font.zcool_kuaile)
)

private fun AppTypographyWithCuteFont(): Typography = AppTypography.copy(
    displayLarge = AppTypography.displayLarge.copy(fontFamily = CuteFont),
    displayMedium = AppTypography.displayMedium.copy(fontFamily = CuteFont),
    displaySmall = AppTypography.displaySmall.copy(fontFamily = CuteFont),
    headlineLarge = AppTypography.headlineLarge.copy(fontFamily = CuteFont),
    headlineMedium = AppTypography.headlineMedium.copy(fontFamily = CuteFont),
    headlineSmall = AppTypography.headlineSmall.copy(fontFamily = CuteFont),
    titleLarge = AppTypography.titleLarge.copy(fontFamily = CuteFont),
    titleMedium = AppTypography.titleMedium.copy(fontFamily = CuteFont),
    titleSmall = AppTypography.titleSmall.copy(fontFamily = CuteFont),
    bodyLarge = AppTypography.bodyLarge.copy(fontFamily = CuteFont),
    bodyMedium = AppTypography.bodyMedium.copy(fontFamily = CuteFont),
    bodySmall = AppTypography.bodySmall.copy(fontFamily = CuteFont),
    labelLarge = AppTypography.labelLarge.copy(fontFamily = CuteFont),
    labelMedium = AppTypography.labelMedium.copy(fontFamily = CuteFont),
    labelSmall = AppTypography.labelSmall.copy(fontFamily = CuteFont),
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

/** 根据用户选的种子色，生成一套白底亮色主题 */
private fun schemeFromSeed(seed: Color): androidx.compose.material3.ColorScheme {
    val white = Color.White
    val black = Color.Black
    return lightColorScheme(
        primary = seed,
        onPrimary = Color.White,
        primaryContainer = lerp(white, seed, 0.18f),
        onPrimaryContainer = lerp(black, seed, 0.32f),
        secondary = lerp(black, seed, 0.38f),
        secondaryContainer = lerp(white, seed, 0.12f),
        background = lerp(white, seed, 0.04f),
        surface = Color.White,
        surfaceVariant = lerp(white, seed, 0.07f),
        surfaceContainer = lerp(white, seed, 0.05f),
        surfaceContainerHigh = lerp(white, seed, 0.09f),
        outline = lerp(Color(0xFF9AA3B2), seed, 0.18f),
        outlineVariant = lerp(Color(0xFFDDE2EA), seed, 0.12f),
        error = Color(0xFFEF4444),
    )
}

@Composable
fun HandLinkTheme(
    mode: ThemeMode = ThemeMode.LIGHT,
    themeColor: Long = 0xFFFF98BE,
    fontStyle: String = "kuaile",
    dynamic: Boolean = false,
    content: @Composable () -> Unit,
) {
    val ctx = LocalContext.current
    val scheme = when (mode) {
        // 粉色：白底粉字
        ThemeMode.LIGHT -> Light
        // 蓝色：白底蓝字
        ThemeMode.DARK -> Blue
        // 自定义：用用户选的颜色生成
        ThemeMode.CUSTOM -> schemeFromSeed(Color(themeColor))
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
    val typography = when (fontStyle) {
        "system" -> AppTypography
        else -> AppTypographyWithCuteFont()
    }
    MaterialTheme(colorScheme = scheme, typography = typography, shapes = AppShapes, content = content)
}
