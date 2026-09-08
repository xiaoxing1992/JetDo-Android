package com.arkdo.android.services.user

import com.arkdo.android.data.models.UserProfileDetail
import com.arkdo.android.data.models.UserSummary
import com.arkdo.android.data.models.UserActivityItem
import com.arkdo.android.data.models.SolvedPostPage
import com.arkdo.android.data.models.SolvedPostItem
import com.arkdo.android.data.models.UserBadgeItem
import com.arkdo.android.data.models.UserTopTopic
import com.arkdo.android.data.models.UserTopReply
import com.arkdo.android.data.models.UserInteraction
import com.arkdo.android.data.models.UserSummaryStats
import com.arkdo.android.data.models.User
import com.arkdo.android.services.network.ChallengeState
import com.arkdo.android.services.network.JsonNetworkClient
import com.arkdo.android.services.network.JsonRequestOptions
import com.arkdo.android.services.network.NetworkResult
import com.arkdo.android.services.network.NetworkResults
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// ==========================================================================
// 个人中心数据服务，对齐鸿蒙 services/user/UserProfileService.ets。
//
// 资料 /u/{username}.json、摘要 /u/{username}/summary.json、
// 最近活动 /user_actions.json?filter=4,5（4=发布话题 5=回复）。均为 Discourse 标准接口。
// ==========================================================================

@Serializable
private data class DiscourseUserProfileResponse(val user: DiscourseProfileUser? = null)

@Serializable
private data class DiscourseProfileUser(
    val id: Long? = null,
    val username: String? = null,
    val name: String? = null,
    val title: String? = null,
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
    @SerialName("website_name") val websiteName: String? = null,
    @SerialName("can_follow") val canFollow: Boolean? = null,
    @SerialName("is_followed") val isFollowed: Boolean? = null,
    @SerialName("total_followers") val totalFollowers: Int? = null,
    @SerialName("total_following") val totalFollowing: Int? = null
)

@Serializable
private data class DiscourseSummaryResponse(@SerialName("user_summary") val userSummary: DiscourseUserSummary? = null)

@Serializable
private data class DiscourseUserSummary(
    @SerialName("days_visited") val daysVisited: Int? = null,
    @SerialName("time_read") val timeReadSeconds: Int? = null,
    @SerialName("likes_given") val likesGiven: Int? = null,
    @SerialName("likes_received") val likesReceived: Int? = null,
    @SerialName("topic_count") val topicCount: Int? = null,
    @SerialName("post_count") val postCount: Int? = null,
    @SerialName("topics_entered") val topicsEntered: Int? = null,
    @SerialName("solved_count") val solvedCount: Int? = null,
    @SerialName("badges") val badges: List<DiscourseSummaryBadge>? = null,
    @SerialName("top_topics") val topTopics: List<DiscourseTopTopic>? = null,
    @SerialName("top_replies") val topReplies: List<DiscourseTopReply>? = null,
    @SerialName("most_replied_to_users") val mostRepliedToUsers: List<DiscourseInteraction>? = null,
    @SerialName("most_liked_users") val mostLikedUsers: List<DiscourseInteraction>? = null,
    @SerialName("most_liked_by_users") val mostLikedByUsers: List<DiscourseInteraction>? = null
)

@Serializable
private data class DiscourseSummaryBadge(
    val id: Long? = null,
    val name: String? = null,
    @SerialName("badge_type_id") val badgeTypeId: Int? = null,
    val icon: String? = null,
    val image: String? = null,
    @SerialName("grant_count") val grantCount: Int? = null,
    val description: String? = null
)

@Serializable
private data class DiscourseTopTopic(
    val id: Long? = null,
    val slug: String? = null,
    val title: String? = null,
    @SerialName("like_count") val likeCount: Int? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("category_id") val categoryId: Long? = null
)

