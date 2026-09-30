package com.arkdo.android.services.content

import com.arkdo.android.data.models.TopicCategory
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

// ==========================================================================
// cooked HTML 块级解析器，1:1 对齐鸿蒙 services/content/CookedHtmlParser.ets。
// 输出 RichBlock 块序列，供渲染层（PostRichContent）消费。
// 实现差异（行为不变）：鸿蒙手写词法逐字符扫原始 HTML，Android 用 jsoup 建树后
// 按元素分派 —— jsoup 会把 <p> 内非法嵌套的 <div>（lightbox 容器等）重排为兄弟块，
// 这与鸿蒙 raw 扫描看到的布局在真实 Discourse 输出上等价，且更健壮。
// 行为要点逐条保留（详见 RichContentTypes 与各分支注释）。
// ==========================================================================

/** 行内小图（emoji/小图标）尺寸上限：宽或高超过它的独立 <img> 提升为 IMAGE 块。 */
private const val INLINE_IMAGE_MAX = 48L

/** callout 类型别名 → 归一类型（与 discourse-quote-callouts settings.yml 的 alias 一致）。 */
private val CALLOUT_ALIASES = mapOf(
    "summary" to "abstract", "tldr" to "abstract",
    "hint" to "tip", "important" to "tip",
    "check" to "success", "done" to "success",
    "help" to "question", "faq" to "question",
    "caution" to "warning", "attention" to "warning",
    "fail" to "failure", "missing" to "failure",
    "error" to "danger", "cite" to "quote"
)

/** 归一类型 → 主色 + 图标 id（discourse-quote-callouts 上游默认）。 */
private val CALLOUT_STYLES = mapOf(
    "note" to CalloutStyleDef("#086ddd", "far-pen-to-square"),
    "abstract" to CalloutStyleDef("#00bfbc", "far-clipboard"),
    "info" to CalloutStyleDef("#027aff", "far-lightbulb"),
    "todo" to CalloutStyleDef("#027aff", "far-circle-check"),
    "tip" to CalloutStyleDef("#00bfbc", "fire-flame-curved"),
    "success" to CalloutStyleDef("#44cf6e", "check"),
    "question" to CalloutStyleDef("#ec7500", "far-circle-question"),
    "warning" to CalloutStyleDef("#ec7500", "triangle-exclamation"),
    "failure" to CalloutStyleDef("#e93147", "xmark"),
    "danger" to CalloutStyleDef("#e93147", "bolt"),
    "bug" to CalloutStyleDef("#e93147", "bug"),
    "example" to CalloutStyleDef("#7852ee", "list"),
    "quote" to CalloutStyleDef("#9e9e9e", "quote-left")
)

/** 无法识别的类型：回退主色/图标（callout_fallback_*），行为按 note 处理。 */
private val CALLOUT_FALLBACK = CalloutStyleDef("#027aff", "far-pen-to-square")

/** blockquote 首段命中 [!type]± 才是 callout；type 仅取 ASCII 字母，可选 +/- 折叠标记。 */
private val CALLOUT_MARKER = Regex("^\\s*\\[!([a-zA-Z]+)]([+-]?)\\s?")

private data class CalloutStyleDef(val color: String, val icon: String)

private val BLOCK_TAGS = setOf(
    "p", "blockquote", "aside", "pre", "ul", "ol", "hr", "img", "div",
    "figure", "table", "details", "audio", "video", "h1", "h2", "h3", "h4", "h5", "h6"
)

private val HEADING_TAGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")

object CookedHtmlParser {

    fun parse(html: String): List<RichBlock> {
        val blocks = parseNodes(Jsoup.parseBodyFragment(html).body().childNodes())
        val visible = blocks.filter { hasBlockContent(it) }
        return mergeAdjacentParagraphs(visible)
    }

    fun toPlainText(html: String): String = htmlToPlainText(html)

    /** 单段落 HTML → 行内节点（文字 + emoji 图等），供 boost 气泡等短内容混排。 */
    fun toInlineNodes(html: String): List<RichInlineNode> = parseInlines(html)

    // ------------------------------------------------------------------
    // 顶层块切分：块级元素分派，块间的行内内容累积成段落。
    // ------------------------------------------------------------------

