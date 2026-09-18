package com.arkdo.android.feature.search

import androidx.compose.ui.graphics.Color

/**
 * 搜索页的视觉 token。
 *
 * 与 `feature:home` 的 HomeVisualTokens 同思路:feature 模块不依赖 app 主题,
 * 由宿主(app 层)把 `arkdoColors` 映射进来。默认值保证模块可独立预览/测试。
 */
data class SearchVisualTokens(
    val pageBg: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textFaint: Color,
    val ldAccent: Color,
    val border: Color,
    val fillSubtle: Color,
    val surfaceMuted: Color,
    val danger: Color,
    val highlightBg: Color,
    /** 选中 chip 的浅品牌底(对齐鸿蒙 info_soft_bg:brand 字色压在它上面)。 */
    val infoSoftBg: Color = Color(0xFFE8F1FE),
    /** 警示文案色(对齐鸿蒙 app.color.warning,错误提示用)。 */
    val warning: Color = Color(0xFFB45309)
) {
    companion object {
        val Default = SearchVisualTokens(
            pageBg = Color.White,
            textPrimary = Color(0xFF111827),
            textSecondary = Color(0xFF6B7280),
            textFaint = Color(0xFF9CA3AF),
            ldAccent = Color(0xFF0088CC),
            border = Color(0xFFE5E7EB),
            fillSubtle = Color(0xFFF3F4F6),
            surfaceMuted = Color(0xFFE9E9E9),
            danger = Color(0xFFDC2626),
            highlightBg = Color(0xFFFFF3B0)
        )
    }
}