@Serializable
private data class DiscourseTopReply(
    val id: Long? = null,
    val slug: String? = null,
    val title: String? = null,
    @SerialName("like_count") val likeCount: Int? = null,
    @SerialName("post_number") val postNumber: Int? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("category_id") val categoryId: Long? = null
)

@Serializable
private data class DiscourseInteraction(
    val user: DiscourseInteractionUser? = null,
    val count: Int? = null
)

@Serializable
private data class DiscourseInteractionUser(
    val id: Long? = null,
    val username: String? = null,
    val name: String? = null,
    @SerialName("avatar_template") val avatarTemplate: String? = null,
    @SerialName("animated_avatar") val animatedAvatar: String? = null
)

@Serializable
private data class DiscourseUserActionsResponse(val user_actions: List<DiscourseUserAction>? = null)

@Serializable
private data class DiscourseUserAction(
    @SerialName("action_type") val actionType: Int? = null,
    @SerialName("topic_id") val topicId: Long? = null,
    @SerialName("post_number") val postNumber: Int? = null,
    val title: String? = null,
    val excerpt: String? = null,
    @SerialName("category_id") val categoryId: Long? = null,
    val tags: List<String>? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val username: String? = null,
    val name: String? = null,
    @SerialName("avatar_template") val avatarTemplate: String? = null
)

@Serializable
private data class DiscourseSolvedResponse(
    val posts: List<DiscourseSolvedPost>? = null,
    val count: Int? = null
)

@Serializable
private data class DiscourseSolvedPost(
    @SerialName("post_id") val postId: Long? = null,
    @SerialName("topic_id") val topicId: Long? = null,
    @SerialName("post_number") val postNumber: Int? = null,
    @SerialName("topic_title") val topicTitle: String? = null,
    val slug: String? = null,
    val excerpt: String? = null,
    @SerialName("category_id") val categoryId: Long? = null,
    @SerialName("created_at") val createdAt: String? = null
)

class UserProfileService(private val bridge: JsonNetworkClient) {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /** 资料：/u/{username}.json。 */
    suspend fun getProfileDetail(username: String): NetworkResult<UserProfileDetail> {
        val response = bridge.getJson("/u/${java.net.URLEncoder.encode(username, "UTF-8")}.json")
        if (!response.success) {
            return NetworkResults.forwardFailure(response)
        }
        val data = response.data
            ?: return NetworkResults.unexpectedResponse(
                response.statusCode, response.challengeState, "站点用户资料响应为空。"
            )
        return try {
            val payload = json.decodeFromString<DiscourseUserProfileResponse>(data)
            val source = payload.user
            if (source == null || source.id == null || source.username.isNullOrEmpty()) {
                return NetworkResults.unexpectedResponse(
                    response.statusCode, response.challengeState, "站点用户资料响应字段无效。"
                )
            }
            NetworkResult(
                success = true,
                statusCode = response.statusCode,
                challengeState = response.challengeState,
                data = UserProfileDetail(
                    id = source.id,
                    username = source.username,
                    displayName = source.name,
                    title = source.title,
                    trustLevel = source.trustLevel,
                    createdAt = source.createdAt,
                    lastPostedAt = source.lastPostedAt,
                    badgeCount = source.badgeCount,
                    profileViewCount = source.profileViewCount,
                    avatarTemplate = source.avatarTemplate,
                    animatedAvatar = source.animatedAvatar?.takeIf { it.isNotEmpty() },
                    flairUrl = source.flairUrl?.takeIf { it.isNotEmpty() },
                    flairBgColor = source.flairBgColor?.takeIf { it.isNotEmpty() },
                    flairColor = source.flairColor?.takeIf { it.isNotEmpty() },
                    flairName = source.flairName?.takeIf { it.isNotEmpty() },
                    bioExcerpt = source.bioExcerpt,
                    websiteName = source.websiteName,
                    canFollow = source.canFollow,
                    isFollowed = source.isFollowed,
                    totalFollowers = source.totalFollowers,
                    totalFollowing = source.totalFollowing
                )
            )
        } catch (_: Exception) {
            NetworkResults.unexpectedResponse(
                response.statusCode, response.challengeState, "站点用户资料响应不是有效 JSON。"
            )
        }
    }

