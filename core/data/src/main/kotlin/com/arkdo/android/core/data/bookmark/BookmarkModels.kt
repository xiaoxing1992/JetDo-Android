package com.arkdo.android.core.data.bookmark

import com.arkdo.android.core.model.HomeTag
import com.arkdo.android.core.model.HomeUser

data class BookmarkItem(
    val id: Long,
    val topicId: Long,
    val title: String,
    val excerpt: String,
    val linkedPostNumber: Int,
    val pinned: Boolean,
    val categoryId: Long?,
    val tags: List<HomeTag>,
    val author: HomeUser?,
    val highestPostNumber: Int?,
    val createdAt: String?
) {
    /** 回复数与首页同一口径：总楼数减 1，缺楼数时为 0。 */
    val replyCount: Int
        get() = highestPostNumber?.takeIf { it > 0 }?.minus(1) ?: 0
}

data class BookmarkPage(
    val items: List<BookmarkItem>,
    val moreUrl: String?
)

sealed interface BookmarkPageResult {
    data class Success(val page: BookmarkPage) : BookmarkPageResult
    data class Failure(val message: String, val requiresChallenge: Boolean = false) : BookmarkPageResult
}