    private fun parseNodes(nodes: List<Node>): List<RichBlock> {
        val blocks = mutableListOf<RichBlock>()
        val pending = mutableListOf<Node>()
        for (node in nodes) {
            val element = node as? Element
            val name = element?.tagName()?.lowercase()
            if (element == null || name !in BLOCK_TAGS) {
                // 非块级内容归属当前段落；jsoup 树已配平，无需鸿蒙的闭标签修复逻辑。
                pending.add(node)
                continue
            }
            flushPending(pending, blocks)
            when (name) {
                "hr" -> blocks.add(RichBlock(type = RichBlockType.DIVIDER))
                "img" -> imageBlockFrom(element, "")?.let(blocks::add)
                else -> blocks.addAll(blockFromElement(element))
            }
        }
        flushPending(pending, blocks)
        return blocks
    }

    private fun flushPending(pending: MutableList<Node>, blocks: MutableList<RichBlock>) {
        if (pending.isEmpty()) {
            return
        }
        val inlines = parseInlinesNodes(pending)
        pending.clear()
        if (hasVisibleInlines(inlines)) {
            blocks.add(RichBlock(type = RichBlockType.PARAGRAPH, inlines = inlines))
        }
    }

    // ------------------------------------------------------------------
    // 块级元素分派
    // ------------------------------------------------------------------

    private fun blockFromElement(el: Element): List<RichBlock> {
        val name = el.tagName().lowercase()
        val classes = el.className()

        if (name == "p") {
            // 只有 emoji 的段落必须走行内 ImageSpan，不能被单图段落快路径提升为大图。
            if (isEmojiOnlyParagraph(el)) {
                return paragraphNodes(el)
            }
            singleImageParagraph(el)?.let { return listOf(it) }
            imageContainerBlocks(el).takeIf { it.isNotEmpty() }?.let { return it }
            // Discourse 会把 <audio>/<video> 裹进 <p>（内部带降级 <a>），先提升为播放块。
            mediaParagraphBlocks(el)?.let { return it }
            return mixedBlocks(el)
        }

        if (name in HEADING_TAGS) {
            return listOf(
                RichBlock(
                    type = RichBlockType.HEADING,
                    level = name[1].digitToInt(),
                    inlines = parseInlinesNodes(el.childNodes())
                )
            )
        }

        if (name == "aside" && classes.split(WHITESPACE).contains("quote")) {
            topicQuoteFromAside(el)?.let { return listOf(it) }
        }

        if (name == "blockquote" || (name == "aside" && classes.split(WHITESPACE).contains("quote"))) {
            var quoteBlocks = parseNodes(el.childNodes())
            if (name == "aside") {
                // Discourse 引用：<aside class="quote"> 内的 <blockquote> 才是引用正文，展开它。
                quoteBlocks = unwrapNestedQuotes(quoteBlocks)
            } else {
                // 纯 blockquote：首段 [!type]± 命中则升级为 callout。
                calloutFromQuote(quoteBlocks)?.let { return listOf(it) }
            }
            return listOf(
                RichBlock(
                    type = RichBlockType.QUOTE,
                    blocks = quoteBlocks.ifEmpty { paragraphNodes(el) }
                )
            )
        }

        // Discourse 链接预览：<aside class="onebox"> 或 <div class="onebox">。
        if ((name == "aside" || name == "div") && classes.contains("onebox")) {
            return listOf(oneboxBlock(el))
        }

        if (name == "details") {
            return listOf(disclosureBlock(el))
        }

        if (name == "pre") {
            return listOf(codeBlockFromPre(el))
        }

        if (name == "ul" || name == "ol") {
            return listOf(listBlock(el, ordered = name == "ol"))
        }

        // discourse-poll：轻量占位块，真正的票数/选项由渲染层按 name 关联 TopicPost.polls。
        if (classes.contains("poll") || el.hasAttr("data-poll-name")) {
            return listOf(
                RichBlock(
                    type = RichBlockType.POLL,
                    pollName = el.attr("data-poll-name").ifEmpty { "poll" }
                )
            )
        }

        // discourse-spoiler-alert 块级剧透：内部照常递归解析，遮蔽交给渲染层。
        if (classes.contains("spoiler") || classes.contains("spoiled")) {
            return listOf(
                RichBlock(type = RichBlockType.SPOILER, blocks = parseNodes(el.childNodes()))
            )
        }

        if (name == "audio" || name == "video") {
            mediaBlockFrom(el)?.let { return listOf(it) }
        }

        // discourse-math 块级公式：<div class="math">裸 LaTeX</div>。须在 unsupported 兜底之前。
        if (classes.split(WHITESPACE).contains("math")) {
            val latex = htmlToPlainText(el.html())
            return if (latex.isNotEmpty()) {
                listOf(RichBlock(type = RichBlockType.MATH, text = latex))
            } else {
                emptyList()
            }
        }

        // Markdown 表格：<table> 直挂或裹 <div class="md-table">。解析失败回退占位。
        tableBlock(el)?.let { return listOf(it) }

        detectUnsupportedKind(el)?.let { kind -> return listOf(unsupportedBlock(kind, el)) }

        if (name == "figure" || name == "div") {
            imageContainerBlocks(el).takeIf { it.isNotEmpty() }?.let { return it }
            if (htmlToPlainText(el.html()).isNotEmpty()) {
                return mixedBlocks(el)
            }
        }

        val fallbackText = htmlToPlainText(el.html())
        if (fallbackText.isEmpty()) {
            return emptyList()
        }
        return listOf(
            RichBlock(type = RichBlockType.UNSUPPORTED, text = fallbackText)
        )
    }

