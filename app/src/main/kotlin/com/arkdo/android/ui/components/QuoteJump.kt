package com.arkdo.android.ui.components

import androidx.compose.runtime.compositionLocalOf

/**
 * 引用卡（TOPIC_QUOTE）的楼层上下文与两个回调。
 *
 * 为什么走 CompositionLocal 而不是再加参数：`TopicQuoteBlock` 埋在 `BlockView` 的递归链里，
 * 中间隔着 Quote / Callout / List / Spoiler / Disclosure 等 10 来个 composable，每个都已经背着
 * `onLinkClick`、`onResolveMedia`、`onOpenImages`、`linkClicks`、`viewerImages` —— 再加三个等于把
 * 10 个签名和 20 多个调用点全改一遍，纯机械噪音且极易漏（同 AttachmentClick.kt 的判断）。
 *
 * 语义对齐鸿蒙 PostRichContent.ets：那边只有一个 @Component 实例，`this.currentPostNumber` /
 * `this.onJumpToQuote` / `this.onResolveQuotedPost` 天然全树可见；这里等价地把这三样下发到子树。
 * 引用卡「展开全文」里若又嵌了引用卡，取到的仍是同一份上下文（与鸿蒙同一实例表现一致）。
 */
internal data class QuoteJumpContext(
    /**
     * 当前渲染的是第几楼。同帖引用卡据此判箭头朝上还是朝下（见 [QuoteJumpContext.onJumpToQuote]）；
     * 0 = 无楼层上下文（编辑器预览、公告弹层），此时统一退回 arrow-up。
     */
    val currentPostNumber: Int = 0,
    /**
     * 点引用卡右上角跳转箭头：同帖（topicId 就是当前帖）由页面滚动定位，
     * 跨帖走 [fallbackUrl] 开详情。对齐鸿蒙 TopicDetailPage.jumpToQuote。
     */
    val onJumpToQuote: (topicId: Long, postNumber: Int, fallbackUrl: String?) -> Unit = { _, _, _ -> },
    /**
     * 取被引用楼层的全文 cooked HTML：页面先翻已加载楼层，没有再 `GET /posts/by_number` 拉。
     * 拿不到返回空串 —— 渲染层据此隐藏展开箭头，只保留折叠预览。
     */
    val onResolveQuotedPost: suspend (topicId: Long, postNumber: Int) -> String = { _, _ -> "" }
)

/** 无楼层上下文的默认值：箭头退化成 arrow-up，展开与跳转均为空动作。 */
private val EmptyQuoteJumpContext = QuoteJumpContext()

internal val LocalQuoteJump = compositionLocalOf { EmptyQuoteJumpContext }
