package com.jetdo.android.services.content

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

// ==========================================================================
// 行内解析层，1:1 对齐鸿蒙 services/content/InlineHtmlParser.ets 的输出语义：
// 把一段 HTML 解析为行内节点序列（文字/链接/行内代码/行内图片/行内公式/换行）。
//
// 实现差异（行为不变）：鸿蒙手写词法逐字符扫描，Android 用 jsoup 建树后遍历 ——
// 实体解码由 jsoup 完成（不要再对 TextNode 二次解码，否则用户手打的 "&lt;" 会被误还原）。
// 语义要点逐条保留：
// · 源码换行/缩进/连续空格折叠成单空格（nbsp 不折叠，予以保留）
// · 段首及换行之后的行首空白不可见，去掉
// · 相邻同型 token（type/href/attachment/spoiler/bold/italic/code 全同）合并
// · <span class="math"> 整体抽成 MATH token，LaTeX 不泄进正文流
// · 链接嵌套时图片/文字继承当前 href；附件链接标记 attachment
// ============================================================================

/** 行内解析的可变状态：bold/italic/code 计数 + href/attachment 栈 + spoiler 栈。 */
private class InlineState {
    var bold = 0
    var italic = 0
    var code = 0
    val hrefStack = ArrayDeque<String>()
    val attachmentStack = ArrayDeque<Boolean>()
    val spanSpoilerStack = ArrayDeque<Boolean>()

    val currentHref: String get() = hrefStack.lastOrNull() ?: ""
    val currentAttachment: Boolean get() = attachmentStack.lastOrNull() ?: false
    val currentSpoiler: Boolean get() = spanSpoilerStack.any { it }
}

fun parseInlines(html: String): List<RichInlineNode> {
    if (html.isEmpty()) {
        return emptyList()
    }
    return parseInlinesNodes(Jsoup.parseBodyFragment(html).body().childNodes())
}

/** 块解析器直接喂 jsoup 节点（顶层行内内容累积场景），语义同 [parseInlines]。 */
fun parseInlinesNodes(nodes: List<Node>): List<RichInlineNode> {
    val out = mutableListOf<RichInlineNode>()
    if (nodes.isEmpty()) {
        return out
    }
    val state = InlineState()
    walk(nodes, state, out)
    return compactInlines(out)
}

private fun walk(nodes: List<Node>, state: InlineState, out: MutableList<RichInlineNode>) {
    for (node in nodes) {
        when (node) {
            is TextNode -> appendText(node.wholeText, state, out)

            is Element -> when (node.tagName().lowercase()) {
                "br" -> out.add(
                    RichInlineNode(type = RichInlineType.LINE_BREAK, text = "\n")
                )

                "strong", "b" -> {
                    state.bold++
                    walk(node.childNodes(), state, out)
                    state.bold--
                }

                "em", "i" -> {
                    state.italic++
                    walk(node.childNodes(), state, out)
                    state.italic--
                }

                "code" -> {
                    state.code++
                    walk(node.childNodes(), state, out)
                    state.code--
                }

                "span" -> {
                    val classes = node.className()
                    if (classes.split(Regex("\\s+")).contains("math")) {
                        // discourse-math 行内公式：整体抽成一个 MATH token。
                        val latex = htmlToPlainText(node.html())
                        if (latex.isNotEmpty()) {
                            out.add(RichInlineNode(type = RichInlineType.MATH, text = latex))
                        }
                    } else {
                        // 闭合标签不带 class，只能整体跟踪 span 嵌套才能正确配对 spoiler。
                        state.spanSpoilerStack.addLast(classes.contains("spoiler"))
                        walk(node.childNodes(), state, out)
                        state.spanSpoilerStack.removeLast()
                    }
                }

                "a" -> {
                    val resolvedHref = resolveUrl(node.attr("href"))
                    state.hrefStack.addLast(resolvedHref)
                    state.attachmentStack.addLast(isAttachmentHref(node.className(), resolvedHref))
                    walk(node.childNodes(), state, out)
                    state.hrefStack.removeLast()
                    state.attachmentStack.removeLast()
                }

                "img" -> {
                    val src = resolveUrl(node.attr("src"))
                    val alt = node.attr("alt")
                    if (src.isNotEmpty()) {
                        out.add(
                            RichInlineNode(
                                type = RichInlineType.IMAGE,
                                text = alt.ifEmpty { " " },
                                src = src,
                                alt = alt,
                                href = state.currentHref.ifEmpty { null },
                                spoiler = state.currentSpoiler
                            )
                        )
                    } else if (alt.isNotEmpty()) {
                        appendText(alt, state, out)
                    }
                }

                // script/style 的孩子当纯文本读（对齐 shouldReadChildrenAsPlainText）。
                "script", "style" -> appendText(htmlToPlainText(node.html()), state, out)

                else -> walk(node.childNodes(), state, out)
            }
        }
    }
}