    /** 摘要：/u/{username}/summary.json。 */
    suspend fun getSummary(username: String): NetworkResult<UserSummary> {
        val response = bridge.getJson("/u/${java.net.URLEncoder.encode(username, "UTF-8")}/summary.json")
        if (!response.success) {
            return NetworkResults.forwardFailure(response)
        }
        val data = response.data
            ?: return NetworkResults.unexpectedResponse(
                response.statusCode, response.challengeState, "站点用户摘要响应为空。"
            )
        return try {
            val payload = json.decodeFromString<DiscourseSummaryResponse>(data)
            val src = payload.userSummary
            if (src == null) {
                return NetworkResults.unexpectedResponse(
                    response.statusCode, response.challengeState, "站点用户摘要响应缺少 user_summary。"
                )
            }
            NetworkResult(
                success = true,
                statusCode = response.statusCode,
                challengeState = response.challengeState,
                data = UserSummary(
                    stats = UserSummaryStats(
                        daysVisited = src.daysVisited ?: 0,
                        timeReadSeconds = src.timeReadSeconds ?: 0,
                        likesGiven = src.likesGiven ?: 0,
                        likesReceived = src.likesReceived ?: 0,
                        topicCount = src.topicCount ?: 0,
                        postCount = src.postCount ?: 0,
                        topicsEntered = src.topicsEntered ?: 0,
                        solvedCount = src.solvedCount
                    ),
                    topBadges = src.badges.orEmpty().mapNotNull { b ->
                        if (b.id == null || b.name.isNullOrEmpty()) return@mapNotNull null
                        UserBadgeItem(
                            id = b.id,
                            name = b.name,
                            badgeTypeId = b.badgeTypeId ?: 0,
                            icon = b.icon,
                            imageUrl = b.image,
                            grantCount = b.grantCount,
                            description = b.description
                        )
                    },
                    topTopics = src.topTopics.orEmpty().mapNotNull { t ->
                        if (t.id == null || t.title.isNullOrEmpty()) return@mapNotNull null
                        UserTopTopic(
                            topicId = t.id,
                            slug = t.slug,
                            title = t.title,
                            likeCount = t.likeCount ?: 0,
                            createdAt = t.createdAt,
                            categoryId = t.categoryId
                        )
                    },
                    topReplies = src.topReplies.orEmpty().mapNotNull { r ->
                        if (r.id == null || r.title.isNullOrEmpty() || r.postNumber == null) return@mapNotNull null
                        UserTopReply(
                            topicId = r.id,
                            slug = r.slug,
                            topicTitle = r.title,
                            likeCount = r.likeCount ?: 0,
                            postNumber = r.postNumber,
                            createdAt = r.createdAt,
                            categoryId = r.categoryId
                        )
                    },
                    mostRepliedToUsers = src.mostRepliedToUsers.orEmpty().mapNotNull { it.toInteraction() },
                    mostLikedUsers = src.mostLikedUsers.orEmpty().mapNotNull { it.toInteraction() },
                    mostLikedByUsers = src.mostLikedByUsers.orEmpty().mapNotNull { it.toInteraction() }
                )
            )
        } catch (_: Exception) {
            NetworkResults.unexpectedResponse(
                response.statusCode, response.challengeState, "站点用户摘要响应不是有效 JSON。"
            )
        }
    }

