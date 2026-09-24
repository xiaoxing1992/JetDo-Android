package com.arkdo.android.services.user

import com.arkdo.android.data.models.UserProfileDetail
import com.arkdo.android.data.models.EndorsableCategoriesResult
import com.arkdo.android.data.models.EndorsableCategory
import com.arkdo.android.data.models.UserSummary
import com.arkdo.android.data.models.UserActivityItem
import com.arkdo.android.data.models.UserActivityPage
import com.arkdo.android.data.models.SolvedPostPage
import com.arkdo.android.data.models.SolvedPostItem
import com.arkdo.android.data.models.UserBadgeGroup
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
    @SerialName("total_following") val totalFollowing: Int? = null,
    val status: com.arkdo.android.data.models.UserStatus? = null,
    @SerialName("category_expert_endorsements")
    val categoryExpertEndorsements: List<DiscourseEndorsementRecord>? = null
)

@Serializable
private data class DiscourseEndorsementRecord(
    @SerialName("category_id") val categoryId: Long? = null
)

@Serializable
private data class DiscourseEndorsableCategory(
    val id: Long? = null,
    val name: String? = null,
    val color: String? = null
)

@Serializable
private data class DiscourseEndorsableExtras(
    @SerialName("remaining_endorsements") val remainingEndorsements: Int? = null
)

@Serializable
private data class DiscourseEndorsableResponse(
    val categories: List<DiscourseEndorsableCategory>? = null,
    val extras: DiscourseEndorsableExtras? = null
)

@Serializable
private data class DiscourseSummaryResponse(
    @SerialName("user_summary") val userSummary: DiscourseUserSummary? = null,
    // 顶层徽章定义与话题字典(所有被引用话题,含热门回复所在话题)。
    val badges: List<DiscourseSummaryBadge>? = null,
    val topics: List<DiscourseSummaryTopic>? = null
)

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
    // 本用户的徽章授予记录:badge_id 对应顶层 badges 定义(计数来自这里)。
    @SerialName("badges") val grants: List<DiscourseBadgeGrant>? = null,
    // 热门话题 id 列表(顺序即展示顺序),详情在顶层 topics。
    @SerialName("topic_ids") val topicIds: List<Long>? = null,
    // 获赞最多回复:自身无标题,需按 topic_id 去顶层 topics 关联。
    val replies: List<DiscourseSummaryReply>? = null,
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
    @SerialName("image_url") val imageUrl: String? = null,
    val description: String? = null
)

@Serializable
private data class DiscourseBadgeGrant(
    @SerialName("badge_id") val badgeId: Long? = null,
    val count: Int? = null
)

@Serializable
private data class DiscourseSummaryTopic(
    val id: Long? = null,
    val title: String? = null,
    val slug: String? = null,
    @SerialName("like_count") val likeCount: Int? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("category_id") val categoryId: Long? = null
)

@Serializable
private data class DiscourseSummaryReply(
    @SerialName("topic_id") val topicId: Long? = null,
    @SerialName("post_number") val postNumber: Int? = null,
    @SerialName("like_count") val likeCount: Int? = null,
    @SerialName("created_at") val createdAt: String? = null
)

// most_*_users 一条:BasicUser 字段与 count 同级(扁平,非嵌套 user 对象),对齐鸿蒙 DiscourseUserWithCount。
@Serializable
private data class DiscourseInteraction(
    val id: Long? = null,
    val username: String? = null,
    val name: String? = null,
    @SerialName("avatar_template") val avatarTemplate: String? = null,
    @SerialName("animated_avatar") val animatedAvatar: String? = null,
    val count: Int? = null,
    @SerialName("flair_url") val flairUrl: String? = null,
    @SerialName("flair_bg_color") val flairBgColor: String? = null,
    @SerialName("flair_color") val flairColor: String? = null,
    @SerialName("flair_name") val flairName: String? = null
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
    // 动作发起人(自己的动态流与作者同一人),楼层作者头像用。
    @SerialName("acting_user_id") val actingUserId: Long? = null,
    @SerialName("acting_username") val actingUsername: String? = null,
    @SerialName("acting_name") val actingName: String? = null,
    @SerialName("acting_avatar_template") val actingAvatarTemplate: String? = null,
    val username: String? = null,
    val name: String? = null,
    @SerialName("avatar_template") val avatarTemplate: String? = null
)

