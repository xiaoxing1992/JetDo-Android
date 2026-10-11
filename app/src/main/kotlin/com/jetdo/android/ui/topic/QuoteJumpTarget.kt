package com.jetdo.android.ui.topic

/**
 * 点引用卡右上角跳转箭头时该做什么。对齐鸿蒙 TopicDetailPage.jumpToQuote（:1646）。
 *
 * 单独提成纯函数而不是内联在 Composable 的 lambda 里，理由很实际：
 * 这个「3 行分支」里藏着一条容易写反的判据 —— **同帖**必须同时满足
 * `topicId == 当前帖` 与 `postNumber > 0`，此时才谈得上滚动定位；否则一律退到
 * fallbackUrl。写反了的表现是「点箭头跳到另一篇帖子，而不是本贴的某一楼」，
 * 而这条路径在有引用卡的帖子里才走得到、手工测一次成本很高。
 */
internal sealed interface QuoteJumpTarget {
    /** 同帖引用：滚动定位到 [postNumber] 楼（走 ViewModel 的一次性滚动请求 + 落地高亮）。 */
    data class Post(val postNumber: Int) : QuoteJumpTarget

    /** 跨帖引用（或同帖但缺楼号）：打开 [url]。 */
    data class Link(val url: String) : QuoteJumpTarget

    /** 既不是本贴、又没有可用的 href：什么都不做。 */
    data object None : QuoteJumpTarget
}

/**
 * @param topicId 引用卡记录的目标话题（`aside[data-topic]`）
 * @param postNumber 目标楼号（缺 `data-post` 时为 0）
 * @param fallbackUrl 引用卡上的 href（站内绝对地址）
 * @param currentTopicId 当前正在浏览的话题
 */
internal fun resolveQuoteJump(
    topicId: Long,
    postNumber: Int,
    fallbackUrl: String?,
    currentTopicId: Long
): QuoteJumpTarget = when {
    // 本贴的某一楼：滚动定位。postNumber 必须 > 0，否则没有目标可滚。
    topicId == currentTopicId && postNumber > 0 -> QuoteJumpTarget.Post(postNumber)
    // 跨帖：开链接。同帖但缺楼号也落这里（href 指向本贴首页，仍是合理落点）。
    !fallbackUrl.isNullOrEmpty() -> QuoteJumpTarget.Link(fallbackUrl)
    else -> QuoteJumpTarget.None
}