    private fun paragraphNodes(el: Element): List<RichBlock> {
        val inlines = parseInlinesNodes(el.childNodes())
        if (!hasVisibleInlines(inlines)) {
            return emptyList()
        }
        return listOf(RichBlock(type = RichBlockType.PARAGRAPH, inlines = inlines))
    }

    // ------------------------------------------------------------------
    // 话题/帖子引用卡
    // ------------------------------------------------------------------

    /**
     * 解析 <aside class="quote" data-topic data-post> 的头部与预览。
     * 判据是标题里有没有指向话题的 <a>：有 = 跨帖引用 → 卡片；无（同帖引用楼层）
     * 或缺 data-topic → 返回 null，回退成普通引用。
     */
    private fun topicQuoteFromAside(el: Element): RichBlock? {
        val topicId = parsePositiveNumber(el.attr("data-topic"))
        if (topicId <= 0L) {
            return null
        }
        val postNumber = parsePositiveNumber(el.attr("data-post"))
        val username = el.attr("data-username")

        val avatar = el.select("img").firstOrNull { it.className().contains("avatar") }
        val avatarUrl = avatar?.let { resolveUrl(it.attr("src")) } ?: ""

        val titleContent = el.selectFirst(".quote-title__text-content") ?: el.selectFirst(".title")
        val titleAnchor = titleContent
            ?.select("a")
            ?.firstOrNull { !it.className().contains("badge-category") }

        val block: RichBlock
        if (titleAnchor != null) {
            // 跨话题引用：标题取链接文字、href 取链接、分类取徽章。
            val title = htmlToPlainText(titleAnchor.html())
            val href = resolveUrl(titleAnchor.attr("href"))
            val category = titleContent?.let(::categoryFromBadge)
            block = RichBlock(
                type = RichBlockType.TOPIC_QUOTE,
                quoteTopicId = topicId,
                quotePostNumber = postNumber.takeIf { it > 0 }?.toInt(),
                quoteAvatarUrl = avatarUrl.ifEmpty { null },
                quoteAuthor = username.ifEmpty { null },
                quoteTitle = title.ifEmpty { null },
                href = href.ifEmpty { null },
                quoteCategory = category
            )
        } else {
            // 同帖回复引用：title 只有「显示名:」无链接 → 用 aside 属性组卡。
            val displayName = el.attr("data-display-name").ifEmpty { username }
            val href = resolveUrl("/t/topic/$topicId/${if (postNumber > 0) postNumber else 1}")
            block = RichBlock(
                type = RichBlockType.TOPIC_QUOTE,
                quoteTopicId = topicId,
                // 缺 data-post 保持 0：渲染层据此不出展开箭头（没有目标楼，拉全文无从谈起）。
                quotePostNumber = postNumber.takeIf { it > 0 }?.toInt(),
                quoteAvatarUrl = avatarUrl.ifEmpty { null },
                quoteAuthor = username.ifEmpty { null },
                quoteTitle = displayName.ifEmpty { null },
                href = href.ifEmpty { null },
                quoteIsReply = true
            )
        }
        // 正文预览：第一个 <blockquote> 的内部 HTML（截断预览），交渲染层内嵌解析。
        block.copy(text = el.selectFirst("blockquote")?.html() ?: "").let { return it }
    }

