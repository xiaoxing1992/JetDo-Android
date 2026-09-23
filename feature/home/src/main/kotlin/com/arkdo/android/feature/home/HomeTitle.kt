package com.arkdo.android.feature.home

import com.arkdo.android.core.model.HomeFeed
import com.arkdo.android.core.model.HomeQuery

/** Same priority as the legacy home title: category, tag, feed, then app name. */
internal fun homeTitle(query: HomeQuery, categoryName: String? = query.categoryName): String = when {
    !categoryName.isNullOrBlank() -> categoryName
    !query.tag.isNullOrBlank() -> query.tag!!
    query.feed == HomeFeed.LATEST -> "ArkDO"
    else -> query.feed.label
}

internal fun HomeQuery.isDefaultHomeQuery(): Boolean =
    feed == HomeFeed.LATEST && categoryId == null && tag == null

/** 版块和标签都选了，或者换了排序，顶栏才需要第二行。 */
internal fun HomeQuery.hasSummary(): Boolean {
    val hasScope = categoryId != null || !tag.isNullOrBlank()
    if (!hasScope) return false
    return feed != HomeFeed.LATEST || (categoryId != null && !tag.isNullOrBlank())
}

internal fun HomeQuery.summaryLead(): String = if (hasSummary()) feed.label else ""

/** 版块占了主标题时，标签放到副行。 */
internal fun HomeQuery.summaryTag(): String? =
    if (categoryId != null) tag?.takeIf(String::isNotBlank) else null

/** 抽屉确认区的完整描述，不省略任何一项。 */
internal fun describeHomeQuery(query: HomeQuery): String {
    val parts = mutableListOf(query.feed.label)
    when {
        !query.categoryName.isNullOrBlank() -> parts.add(query.categoryName!!)
        query.tag.isNullOrBlank() -> parts.add("全站")
    }
    query.tag?.takeIf(String::isNotBlank)?.let { parts.add(it) }
    return parts.joinToString(" · ")
}

internal fun sameHomeQuery(a: HomeQuery, b: HomeQuery): Boolean =
    a.feed == b.feed && (a.categoryId ?: 0L) == (b.categoryId ?: 0L) &&
        (a.tag ?: "") == (b.tag ?: "")
