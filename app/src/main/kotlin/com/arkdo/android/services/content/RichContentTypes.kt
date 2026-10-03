package com.arkdo.android.services.content

// ==========================================================================
// 富内容领域模型，1:1 对齐鸿蒙 services/content/RichContentTypes.ets：
// CookedHtmlParser 解析 Discourse cooked HTML 后产出的块/行内节点结构。
// 纯类型定义，不含解析逻辑，供解析器与渲染层（PostRichContent 等）共享。
// 忠实沿用 ArkTS 的「单类 + 可选字段 + 枚举判别」结构（而非 Kotlin sealed class），
// 字段名与源逐一对应，便于逐字段核对迁移。
// ==========================================================================

import com.arkdo.android.data.models.TopicCategory

enum class RichBlockType {
    PARAGRAPH,
    HEADING,
    QUOTE,
    /** Discourse 话题/帖子引用（aside.quote，带 quote-title 头部）：渲染成带头部的引用卡，右上角可展开拉全文。 */
    TOPIC_QUOTE,
    /**
     * Obsidian/Discourse callout（discourse-quote-callouts 主题组件）：首段以 [!type]± 开头的
     * blockquote → 彩色可折叠提示框。type 决定图标/主色，+ 默认展开、- 默认折叠、无后缀不可折叠。
     */
    CALLOUT,
    CODE,
    IMAGE,
    LIST,
    DISCLOSURE,
    /** discourse-spoiler-alert 块级剧透：<div class="spoiler">…</div>，揭示前整块遮蔽。 */
    SPOILER,
    DIVIDER,
    POLL,
    ONEBOX,
    /** <audio>/<video>：原生播放器就地播放。src 为解析后的媒体 URL。 */
    AUDIO,
    VIDEO,
    /** discourse-math 块级公式（div.math）：text 存裸 LaTeX（displayMode=true），渲染层离屏 KaTeX 出图后居中。 */
    MATH,
    /** Markdown 表格（Discourse 常裹 div.md-table）：渲染层等宽列对齐 + 横向滚动。 */
    TABLE,
    UNSUPPORTED
}

enum class RichInlineType {
    TEXT,
    LINK,
    CODE,
    IMAGE,
    /** discourse-math 行内公式（span.math）：text 存裸 LaTeX（displayMode=false），出图后基线内联。 */
    MATH,
    LINE_BREAK
}

data class RichInlineNode(
    val type: RichInlineType,
    val text: String = "",
    val href: String? = null,
    val src: String? = null,
    val alt: String? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    /** 附件下载链接（<a class="attachment"> 或指向 /uploads/ 的链接）：渲染时前置下载图标，点击触发原生下载。 */
    val attachment: Boolean = false,
    /** 行内剧透（<span class="spoiler">）：揭示前渲染为涂抹条，点击后显示。 */
    val spoiler: Boolean = false,
    /**
     * 段落分隔用的换行（仅 LINE_BREAK）：由 mergeAdjacentParagraphs 在合并的相邻段落之间插入，
     * 渲染成双换行空行营造段距。与 <br> 普通换行区分，后者仍是单换行。
     */
    val paragraphBreak: Boolean = false
)

data class RichListItem(
    val inlines: List<RichInlineNode>,
    /** 列表项内含块级图时改用 blocks：文字成段落、图片各自成块级 IMAGE。纯文字项只用 inlines。 */
    val blocks: List<RichBlock>? = null
)

/** TABLE 单元格：行内节点序列 + 是否表头（<th> 或位于 <thead> 内）。 */
data class RichTableCell(
    val inlines: List<RichInlineNode>,
    val header: Boolean = false
)

data class RichTableRow(
    val cells: List<RichTableCell>,
    /** 整行是否表头行（全部单元格来自 thead / 全为 th）。渲染表头行加底色与粗体。 */
    val header: Boolean = false
)

data class RichBlock(
    val type: RichBlockType,
    val inlines: List<RichInlineNode>? = null,
    val blocks: List<RichBlock>? = null,
    val items: List<RichListItem>? = null,
    /** TABLE 块：按出现顺序的行，每行含若干单元格。 */
    val tableRows: List<RichTableRow>? = null,
    val text: String? = null,
    val title: String? = null,
    val language: String? = null,
    val src: String? = null,
    val alt: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val href: String? = null,
    val ordered: Boolean = false,
    val level: Int? = null,
    /** POLL 块：data-poll-name，用于在正文正确位置占位、并按名字关联 TopicPost.polls 的动态数据。 */
    val pollName: String? = null,
    /** ONEBOX（链接预览卡片）字段：域名/站点图标/描述/缩略图；title 复用 title，跳转 URL 复用 href。 */
    val domain: String? = null,
    val faviconUrl: String? = null,
    val description: String? = null,
    val thumbnailUrl: String? = null,
    /** CALLOUT 块字段：kind 归一后的类型；color 主色；icon FA 图标 id；collapsible/defaultExpanded 折叠态；titleInlines 标题行内节点；blocks 为正文子块。 */
    val calloutKind: String? = null,
    val calloutColor: String? = null,
    val calloutIcon: String? = null,
    val collapsible: Boolean = false,
    val defaultExpanded: Boolean = false,
    val titleInlines: List<RichInlineNode>? = null,
    /** TOPIC_QUOTE 头部数据：头像/作者/话题标题/目标 topic 与楼号/分类徽章。折叠预览复用 text，话题链接复用 href。 */
    val quoteAvatarUrl: String? = null,
    val quoteAuthor: String? = null,
    val quoteTitle: String? = null,
    val quoteTopicId: Long? = null,
    val quotePostNumber: Int? = null,
    val quoteCategory: TopicCategory? = null,
    /** 同帖回复引用：按被引用楼在本楼之前/之后给 arrow-up/arrow-down；跨帖引用用 arrow-right。 */
    val quoteIsReply: Boolean = false
)
