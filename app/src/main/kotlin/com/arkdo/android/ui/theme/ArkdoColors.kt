package com.arkdo.android.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 鸿蒙版语义色 token，逐条对齐 /Users/tongtong/ArkDO 的 resources/{base,dark}/element/color.json。
 *
 * 为什么不直接塞进 Material 的 ColorScheme：鸿蒙这套是语义命名（text_body / surface_muted /
 * border / ld_accent…），和 M3 的 onSurface / surfaceVariant / outline 不是一一对应关系。
 * 硬套过去会在某几个 token 上撞车，取色也会被 M3 的角色推导规则改写。
 * 所以单独走一条 CompositionLocal，需要像素级对齐的地方用 ArkdoColors，其余仍走 MaterialTheme。
 */
@Immutable
data class ArkdoColorTokens(
    val pageBg: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textBody: Color,
    val textTertiary: Color,
    val textFaint: Color,
    val brand: Color,
    val ldAccent: Color,
    val warning: Color,
    val border: Color,
    val surfaceMuted: Color,
    val fillSubtle: Color,
    val infoSoftBg: Color,
    val avatarFg: Color,
    val chipDisabledText: Color,
    val titleBarScrolledBg: Color,
    val floatingActionBg: Color,
    val floatingActionHighlight: Color,
    val fabComposeBg: Color,
    val fabTopFg: Color,
    /** 增量插入时新行的高亮底色；淡出目标是同色全透明，避免过渡中途跳色。 */
    val incomingHighlight: Color
) {
    /** 高亮淡出终点：同色 alpha=0，鸿蒙用 #00E8F1FE / #0013202D 表达的就是这个。 */
    val incomingHighlightFaded: Color get() = incomingHighlight.copy(alpha = 0f)
}

/** 亮色。对应鸿蒙 resources/base/element/color.json。 */
val ArkdoLightColors = ArkdoColorTokens(
    pageBg = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF111827),
    textSecondary = Color(0xFF6B7280),
    textBody = Color(0xFF374151),
    textTertiary = Color(0xFF9CA3AF),
    textFaint = Color(0xFF909090),
    brand = Color(0xFF2563EB),
    ldAccent = Color(0xFF0088CC),
    warning = Color(0xFFB45309),
    border = Color(0xFFE5E7EB),
    surfaceMuted = Color(0xFFE9E9E9),
    fillSubtle = Color(0xFFF3F4F6),
    infoSoftBg = Color(0xFFE8F1FE),
    avatarFg = Color(0xFF4B5563),
    chipDisabledText = Color(0xFF5D646F),
    titleBarScrolledBg = Color(0xBFFFFFFF),
    floatingActionBg = Color(0x33F2F3F5),
    floatingActionHighlight = Color(0x59FFFFFF),
    fabComposeBg = Color(0xFF0088CC),
    fabTopFg = Color(0xFF5A6169),
    incomingHighlight = Color(0xFFE8F1FE)
)

/** 暗色。对应鸿蒙 resources/dark/element/color.json。 */
val ArkdoDarkColors = ArkdoColorTokens(
    pageBg = Color(0xFF17191D),
    textPrimary = Color(0xFFE7E9EE),
    textSecondary = Color(0xFF9AA0AB),
    textBody = Color(0xFFC9CED7),
    textTertiary = Color(0xFF8A9099),
    textFaint = Color(0xFF7C828B),
    brand = Color(0xFF5B8DEF),
    ldAccent = Color(0xFF33A6DD),
    warning = Color(0xFFE0954A),
    border = Color(0xFF303338),
    surfaceMuted = Color(0xFF26292E),
    fillSubtle = Color(0xFF23262C),
    infoSoftBg = Color(0xFF16283C),
    avatarFg = Color(0xFFC2C7CF),
    chipDisabledText = Color(0xFF9096A0),
    titleBarScrolledBg = Color(0xBF17191D),
    floatingActionBg = Color(0x332A2E33),
    floatingActionHighlight = Color(0x59FFFFFF),
    fabComposeBg = Color(0xFF3AA3DC),
    fabTopFg = Color(0xFFC5CAD1),
    incomingHighlight = Color(0xFF13202D)
)

/**
 * 不随明暗翻转的字面色。鸿蒙侧同样是写死的，不走 color.json。
 */
object ArkdoFixedColors {
    /** 抽屉遮罩，20% 黑。 */
    val Scrim = Color(0x33000000)

    /** 新话题提示条的文字与 loading 色。 */
    val BrandBlue = Color(0xFF0088CC)

    /** 通知铃铛。 */
    val Amber = Color(0xFFF5A623)

    /** 铃铛未读红点底色。 */
    val BadgeRed = Color(0xFFF5222D)

    /** 列表行未读药丸底色 / 未读小蓝点。 */
    val Unread = Color(0xFF66CCFF)

    /** 头像描边。 */
    val AvatarBorder = Color(0x4D00AEFF)

    /** flair 徽章缺省底色。 */
    val FlairFallbackBg = Color(0xFFBE123C)
}

val LocalArkdoColors = staticCompositionLocalOf { ArkdoLightColors }

/** 取当前明暗下的鸿蒙语义色。 */
val arkdoColors: ArkdoColorTokens
    @androidx.compose.runtime.Composable
    @androidx.compose.runtime.ReadOnlyComposable
    get() = LocalArkdoColors.current
