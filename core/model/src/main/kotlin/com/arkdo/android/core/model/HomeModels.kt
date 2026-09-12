package com.arkdo.android.core.model

enum class HomeFeed(val apiValue: String, val label: String) {
    LATEST("latest", "最新"),
    HOT("hot", "热门"),
    UNREAD("unread", "未读")
}

data class HomeQuery(
    val feed: HomeFeed = HomeFeed.LATEST,
    val categoryId: Long? = null,
    val categorySlug: String? = null,
    val tag: String? = null
)

data class HomeTag(
    val name: String,
    val id: Long? = null,
    val slug: String? = null
)

data class HomeUser(
    val id: Long,
    val username: String,
    val name: String? = null,
    val avatarTemplate: String? = null
)

data class HomeTopic(
    val id: Long,
    val title: String,
    val slug: String? = null,
    val categoryId: Long? = null,
    val tags: List<HomeTag> = emptyList(),
    val replyCount: Int? = null,
    val views: Int? = null,
    val lastPostedAt: String? = null,
    val unseen: Boolean = false,
    val unreadPosts: Int = 0,
    val bookmarked: Boolean = false,
    val hasAcceptedAnswer: Boolean = false,
    val author: HomeUser? = null
)

data class HomePage(
    val items: List<HomeTopic>,
    val page: Int,
    val hasMore: Boolean
)

sealed interface HomePageResult {
    data class Success(val page: HomePage) : HomePageResult
    data class Failure(val message: String, val requiresChallenge: Boolean = false) : HomePageResult
}
