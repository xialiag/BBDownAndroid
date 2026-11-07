package com.bbdown.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设计令牌：逐项对应 app.css 的 CSS 变量，保证 Compose 版与 WebView 版像素级一致。
 * 深浅两套色板同名，切换主题只换 Palette 实例。
 */
@Immutable
data class Palette(
    val bg: Color,
    val bgSidebar: Color,
    val bgActivity: Color,
    val bgTitlebar: Color,
    val bgStatusbar: Color,
    val bgInput: Color,
    val bgHover: Color,
    val bgActive: Color,
    val bgListActive: Color,
    val fg: Color,
    val fgDim: Color,
    val fgBright: Color,
    val fgTitle: Color,
    val border: Color,
    val borderLight: Color,
    val accent: Color,
    val accentBtn: Color,
    val accentBtnHover: Color,
    val success: Color,
    val error: Color,
    val warn: Color,
    val cardBg: Color,
    val cardBorder: Color,
    val pillBg: Color,
    val pillBorder: Color,
    val dropdownBg: Color,
    val badgeRunBg: Color,
    val badgeDoneBg: Color,
    val badgeFailBg: Color,
    val errorBg: Color,
    val dangerBg: Color,
    val dangerFg: Color,
    val scrim: Color,
    val isDark: Boolean,
)

/** 深色（默认）：对应 :root 变量块 */
val DarkPalette = Palette(
    bg = Color(0xFF1A1A1E),
    bgSidebar = Color(0xFF222226),
    bgActivity = Color(0xFF2A2A2E),
    bgTitlebar = Color(0xFF2E2E32),
    bgStatusbar = Color(0xFFFB7299),
    bgInput = Color(0xFF36363A),
    bgHover = Color(0xFF2E2E32),
    bgActive = Color(0xFF3D1F2A),
    bgListActive = Color(0xFF38383E),
    fg = Color(0xFFCCCCCC),
    fgDim = Color(0xFF858585),
    fgBright = Color(0xFFE8E8E8),
    fgTitle = Color(0xFFAAAAAA),
    border = Color(0xFF2D2D30),
    borderLight = Color(0xFF3A3A3E),
    accent = Color(0xFFFB7299),
    accentBtn = Color(0xFFE8869C),
    accentBtnHover = Color(0xFFD45D7E),
    success = Color(0xFF89D185),
    error = Color(0xFFF48771),
    warn = Color(0xFFCCA700),
    cardBg = Color(0xFF252528),
    cardBorder = Color(0xFF333338),
    pillBg = Color(0xFF333338),
    pillBorder = Color(0xFF3E3E44),
    dropdownBg = Color(0xFF2E2E32),
    badgeRunBg = Color(0xFF3D1F2A),
    badgeDoneBg = Color(0xFF1F3A1F),
    badgeFailBg = Color(0xFF3A1F1F),
    errorBg = Color(0xFF3A1F1F),
    dangerBg = Color(0xFF5A1D1D),
    dangerFg = Color(0xFFF6B6B6),
    scrim = Color(0xCC000000),
    isDark = true,
)

/** 浅色：对应 :root[data-theme="light"] 变量块 */
val LightPalette = Palette(
    bg = Color(0xFFF5F5F7),
    bgSidebar = Color(0xFFFFFFFF),
    bgActivity = Color(0xFFECECEF),
    bgTitlebar = Color(0xFFFFFFFF),
    bgStatusbar = Color(0xFFFB7299),
    bgInput = Color(0xFFFFFFFF),
    bgHover = Color(0xFFF5F5F5),
    bgActive = Color(0xFFFCE4EC),
    bgListActive = Color(0xFFFCE4EC),
    fg = Color(0xFF333333),
    fgDim = Color(0xFF999999),
    fgBright = Color(0xFF1A1A1A),
    fgTitle = Color(0xFF666666),
    border = Color(0xFFE0E0E0),
    borderLight = Color(0xFFECECEC),
    accent = Color(0xFFFB7299),
    accentBtn = Color(0xFFFB7299),
    accentBtnHover = Color(0xFFE8869C),
    success = Color(0xFF4CAF50),
    error = Color(0xFFEF5350),
    warn = Color(0xFFFF9800),
    cardBg = Color(0xFFFFFFFF),
    cardBorder = Color(0xFFE8E8E8),
    pillBg = Color(0xFFF0F0F4),
    pillBorder = Color(0xFFE0E0E4),
    dropdownBg = Color(0xFFFFFFFF),
    badgeRunBg = Color(0xFFFCE4EC),
    badgeDoneBg = Color(0xFFE8F5E9),
    badgeFailBg = Color(0xFFFBE9E9),
    errorBg = Color(0xFFFBE9E9),
    dangerBg = Color(0xFFFDE8E8),
    dangerFg = Color(0xFFC62828),
    scrim = Color(0x66000000),
    isDark = false,
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }

/** 尺寸令牌：对应 CSS 中的固定像素 */
object Dimens {
    val titleBarHeight = 38.dp
    val tabsHeight = 35.dp
    val statusBarHeight = 26.dp
    val activityBarWidth = 48.dp
    val activityBtn = 48.dp
    val sidebarWidth = 240.dp
    val radiusSm = 6.dp
    val radiusMd = 8.dp
    val radiusLg = 10.dp
    val radiusXl = 12.dp
    val radiusPill = 50
    val cardGap = 8.dp
    val screenPadding = 12.dp
}

val MonoFamily = FontFamily.Monospace

private val AppTypography = Typography(
    bodyLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 26.sp),
)

@Composable
fun BBDownTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    val palette = if (darkTheme) DarkPalette else LightPalette
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = Color.White,
            background = palette.bg,
            onBackground = palette.fg,
            surface = palette.cardBg,
            onSurface = palette.fg,
            surfaceVariant = palette.bgInput,
            onSurfaceVariant = palette.fgDim,
            outline = palette.borderLight,
            error = palette.error,
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            onPrimary = Color.White,
            background = palette.bg,
            onBackground = palette.fg,
            surface = palette.cardBg,
            onSurface = palette.fg,
            surfaceVariant = palette.bgInput,
            onSurfaceVariant = palette.fgDim,
            outline = palette.borderLight,
            error = palette.error,
        )
    }
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}

/** 便捷取色：val c = AppTheme.colors */
object AppTheme {
    val colors: Palette
        @Composable get() = LocalPalette.current
}
