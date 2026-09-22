package com.arkdo.android.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.arkdo.android.common.theme.AppThemeMode
import com.arkdo.android.common.theme.ThemeModeStore

/**
 * ArkDO 主题系统
 *
 * 配色对齐鸿蒙版 resources/{base,dark}/element/color.json —— 蓝灰体系，
 * 不是之前注释里写的"温暖土色调"（那套赤陶橙与鸿蒙侧毫无关系）。
 *
 * 动态主题（Material You 壁纸取色）默认关闭：它会覆盖 onSurface / surfaceVariant /
 * outlineVariant / primaryContainer 等几乎所有角色色，一开就没有任何视觉对齐可言。
 * 需要像素级对齐鸿蒙的地方直接用 [arkdoColors]，Material token 只作兜底。
 */

private val LightColorScheme = lightColorScheme(
    primary = ArkdoLightColors.brand,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = ArkdoLightColors.infoSoftBg,
    onPrimaryContainer = ArkdoLightColors.ldAccent,

    secondary = ArkdoLightColors.ldAccent,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = ArkdoLightColors.surfaceMuted,
    onSecondaryContainer = ArkdoLightColors.textBody,

    tertiary = ArkdoLightColors.ldAccent,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = ArkdoLightColors.fillSubtle,
    onTertiaryContainer = ArkdoLightColors.textBody,

    background = ArkdoLightColors.pageBg,
    onBackground = ArkdoLightColors.textPrimary,

    surface = ArkdoLightColors.pageBg,
    onSurface = ArkdoLightColors.textPrimary,
    surfaceVariant = ArkdoLightColors.surfaceMuted,
    onSurfaceVariant = ArkdoLightColors.textSecondary,

    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    outline = ArkdoLightColors.border,
    outlineVariant = ArkdoLightColors.border,
)

private val DarkColorScheme = darkColorScheme(
    primary = ArkdoDarkColors.brand,
    onPrimary = Color(0xFF0B1220),
    primaryContainer = ArkdoDarkColors.infoSoftBg,
    onPrimaryContainer = ArkdoDarkColors.ldAccent,

    secondary = ArkdoDarkColors.ldAccent,
    onSecondary = Color(0xFF0B1220),
    secondaryContainer = ArkdoDarkColors.surfaceMuted,
    onSecondaryContainer = ArkdoDarkColors.textBody,

    tertiary = ArkdoDarkColors.ldAccent,
    onTertiary = Color(0xFF0B1220),
    tertiaryContainer = ArkdoDarkColors.fillSubtle,
    onTertiaryContainer = ArkdoDarkColors.textBody,

    background = ArkdoDarkColors.pageBg,
    onBackground = ArkdoDarkColors.textPrimary,

    surface = ArkdoDarkColors.pageBg,
    onSurface = ArkdoDarkColors.textPrimary,
    surfaceVariant = ArkdoDarkColors.surfaceMuted,
    onSurfaceVariant = ArkdoDarkColors.textSecondary,

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    outline = ArkdoDarkColors.border,
    outlineVariant = ArkdoDarkColors.border,
)

private val LocalArkdoDark = staticCompositionLocalOf { false }

/**
 * 当前是否画深色。由 [ArkDoTheme] 写入：跟随系统，或设置页的浅色/深色。
 * 只读 CompositionLocal，引用块这类 @ReadOnlyComposable 也能用。
 * 不要再直接调 [isSystemInDarkTheme]，否则强制浅色/深色时局部色不会跟着变。
 */
@Composable
@ReadOnlyComposable
fun isArkdoInDarkTheme(): Boolean = LocalArkdoDark.current

@Composable
fun ArkDoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // darkTheme 只作为「跟随系统」时的取值（预览可显式传入）。浅色/深色档无视它。
    val themeMode by ThemeModeStore.mode.collectAsState()
    val dark = when (themeMode) {
        AppThemeMode.SYSTEM -> darkTheme
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }
    val colorScheme = if (dark) DarkColorScheme else LightColorScheme
    val tokens = if (dark) ArkdoDarkColors else ArkdoLightColors

    // Edge-to-Edge 下状态栏/导航栏透明,图标深浅跟当前主题走,不跟系统夜间模式。
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    CompositionLocalProvider(
        LocalArkdoColors provides tokens,
        LocalArkdoDark provides dark
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
