package com.arkdo.android.core.data.bookmark

import com.arkdo.android.core.common.RelativeTime

/** 书签摘要：去标签、解实体、空白压成一格。对齐鸿蒙 HtmlText.toPlainText。 */
fun bookmarkPlainText(html: String?): String {
    if (html.isNullOrBlank()) return ""
    return bookmarkDecodeEntities(html.replace(Regex("<[^>]*>"), ""))
        .replace(Regex("\\s+"), " ")
        .trim()
}

/** 标题优先 fancy_title。两边都没有时返回空串。 */
fun bookmarkTitle(fancyTitle: String?, title: String?): String {
    val raw = fancyTitle?.takeIf { it.isNotEmpty() } ?: title.orEmpty()
    return bookmarkDecodeEntities(raw)
}

/** 楼号缺省或小于等于 0 时当成主楼。 */
fun bookmarkPostNumber(linkedPostNumber: Int?): Int =
    linkedPostNumber?.takeIf { it > 0 } ?: 1

/** 置顶排最前，组内保持原顺序。 */
fun <T> sortPinnedFirst(items: List<T>, pinned: (T) -> Boolean): List<T> {
    val pinnedItems = ArrayList<T>()
    val rest = ArrayList<T>()
    for (item in items) {
        if (pinned(item)) pinnedItems += item else rest += item
    }
    return pinnedItems + rest
}

fun sortPinnedFirst(items: List<BookmarkItem>): List<BookmarkItem> =
    sortPinnedFirst(items) { it.pinned }

/** 没有可显示的相对时间时返回 null。 */
fun bookmarkTimeLabel(createdAt: String?): String? {
    val relative = RelativeTime.fromNow(createdAt)
    return relative.takeIf { it.isNotEmpty() }?.let { "收藏于 $it" }
}

/**
 * 对齐鸿蒙 HtmlText.decodeEntities：具名实体先解，数字实体其次，`&amp;` 最后，
 * 避免把刚解出来的 `&` 再当成实体。
 */
internal fun bookmarkDecodeEntities(input: String): String {
    if (!input.contains('&')) return input
    var text = input
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&nbsp;", " ")
        .replace("&hellip;", "…")
        .replace("&mdash;", "—")
        .replace("&ndash;", "–")
    text = Regex("&#(\\d+);").replace(text) { match ->
        match.groupValues[1].toIntOrNull()?.toChar()?.toString().orEmpty()
    }
    text = Regex("&#[xX]([0-9a-fA-F]+);").replace(text) { match ->
        match.groupValues[1].toIntOrNull(16)?.toChar()?.toString().orEmpty()
    }
    return text.replace("&amp;", "&")
}
