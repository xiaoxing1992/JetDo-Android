package com.jetdo.android.services.topic

import com.jetdo.android.services.content.resolveUrl
import org.jsoup.Jsoup

// ==========================================================================
// 正文链接的点击数（Discourse link_counts）——建表与查表。
// 1:1 对齐鸿蒙 services/topic/LinkClicks.ets。
//
// 数据随 post JSON 下发，不在 cooked HTML 里，这一层负责收成「渲染用 href → 点击数」
// 的表，渲染层按 href 查表决定要不要在链接后挂灰底角标。行为对齐官方
// post-cooked 的 _showLinkCounts 与 click-track 的 isValidLink：
//   1. clicks < 1 不显示（而不是显示 0）；
//   2. reflection（反链：别的帖引用了本帖）不显示——它根本不在本楼正文里；
//   3. 引用卡 <aside class="quote"> 里的链接不显示。服务端会把引用里的链接算进
//      link_counts，这条必须自己滤，不能指望数据里没有；
//   4. 版块徽章 / 话题标签 / 灯箱图这类链接不显示（官方 isValidLink 同样排除）。
//
// 键取「resolveUrl 之后的 href」：渲染层的 token.href 是解析器 resolveUrl 过的，
// 两边同形才配得上。模糊匹配（去 query、附件按包含）一次性在建表时做完。
// ============================================================================

/** 建表。无可展示项返回 null，让 TopicPost.linkClicks 保持空缺，渲染层一次判空即可短路。 */
fun buildLinkClicks(cookedHtml: String, counts: List<DiscourseLinkCount>?): Map<String, Int>? {
    if (counts.isNullOrEmpty() || cookedHtml.isEmpty()) {
        return null
    }

    val candidates = counts.filter { item ->
        !item.url.isNullOrEmpty() && item.reflection != true && (item.clicks ?: 0) >= 1
    }
    if (candidates.isEmpty()) {
        return null
    }

    val body = Jsoup.parseBodyFragment(cookedHtml).body()
    // 引用卡里的链接属于别人的帖子，点击数记在原帖上，整块剔掉。
    body.select("aside.quote").remove()
    val hrefs = body.select("a[href]")
        .filter { anchor ->
            val cls = anchor.className()
            // 版块徽章 / 话题标签 / 灯箱大图 / 引用卡回跳箭头：官方一律不挂角标。
            !cls.contains("hashtag") && !cls.contains("badge-category") &&
                !cls.contains("lightbox") && !cls.contains("back")
        }
        .map { resolveUrl(it.attr("href")) }
        .distinct()

    val result = mutableMapOf<String, Int>()
    for (href in hrefs) {
        val clicks = matchClicks(candidates, href)
        if (clicks > 0) {
            result[href] = clicks
        }
    }
    return result.ifEmpty { null }
}

/** 渲染层入口：按 href 取点击数，0 表示不挂角标。 */
fun clicksForHref(clicks: Map<String, Int>?, href: String?): Int =
    if (href.isNullOrEmpty()) 0 else (clicks?.get(href) ?: 0)

/**
 * 给定 href 在 link_counts 里找对应计数。匹配顺序照抄官方：
 * 精确匹配 → 去掉 query 再匹配（服务端对站内链接按去 query 的地址计数）→
 * 附件按包含匹配（link_counts 给的是 /uploads/… 相对路径，而 href 已补成绝对地址）。
 */
private fun matchClicks(candidates: List<DiscourseLinkCount>, href: String): Int {
    val queryAt = href.indexOf('?')
    val bare = if (queryAt > 0) href.substring(0, queryAt) else href

    for (item in candidates) {
        val url = item.url ?: continue
        if (url == href || url == bare) {
            return item.clicks ?: 0
        }
    }
    for (item in candidates) {
        val url = item.url ?: continue
        if (url.startsWith("/uploads/") && href.contains(url)) {
            return item.clicks ?: 0
        }
    }
    return 0
}