    /** 从 quote 头部 badge-category__wrapper 抽分类。缺 id/名字返回 null。 */
    private fun categoryFromBadge(titleContent: Element): TopicCategory? {
        val wrapper = titleContent.selectFirst(".badge-category__wrapper") ?: return null
        val badge = wrapper.selectFirst(".badge-category")
        val name = wrapper.selectFirst(".badge-category__name")?.text() ?: ""
        if (name.isEmpty()) {
            return null
        }
        val style = badge?.attr("style") ?: ""
        val id = parsePositiveNumber(badge?.attr("data-category-id") ?: "")
        val color = cssVar(style, "--category-badge-color")
        val textColor = cssVar(style, "--category-badge-text-color")
        // 图标：徽章内最内层 <svg id="xxx">（外层是 fa 包装 svg）。
        val iconId = wrapper.select("svg[id]").lastOrNull()?.attr("id") ?: ""
        return TopicCategory(
            id = if (id > 0) id else 0,
            name = name,
            color = color.removePrefix("#").ifEmpty { "6B7280" },
            textColor = textColor.removePrefix("#").ifEmpty { "FFFFFF" },
            icon = iconId.ifEmpty { null }
        )
    }

    // ------------------------------------------------------------------
    // callout（[!type]± blockquote）
    // ------------------------------------------------------------------

    private fun unwrapNestedQuotes(blocks: List<RichBlock>): List<RichBlock> {
        val out = mutableListOf<RichBlock>()
        for (block in blocks) {
            if (block.type == RichBlockType.QUOTE && !block.blocks.isNullOrEmpty()) {
                out.addAll(block.blocks)
            } else {
                out.add(block)
            }
        }
        return out
    }

    /** 首段首个文本节点以 [!type]± 开头才识别为 callout；命中后剥标记、首行作标题。 */
    private fun calloutFromQuote(quoteBlocks: List<RichBlock>): RichBlock? {
        val first = quoteBlocks.firstOrNull() ?: return null
        if (first.type != RichBlockType.PARAGRAPH || first.inlines.isNullOrEmpty()) {
            return null
        }
        val head = first.inlines.first()
        if (head.type != RichInlineType.TEXT) {
            return null
        }
        val match = CALLOUT_MARKER.find(head.text) ?: return null
        val rawType = match.groupValues[1].lowercase()
        val fold = match.groupValues[2]
        val style = resolveCalloutStyle(rawType)

        // 去掉首文本节点里的标记（其余标题文本/样式保留）。
        val strippedHead = head.copy(text = head.text.replaceFirst(CALLOUT_MARKER, ""))
        val firstInlines = listOf(strippedHead) + first.inlines.drop(1)

        // 标题段可能内含 <br>：换行前为标题，换行后回落成正文首段。
        val titleInlines = mutableListOf<RichInlineNode>()
        val overflowInlines = mutableListOf<RichInlineNode>()
        var hitBreak = false
        for (node in firstInlines) {
            if (!hitBreak && node.type == RichInlineType.LINE_BREAK) {
                hitBreak = true
                continue
            }
            if (hitBreak) overflowInlines.add(node) else titleInlines.add(node)
        }

        val contentBlocks = mutableListOf<RichBlock>()
        if (overflowInlines.isNotEmpty() && hasVisibleInlines(overflowInlines)) {
            contentBlocks.add(RichBlock(type = RichBlockType.PARAGRAPH, inlines = overflowInlines))
        }
        contentBlocks.addAll(quoteBlocks.drop(1))

        return RichBlock(
            type = RichBlockType.CALLOUT,
            calloutKind = style.first,
            calloutColor = style.second,
            calloutIcon = style.third,
            collapsible = fold.isNotEmpty(),
            defaultExpanded = fold != "-",
            titleInlines = trimLeadingEmptyText(titleInlines),
            blocks = contentBlocks
        )
    }

