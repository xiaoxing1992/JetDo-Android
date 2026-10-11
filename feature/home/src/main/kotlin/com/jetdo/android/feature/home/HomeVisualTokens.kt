package com.jetdo.android.feature.home

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** Visual tokens supplied by the host so the feature uses the app's existing theme. */
@Immutable
data class HomeVisualTokens(
    val pageBg: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textBody: Color,
    val textTertiary: Color,
    val brand: Color,
    val ldAccent: Color,
    val border: Color,
    val surfaceMuted: Color,
    val fillSubtle: Color,
    val infoSoftBg: Color,
    val avatarFg: Color,
    val avatarBorder: Color,
    val fabComposeBg: Color,
    val fabTopFg: Color,
    /** 玻璃态蒙版。浅色 20% 的 #F2F3F5，深色 20% 的 #2A2E33。 */
    val fabGlassTint: Color,
    val incomingHighlight: Color,
    val unread: Color,
    val amber: Color,
    val scrim: Color
) {
    companion object {
        val Default = HomeVisualTokens(
            pageBg = Color.White,
            textPrimary = Color(0xFF111827),
            textSecondary = Color(0xFF6B7280),
            textBody = Color(0xFF374151),
            textTertiary = Color(0xFF9CA3AF),
            brand = Color(0xFF2563EB),
            ldAccent = Color(0xFF0088CC),
            border = Color(0xFFE5E7EB),
            surfaceMuted = Color(0xFFE9E9E9),
            fillSubtle = Color(0xFFF3F4F6),
            infoSoftBg = Color(0xFFE8F1FE),
            avatarFg = Color(0xFF4B5563),
            avatarBorder = Color(0x4D00AEFF),
            fabComposeBg = Color(0xFF0088CC),
            fabTopFg = Color(0xFF5A6169),
            fabGlassTint = Color(0x33F2F3F5),
            incomingHighlight = Color(0xFFE8F1FE),
            unread = Color(0xFF66CCFF),
            amber = Color(0xFFF5A623),
            scrim = Color(0x33000000)
        )
    }
}
