package com.arkdo.android.services.notification

import com.arkdo.android.data.models.RawNotification
import com.arkdo.android.data.models.RawNotificationData
import com.arkdo.android.data.models.RawNotificationsResponse
import com.arkdo.android.data.models.NotificationItem
import com.arkdo.android.services.network.JsonNetworkClient
import com.arkdo.android.services.network.JsonRequestOptions
import com.arkdo.android.services.network.NetworkError
import com.arkdo.android.services.network.NetworkErrorCode
import com.arkdo.android.services.network.NetworkResult
import kotlinx.serialization.json.Json

// ==========================================================================
// 与 HarmonyOS 版 services/notification/NotificationApi.ets 逐字段对齐。
// 通知列表 / 标记已读 / 按主题反查已读。类型 → 图标/颜色/文案 映射完整保留。
// ==========================================================================

private const val LOG_TAG = "[ArkDO][NotificationApi]"
private const val MARK_READ_PATH = "/notifications/mark-read"

// Discourse notification_type 枚举(核心稳定值)。
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
private const val T_BOOST = 43            // linux.do 的 Boost 助力
private const val T_FOLLOWING = 800       // discourse-follow：有人关注了你
private const val T_FOLLOWING_CREATED_TOPIC = 801
private const val T_FOLLOWING_REPLIED = 802

// 角标配色(对齐设计稿)。
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
    val hasActor: Boolean // false=系统类(无发起人)，用系统图标圈代替头像
)

data class MarkReadByTopicResult(
    val marked: Boolean,
    val notificationType: Int
)

class NotificationApi(private val bridge: JsonNetworkClient) {

    /** 拉取通知列表。首次进页 recent=true(顺带 bump seen 清红点)；加载更多用 offset 不带 recent。 */
    suspend fun load(limit: Int, offset: Int, recent: Boolean): NetworkResult<List<NotificationItem>> {
        var path = "/notifications.json?limit=$limit&offset=$offset"
        if (recent) path += "&recent=true&bump_last_seen_reviewable=true"

        val response = bridge.getJson(path)
        if (!response.success) {
            return NetworkResult(
                success = false,
                statusCode = response.statusCode,
                challengeState = response.challengeState,
                error = response.error
            )
        }

        val items = mutableListOf<NotificationItem>()
        try {
            val payload = Json { ignoreUnknownKeys = true }
                .decodeFromString<RawNotificationsResponse>(response.data ?: "{}")
            val list = payload.notifications ?: emptyList()
            list.forEach { raw ->
                parseOne(raw)?.let { items.add(it) }
            }
        } catch (e: Exception) {
            return NetworkResult(
                success = false,
                statusCode = response.statusCode,
                challengeState = response.challengeState,
                error = NetworkError(
                    code = NetworkErrorCode.UNEXPECTED_RESPONSE,
                    message = "通知数据解析失败。",
                    requiresChallenge = false
                )
            )
        }

        timber.log.Timber.i("$LOG_TAG loaded count=%d recent=%b offset=%d", items.size, recent, offset)
        return NetworkResult(
            success = true,
            statusCode = response.statusCode,
            challengeState = response.challengeState,
            data = items
        )
    }

    /** 标记单条已读。 */
    suspend fun markRead(id: Long): Boolean {
        val response = bridge.putForm(MARK_READ_PATH, "id=$id", JsonRequestOptions(referrer = "https://linux.do/", notifyChallenge = false))
        return response.success
    }

    /**
     * 点系统推送打开帖子时，把该帖对应楼层那条"未读"通知标为已读。
     * 走 /notifications.json 反查 id 再 mark-read（timings 接口会被针对性挡下，不可靠）。
     * postNumber 缺省时按 topicId 匹配首条未读。
     */
    suspend fun markReadByTopic(topicId: Long, postNumber: Int? = null): MarkReadByTopicResult {
        if (topicId <= 0) return MarkReadByTopicResult(false, 0)
        val listed = load(30, 0, false)
        if (!listed.success || listed.data == null) return MarkReadByTopicResult(false, 0)

        val target = matchUnreadByTopic(listed.data, topicId, postNumber)
        if (target == null) return MarkReadByTopicResult(false, 0)

        val ok = markRead(target.id)
        return MarkReadByTopicResult(ok, target.type)
    }

