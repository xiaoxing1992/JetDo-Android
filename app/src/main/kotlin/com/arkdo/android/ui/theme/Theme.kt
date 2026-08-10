package com.arkdo.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * ArkDO 主题系统
 *
 * - Android 12+ 支持 Material You 动态主题（从壁纸提取颜色）
 * - 降级方案使用固定配色（温暖土色调）
 * - 三态深色模式：跟随系统 / 强制浅色 / 强制深色
 */

// 浅色主题固定配色（温暖土色调，对齐 HarmonyOS 版本）
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFFC4612F),           // 赤陶橙（主色调）
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF2E3D6),  // 柔和赤陶色背景
    onPrimaryContainer = Color(0xFF5C2E10),

    secondary = Color(0xFF8B7355),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE7DDD0),
    onSecondaryContainer = Color(0xFF3A2F23),

    tertiary = Color(0xFF6B8E7C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD5E8DC),
    onTertiaryContainer = Color(0xFF2A3D34),

    background = Color(0xFFF7F4EF),        // 温暖奶油背景
    onBackground = Color(0xFF1F2421),      // 墨色文字

    surface = Color(0xFFFBF9F5),           // 卡片表面
    onSurface = Color(0xFF1F2421),
    surfaceVariant = Color(0xFFE7E1D7),    // 温暖分割线
    onSurfaceVariant = Color(0xFF5C635D),  // 次要文字

    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    outline = Color(0xFFE7E1D7),           // 边框线
    outlineVariant = Color(0xFFF2EDE3),
)

// 深色主题固定配色（温暖炭色调）
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFFFB59A),
    onPrimary = Color(0xFF5C2E10),
    primaryContainer = Color(0xFF8B4423),
    onPrimaryContainer = Color(0xFFF2E3D6),

    secondary = Color(0xFFD1C4B5),
    onSecondary = Color(0xFF3A2F23),
    secondaryContainer = Color(0xFF5C4F3F),
    onSecondaryContainer = Color(0xFFE7DDD0),

    tertiary = Color(0xFFB9CCC1),
    onTertiary = Color(0xFF2A3D34),
    tertiaryContainer = Color(0xFF4A5E54),
    onTertiaryContainer = Color(0xFFD5E8DC),

    background = Color(0xFF1F2421),        // 温暖炭色背景
    onBackground = Color(0xFFE7E3DE),

    surface = Color(0xFF2A2D2A),           // 卡片表面
    onSurface = Color(0xFFE7E3DE),
    surfaceVariant = Color(0xFF3D4140),
    onSurfaceVariant = Color(0xFFC4C7C4),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    outline = Color(0xFF3D4140),
    outlineVariant = Color(0xFF2A2D2A),
)

@Composable
fun ArkDoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true, // Android 12+ 动态主题
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
