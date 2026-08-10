package com.arkdo.android.data.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ==========================================================================
// 与 HarmonyOS 版 models/Bookmark.ets 逐字段对齐。
// 来源：/u/{username}/bookmarks.json 的 user_bookmark_list.bookmarks[]
// ==========================================================================

/** 书签列表项：单条对应"某话题的某一层楼"的收藏。 */
@Serializable
data class Bookmark(
    @SerialName("id") val id: Long = 0,
    @SerialName("topic_id") val topicId: Long = 0,
    @SerialName("fancy_title") val fancyTitle: String? = null,
    @SerialName("title") val titleRaw: String? = null,
    @SerialName("excerpt") val excerptRaw: String? = null,
    @SerialName("linked_post_number") val linkedPostNumber: Int? = null,
    @SerialName("pinned") val pinned: Boolean = false,
    @SerialName("category_id") val categoryId: Long? = null,
    @SerialName("tags") val tags: List<BookmarkTag> = emptyList(),
    @SerialName("user") val user: User? = null,
    @SerialName("highest_post_number") val highestPostNumber: Int? = null,
    @SerialName("created_at") val createdAt: String? = null
) {
    /** 标题：fancy_title 优先，回退 title，需 decodeEntities。 */
    val title: String
        get() = fancyTitle ?: titleRaw ?: ""

    /** 摘要：HTML → 纯文本。 */
    val excerpt: String
        get() = excerptRaw ?: ""

    /** 收藏定位楼号；主楼(1)不展示 #N 前缀但仍用于跳转。 */
    val linkedPostNumberResolved: Int
        get() = (linkedPostNumber ?: 0).takeIf { it > 0 } ?: 1

    /** 被收藏楼层作者。 */
    val author: User?
        get() = user
}

/** 书签接口的 tags 是对象数组（不同于列表的字符串数组）。 */
@Serializable
data class BookmarkTag(
    @SerialName("id") val id: Long? = null,
    @SerialName("name") val name: String = "",
    @SerialName("slug") val slug: String? = null
)

/** 一页书签 + 下一页地址（more_bookmarks_url，缺省表示到底）。 */
@Serializable
data class BookmarkPage(
    @SerialName("bookmarks") val bookmarks: List<Bookmark> = emptyList(),
    @SerialName("more_bookmarks_url") val moreUrl: String? = null
)

/** /u/{username}/bookmarks.json 的外壳。 */
@Serializable
data class UserBookmarkListPayload(
    @SerialName("user_bookmark_list") val userBookmarkList: UserBookmarkList? = null
)

@Serializable
data class UserBookmarkList(
    @SerialName("bookmarks") val bookmarks: List<Bookmark> = emptyList(),
    @SerialName("more_bookmarks_url") val moreUrl: String? = null
)