    /** 类型 → (kind, color, icon)。先按别名归一再查样式表；未命中用 fallback 色，kind 保留原始串。 */
    private fun resolveCalloutStyle(rawType: String): Triple<String, String, String> {
        val canonical = CALLOUT_ALIASES[rawType] ?: rawType
        val def = CALLOUT_STYLES[canonical]
        return if (def != null) {
            Triple(canonical, def.color, def.icon)
        } else {
            Triple(rawType, CALLOUT_FALLBACK.color, CALLOUT_FALLBACK.icon)
        }
    }

    // ------------------------------------------------------------------
    // 代码块 / 列表 / 表格 / 折叠 / onebox
    // ------------------------------------------------------------------

    private fun codeBlockFromPre(pre: Element): RichBlock {
        val code = pre.selectFirst("code")
        return RichBlock(
            type = RichBlockType.CODE,
            text = stripTagsKeepingLines(code?.html() ?: pre.html()).trim(),
            language = extractCodeLanguage(code?.className() ?: "")
        )
    }

    private fun listBlock(el: Element, ordered: Boolean): RichBlock {
        val items = el.children()
            .filter { it.tagName().lowercase() == "li" }
            .map { li ->
                // 列表项裹了块级图时拆成块级序列；纯文字/行内（含 emoji）走行内快路径。
                if (findImageUnitIn(li) != null) {
                    RichListItem(inlines = emptyList(), blocks = mixedBlocks(li))
                } else {
                    RichListItem(inlines = parseInlinesNodes(li.childNodes()))
                }
            }
        return RichBlock(type = RichBlockType.LIST, ordered = ordered, items = items)
    }

    private fun tableBlock(el: Element): RichBlock? {
        val table = if (el.tagName().lowercase() == "table") el else el.selectFirst("table") ?: return null
        val rows = table.select("tr").mapNotNull { tr ->
            val cells = tr.children()
                .filter { it.tagName().lowercase() in setOf("th", "td") }
                .map { cell ->
                    RichTableCell(
                        inlines = parseInlinesNodes(cell.childNodes()),
                        header = cell.tagName().lowercase() == "th"
                    )
                }
            if (cells.isEmpty()) {
                null
            } else {
                RichTableRow(cells = cells, header = cells.all { it.header })
            }
        }
        return if (rows.isEmpty()) null else RichBlock(type = RichBlockType.TABLE, tableRows = rows)
    }

    private fun disclosureBlock(el: Element): RichBlock {
        val summary = el.selectFirst("summary")
        val title = summary?.let { htmlToPlainText(it.html()) } ?: ""
        // 内容 = 去掉 summary 后的剩余子节点。
        val contentNodes = el.childNodes().filterNot { it is Element && it.tagName() == "summary" }
        val contentBlocks = parseNodes(contentNodes)
        return RichBlock(
            type = RichBlockType.DISCLOSURE,
            title = title.ifEmpty { "详情" },
            blocks = contentBlocks.ifEmpty {
                val inlines = parseInlinesNodes(contentNodes)
                if (hasVisibleInlines(inlines)) {
                    listOf(RichBlock(type = RichBlockType.PARAGRAPH, inlines = inlines))
                } else {
                    emptyList()
                }
            }
        )
    }

    private fun oneboxBlock(el: Element): RichBlock {
        val header = el.selectFirst("header")
        val favicon = (header ?: el).selectFirst("img")?.let { resolveUrl(it.attr("src")) } ?: ""
        val headerAnchor = header?.selectFirst("a")
        val domainText = headerAnchor?.text() ?: ""
        val sourceHref = headerAnchor?.attr("href")?.let(::resolveUrl) ?: ""

        val body = el.selectFirst("article") ?: el
        val titleEl = body.selectFirst("h3") ?: body.selectFirst("h4")
        val title = titleEl?.let { htmlToPlainText(it.html()) } ?: ""
        val titleHref = titleEl?.selectFirst("a")?.attr("href")?.let(::resolveUrl) ?: ""
        val description = body.selectFirst("p")?.let { htmlToPlainText(it.html()) } ?: ""
        val thumbnail = body.selectFirst("img")?.let { resolveUrl(it.attr("src")) } ?: ""

        val href = titleHref.ifEmpty { sourceHref }
        val domain = domainText.ifEmpty { hostFromUrl(href) }
        return RichBlock(
            type = RichBlockType.ONEBOX,
            href = href.ifEmpty { null },
            domain = domain.ifEmpty { null },
            title = title.ifEmpty { null },
            description = description.ifEmpty { null },
            faviconUrl = favicon.ifEmpty { null },
            thumbnailUrl = thumbnail.ifEmpty { null }
        )
    }

