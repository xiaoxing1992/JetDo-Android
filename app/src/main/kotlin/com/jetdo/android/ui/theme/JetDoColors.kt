package com.jetdo.android.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 鸿蒙版语义色 token，逐条对齐 ~/JetDo 的 resources/{base,dark}/element/color.json。
 *
 * 为什么不直接塞进 Material 的 ColorScheme：鸿蒙这套是语义命名（text_body / surface_muted /
 * border / ld_accent…），和 M3 的 onSurface / surfaceVariant / outline 不是一一对应关系。
 * 硬套过去会在某几个 token 上撞车，取色也会被 M3 的角色推导规则改写。
 * 所以单独走一条 CompositionLocal，需要像素级对齐的地方用 JetDoColors，其余仍走 MaterialTheme。
 */
@Immutable
data class JetDoColorTokens(
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
    // 详情页专用浅灰底（对齐鸿蒙 topic_detail_page_bg：亮 #F2F3F5 / 暗同 pageBg）。
    // 首页用 pageBg（白），详情页用这个——源码在详情页外层 Column 上显式设过该色，是「鸿蒙详情页更灰」的根因。
    val topicDetailPageBg: Color,
    // 详情页回复 FAB 玻璃态：鸿蒙靠 backgroundEffect 模糊背后页面得到亮白圆。
    // Android 无背后模糊时给一个高亮色值 + 阴影，让按钮在浅灰底上"托起"——按 brightness 反插到底色之上。
    val topicDetailReplyFabBg: Color,
    val topicDetailReplyFabBorder: Color,
    // 沉浸式输入条（详情页底部回复浮层）：对应鸿蒙 immersive_input_* 三色。
    val immersiveInputBarBg: Color,
    val immersiveInputFieldBg: Color,
    val immersiveInputBarHighlight: Color,
    /** 增量插入时新行的高亮底色；淡出目标是同色全透明，避免过渡中途跳色。 */
    val incomingHighlight: Color,
    // 「俺也一样」按钮（对齐鸿蒙 me_too_fg / me_too_border）与 boost 气泡底（boost_bubble_bg）。
    val meTooFg: Color,
    val meTooBorder: Color,
    val boostBubbleBg: Color,
    // 抽奖帖提醒卡的琥珀底/描边(对齐鸿蒙 lottery_notice_bg / lottery_notice_border)。
    val lotteryNoticeBg: Color,
    val lotteryNoticeBorder: Color,
    // 个人主页等灰底容器页(对齐鸿蒙 page_bg_gray:亮 #F2F3F5 / 暗 #000000)。
    val pageBgGray: Color,
    // 白卡底(对齐鸿蒙 surface_card:亮 #FFFFFF / 暗 #191919)。
    val surfaceCard: Color,
    // 认可/关注与警示语义色(对齐鸿蒙 ld_accent / ld_success / ld_danger)。
    val ldSuccess: Color,
    val ldDanger: Color,
    // 帖子楼层里不随页面底色走、但要随明暗翻转的控件色。
    // 对齐鸿蒙 post_reply_control_* / icon_muted / reaction_count_fg。
    val postReplyControlBg: Color,
    val postReplyControlActiveBg: Color,
    val postReplyControlFg: Color,
    val iconMuted: Color,
    val reactionCountFg: Color
) {
    /** 高亮淡出终点：同色 alpha=0，鸿蒙用 #00E8F1FE / #0013202D 表达的就是这个。 */
    val incomingHighlightFaded: Color get() = incomingHighlight.copy(alpha = 0f)
}

