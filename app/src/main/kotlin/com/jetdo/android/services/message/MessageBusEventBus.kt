package com.jetdo.android.services.message

import com.jetdo.android.services.challenge.ChallengeEventBus
import com.jetdo.android.services.network.ChallengeState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

// ==========================================================================
// 与 HarmonyOS 版 services/message/MessageBusEventBus.ets 对齐。
// MessageBus 长轮询事件总线：解析注入脚本上抛的 payload，广播给订阅方。
// ==========================================================================

/** Presence 频道 payload 里的用户（BasicUserSerializer：id/username/name/avatar_template）。 */
@Serializable
data class MessageBusPresenceUser(
    @SerialName("id") val id: Long? = null,
    @SerialName("username") val username: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("avatar_template") val avatarTemplate: String? = null
)

/**
 * MessageBus 频道 payload 的可选字段。
 * /notification/<id> 用 all_unread_notifications_count（总未读，含私信）；
 * /topic/<id> 用 id/post_number/type（新楼 'created'）；
 * /presence/<channel> 用 entering_users/leaving_user_ids/count_delta。
 */
@Serializable
data class MessageBusData(
    @SerialName("topic_id") val topicId: Long? = null,
    @SerialName("message_type") val messageType: String? = null,
    @SerialName("entering_users") val enteringUsers: List<MessageBusPresenceUser>? = null,
    @SerialName("leaving_user_ids") val leavingUserIds: List<Long>? = null,
    @SerialName("count_delta") val countDelta: Int? = null,
    @SerialName("all_unread_notifications_count") val allUnreadNotificationsCount: Int? = null,
    @SerialName("unread_notifications") val unreadNotifications: Int? = null,
    @SerialName("unread_high_priority_notifications") val unreadHighPriorityNotifications: Int? = null,
    @SerialName("id") val id: Long? = null,
    @SerialName("post_number") val postNumber: Int? = null,
    @SerialName("type") val type: String? = null,
    @SerialName("payload") val payload: MessageBusTopicPayload? = null
)

/** /latest 与 /new 消息里嵌套的话题字段。 */
@Serializable
data class MessageBusTopicPayload(
    @SerialName("category_id") val categoryId: Long? = null,
    @SerialName("created_at") val createdAt: String? = null
)

/** MessageBus 事件模型。 */
@Serializable
data class MessageBusEvent(
    val type: String? = null,
    val channel: String? = null,
    @SerialName("message_id") val messageId: Long? = null,
    val data: MessageBusData? = null,
    @SerialName("statusCode") val statusCode: Int? = null,
    val stage: String? = null,
    val message: String? = null
)

typealias MessageBusListener = (MessageBusEvent) -> Unit

const val LATEST_TOPICS_CHANNEL = "/latest"
const val NEW_TOPICS_CHANNEL = "/new"

/**
 * 首页「新的或更新的话题」用的话题 id。
 * /latest 只认 latest 与 new_topic，/new 只认 new_topic。静音类消息和别的频道都不算。
 */
fun extractIncomingHomeTopicId(event: MessageBusEvent?): Long? {
    if (event?.type != "message" || event.data == null) return null
    val allowed = when (event.channel) {
        NEW_TOPICS_CHANNEL -> setOf("new_topic")
        LATEST_TOPICS_CHANNEL -> setOf("latest", "new_topic")
        else -> return null
    }
    if (event.data.messageType !in allowed) return null
    val topicId = event.data.topicId ?: return null
    return topicId.takeIf { it > 0 }
}

/** 从 /topic/<id> 频道事件里取话题 id。 */
fun extractMessageBusTopicId(event: MessageBusEvent?): Long? {
    if (event?.type != "message" || event.data == null) return null
    if (!event.channel.orEmpty().startsWith("/topic/")) return null
    val topicId = event.data?.topicId ?: return null
    if (topicId <= 0) return null
    return topicId
}

/** 从 /topic/<id> 频道事件里取"新建帖子"的 post id：仅 type==='created' 且 data.id 为正整数时返回。 */
fun extractCreatedPostId(event: MessageBusEvent?): Long? {
    if (event?.type != "message" || event.data == null || event.data.type != "created") return null
    val postId = event.data?.id ?: return null
    if (postId <= 0) return null
    return postId
}

object MessageBusEventBus {
    private val listeners = mutableMapOf<Long, MessageBusListener>()
    private var nextListenerId = 0L
    private val json = Json { ignoreUnknownKeys = true }

    fun subscribe(listener: MessageBusListener): () -> Unit {
        val id = nextListenerId++
        listeners[id] = listener
        return { listeners.remove(id) }
    }

    /**
     * 解析并广播一条 payload（来自注入的 MessageBus 长轮询脚本）。
     * type === 'challenge' 时转 ChallengeEventBus.notifyChallengeRequired。
     */
    fun publishPayload(payload: String) {
        val event = runCatching {
            json.decodeFromString<MessageBusEvent>(payload)
        }.getOrElse {
            Timber.w(it, "[JetDo][MessageBus] payload parse failed len=%d", payload.length)
            return
        }

        if (event.type == "challenge") {
            ChallengeEventBus.notifyChallengeRequired(
                event.channel ?: "/message-bus",
                event.statusCode ?: 0,
                ChallengeState.REQUIRED
            )
        }

        listeners.values.toList().forEach { l ->
            runCatching { l(event) }.onFailure {
                Timber.e(it, "[JetDo][MessageBus] listener threw")
            }
        }
    }
}
