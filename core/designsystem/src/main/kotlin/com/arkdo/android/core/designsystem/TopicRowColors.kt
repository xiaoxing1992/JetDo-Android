package com.arkdo.android.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** 帖子行用到的颜色。首页和搜索各自从自己的色板映射进来。 */
@Immutable
data class TopicRowColors(
    val pageBg: Color,
    val textSecondary: Color,
    val textBody: Color,
    val brand: Color,
    val border: Color,
    val surfaceMuted: Color,
    val avatarFg: Color,
    val avatarBorder: Color,
    val unread: Color
) {
    companion object {
        val Default = TopicRowColors(
            pageBg = Color.White,
            textSecondary = Color(0xFF6B7280),
            textBody = Color(0xFF374151),
            brand = Color(0xFF2563EB),
            border = Color(0xFFE5E7EB),
            surfaceMuted = Color(0xFFE9E9E9),
            avatarFg = Color(0xFF4B5563),
            avatarBorder = Color(0x4D00AEFF),
            unread = Color(0xFF66CCFF)
        )
    }
}
