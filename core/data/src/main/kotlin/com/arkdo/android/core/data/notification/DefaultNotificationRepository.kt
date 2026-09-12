package com.arkdo.android.core.data.notification

import com.arkdo.android.core.model.NotificationItem
import com.arkdo.android.core.network.JsonNetworkClient
import com.arkdo.android.core.network.JsonRequestOptions
import com.arkdo.android.core.network.NetworkResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val MARK_READ_PATH = "/notifications/mark-read"
private const val REFERRER = "https://linux.do/"

private const val T_MENTIONED = 1
private const val T_REPLIED = 2
private const val T_QUOTED = 3
private const val T_EDITED = 4
private const val T_LIKED = 5
private const val T_PRIVATE_MESSAGE = 6
private const val T_INVITED_TO_PM = 7
private const val T_INVITEE_ACCEPTED = 8
private const val T_POSTED = 9
private const val T_MOVED_POST = 10
private const val T_LINKED = 11
private const val T_GRANTED_BADGE = 12
private const val T_INVITED_TO_TOPIC = 13
private const val T_CUSTOM = 14
private const val T_GROUP_MENTIONED = 15
private const val T_GROUP_MESSAGE_SUMMARY = 16
private const val T_WATCHING_FIRST_POST = 17
private const val T_TOPIC_REMINDER = 18
private const val T_LIKED_CONSOLIDATED = 19
private const val T_POST_APPROVED = 20
private const val T_MEMBERSHIP_REQUEST_ACCEPTED = 22
private const val T_MEMBERSHIP_REQUEST_CONSOLIDATED = 23
private const val T_BOOKMARK_REMINDER = 24
private const val T_REACTION = 25
private const val T_VOTES_RELEASED = 26
private const val T_WATCHING_CATEGORY_OR_TAG = 36
private const val T_NEW_FEATURES = 37
private const val T_ADMIN_PROBLEMS = 38
private const val T_LINKED_CONSOLIDATED = 39
private const val T_BOOST = 43
private const val T_FOLLOWING = 800
private const val T_FOLLOWING_CREATED_TOPIC = 801
private const val T_FOLLOWING_REPLIED = 802

private const val C_REPLY = "#3B82F6"
private const val C_LIKE = "#F5455C"
private const val C_MENTION = "#6366F1"
private const val C_PM = "#8B5CF6"
private const val C_BADGE = "#E0A100"
private const val C_BOOK = "#FB923C"
private const val C_QUOTE = "#64748B"
private const val C_WATCH = "#FBBF24"
private const val C_GRAY = "#9CA3AF"
private const val C_APPROVED = "#00C950"

private data class NotificationVisual(
    val iconId: String,
    val iconColor: String,
    val actionText: String,
    val hasActor: Boolean
)

@Serializable
private data class RawNotificationData(
    @SerialName("topic_title") val topicTitle: String? = null,
    @SerialName("display_username") val displayUsername: String? = null,
    @SerialName("original_username") val originalUsername: String? = null,
    @SerialName("username") val username: String? = null,
    @SerialName("group_name") val groupName: String? = null,
    @SerialName("badge_name") val badgeName: String? = null
)

@Serializable
private data class RawNotification(
    val id: Long? = null,
    @SerialName("notification_type") val notificationType: Int? = null,
    val read: Boolean? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("post_number") val postNumber: Int? = null,
    @SerialName("topic_id") val topicId: Long? = null,
    @SerialName("fancy_title") val fancyTitle: String? = null,
    val slug: String? = null,
    @SerialName("acting_user_avatar_template") val actingUserAvatarTemplate: String? = null,
    @SerialName("acting_user_name") val actingUserName: String? = null,
    val data: RawNotificationData? = null
)

@Serializable
private data class RawNotificationsResponse(
    val notifications: List<RawNotification> = emptyList()
)