    private fun unsupportedBlock(kind: String, el: Element): RichBlock {
        val text = htmlToPlainText(el.html())
        return RichBlock(
            type = RichBlockType.UNSUPPORTED,
            unsupportedKind = kind,
            text = text.ifEmpty { "${kind}待实现" }
        )
    }

    /** 投票/表格/嵌入/链接预览/数学公式的「待实现」分类兜底（在各自拦截分支之后仍落空的情形）。 */
    private fun detectUnsupportedKind(el: Element): String? {
        val combined = el.outerHtml().lowercase()
        val classes = el.className()
        return when {
            classes.contains("poll") || el.hasAttr("data-poll-name") ||
                combined.contains("data-poll-name") -> "投票"
            el.tagName().lowercase() == "table" || combined.contains("<table") -> "表格"
            el.tagName().lowercase() == "iframe" || combined.contains("<iframe") -> "视频/嵌入内容"
            classes.contains("onebox") -> "链接预览"
            classes.contains("math") || combined.contains("math-container") ||
                combined.contains("<math") -> "数学公式"
            else -> null
        }
    }

    // ------------------------------------------------------------------
    // 图片：单图段落 / 图片容器 / 混排切分
    // ------------------------------------------------------------------

    /** 单图段落：<img>、<a><img></a>、上传预览 <a><img></a><a>文件名</a> 三种形态。 */
    private fun singleImageParagraph(el: Element): RichBlock? {
        val meaningful = el.childNodes().filter { node ->
            when (node) {
                is TextNode -> node.wholeText.isNotBlank()
                is Element -> node.tagName().lowercase() != "br"
                else -> true
            }
        }
        if (meaningful.isEmpty()) {
            return null
        }

        fun imgHref(img: Element, anchor: Element?): RichBlock? =
            imageBlockFrom(img, anchor?.attr("href") ?: "")

        val first = meaningful[0]
        return when {
            // <img>
            first is Element && first.tagName() == "img" && meaningful.size == 1 ->
                imgHref(first, null)

            // <a><img></a>（或上传预览双链接：图片链接 + 文件名链接）
            first is Element && first.tagName() == "a" -> {
                val img = first.selectFirst("img") ?: return null
                // 上传预览的第二个 <a>（文件名链接）忽略，只取图片。
                imgHref(img, first)
            }

            else -> null
        }
    }

    private fun isEmojiOnlyParagraph(el: Element): Boolean {
        val imgs = el.select("img")
        if (imgs.isEmpty()) {
            return false
        }
        if (imgs.any { !it.className().contains("emoji") }) {
            return false
        }
        // 残留文本（除 emoji 图片外）必须为空。
        val clone = el.clone()
        clone.select("img, br").remove()
        return clone.wholeText().isBlank()
    }

    /**
     * Discourse 上传图片包成 <div class="lightbox-wrapper"><a class="lightbox">…<div class="meta">…。
     * 去掉 meta 元信息与图标后若无实质文字，则视为纯图片容器，逐张渲染为图片块
     * （否则会退化成一个 18px 小图标 + 蓝色链接）。
     */
    private fun imageContainerBlocks(el: Element): List<RichBlock> {
        if (el.select("img").isEmpty()) {
            return emptyList()
        }
        val clone = el.clone()
        clone.select(".meta, svg, img").remove()
        if (clone.wholeText().isNotBlank()) {
            return emptyList()
        }
        return collectImageBlocks(el)
    }