private fun appendText(raw: String, state: InlineState, out: MutableList<RichInlineNode>) {
    var text = raw
    // HTML 语义：源码里的换行/缩进/连续空格是不可见排版空白，浏览器折叠成单个空格。
    // nbsp（\u00A0）不在其列，予以保留。
    text = text.replace(Regex("[ \\t\\r\\n\\f]+"), " ")
    // 段首及 <br>/段落换行之后的行首空白不可见，去掉，避免每行多一个前导空格。
    val prev = out.lastOrNull()
    if (prev == null || prev.type == RichInlineType.LINE_BREAK) {
        text = text.replaceFirst("^ ".toRegex(), "")
    }
    if (text.isEmpty()) {
        return
    }

    val href = state.currentHref
    val type = if (state.code > 0) RichInlineType.CODE else if (href.isNotEmpty()) RichInlineType.LINK else RichInlineType.TEXT
    val attachment = type == RichInlineType.LINK && state.currentAttachment
    val spoiler = state.currentSpoiler
    val last = out.lastOrNull()
    if (
        last != null &&
        last.type == type &&
        (last.href ?: "") == href &&
        last.attachment == attachment &&
        last.spoiler == spoiler &&
        last.bold == (state.bold > 0) &&
        last.italic == (state.italic > 0) &&
        last.code == (state.code > 0)
    ) {
        out[out.lastIndex] = last.copy(text = last.text + text)
        return
    }

    out.add(
        RichInlineNode(
            type = type,
            text = text,
            href = href.ifEmpty { null },
            attachment = attachment,
            spoiler = spoiler,
            bold = state.bold > 0,
            italic = state.italic > 0,
            code = state.code > 0
        )
    )
}

/** 连续 LINE_BREAK 折叠为一个；空 TEXT 丢弃；修剪首尾空白 token 与边缘空格。 */
private fun compactInlines(inlines: List<RichInlineNode>): List<RichInlineNode> {
    val compacted = mutableListOf<RichInlineNode>()
    for (token in inlines) {
        if (token.type != RichInlineType.LINE_BREAK) {
            compacted.add(token)
            continue
        }
        val last = compacted.lastOrNull()
        if (last != null && last.type == RichInlineType.LINE_BREAK) {
            continue
        }
        compacted.add(token)
    }
    val filtered = compacted.filterNot { it.type == RichInlineType.TEXT && it.text.isEmpty() }
    return trimInlineEdges(filtered)
}

private fun trimInlineEdges(inlines: List<RichInlineNode>): List<RichInlineNode> {
    var start = 0
    var end = inlines.size
    while (start < end && isBlankInline(inlines[start])) start++
    while (end > start && isBlankInline(inlines[end - 1])) end--
    if (start == 0 && end == inlines.size) return inlines
    val trimmed = inlines.subList(start, end)
    if (trimmed.isEmpty()) return trimmed
    // 去掉首个文本的前导空白、末个文本的尾随空白（标签间换行/缩进噪声）。
    val out = trimmed.toMutableList()
    out[0] = out[0].copy(text = out[0].text.trimStart())
    out[out.lastIndex] = out[out.lastIndex].copy(text = out[out.lastIndex].text.trimEnd())
    return out
}

private fun isBlankInline(token: RichInlineNode): Boolean =
    token.type == RichInlineType.LINE_BREAK ||
        (token.type == RichInlineType.TEXT && token.text.isBlank())

/** 是否含有可见内容（图片/链接/非空白文本），块解析器用它丢弃空段落。 */
fun hasVisibleInlines(inlines: List<RichInlineNode>): Boolean {
    for (token in inlines) {
        if (token.type == RichInlineType.IMAGE || token.type == RichInlineType.LINK) {
            return true
        }
        if (token.type == RichInlineType.LINE_BREAK) {
            continue
        }
        if (token.text.isNotBlank()) {
            return true
        }
    }
    return false
}

/** 丢掉标题前导的空白文本节点，并去掉首个文本节点的左侧空格。 */
fun trimLeadingEmptyText(inlines: List<RichInlineNode>): List<RichInlineNode> {
    val out = inlines.toMutableList()
    while (out.isNotEmpty() && out[0].type == RichInlineType.TEXT && out[0].text.isBlank()) {
        out.removeAt(0)
    }
    if (out.isNotEmpty() && out[0].type == RichInlineType.TEXT) {
        out[0] = out[0].copy(text = out[0].text.trimStart())
    }
    return out
}