/** 亮色。对应鸿蒙 resources/base/element/color.json。 */
val JetDoLightColors = JetDoColorTokens(
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
    // 详情页回复 FAB 底色：模拟鸿蒙 backgroundEffect(blur=28) 后的"亮白圆"观感。
    // 鸿蒙源 20% 灰 + 强磨砂，模糊均色后接近 #E8EAEC 这种亮白。Android 侧无背后模糊时直接给
    // 一个对应亮度的色，让按钮在 topicDetailPageBg(#F2F3F5) 上有清晰的"托起"感。
    // 暗色主题维持深色——浅灰背景下也按 20% 主题底色（暗 #2A2E33）线性插值到 ~#3A3D42 区间。
    topicDetailPageBg = Color(0xFFF2F3F5),
    topicDetailReplyFabBg = Color(0xFFF8F9FB),
    topicDetailReplyFabBorder = Color(0xFFB0B5BC),
    meTooFg = Color(0xFF434343),
    meTooBorder = Color(0xFFD3D3D3),
    boostBubbleBg = Color(0xFFE6E8EB),
    lotteryNoticeBg = Color(0xFFFFF8ED),
    lotteryNoticeBorder = Color(0xFFF0DDBC),
    pageBgGray = Color(0xFFF2F3F5),
    surfaceCard = Color(0xFFFFFFFF),
    immersiveInputBarBg = Color(0xB3F2F3F5),
    immersiveInputFieldBg = Color(0x80FFFFFF),
    immersiveInputBarHighlight = Color(0x66FFFFFF),
    incomingHighlight = Color(0xFFE8F1FE),
    ldSuccess = Color(0xFF009900),
    ldDanger = Color(0xFFC80001),
    postReplyControlBg = Color(0xFFE6E8EB),
    postReplyControlActiveBg = Color(0xFFD9DDE2),
    postReplyControlFg = Color(0xFF575D66),
    iconMuted = Color(0xFFBDBDBD),
    reactionCountFg = Color(0xFF888888)
)

/** 暗色。对应鸿蒙 resources/dark/element/color.json。 */
val JetDoDarkColors = JetDoColorTokens(
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
    topicDetailPageBg = Color(0xFF17191D),
    topicDetailReplyFabBg = Color(0xFF26292E),
    topicDetailReplyFabBorder = Color(0xFF3A3D42),
    meTooFg = Color(0xFFC8CCD2),
    meTooBorder = Color(0xFF3A3D42),
    boostBubbleBg = Color(0xFF26292E),
    lotteryNoticeBg = Color(0xFF241D14),
    lotteryNoticeBorder = Color(0xFF3F3424),
    pageBgGray = Color(0xFF000000),
    surfaceCard = Color(0xFF191919),
    immersiveInputBarBg = Color(0xB32A2E33),
    immersiveInputFieldBg = Color(0x80505760),
    immersiveInputBarHighlight = Color(0x66FFFFFF),
    incomingHighlight = Color(0xFF13202D),
    ldSuccess = Color(0xFF4CAF50),
    ldDanger = Color(0xFFE85D5D),
    postReplyControlBg = Color(0xFF23262C),
    postReplyControlActiveBg = Color(0xFF30343A),
    postReplyControlFg = Color(0xFF9DA3AE),
    iconMuted = Color(0xFF5C626B),
    reactionCountFg = Color(0xFF5C626B)
)

/**
 * 不随明暗翻转的字面色。鸿蒙侧同样是写死的，不走 color.json。
 */
object JetDoFixedColors {
    /** 抽屉遮罩，20% 黑。 */
    val Scrim = Color(0x33000000)

    /** 新话题提示条的文字与 loading 色。 */
    val BrandBlue = Color(0xFF0088CC)

    /** 盖在深色/彩色表面上的前景（回复按钮图标/提交 loading），对齐鸿蒙 ON_DARK_FG。 */
    val OnDarkFg = Color(0xFFFFFFFF)

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

    /** 悬浮按钮/胶囊软阴影（对齐鸿蒙 SHADOW_SOFT，亮/暗统一 13% 黑，不随主题变）。 */
    val ShadowSoft = Color(0x22000000)
}

val LocalJetDoColors = staticCompositionLocalOf { JetDoLightColors }

/** 取当前明暗下的鸿蒙语义色。 */
val jetdoColors: JetDoColorTokens
    @androidx.compose.runtime.Composable
    @androidx.compose.runtime.ReadOnlyComposable
    get() = LocalJetDoColors.current
