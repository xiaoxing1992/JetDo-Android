package com.arkdo.android.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

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

@Composable
fun ArkDoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val tokens = if (darkTheme) ArkdoDarkColors else ArkdoLightColors

    // Edge-to-Edge 下状态栏/导航栏透明,图标深浅得跟主题走(而不是跟系统夜间模式):
    // 将来接入「强制浅色/深色」三态时,这里保证图标不会在深色页上变成深色。
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalArkdoColors provides tokens) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