class DefaultNotificationRepository(
    private val client: JsonNetworkClient,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : NotificationRepository {

    override suspend fun load(limit: Int, offset: Int, recent: Boolean): NotificationPageResult {
        val recentQuery = if (recent) "&recent=true&bump_last_seen_reviewable=true" else ""
        val response = client.getJson("/notifications.json?limit=$limit&offset=$offset$recentQuery")
        if (!response.success) return response.failure("通知加载失败")

        return runCatching {
            val payload = json.decodeFromString<RawNotificationsResponse>(response.data ?: "{}")
            NotificationPage(payload.notifications.mapNotNull(::mapNotification))
        }.fold(
            onSuccess = { NotificationPageResult.Success(it) },
            onFailure = { response.failure("通知数据解析失败") }
        )
    }

    override suspend fun markRead(id: Long): Boolean = client.putForm(
        MARK_READ_PATH,
        "id=$id",
        JsonRequestOptions(referrer = REFERRER, notifyChallenge = false)
    ).success

    override suspend fun markAllRead(): Boolean = client.putForm(
        MARK_READ_PATH,
        "",
        JsonRequestOptions(referrer = REFERRER, notifyChallenge = false)
    ).success

    private fun mapNotification(raw: RawNotification): NotificationItem? {
        val id = raw.id ?: return null
        val type = raw.notificationType ?: return null
        val data = raw.data ?: RawNotificationData()
        val actor = raw.actingUserName.orEmpty()
            .ifEmpty { data.displayUsername.orEmpty() }
            .ifEmpty { data.originalUsername.orEmpty() }
            .ifEmpty { data.username.orEmpty() }
        val visual = describe(type, data, raw.postNumber, actor.isNotEmpty())
        return NotificationItem(
            id = id,
            type = type,
            read = raw.read == true,
            createdAt = raw.createdAt.orEmpty(),
            actorName = if (visual.hasActor) actor else "",
            actorUsername = actor,
            avatarTemplate = raw.actingUserAvatarTemplate?.takeIf(String::isNotEmpty),
            actionText = visual.actionText,
            title = when {
                type == T_GRANTED_BADGE && !data.badgeName.isNullOrEmpty() -> data.badgeName
                !raw.fancyTitle.isNullOrEmpty() -> raw.fancyTitle
                !data.topicTitle.isNullOrEmpty() -> data.topicTitle
                else -> data.badgeName.orEmpty()
            },
            topicId = raw.topicId,
            postNumber = raw.postNumber,
            slug = raw.slug,
            iconId = visual.iconId,
            iconColor = visual.iconColor
        )
    }

    private fun postVerb(postNumber: Int?): String =
        if (postNumber != null && postNumber > 1) "回复了主题" else "发布了主题"

    private fun describe(type: Int, data: RawNotificationData, postNumber: Int?, hasActor: Boolean): NotificationVisual {
        return when (type) {
            T_FOLLOWING -> NotificationVisual("discourse-follow-new-follower", C_REPLY, "关注了你", true)
            T_FOLLOWING_CREATED_TOPIC -> NotificationVisual("discourse-follow-new-topic", C_REPLY, "发布了新主题", true)
            T_FOLLOWING_REPLIED -> NotificationVisual("discourse-follow-new-reply", C_REPLY, "回复了主题", true)
            T_CUSTOM -> NotificationVisual("comment", C_QUOTE, postVerb(postNumber), true)
            T_BOOST -> NotificationVisual("rocket", C_WATCH, "为你的帖子助力", true)
            T_POST_APPROVED -> NotificationVisual("check", C_APPROVED, "你的帖子已通过审核", false)
            T_REPLIED -> NotificationVisual("reply", C_REPLY, "回复了你", true)
            T_POSTED, T_WATCHING_CATEGORY_OR_TAG -> NotificationVisual("discourse-bell-exclamation", C_REPLY, if (type == T_POSTED) "回复了主题" else "发布了新主题", true)
            T_MENTIONED, T_GROUP_MENTIONED -> NotificationVisual("at", C_MENTION, if (type == T_GROUP_MENTIONED) "在 @${data.groupName ?: "群组"} 提到了你" else "提到了你", true)
            T_LIKED, T_LIKED_CONSOLIDATED, T_REACTION -> NotificationVisual("heart", C_LIKE, if (type == T_REACTION) "点了表情回应" else "赞了你的帖子", true)
            T_QUOTED -> NotificationVisual("quote-right", C_QUOTE, "引用了你", true)
            T_LINKED, T_LINKED_CONSOLIDATED -> NotificationVisual("link", C_QUOTE, "引用了你的帖子", true)
            T_INVITED_TO_TOPIC -> NotificationVisual("hand-point-right", C_PM, "邀请你参与话题", true)
            T_PRIVATE_MESSAGE, T_INVITED_TO_PM -> NotificationVisual("envelope", C_PM, if (type == T_INVITED_TO_PM) "邀请你参与私信" else "给你发了私信", true)
            T_GROUP_MESSAGE_SUMMARY -> NotificationVisual("users", C_PM, "有新的群组私信", false)
            T_INVITEE_ACCEPTED -> NotificationVisual("user", C_PM, "接受了你的邀请", true)
            T_EDITED -> NotificationVisual("pencil", C_GRAY, "编辑了你的帖子", true)
            T_MOVED_POST -> NotificationVisual("right-from-bracket", C_GRAY, "移动了你的帖子", true)
            T_GRANTED_BADGE -> NotificationVisual("certificate", C_BADGE, "你获得了徽章", false)
            T_MEMBERSHIP_REQUEST_ACCEPTED -> NotificationVisual("user-plus", C_APPROVED, "通过了你的加群申请", false)
            T_MEMBERSHIP_REQUEST_CONSOLIDATED -> NotificationVisual("users", C_MENTION, "有新的加群申请", false)
            T_BOOKMARK_REMINDER -> NotificationVisual("discourse-bookmark-clock", C_BOOK, "书签提醒", false)
            T_TOPIC_REMINDER -> NotificationVisual("far-clock", C_WATCH, "主题提醒", false)
            T_VOTES_RELEASED -> NotificationVisual("plus", C_APPROVED, "你的投票已释放", false)
            T_NEW_FEATURES -> NotificationVisual("gift", C_REPLY, "有新功能上线", false)
            T_ADMIN_PROBLEMS -> NotificationVisual("triangle-exclamation", C_LIKE, "站点存在待处理问题", false)
            T_WATCHING_FIRST_POST -> NotificationVisual("discourse-bell-one", C_WATCH, "发布了新主题", true)
            else -> if (hasActor) NotificationVisual("comment", C_QUOTE, postVerb(postNumber), true)
            else NotificationVisual("bell", C_GRAY, "有一条新通知", false)
        }
    }

    private fun NetworkResult<String>.failure(defaultMessage: String): NotificationPageResult.Failure =
        NotificationPageResult.Failure(error?.message ?: defaultMessage, error?.requiresChallenge == true)
}
