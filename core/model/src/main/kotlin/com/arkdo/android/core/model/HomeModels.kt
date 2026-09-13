package com.arkdo.android.core.model

enum class HomeFeed(val apiValue: String, val label: String) {
    LATEST("latest", "最新"),
    CREATED("created", "最新发布"),
    TOP("top", "排行榜"),
    HOT("hot", "热门"),
    UNREAD("unread", "未读"),
    NEW("new", "未读新帖")
}

data class HomeQuery(
    val feed: HomeFeed = HomeFeed.LATEST,
    val categoryId: Long? = null,
    val categorySlug: String? = null,
    val categoryName: String? = null,
    val tag: String? = null,
    val tagId: Long? = null,
    val tagSlug: String? = null
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
    val avatarTemplate: String? = null,
    val animatedAvatar: String? = null,
    val flairUrl: String? = null,
    val flairBgColor: String? = null,
    val flairColor: String? = null,
    val flairName: String? = null
)

data class HomeCategory(
    val id: Long,
    val name: String,
    val color: String = "6B7280",
    val textColor: String = "FFFFFF",
    val parentCategoryId: Long? = null,
    val slug: String? = null,
    val uploadedLogo: String? = null,
    val icon: String? = null,
    val readRestricted: Boolean = false
)

data class HomeTopic(
    val id: Long,
    val title: String,
    val slug: String? = null,
    val categoryId: Long? = null,
    val tags: List<HomeTag> = emptyList(),
    val createdAt: String? = null,
    val replyCount: Int? = null,
    val views: Int? = null,
    val lastPostedAt: String? = null,
    val unseen: Boolean = false,
    val unreadPosts: Int = 0,
    val lastReadPostNumber: Int? = null,
    val highestPostNumber: Int? = null,
    val bookmarked: Boolean = false,
    val hasAcceptedAnswer: Boolean = false,
    val searchBlurb: String? = null,
    val category: HomeCategory? = null,
    val author: HomeUser? = null
) {
    /** The first unread post, capped to the topic's highest known post. */
    fun firstUnreadPostNumber(): Int? {
        val lastRead = lastReadPostNumber ?: 0
        if (lastRead <= 0) return null
        val highest = highestPostNumber ?: 0
        return if (highest > 0) minOf(lastRead + 1, highest) else lastRead + 1
    }
}

data class HomePage(
    val items: List<HomeTopic>,
    val page: Int,
    val hasMore: Boolean
)

sealed interface HomePageResult {
    data class Success(val page: HomePage) : HomePageResult
    data class Failure(val message: String, val requiresChallenge: Boolean = false) : HomePageResult
}
