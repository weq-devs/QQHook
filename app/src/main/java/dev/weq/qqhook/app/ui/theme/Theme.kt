package dev.weq.qqhook.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// ---------------------------------------------------------------- 语义色
/**
 * 抓包场景专用的语义色：收发方向、协议类型、状态。
 * 这些颜色在浅色 / 深色下各有一套值，不跟随动态取色（否则会失去辨识度）。
 */
data class CaptureColors(
    /** 发送方向 */
    val send: Color,
    val sendContainer: Color,
    val onSendContainer: Color,
    /** 接收方向 */
    val recv: Color,
    val recvContainer: Color,
    val onRecvContainer: Color,
    /** 已读 / 未读 */
    val unreadDot: Color,
    val readContent: Color,
    /** 协议标识 */
    val proto: Color,
    val jce: Color,
    val text: Color,
    /** 等宽字体的背景（hex 区域） */
    val codeBackground: Color,
    val onCodeBackground: Color,
    /** 卡片描边与分隔 */
    val cardBorder: Color,
    val divider: Color,
    /** 强调色（悬浮按钮 / 选中态） */
    val accent: Color,
)

private val LightCaptureColors = CaptureColors(
    send = Color(0xFF1A73E8),
    sendContainer = Color(0xFFE3EEFF),
    onSendContainer = Color(0xFF0B3D7A),
    recv = Color(0xFF0F9D58),
    recvContainer = Color(0xFFE2F5EA),
    onRecvContainer = Color(0xFF0A5C34),
    unreadDot = Color(0xFF1A73E8),
    readContent = Color(0xFF9AA0A6),
    proto = Color(0xFF7B4DDB),
    jce = Color(0xFFD97400),
    text = Color(0xFF2E7D32),
    codeBackground = Color(0xFFF5F6F9),
    onCodeBackground = Color(0xFF2B2F36),
    cardBorder = Color(0xFFE3E6EC),
    divider = Color(0xFFEDEFF3),
    accent = Color(0xFF1A73E8),
)

private val DarkCaptureColors = CaptureColors(
    send = Color(0xFF6FA8FF),
    sendContainer = Color(0xFF1B3556),
    onSendContainer = Color(0xFFD6E5FF),
    recv = Color(0xFF5FD08A),
    recvContainer = Color(0xFF163D28),
    onRecvContainer = Color(0xFFC9F2DA),
    unreadDot = Color(0xFF6FA8FF),
    readContent = Color(0xFF6B7078),
    proto = Color(0xFFB79BFF),
    jce = Color(0xFFFFB067),
    text = Color(0xFF7FD48F),
    codeBackground = Color(0xFF17181C),
    onCodeBackground = Color(0xFFD4D7DD),
    cardBorder = Color(0xFF2C2F36),
    divider = Color(0xFF24272D),
    accent = Color(0xFF6FA8FF),
)

val LocalCaptureColors = staticCompositionLocalOf { LightCaptureColors }

// ---------------------------------------------------------------- 基础配色

private val LightColors = lightColorScheme(
    primary = Color(0xFF1A73E8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E6FF),
    onPrimaryContainer = Color(0xFF062B5C),
    secondary = Color(0xFF0F9D58),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDF3E6),
    onSecondaryContainer = Color(0xFF073B22),
    tertiary = Color(0xFF7B4DDB),
    onTertiary = Color.White,
    error = Color(0xFFD93025),
    onError = Color.White,
    errorContainer = Color(0xFFFCE4E2),
    onErrorContainer = Color(0xFF5C0F0A),
    background = Color(0xFFF7F8FB),
    onBackground = Color(0xFF1A1C1F),
    surface = Color.White,
    onSurface = Color(0xFF1A1C1F),
    surfaceVariant = Color(0xFFEFF1F6),
    onSurfaceVariant = Color(0xFF5A6069),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF2F4F8),
    surfaceContainer = Color(0xFFEDEFF4),
    surfaceContainerHigh = Color(0xFFE6E9F0),
    surfaceContainerHighest = Color(0xFFDFE3EB),
    outline = Color(0xFFC9CDD6),
    outlineVariant = Color(0xFFE3E6EC),
    inverseSurface = Color(0xFF2B2F36),
    inverseOnSurface = Color(0xFFF2F4F8),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6FA8FF),
    onPrimary = Color(0xFF03284F),
    primaryContainer = Color(0xFF1B3556),
    onPrimaryContainer = Color(0xFFD6E5FF),
    secondary = Color(0xFF5FD08A),
    onSecondary = Color(0xFF06341D),
    secondaryContainer = Color(0xFF163D28),
    onSecondaryContainer = Color(0xFFC9F2DA),
    tertiary = Color(0xFFB79BFF),
    onTertiary = Color(0xFF2A1257),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF5C0F0A),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFFCE4E2),
    background = Color(0xFF121316),
    onBackground = Color(0xFFE4E6EA),
    surface = Color(0xFF191A1E),
    onSurface = Color(0xFFE4E6EA),
    surfaceVariant = Color(0xFF24262C),
    onSurfaceVariant = Color(0xFF9BA1AA),
    surfaceContainerLowest = Color(0xFF0E0F12),
    surfaceContainerLow = Color(0xFF1D1E23),
    surfaceContainer = Color(0xFF222429),
    surfaceContainerHigh = Color(0xFF2A2C33),
    surfaceContainerHighest = Color(0xFF33363E),
    outline = Color(0xFF4A4E57),
    outlineVariant = Color(0xFF2C2F36),
    inverseSurface = Color(0xFFE4E6EA),
    inverseOnSurface = Color(0xFF191A1E),
)

// ---------------------------------------------------------------- 字体

private val AppTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 27.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.1.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.2.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 13.5.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.15.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.5.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.3.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 10.5.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.4.sp,
    ),
)

/** 等宽字体，用于 hex / 协议字段值。 */
val MonoStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 17.sp,
)

@Composable
fun TxHookTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** 是否跟随系统取色（壁纸取色）。抓包工具的语义色不跟随，仅基础色跟随。 */
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val baseScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    val captureColors = if (darkTheme) DarkCaptureColors else LightCaptureColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalCaptureColors provides captureColors) {
        MaterialTheme(
            colorScheme = baseScheme,
            typography = AppTypography,
            shapes = androidx.compose.material3.Shapes(
                extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                medium = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                large = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
            ),
            content = content,
        )
    }
}
