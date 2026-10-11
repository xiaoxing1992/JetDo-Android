package com.jetdo.android.services.content

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Tag
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor

// ==========================================================================
// 把 Markdown 渲染结果里的已知表情短码转成行内图片，**仅供本地预览**。
//
// 为什么只有预览需要：详情页正文拿的是服务端 cooked HTML，里面表情**已经是**
// `<img class="emoji" src="...">`；只有本地 markdown → HTML 这一步不会把 `:smile:`
// 变成图，所以要在编译产物上补一刀，否则预览里看到的是一串裸短码。
//
// 与鸿蒙 EmojiPreviewHtml.ets 的差异：那边手写词法逐字符扫原始 HTML，这边用 jsoup
// 建树后只改文本节点。行为等价（jsoup 解析出的文本节点天然就是"非标签"部分），
// 且不用自己处理属性里的 `>`、注释、自闭合等边界。
//
// 纯函数、无 Android 依赖，可直接单测。
// ==========================================================================

/** 这些容器内部的文本是"代码/原样文本"，里面的 `:` 不该被当成表情。 */
private val PLAIN_TEXT_CONTAINERS = setOf("code", "pre", "script", "style", "textarea")

object EmojiPreviewHtml {

    /**
     * @param resolveUrl 短名 → 图片 url；返回 null/空 表示不是已知表情，原样保留短码。
     */
    fun render(html: String, resolveUrl: (String) -> String?): String {
        if (html.isEmpty() || !html.contains(':')) return html

        val body = Jsoup.parseBodyFragment(html).body()

        // 先收集、再修改：遍历过程中改树会让 NodeTraversor 的游标失效。
        val targets = mutableListOf<TextNode>()
        NodeTraversor.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                if (node is TextNode && node.text().contains(':') && !insidePlainTextContainer(node)) {
                    targets.add(node)
                }
            }

            override fun tail(node: Node, depth: Int) = Unit
        }, body)

        for (textNode in targets) {
            val fragments = splitToFragments(textNode.text(), resolveUrl) ?: continue
            // before() 多次调用按顺序插入，恰好拼回原文本顺序。
            fragments.forEach { textNode.before(it) }
            textNode.remove()
        }

        return body.html()
    }

    /**
     * 把一段纯文本切成「文本 / 表情图 / 文本 …」。无命中返回 null（调用方跳过，不白建节点）。
     *
     * 未命中时从闭冒号继续搜而不是从闭冒号 +1，这样才能正确处理 `:unknown::known:` 这类相邻短码。
     */
    private fun splitToFragments(text: String, resolveUrl: (String) -> String?): List<Node>? {
        val out = mutableListOf<Node>()
        var appendedUntil = 0
        var searchFrom = 0
        var hit = false

        while (searchFrom < text.length) {
            val opening = text.indexOf(':', searchFrom)
            if (opening < 0) break
            val closing = text.indexOf(':', opening + 1)
            if (closing < 0) break

            val name = text.substring(opening + 1, closing)
            val url = if (name.isNotEmpty()) resolveUrl(name) else null
            if (!url.isNullOrEmpty()) {
                out.add(TextNode(text.substring(appendedUntil, opening)))
                out.add(imageElement(name, url))
                appendedUntil = closing + 1
                searchFrom = appendedUntil
                hit = true
            } else {
                searchFrom = closing
            }
        }

        if (!hit) return null
        out.add(TextNode(text.substring(appendedUntil)))
        return out
    }

    private fun imageElement(name: String, url: String): Element {
        val img = Element(Tag.valueOf("img"), "")
        img.attr("class", "emoji")
        img.attr("src", url)
        img.attr("alt", ":$name:")
        img.attr("title", ":$name:")
        img.attr("width", "20")
        img.attr("height", "20")
        return img
    }

    /** 祖先里有没有 code/pre 这类「原样文本」容器（手动向上走，jsoup 的 Node 没有 parents()）。 */
    private fun insidePlainTextContainer(node: Node): Boolean {
        var parent = node.parent()
        while (parent != null) {
            // tagName() 只有 Element 有，Node 层面拿不到标签名。
            if (parent is Element && parent.tagName().lowercase() in PLAIN_TEXT_CONTAINERS) return true
            parent = parent.parent()
        }
        return false
    }
}
