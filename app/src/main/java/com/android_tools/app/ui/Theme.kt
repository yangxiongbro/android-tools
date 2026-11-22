package com.android_tools.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val RecordRed = Color(0xFFE5484D)

/**
 * 应用与每个工具各一套强调色（渐变色标），工具卡片、图标徽章都用它，
 * 保证后续新增工具时视觉风格统一。
 */
object AppAccents {
    /** 应用品牌色，取自「工具箱」图标的蓝色（#1296DB）。 */
    val Brand = listOf(Color(0xFF35B0F2), Color(0xFF1296DB), Color(0xFF0B6FB3))
    val Record = listOf(Color(0xFFF4665F), Color(0xFFE5484D), Color(0xFFC72F5A))
    val Sync = listOf(Color(0xFFA794FF), Color(0xFF8B7CF6), Color(0xFF6C5CE7))
    val Chat = listOf(Color(0xFF4ADE80), Color(0xFF22C55E), Color(0xFF15A34A))
}

private val DarkColors = darkColorScheme(
    primary = RecordRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF4A1417),
    onPrimaryContainer = Color(0xFFFFDAD8),
    secondary = Color(0xFF8FB4FF),
    onSecondary = Color(0xFF0F2038),
    secondaryContainer = Color(0xFF1B2E4A),
    onSecondaryContainer = Color(0xFFD7E4FF),
    background = Color(0xFF090D12),
    onBackground = Color(0xFFE7ECF3),
    surface = Color(0xFF111821),
    onSurface = Color(0xFFE7ECF3),
    surfaceVariant = Color(0xFF19212C),
    onSurfaceVariant = Color(0xFFA6B3C5),
    surfaceTint = Color(0xFF2C3947),
    surfaceContainerLowest = Color(0xFF0A0F14),
    surfaceContainerLow = Color(0xFF0E141B),
    surfaceContainer = Color(0xFF121A23),
    surfaceContainerHigh = Color(0xFF18222D),
    surfaceContainerHighest = Color(0xFF1E2937),
    outline = Color(0xFF39434F),
    outlineVariant = Color(0xFF232D3A),
    error = Color(0xFFFFB4AB),
)

private val LightColors = lightColorScheme(
    primary = RecordRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD8),
    onPrimaryContainer = Color(0xFF410004),
    secondary = Color(0xFF2C5D9E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2E9F4),
    onSecondaryContainer = Color(0xFF16304F),
    background = Color(0xFFF5F7FA),
    onBackground = Color(0xFF161B22),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF161B22),
    surfaceVariant = Color(0xFFEDEFF3),
    onSurfaceVariant = Color(0xFF5A6472),
    surfaceTint = Color(0xFFDDE3EB),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF9FAFC),
    surfaceContainer = Color(0xFFF1F3F7),
    surfaceContainerHigh = Color(0xFFEBEEF3),
    surfaceContainerHighest = Color(0xFFE4E8EE),
    outline = Color(0xFFC2C9D2),
    outlineVariant = Color(0xFFE1E5EB),
)

private val AppTypography = Typography(
    headlineLarge = TextStyle(
        fontSize = 30.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontSize = 25.sp,
        lineHeight = 31.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.4).sp,
    ),
    headlineSmall = TextStyle(
        fontSize = 21.sp,
        lineHeight = 27.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontSize = 19.sp,
        lineHeight = 25.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 21.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleSmall = TextStyle(
        fontSize = 14.sp,
        lineHeight = 19.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Medium,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp,
        lineHeight = 15.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.2.sp,
    ),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun ScreenRecorderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