    private fun matchUnreadByTopic(items: List<NotificationItem>, topicId: Long, postNumber: Int?): NotificationItem? {
        var topicFallback: NotificationItem? = null
        for (item in items) {
            if (item.read || item.topicId != topicId) continue
            if (postNumber != null && item.postNumber == postNumber) return item
            if (topicFallback == null) topicFallback = item
        }
        if (postNumber != null) return null
        return topicFallback
    }

    /** 全部标记已读(不带 id)。 */
    suspend fun markAllRead(): Boolean {
        val response = bridge.putForm(MARK_READ_PATH, "", JsonRequestOptions(referrer = "https://linux.do/", notifyChallenge = false))
        return response.success
    }

    private fun parseOne(raw: RawNotification): NotificationItem? {
        if (raw.id == null || raw.notificationType == null) return null
        val data = raw.data ?: RawNotificationData()
        val actor = resolveActor(raw, data)
        val visual = describe(raw.notificationType, data, raw.postNumber, actor.isNotEmpty())
        val actorName = if (visual.hasActor) actor else ""
        val title = resolveTitle(raw, data)
        val avatarTemplate = raw.actingUserAvatarTemplate?.takeIf { it.isNotEmpty() }

        return NotificationItem(
            id = raw.id,
            type = raw.notificationType,
            read = raw.read == true,
            createdAt = raw.createdAt ?: "",
            actorName = actorName,
            actorUsername = actor,
            avatarTemplate = avatarTemplate,
            actionText = visual.actionText,
            title = title,
            topicId = raw.topicId,
            postNumber = raw.postNumber,
            slug = raw.slug,
            iconId = visual.iconId,
            iconColor = visual.iconColor
        )
    }

    private fun resolveActor(raw: RawNotification, data: RawNotificationData): String {
        raw.actingUserName?.takeIf { it.isNotEmpty() }?.let { return it }
        data.displayUsername?.takeIf { it.isNotEmpty() }?.let { return it }
        data.originalUsername?.takeIf { it.isNotEmpty() }?.let { return it }
        data.username?.takeIf { it.isNotEmpty() }?.let { return it }
        return ""
    }

    private fun resolveTitle(raw: RawNotification, data: RawNotificationData): String {
        if (raw.notificationType == T_GRANTED_BADGE && !data.badgeName.isNullOrEmpty()) {
            return data.badgeName
        }
        raw.fancyTitle?.takeIf { it.isNotEmpty() }?.let { return it }
        data.topicTitle?.takeIf { it.isNotEmpty() }?.let { return it }
        data.badgeName?.takeIf { it.isNotEmpty() }?.let { return it }
        return ""
    }

    private fun postVerb(postNumber: Int?): String =
        if (postNumber != null && postNumber > 1) "回复了主题" else "发布了主题"