    /** 收集容器内全部图片块；<a class="lightbox"> 包裹时 href 取链接（原图）。 */
    private fun collectImageBlocks(el: Element): List<RichBlock> =
        el.select("img").mapNotNull { img ->
            imageBlockFrom(img, img.closest("a")?.attr("href") ?: "")
        }

    private fun mixedBlocks(el: Element): List<RichBlock> {
        val result = mutableListOf<RichBlock>()
        val pending = mutableListOf<Node>()
        for (node in el.childNodes()) {
            val element = node as? Element
            if (element != null && isImageUnit(element)) {
                flushPending(pending, result)
                result.addAll(collectImageBlocks(element))
                continue
            }
            if (element != null && findImageUnitIn(element) != null) {
                // 透明容器（如裹着块级图的内联元素）：递归处理其孩子。
                result.addAll(mixedBlocks(element))
                continue
            }
            pending.add(node)
        }
        flushPending(pending, result)
        return result
    }

    /** 文档序找第一个"图片单元"：lightbox 容器（div/a）优先于独立块级 <img>。 */
    private fun findImageUnitIn(el: Element): Element? {
        if (isImageUnit(el)) {
            return el
        }
        for (child in el.children()) {
            findImageUnitIn(child)?.let { return it }
        }
        return null
    }

    /**
     * 独立 <img> 是否按整幅图片块渲染：emoji 永远行内；动图（class="animated"）或
     * 标注了大尺寸（宽或高 > 48）提升为块级。无尺寸又非动图的裸图保持行内（安全默认）。
     */
    private fun isImageUnit(el: Element): Boolean {
        val name = el.tagName().lowercase()
        if (name == "div" || name == "a") {
            return el.className().contains("lightbox")
        }
        if (name == "img") {
            if (el.className().contains("emoji")) {
                return false
            }
            if (el.className().contains("animated")) {
                return true
            }
            return parsePositiveNumber(el.attr("width")) > INLINE_IMAGE_MAX ||
                parsePositiveNumber(el.attr("height")) > INLINE_IMAGE_MAX
        }
        return false
    }

    /**
     * <img> → IMAGE 块。src 优先取 srcset 里倍率最高的那档（通常 2x）：Discourse 的 src
     * 是按 CSS 像素切的最小档，手机高 DPI 直渲必糊；srcset 一般给到 2x，清晰度够且远小于原图。
     */
    private fun imageBlockFrom(img: Element, href: String): RichBlock? {
        val src = resolveUrl(bestSrcFrom(img))
        if (src.isEmpty()) {
            return null
        }
        val resolvedHref = resolveUrl(href)
        return RichBlock(
            type = RichBlockType.IMAGE,
            src = src,
            alt = img.attr("alt").ifEmpty { null },
            width = parsePositiveNumber(img.attr("width")).takeIf { it > 0 }?.toInt(),
            height = parsePositiveNumber(img.attr("height")).takeIf { it > 0 }?.toInt(),
            href = resolvedHref.ifEmpty { null }
        )
    }

    /** srcset 优先取倍率最高档；无描述符按 1x，解析不出也按 1 算（不丢档）。 */
    private fun bestSrcFrom(img: Element): String {
        val src = img.attr("src")
        val srcset = img.attr("srcset")
        if (srcset.isEmpty()) {
            return src
        }
        var bestUrl = ""
        var bestScale = 0.0
        for (entry in srcset.split(Regex(",\\s+"))) {
            val parts = entry.trim().split(Regex("\\s+"))
            val url = parts.firstOrNull() ?: ""
            if (url.isEmpty()) {
                continue
            }
            var scale = 1.0
            if (parts.size > 1) {
                val parsed = parts[1].replace(Regex("[xw]$", RegexOption.IGNORE_CASE), "").toDoubleOrNull()
                if (parsed != null && parsed > 0) {
                    scale = parsed
                }
            }
            if (scale > bestScale) {
                bestScale = scale
                bestUrl = url
            }
        }
        return bestUrl.ifEmpty { src }
    }

    // ------------------------------------------------------------------
    // 音频/视频
    // ------------------------------------------------------------------

