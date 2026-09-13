package com.arkdo.android.feature.home

import com.arkdo.android.core.model.HomeFeed
import com.arkdo.android.core.model.HomeQuery

/** Same priority as the legacy home title: category, tag, feed, then app name. */
fun homeTitle(query: HomeQuery, categoryName: String? = query.categoryName): String = when {
    !categoryName.isNullOrBlank() -> categoryName
    !query.tag.isNullOrBlank() -> query.tag!!
    query.feed == HomeFeed.LATEST -> "ArkDO"
    else -> query.feed.label
}

internal fun HomeQuery.isDefaultHomeQuery(): Boolean =
    feed == HomeFeed.LATEST && categoryId == null && tag == null