    private fun describe(type: Int, data: RawNotificationData, postNumber: Int?, hasActorHint: Boolean): NotificationVisual {
        if (type == T_FOLLOWING) return NotificationVisual("discourse-follow-new-follower", C_REPLY, "关注了你", true)
        if (type == T_FOLLOWING_CREATED_TOPIC) return NotificationVisual("discourse-follow-new-topic", C_REPLY, "发布了新主题", true)
        if (type == T_FOLLOWING_REPLIED) return NotificationVisual("discourse-follow-new-reply", C_REPLY, "回复了主题", true)
        if (type == T_CUSTOM) return NotificationVisual("comment", C_QUOTE, postVerb(postNumber), true)
        if (type == T_BOOST) return NotificationVisual("rocket", C_WATCH, "为你的帖子助力", true)
        if (type == T_POST_APPROVED) return NotificationVisual("check", C_APPROVED, "你的帖子已通过审核", false)
        if (type == T_REPLIED) return NotificationVisual("reply", C_REPLY, "回复了你", true)
        if (type == T_POSTED) return NotificationVisual("discourse-bell-exclamation", C_REPLY, "回复了主题", true)
        if (type == T_WATCHING_CATEGORY_OR_TAG) return NotificationVisual("discourse-bell-exclamation", C_REPLY, "发布了新主题", true)
        if (type == T_MENTIONED) return NotificationVisual("at", C_MENTION, "提到了你", true)
        if (type == T_GROUP_MENTIONED) {
            val group = data.groupName?.let { "@$it" } ?: "群组"
            return NotificationVisual("users", C_MENTION, "在 $group 提到了你", true)
        }
        if (type == T_LIKED || type == T_LIKED_CONSOLIDATED || type == T_REACTION) {
            return NotificationVisual("heart", C_LIKE, if (type == T_REACTION) "点了表情回应" else "赞了你的帖子", true)
        }
        if (type == T_QUOTED) return NotificationVisual("quote-right", C_QUOTE, "引用了你", true)
        if (type == T_LINKED || type == T_LINKED_CONSOLIDATED) return NotificationVisual("link", C_QUOTE, "引用了你的帖子", true)
        if (type == T_INVITED_TO_TOPIC) return NotificationVisual("hand-point-right", C_PM, "邀请你参与话题", true)
        if (type == T_PRIVATE_MESSAGE || type == T_INVITED_TO_PM) {
            return NotificationVisual("envelope", C_PM, if (type == T_INVITED_TO_PM) "邀请你参与私信" else "给你发了私信", true)
        }
        if (type == T_GROUP_MESSAGE_SUMMARY) return NotificationVisual("users", C_PM, "有新的群组私信", false)
        if (type == T_INVITEE_ACCEPTED) return NotificationVisual("user", C_PM, "接受了你的邀请", true)
        if (type == T_EDITED) return NotificationVisual("pencil", C_GRAY, "编辑了你的帖子", true)
        if (type == T_MOVED_POST) return NotificationVisual("right-from-bracket", C_GRAY, "移动了你的帖子", true)
        if (type == T_GRANTED_BADGE) return NotificationVisual("certificate", C_BADGE, "你获得了徽章", false)
        if (type == T_MEMBERSHIP_REQUEST_ACCEPTED) return NotificationVisual("user-plus", C_APPROVED, "通过了你的加群申请", false)
        if (type == T_MEMBERSHIP_REQUEST_CONSOLIDATED) return NotificationVisual("users", C_MENTION, "有新的加群申请", false)
        if (type == T_BOOKMARK_REMINDER) return NotificationVisual("discourse-bookmark-clock", C_BOOK, "书签提醒", false)
        if (type == T_TOPIC_REMINDER) return NotificationVisual("far-clock", C_WATCH, "主题提醒", false)
        if (type == T_VOTES_RELEASED) return NotificationVisual("plus", C_APPROVED, "你的投票已释放", false)
        if (type == T_NEW_FEATURES) return NotificationVisual("gift", C_REPLY, "有新功能上线", false)
        if (type == T_ADMIN_PROBLEMS) return NotificationVisual("triangle-exclamation", C_LIKE, "站点存在待处理问题", false)
        if (type == T_WATCHING_FIRST_POST) return NotificationVisual("discourse-bell-one", C_WATCH, "发布了新主题", true)
        // 智能兜底：未知类型但有发起人 → 当作帖子动态；无发起人才落到系统铃铛。
        if (hasActorHint) return NotificationVisual("comment", C_QUOTE, postVerb(postNumber), true)
        return NotificationVisual("bell", C_GRAY, "有一条新通知", false)
    }
}