    /** 段落内嵌 <audio>/<video> 提升为媒体块；媒体前后残留文字各自成段。无媒体返回 null。 */
    private fun mediaParagraphBlocks(el: Element): List<RichBlock>? {
        val mediaElements = el.select("audio, video")
        if (mediaElements.isEmpty()) {
            return null
        }
        val result = mutableListOf<RichBlock>()
        val pending = mutableListOf<Node>()
        for (node in el.childNodes()) {
            val element = node as? Element
            if (element != null && element.tagName().lowercase() in setOf("audio", "video")) {
                flushPending(pending, result)
                mediaBlockFrom(element)?.let(result::add)
                continue
            }
            if (element != null && element.selectFirst("audio, video") != null) {
                result.addAll(mediaParagraphBlocks(element) ?: emptyList())
                continue
            }
            pending.add(node)
        }
        flushPending(pending, result)
        return result
    }

    /** 源优先取内部首个 <source src>（type 作 MIME），缺失回退标签自身 src。无有效 src 返回 null。 */
    private fun mediaBlockFrom(el: Element): RichBlock? {
        val source = el.selectFirst("source")
        var src = source?.attr("src") ?: ""
        val mediaType = source?.attr("type") ?: ""
        if (src.isEmpty()) {
            src = el.attr("src")
        }
        val resolvedSrc = resolveUrl(src)
        if (resolvedSrc.isEmpty()) {
            return null
        }
        val isVideo = el.tagName().lowercase() == "video"
        return RichBlock(
            type = if (isVideo) RichBlockType.VIDEO else RichBlockType.AUDIO,
            src = resolvedSrc,
            mediaType = mediaType.ifEmpty { null },
            width = if (isVideo) parsePositiveNumber(el.attr("width")).takeIf { it > 0 }?.toInt() else null,
            height = if (isVideo) parsePositiveNumber(el.attr("height")).takeIf { it > 0 }?.toInt() else null
        )
    }

    // ------------------------------------------------------------------
    // 公共
    // ------------------------------------------------------------------

    /** 相邻纯文本段落合并进同一段，块间插 paragraphBreak 换行（跨段可连续选中/复制）。 */
    private fun mergeAdjacentParagraphs(blocks: List<RichBlock>): List<RichBlock> {
        val out = mutableListOf<RichBlock>()
        for (block in blocks) {
            val prev = out.lastOrNull()
            if (
                block.type == RichBlockType.PARAGRAPH && block.inlines != null &&
                prev != null && prev.type == RichBlockType.PARAGRAPH && prev.inlines != null
            ) {
                val separator = RichInlineNode(
                    type = RichInlineType.LINE_BREAK,
                    text = "\n",
                    paragraphBreak = true
                )
                out[out.lastIndex] = prev.copy(inlines = prev.inlines + separator + block.inlines)
            } else {
                out.add(block)
            }
        }
        return out
    }

    /** 空块过滤（POLL/CALLOUT 恒保留，见源注释）。 */
    private fun hasBlockContent(block: RichBlock): Boolean = when (block.type) {
        RichBlockType.PARAGRAPH, RichBlockType.HEADING -> !block.inlines.isNullOrEmpty()
        RichBlockType.QUOTE, RichBlockType.SPOILER -> !block.blocks.isNullOrEmpty()
        RichBlockType.CODE, RichBlockType.MATH -> !block.text.isNullOrEmpty()
        RichBlockType.UNSUPPORTED -> !block.text.isNullOrEmpty() || !block.unsupportedKind.isNullOrEmpty()
        RichBlockType.IMAGE, RichBlockType.AUDIO, RichBlockType.VIDEO -> !block.src.isNullOrEmpty()
        RichBlockType.LIST -> !block.items.isNullOrEmpty()
        RichBlockType.TABLE -> !block.tableRows.isNullOrEmpty()
        RichBlockType.DISCLOSURE -> !block.title.isNullOrEmpty() || !block.blocks.isNullOrEmpty()
        RichBlockType.CALLOUT -> true
        RichBlockType.POLL -> true
        RichBlockType.ONEBOX -> !block.href.isNullOrEmpty() ||
            !block.title.isNullOrEmpty() || !block.domain.isNullOrEmpty()
        else -> true
    }

    private fun parsePositiveNumber(raw: String): Long {
        val value = raw.trim().toLongOrNull() ?: return 0L
        return if (value <= 0) 0L else value
    }
}

private val WHITESPACE = Regex("\\s+")