@Serializable
private data class DiscourseUserBadgesResponse(
    val badges: List<DiscourseSummaryBadge>? = null,
    @SerialName("badge_types") val badgeTypes: List<DiscourseBadgeType>? = null,
    @SerialName("user_badges") val userBadges: List<DiscourseUserBadgeRecord>? = null
)

@Serializable
private data class DiscourseBadgeType(
    val id: Int? = null,
    val name: String? = null
)

@Serializable
private data class DiscourseUserBadgeRecord(
    @SerialName("badge_id") val badgeId: Long? = null,
    @SerialName("granted_at") val grantedAt: String? = null,
    val count: Int? = null
)

@Serializable
private data class DiscourseSolvedResponse(
    @SerialName("user_solved_posts") val userSolvedPosts: List<DiscourseSolvedPost>? = null
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
            val detail = UserProfileDetail(
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
                totalFollowing = source.totalFollowing,
                status = source.status
            )
            // discourse-category-experts:字段存在(哪怕空数组)= 插件启用,可认可。
            // 元素 category_id 是当前登录者已认可的分类(对齐鸿蒙 Array.isArray 判定)。
            source.categoryExpertEndorsements?.let { records ->
                detail.endorsementAvailable = true
                detail.endorsedCategoryIds = records.mapNotNull { it.categoryId }
            }
            NetworkResult(
                success = true,
                statusCode = response.statusCode,
                challengeState = response.challengeState,
                data = detail
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
            // 对齐鸿蒙映射:徽章取「顶层定义 ∩ 本用户授予计数」;热门话题按 topic_ids 顺序
            // 关联顶层 topics;热门回复 replies 自身无标题,按 topic_id 关联;互动圈各取前 5。
            val topicIndex = payload.topics.orEmpty().associateBy { it.id }
            val grantCounts = src.grants.orEmpty().mapNotNull { g ->
                g.badgeId?.let { it to (g.count ?: 0) }
            }.toMap()
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
                    topBadges = payload.badges.orEmpty().mapNotNull { b ->
                        if (b.id == null || b.name.isNullOrEmpty()) return@mapNotNull null
                        UserBadgeItem(
                            id = b.id,
                            name = b.name,
                            badgeTypeId = b.badgeTypeId ?: 3,
                            icon = b.icon?.takeIf { it.isNotEmpty() },
                            imageUrl = b.imageUrl?.takeIf { it.isNotEmpty() },
                            grantCount = grantCounts[b.id]?.takeIf { it > 0 }
                        )
                    }.take(8),
                    topTopics = src.topicIds.orEmpty().mapNotNull { id ->
                        val t = topicIndex[id] ?: return@mapNotNull null
                        if (t.title.isNullOrEmpty()) return@mapNotNull null
                        UserTopTopic(
                            topicId = id,
                            slug = t.slug,
                            title = t.title,
                            likeCount = t.likeCount ?: 0,
                            createdAt = t.createdAt,
                            categoryId = t.categoryId
                        )
                    }.take(6),
                    topReplies = src.replies.orEmpty().mapNotNull { r ->
                        val t = r.topicId?.let { topicIndex[it] } ?: return@mapNotNull null
                        if (t.title.isNullOrEmpty()) return@mapNotNull null
                        UserTopReply(
                            topicId = r.topicId ?: 0,
                            slug = t.slug,
                            topicTitle = t.title,
                            likeCount = r.likeCount ?: 0,
                            postNumber = r.postNumber ?: 1,
                            createdAt = r.createdAt,
                            categoryId = t.categoryId
                        )
                    }.take(6),
                    mostRepliedToUsers = src.mostRepliedToUsers.orEmpty().mapNotNull { it.toInteraction() }.take(5),
                    mostLikedUsers = src.mostLikedUsers.orEmpty().mapNotNull { it.toInteraction() }.take(5),
                    mostLikedByUsers = src.mostLikedByUsers.orEmpty().mapNotNull { it.toInteraction() }.take(5)
                )
            )
        } catch (_: Exception) {
            NetworkResults.unexpectedResponse(
                response.statusCode, response.challengeState, "站点用户摘要响应不是有效 JSON。"
            )
        }
    }

    /** 每页固定条数(服务端每页返回上限),据此判 hasMore(对齐鸿蒙 ACTIVITY_PAGE_SIZE)。 */
    private companion object {
        const val ACTIVITY_PAGE_SIZE = 30
        const val USER_ACTIONS_FILTER_ALL = "4,5"
    }

    /** 动态分页：/user_actions.json?offset=&username=&filter=。offset = 已加载条数。 */
    suspend fun getActivity(username: String, filter: String, offset: Int): NetworkResult<UserActivityPage> {
        val path = "/user_actions.json?offset=$offset&username=${java.net.URLEncoder.encode(username, "UTF-8")}&filter=$filter"
        val response = bridge.getJson(path)
        if (!response.success) {
            return NetworkResults.forwardFailure(response)
        }
        val data = response.data
            ?: return NetworkResults.unexpectedResponse(
                response.statusCode, response.challengeState, "站点用户活动响应为空。"
            )
        return try {
            val payload = json.decodeFromString<DiscourseUserActionsResponse>(data)
            val sourceActions = payload.user_actions.orEmpty()
            val items = sourceActions.mapNotNull { a -> mapActionToActivity(a) }
            NetworkResult(
                success = true,
                statusCode = response.statusCode,
                challengeState = response.challengeState,
                data = UserActivityPage(
                    items = items,
                    hasMore = sourceActions.size >= ACTIVITY_PAGE_SIZE
                )
            )
        } catch (_: Exception) {
            NetworkResults.unexpectedResponse(
                response.statusCode, response.challengeState, "站点用户活动响应不是有效 JSON。"
            )
        }
    }

    /** 最近动态(他人主页内容卡「最近」段用,取第一页)。 */
    suspend fun getRecentActivity(username: String): NetworkResult<List<UserActivityItem>> {
        val page = getActivity(username, USER_ACTIONS_FILTER_ALL, 0)
        if (!page.success || page.data == null) {
            return NetworkResult(
                success = page.success,
                statusCode = page.statusCode,
                challengeState = page.challengeState,
                data = null,
                error = page.error
            )
        }
        return NetworkResult(
            success = true,
            statusCode = page.statusCode,
            challengeState = page.challengeState,
            data = page.data.items
        )
    }

    /** 对齐鸿蒙 mapAction:作者 = acting_* (自己的动态流与作者同一人),摘要转纯文本。 */
    private fun mapActionToActivity(a: DiscourseUserAction): UserActivityItem? {
        if (a.actionType == null || a.topicId == null || a.title.isNullOrEmpty()) return null
        val item = UserActivityItem(
            actionType = a.actionType,
            topicId = a.topicId,
            postNumber = a.postNumber?.takeIf { it > 1 },
            title = a.title,
            excerpt = com.arkdo.android.services.content.CookedHtmlParser.toPlainText(a.excerpt ?: ""),
            categoryId = a.categoryId,
            tags = emptyList(),
            createdAt = a.createdAt,
            username = a.actingUsername ?: a.username,
            name = a.actingName ?: a.name,
            avatarTemplate = a.actingAvatarTemplate ?: a.avatarTemplate
        )
        item.author = User(
            id = a.actingUserId ?: 0,
            username = a.actingUsername ?: a.username ?: "",
            name = a.actingName ?: a.name,
            avatarTemplate = a.actingAvatarTemplate ?: a.avatarTemplate,
            animatedAvatar = null
        )
        return item
    }

    /** 已解决列表：/solution/by_user.json，对齐鸿蒙 getSolvedPosts。 */
    suspend fun getSolvedPosts(username: String, offset: Int, limit: Int = SOLVED_PAGE_SIZE): NetworkResult<SolvedPostPage> {
        val response = bridge.getJson(
            "/solution/by_user.json?username=${java.net.URLEncoder.encode(username, "UTF-8")}&offset=$offset&limit=$limit"
        )
        if (!response.success) {
            return NetworkResults.forwardFailure(response)
        }
        val data = response.data
            ?: return NetworkResults.unexpectedResponse(
                response.statusCode, response.challengeState, "站点解决方案响应为空。"
            )
        return try {
            val payload = json.decodeFromString<DiscourseSolvedResponse>(data)
            val source = payload.userSolvedPosts.orEmpty()
            val items = source.mapNotNull { p ->
                val title = p.topicTitle
                if (p.topicId == null || title.isNullOrEmpty()) return@mapNotNull null
                SolvedPostItem(
                    postId = p.postId ?: 0,
                    topicId = p.topicId,
                    postNumber = p.postNumber ?: 1,
                    topicTitle = title,
                    slug = p.slug?.takeIf { it.isNotEmpty() },
                    excerpt = solvedPlainText(p.excerpt),
                    categoryId = p.categoryId,
                    createdAt = p.createdAt?.takeIf { it.isNotEmpty() }
                )
            }
            NetworkResult(
                success = true,
                statusCode = response.statusCode,
                challengeState = response.challengeState,
                data = SolvedPostPage(items = items, hasMore = source.size >= limit)
            )
        } catch (_: Exception) {
            NetworkResults.unexpectedResponse(
                response.statusCode, response.challengeState, "站点解决方案响应不是有效 JSON。"
            )
        }
    }

    /** 关注(discourse-follow)。对齐鸿蒙 followUser:PUT /follow/{username}.json 空 body。 */
    suspend fun followUser(username: String): NetworkResult<Boolean> {
        val response = bridge.putJson("/follow/${java.net.URLEncoder.encode(username, "UTF-8")}.json")
        return asWriteResult(response)
    }

    /** 取消关注:DELETE /follow/{username}.json(对齐鸿蒙 unfollowUser)。 */
    suspend fun unfollowUser(username: String): NetworkResult<Boolean> {
        val response = bridge.deleteForm("/follow/${java.net.URLEncoder.encode(username, "UTF-8")}.json", "")
        return asWriteResult(response)
    }

    /** follow/unfollow 只判成败,响应体 {"success":"OK"} 不细解析。 */
    private fun asWriteResult(response: NetworkResult<String>): NetworkResult<Boolean> {
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

    /** 认可弹窗数据 GET /category-experts/endorsable-categories/{u}.json(对齐鸿蒙 getEndorsableCategories)。 */
    suspend fun getEndorsableCategories(username: String): NetworkResult<EndorsableCategoriesResult> {
        val path = "/category-experts/endorsable-categories/${java.net.URLEncoder.encode(username, "UTF-8")}.json"
        val response = bridge.getJson(path)
        if (!response.success) {
            return NetworkResults.forwardFailure(response)
        }
        val data = response.data
            ?: return NetworkResults.unexpectedResponse(
                response.statusCode, response.challengeState, "站点认可分类响应为空。"
            )
        return try {
            val payload = json.decodeFromString<DiscourseEndorsableResponse>(data)
            val categories = payload.categories.orEmpty().mapNotNull { c ->
                if (c.id == null || c.name.isNullOrEmpty()) return@mapNotNull null
                EndorsableCategory(id = c.id, name = c.name, color = c.color?.takeIf { it.isNotEmpty() })
            }
            val remaining = payload.extras?.remainingEndorsements ?: 0
            NetworkResult(
                success = true,
                statusCode = response.statusCode,
                challengeState = response.challengeState,
                data = EndorsableCategoriesResult(categories = categories, remainingEndorsements = remaining)
            )
        } catch (_: Exception) {
            NetworkResults.unexpectedResponse(
                response.statusCode, response.challengeState, "站点认可分类响应不是有效 JSON。"
            )
        }
    }

    /** 提交认可 PUT /category-experts/endorse/{u}.json,body categoryIds[]=id(可多选)。 */
    suspend fun endorseCategories(username: String, categoryIds: List<Long>): NetworkResult<Boolean> {
        if (categoryIds.isEmpty()) {
            return NetworkResult(
                success = false,
                statusCode = 0,
                challengeState = ChallengeState.NONE,
                error = com.arkdo.android.services.network.NetworkError(
                    code = com.arkdo.android.services.network.NetworkErrorCode.UNEXPECTED_RESPONSE,
                    message = "请至少选择一个类别。",
                    requiresChallenge = false
                )
            )
        }
        val body = categoryIds.joinToString("&") { "categoryIds[]=$it" }
        val path = "/category-experts/endorse/${java.net.URLEncoder.encode(username, "UTF-8")}.json"
        val response = bridge.putForm(path, body)
        return asWriteResult(response)
    }


    /** 全部徽章：/user-badges/{u}.json?grouped=true,按金/银/铜分组(对齐鸿蒙 mapUserBadges)。 */
    suspend fun getUserBadges(username: String): NetworkResult<List<UserBadgeGroup>> {
        val path = "/user-badges/${java.net.URLEncoder.encode(username, "UTF-8")}.json?grouped=true"
        val response = bridge.getJson(path)
        if (!response.success) {
            return NetworkResults.forwardFailure(response)
        }
        val data = response.data
            ?: return NetworkResults.unexpectedResponse(
                response.statusCode, response.challengeState, "站点徽章响应为空。"
            )
        return try {
            val payload = json.decodeFromString<DiscourseUserBadgesResponse>(data)
            NetworkResult(
                success = true,
                statusCode = response.statusCode,
                challengeState = response.challengeState,
                data = mapUserBadges(payload)
            )
        } catch (_: Exception) {
            NetworkResults.unexpectedResponse(
                response.statusCode, response.challengeState, "站点徽章响应不是有效 JSON。"
            )
        }
    }

    /** 对齐鸿蒙 mapUserBadges:按 user_badges 顺序遍历归桶、去重;组序 金(1)→银(2)→铜(3)。 */
    private fun mapUserBadges(payload: DiscourseUserBadgesResponse): List<UserBadgeGroup> {
        val records = payload.userBadges.orEmpty()
        if (records.isEmpty()) return emptyList()
        val defById = payload.badges.orEmpty().associateBy { it.id }
        val typeNames = payload.badgeTypes.orEmpty().associate { it.id to (it.name ?: "") }

        val bucketByType = LinkedHashMap<Int, MutableList<UserBadgeItem>>()
        val seen = HashSet<Long>()
        for (record in records) {
            val badgeId = record.badgeId ?: continue
            if (!seen.add(badgeId)) continue
            val def = defById[badgeId] ?: continue
            if (def.id == null || def.name.isNullOrEmpty()) continue
            val typeId = def.badgeTypeId ?: 3
            var item = UserBadgeItem(id = def.id, name = def.name, badgeTypeId = typeId)
            def.imageUrl?.takeIf { it.isNotEmpty() }?.let { u -> item = item.copy(imageUrl = u) }
                ?: def.icon?.takeIf { it.isNotEmpty() }?.let { ic -> item = item.copy(icon = ic) }
            def.description?.takeIf { it.isNotEmpty() }?.let { d -> item = item.copy(description = d) }
            (record.count ?: 0).takeIf { it > 1 }?.let { c -> item = item.copy(grantCount = c) }
            record.grantedAt?.takeIf { it.isNotEmpty() }?.let { g -> item = item.copy(grantedAt = g) }
            bucketByType.getOrPut(typeId) { mutableListOf() }.add(item)
        }
        val groups = mutableListOf<UserBadgeGroup>()
        bucketByType.keys.sorted().forEach { typeId ->
            val list = bucketByType[typeId] ?: return@forEach
            if (list.isNotEmpty()) {
                groups.add(
                    UserBadgeGroup(
                        typeId = typeId.toInt(),
                        typeName = badgeTypeName(typeId, typeNames),
                        badges = list
                    )
                )
            }
        }
        return groups
    }

    private fun badgeTypeName(typeId: Int, typeNames: Map<Int?, String>): String {
        val name = typeNames[typeId]
        if (!name.isNullOrEmpty()) return name
        return when (typeId) {
            1 -> "金牌"
            2 -> "银牌"
            else -> "铜牌"
        }
    }

    private fun DiscourseInteraction.toInteraction(): UserInteraction? {
        // 对齐鸿蒙 mapInteractions:username 空即丢弃;id 缺省 0。
        if (username.isNullOrEmpty()) return null
        return UserInteraction(
            user = User(
                id = id ?: 0,
                username = username,
                name = name,
                avatarTemplate = avatarTemplate,
                animatedAvatar = animatedAvatar?.takeIf { it.isNotEmpty() },
                flairUrl = flairUrl?.takeIf { it.isNotEmpty() },
                flairBgColor = flairBgColor?.takeIf { it.isNotEmpty() },
                flairColor = flairColor?.takeIf { it.isNotEmpty() },
                flairName = flairName?.takeIf { it.isNotEmpty() }
            ),
            count = count ?: 0
        )
    }
}

private const val SOLVED_PAGE_SIZE = 20

/** 对齐鸿蒙 HtmlText.toPlainText：去标签、解实体、空白压成一格。 */
internal fun solvedPlainText(html: String?): String {
    if (html.isNullOrBlank()) return ""
    var text = html.replace(Regex("<[^>]*>"), "")
    if (text.contains('&')) {
        text = text
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
        text = text.replace("&amp;", "&")
    }
    return text.replace(Regex("\\s+"), " ").trim()
}
