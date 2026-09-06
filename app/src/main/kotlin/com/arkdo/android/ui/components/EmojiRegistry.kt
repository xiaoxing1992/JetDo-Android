package com.arkdo.android.ui.components

// ==========================================================================
// 对齐鸿蒙 EmojiRegistry.ets：长按面板 10 个可选回应表情的展示顺序
// （= linux.do 启用的 reactions:reaction_for_like + enabled_reactions），
// 以及 discourse-reactions 表情短名 → assets PNG 的映射。
// 唯一非直观映射：短名 '+1' → 资源 emoji_plus1（资源名不能含 '+'）；其余 = 'emoji_' + 短名。
// ==========================================================================

val REACTION_ORDER: List<String> = listOf(
    "heart", "+1", "laughing", "open_mouth", "clap",
    "confetti_ball", "hugs", "distorted_face", "tieba_087", "bili_057"
)

/** 表情短名 → assets 文件名；未知表情（站点仅启用这 10 个）返回 null 跳过渲染。 */
fun reactionEmojiAsset(name: String?): String? = when (name) {
    "heart" -> "emoji_heart.png"
    "+1" -> "emoji_plus1.png"
    "laughing" -> "emoji_laughing.png"
    "open_mouth" -> "emoji_open_mouth.png"
    "clap" -> "emoji_clap.png"
    "confetti_ball" -> "emoji_confetti_ball.png"
    "hugs" -> "emoji_hugs.png"
    "distorted_face" -> "emoji_distorted_face.png"
    "tieba_087" -> "emoji_tieba_087.png"
    "bili_057" -> "emoji_bili_057.png"
    else -> null
}
