package com.arkdo.android.data.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ==========================================================================
// 与 HarmonyOS 版 models/UserProfile.ets 逐字段对齐。
// 来源：/u/{username}.json（资料）、/u/{username}/summary.json（统计+徽章）、
//       /user_actions.json（动态）、/solution/by_user.json（已解决）。
// ==========================================================================

/** 个人资料。 */
@Serializable
data class UserProfileDetail(
    @SerialName("id") val id: Long,
    @SerialName("username") val username: String = "",
    @SerialName("name") val displayName: String? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("trust_level") val trustLevel: Int? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("last_posted_at") val lastPostedAt: String? = null,
    @SerialName("badge_count") val badgeCount: Int? = null,
    @SerialName("profile_view_count") val profileViewCount: Int? = null,
    @SerialName("avatar_template") val avatarTemplate: String? = null,
    @SerialName("animated_avatar") val animatedAvatar: String? = null,
    @SerialName("flair_url") val flairUrl: String? = null,
    @SerialName("flair_bg_color") val flairBgColor: String? = null,
    @SerialName("flair_color") val flairColor: String? = null,
    @SerialName("flair_name") val flairName: String? = null,
    @SerialName("bio_excerpt") val bioExcerpt: String? = null,
    @SerialName("can_follow") val canFollow: Boolean? = null,
    @SerialName("is_followed") val isFollowed: Boolean? = null,
    @SerialName("total_followers") val totalFollowers: Int? = null,
    @SerialName("total_following") val totalFollowing: Int? = null,
    @SerialName("status") val status: UserStatus? = null
) {
    /** discourse-category-experts 插件启用标志（payload 里存在该字段=启用）。 */
    var endorsementAvailable: Boolean = false

    /** 当前登录者已认可 TA 的分类 id 列表。 */
    var endorsedCategoryIds: List<Long> = emptyList()

    /** 昵称后状态表情短名。 */
    val statusEmoji: String?
        get() = status?.emoji

    /** 转成 UserAvatar 可吃的 User。 */
    fun toAvatarUser(): User = User(
        id = id,
        username = username,
        name = displayName,
        avatarTemplate = avatarTemplate,
        animatedAvatar = animatedAvatar,
        flairUrl = flairUrl,
        flairBgColor = flairBgColor,
        flairColor = flairColor,
        flairName = flairName
    )
}

/** 「认可」弹窗里可勾选的一个分类。 */
@Serializable
data class EndorsableCategory(
    @SerialName("id") val id: Long,
    @SerialName("name") val name: String,
    @SerialName("color") val color: String? = null
)

@Serializable
data class EndorsableCategoriesResult(
    @SerialName("categories") val categories: List<EndorsableCategory> = emptyList(),
    @SerialName("remaining_endorsements") val remainingEndorsements: Int = 0
)

/** 统计项。timeReadSeconds 单位：秒。 */
@Serializable
data class UserSummaryStats(
    @SerialName("days_visited") val daysVisited: Int = 0,
    @SerialName("time_read") val timeReadSeconds: Int = 0,
    @SerialName("likes_given") val likesGiven: Int = 0,
    @SerialName("likes_received") val likesReceived: Int = 0,
    @SerialName("topic_count") val topicCount: Int = 0,
    @SerialName("post_count") val postCount: Int = 0,
    @SerialName("topics_entered") val topicsEntered: Int = 0,
    @SerialName("solved_count") val solvedCount: Int? = null
)

/** 徽章。badgeTypeId：1=金 2=银 3=铜。图标：imageUrl 优先，否则 icon（FA 名，按等级色染色）。 */
@Serializable
data class UserBadgeItem(
    @SerialName("id") val id: Long,
    @SerialName("name") val name: String,
    @SerialName("badge_type_id") val badgeTypeId: Int = 0,
    @SerialName("icon") val icon: String? = null,
    @SerialName("image") val imageUrl: String? = null,
    @SerialName("grant_count") val grantCount: Int? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("granted_at") val grantedAt: String? = null
)

/** 徽章按等级分组。 */
@Serializable
data class UserBadgeGroup(
    @SerialName("badge_type_id") val typeId: Int,
    @SerialName("type_name") val typeName: String,
    @SerialName("badges") val badges: List<UserBadgeItem> = emptyList()
)

/** 按获赞排序的热门话题。 */
@Serializable
data class UserTopTopic(
    @SerialName("id") val topicId: Long,
    @SerialName("slug") val slug: String? = null,
    @SerialName("title") val title: String,
    @SerialName("like_count") val likeCount: Int,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("category_id") val categoryId: Long? = null
)

/** 按获赞排序的热门回复，指向具体楼层。 */
@Serializable
data class UserTopReply(
    @SerialName("id") val topicId: Long,
    @SerialName("slug") val slug: String? = null,
    @SerialName("title") val topicTitle: String,
    @SerialName("like_count") val likeCount: Int,
    @SerialName("post_number") val postNumber: Int,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("category_id") val categoryId: Long? = null
)

/** 互动圈一格：某人 + 互动次数。 */
@Serializable
data class UserInteraction(
    val user: User,
    val count: Int
)

/** 他人主页完整摘要。 */
@Serializable
data class UserSummary(
    val stats: UserSummaryStats,
    val topBadges: List<UserBadgeItem>,
    val topTopics: List<UserTopTopic>,
    val topReplies: List<UserTopReply>,
    val mostRepliedToUsers: List<UserInteraction>,
    val mostLikedUsers: List<UserInteraction>,
    val mostLikedByUsers: List<UserInteraction>
)

/** /solution/by_user.json 一条。 */
@Serializable
data class SolvedPostItem(
    @SerialName("post_id") val postId: Long,
    @SerialName("topic_id") val topicId: Long,
    @SerialName("post_number") val postNumber: Int,
    @SerialName("topic_title") val topicTitle: String,
    @SerialName("slug") val slug: String? = null,
    @SerialName("excerpt") val excerpt: String,
    @SerialName("category_id") val categoryId: Long? = null,
    @SerialName("created_at") val createdAt: String? = null
)

@Serializable
data class SolvedPostPage(
    val items: List<SolvedPostItem> = emptyList(),
    val hasMore: Boolean = false
)

/** /user_actions.json 的 action_type。 */
enum class UserActionType(val value: Int) {
    NEW_TOPIC(4),
    REPLY(5);

    companion object {
        fun fromValue(v: Int?): UserActionType? =
            entries.firstOrNull { it.value == v }
    }
}

/** 用户动态一条。 */
@Serializable
data class UserActivityItem(
    @SerialName("action_type") val actionType: Int,
    @SerialName("topic_id") val topicId: Long,
    @SerialName("post_number") val postNumber: Int? = null,
    @SerialName("title") val title: String,
    @SerialName("excerpt") val excerpt: String,
    @SerialName("category_id") val categoryId: Long? = null,
    @SerialName("tags") val tags: List<TopicTag> = emptyList(),
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("username") val username: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("avatar_template") val avatarTemplate: String? = null
) {
    /** 该动作对应楼层的作者（user_actions 不返回 flair 字段）。 */
    var author: User? = null
}

/** 一页用户动态。 */
@Serializable
data class UserActivityPage(
    val items: List<UserActivityItem> = emptyList(),
    val hasMore: Boolean = false
)