    /** 最近动态：/user_actions.json?filter=4,5。 */
    suspend fun getRecentActivity(username: String): NetworkResult<List<UserActivityItem>> {
        val response = bridge.getJson("/user_actions.json?filter=4,5&username=${java.net.URLEncoder.encode(username, "UTF-8")}")
        if (!response.success) {
            return NetworkResults.forwardFailure(response)
        }
        val data = response.data
            ?: return NetworkResults.unexpectedResponse(
                response.statusCode, response.challengeState, "站点用户动态响应为空。"
            )
        return try {
            val payload = json.decodeFromString<DiscourseUserActionsResponse>(data)
            val items = payload.user_actions.orEmpty().mapNotNull { a ->
                if (a.actionType == null || a.topicId == null || a.title.isNullOrEmpty()) return@mapNotNull null
                UserActivityItem(
                    actionType = a.actionType,
                    topicId = a.topicId,
                    postNumber = a.postNumber,
                    title = a.title,
                    excerpt = a.excerpt ?: "",
                    categoryId = a.categoryId,
                    tags = emptyList(),
                    createdAt = a.createdAt,
                    username = a.username,
                    name = a.name,
                    avatarTemplate = a.avatarTemplate
                )
            }
            NetworkResult(
                success = true,
                statusCode = response.statusCode,
                challengeState = response.challengeState,
                data = items
            )
        } catch (_: Exception) {
            NetworkResults.unexpectedResponse(
                response.statusCode, response.challengeState, "站点用户动态响应不是有效 JSON。"
            )
        }
    }

    /** 已解决列表：/solution/by_user.json。 */
    suspend fun getSolvedPosts(username: String, offset: Int, limit: Int): NetworkResult<SolvedPostPage> {
        val response = bridge.getJson(
            "/solution/by_user.json?username=${java.net.URLEncoder.encode(username, "UTF-8")}&offset=$offset&limit=$limit"
        )
        if (!response.success) {
            return NetworkResults.forwardFailure(response)
        }
        val data = response.data
            ?: return NetworkResults.unexpectedResponse(
                response.statusCode, response.challengeState, "站点已解决列表响应为空。"
            )
        return try {
            val payload = json.decodeFromString<DiscourseSolvedResponse>(data)
            val items = payload.posts.orEmpty().mapNotNull { p ->
                if (p.postId == null || p.topicId == null) return@mapNotNull null
                SolvedPostItem(
                    postId = p.postId,
                    topicId = p.topicId,
                    postNumber = p.postNumber ?: 1,
                    topicTitle = p.topicTitle ?: "",
                    slug = p.slug,
                    excerpt = p.excerpt ?: "",
                    categoryId = p.categoryId,
                    createdAt = p.createdAt
                )
            }
            NetworkResult(
                success = true,
                statusCode = response.statusCode,
                challengeState = response.challengeState,
                data = SolvedPostPage(items = items, hasMore = items.size >= limit)
            )
        } catch (_: Exception) {
            NetworkResults.unexpectedResponse(
                response.statusCode, response.challengeState, "站点已解决列表响应不是有效 JSON。"
            )
        }
    }

    /** 关注 toggle（discourse-follow）。对齐鸿蒙 setFollowing。 */
    suspend fun toggleFollow(username: String, follow: Boolean): NetworkResult<Boolean> {
        val path = if (follow) "/follow" else "/unfollow"
        val response = bridge.postForm(
            path,
            "username=${java.net.URLEncoder.encode(username, "UTF-8")}",
            JsonRequestOptions(notifyChallenge = true)
        )
        if (!response.success) {
            return NetworkResults.forwardFailure(response)
        }
        return NetworkResult(
            success = true,
            statusCode = response.statusCode,
            challengeState = response.challengeState,
            data = true
        )
    }

    private fun DiscourseInteraction.toInteraction(): UserInteraction? {
        val u = user ?: return null
        if (u.id == null || u.username.isNullOrEmpty()) return null
        return UserInteraction(
            user = User(
                id = u.id,
                username = u.username,
                name = u.name,
                avatarTemplate = u.avatarTemplate,
                animatedAvatar = u.animatedAvatar?.takeIf { it.isNotEmpty() }
            ),
            count = count ?: 0
        )
    }
}
