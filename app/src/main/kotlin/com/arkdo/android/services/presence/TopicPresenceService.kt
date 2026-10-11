package com.arkdo.android.services.presence

import com.arkdo.android.data.models.User
import com.arkdo.android.services.message.MessageBusData
import com.arkdo.android.services.message.MessageBusEvent
import com.arkdo.android.services.message.MessageBusPresenceUser
import com.arkdo.android.services.network.ArkWebNetworkBridge
import com.arkdo.android.services.network.NetworkResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import timber.log.Timber

// ==========================================================================
// 与 HarmonyOS 版 services/presence/TopicPresenceService.ets 对齐。
//
// 帖子详情页「谁正在回复」（presence 下行，只读）：
//   1) GET /presence/get 取初始在场用户 + last_message_id；
//   2) 经长轮询循环订阅 MessageBus 频道 /presence/discourse-presence/reply/<id>，
//      收 entering_users / leaving_user_ids 增量；
//   3) 本地维护在场列表，滤掉自己、截到 MAX_SHOWN，回调给页面驱动 ReplyingPresenceBar。
//
// 不做上行（不上报"我自己正在回复"）—— 鸿蒙侧同样只做了下行这一半。
// ==========================================================================

/** 展示上限：对齐 Discourse `presence_max_users_shown` 默认值 5。 */
internal const val PRESENCE_MAX_SHOWN = 5

private const val LOG_TAG = "[JetDo][Presence]"

/** GET /presence/get 单个频道的状态（Discourse presence.js:170-183）。 */
@Serializable
internal data class PresenceGetState(
    val users: List<MessageBusPresenceUser>? = null,
    @SerialName("last_message_id") val lastMessageId: Long? = null,
    val count: Int? = null
)

/** 一次初始同步的结果：在场用户 + 订阅起点游标。 */
internal data class PresenceSnapshot(
    val users: List<User>,
    val lastMessageId: Long
)

private val presenceJson = Json { ignoreUnknownKeys = true }

/**
 * 把 MessageBus/初始态里的 presence 用户映射成 [User]。
 * id 与 username 缺一不可（对齐鸿蒙 mapUser 的两项前置校验）——少了任何一个都画不出头像。
 */
internal fun mapPresenceUser(raw: MessageBusPresenceUser): User? {
    val id = raw.id ?: return null
    val username = raw.username ?: return null
    if (id <= 0L || username.isEmpty()) return null
    return User(
        id = id,
        username = username,
        name = raw.name,
        avatarTemplate = raw.avatarTemplate
    )
}

/**
 * 套用一条 presence 增量：先摘掉 leaving，再把 entering 里没见过的追加到尾部。
 *
 * 顺序即"进入顺序"（Discourse 的 entering_users 也是按进入先后给的），展示时按此顺序取前 N 个，
 * 所以新进的人必须追加到尾部而不是任何位置插队。
 */
internal fun mergePresenceUsers(
    current: List<User>,
    entering: List<MessageBusPresenceUser> = emptyList(),
    leaving: List<Long> = emptyList()
): List<User> {
    var list = current
    if (leaving.isNotEmpty()) {
        val leavingSet = leaving.toHashSet()
        list = list.filterNot { it.id in leavingSet }
    }
    if (entering.isNotEmpty()) {
        val seen = list.mapTo(HashSet()) { it.id }
        val additions = ArrayList<User>(entering.size)
        for (raw in entering) {
            val user = mapPresenceUser(raw) ?: continue
            if (seen.add(user.id)) additions.add(user)
        }
        if (additions.isNotEmpty()) list = list + additions
    }
    return list
}

/** 展示列表：滤掉自己，再截到 [max] 个（对齐鸿蒙 emit 的 filter + slice）。 */
internal fun visiblePresenceUsers(
    all: List<User>,
    me: Long,
    max: Int = PRESENCE_MAX_SHOWN
): List<User> = all.filter { it.id != me }.take(max)

/**
 * 解析 /presence/get 的响应体。形状是 `{"<channel>": {users:[...], last_message_id:N}}`，
 * 取不到该频道（或非 JSON）返回 null —— 调用方据此跳过同步、但仍照常订阅。
 */
internal fun parsePresenceGet(raw: String, channel: String): PresenceSnapshot? {
    val root = runCatching { presenceJson.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return null
    val channelElement = root[channel] ?: return null
    val state = runCatching {
        presenceJson.decodeFromJsonElement(PresenceGetState.serializer(), channelElement)
    }.getOrNull() ?: return null
    val users = state.users.orEmpty().mapNotNull(::mapPresenceUser)
    return PresenceSnapshot(users = users, lastMessageId = state.lastMessageId ?: -1L)
}

/**
 * 详情页「正在回复」下行服务。
 *
 * 生命周期由调用方（ViewModel）托管：attach 时 [start]，离开页面时 [stop]，
 * 长轮询循环重启（host reload / 前后台切换）后 [resync]。
 * 同一 topicId 重复 [start] 是幂等的（换帖才重建频道）。
 */
class TopicPresenceService(
    private val scopeProvider: () -> CoroutineScope,
    private val bridgeProvider: () -> ArkWebNetworkBridge,
    private val currentUserId: () -> Long,
    private val onChange: (List<User>) -> Unit
) {
    private var topicId: Long = 0L
    /** Discourse presence 频道名（不含 /presence 前缀）。 */
    private var presenceChannel: String = ""
    /** 对应的 MessageBus 频道名（含 /presence 前缀）。 */
    private var busChannel: String = ""
    private var started = false
    /** 全量在场用户（含自己，按进入顺序）。展示时再滤自己 + 截断。 */
    private var users: List<User> = emptyList()
    /** 序号：作废过期的 GET 响应（快速换帖 / 重同步时）。 */
    private var syncSeq = 0

    /** 是否正在跟踪某个话题。 */
    val isStarted: Boolean get() = started

    /** 进入某帖：确定频道名并同步一次。重复对同一帖调用安全（幂等）。 */
    fun start(topicId: Long) {
        if (started && this.topicId == topicId) return
        stop()
        started = true
        this.topicId = topicId
        presenceChannel = "/discourse-presence/reply/$topicId"
        busChannel = "/presence$presenceChannel"
        syncNow()
    }

    /** 离开 / 关闭：退订 MessageBus 频道、清空、作废在途 GET。 */
    fun stop() {
        if (!started) return
        bridgeProvider().unsubscribeChannel(busChannel)
        started = false
        topicId = 0L
        presenceChannel = ""
        busChannel = ""
        users = emptyList()
        syncSeq++
        emit()
    }

    /**
     * 长轮询循环重启（config-ready）后重同步：重取初始态并重订阅。
     * 频道会随脚本内 state 重建而丢失，只靠初次订阅接不上后续增量。
     */
    fun resync() {
        if (!started) return
        syncNow()
    }

    /** MessageBus 事件入口：只认本帖 presence 频道上的增量消息。 */
    fun handleBusEvent(event: MessageBusEvent) {
        if (!started || event.type != "message" || event.channel != busChannel) return
        val data: MessageBusData = event.data ?: return
        val merged = mergePresenceUsers(
            current = users,
            entering = data.enteringUsers.orEmpty(),
            leaving = data.leavingUserIds.orEmpty()
        )
        if (merged == users) return
        users = merged
        emit()
    }

    /**
     * 取初始态并订阅增量。GET 回来后用 last_message_id 作订阅起点，
     * 闭合「GET 返回」与「订阅生效」之间那段窗口（否则这段时间进入的人会漏掉）。
     */
    private fun syncNow() {
        val seq = ++syncSeq
        val channel = presenceChannel
        val bus = busChannel
        val path = "/presence/get?channels%5B%5D=${encodeRFC3986(channel)}"
        scopeProvider().launch {
            val response: NetworkResult<String> = bridgeProvider().getJson(path)
            // 已过期或已停：丢弃这次结果（换帖了 / 已离页）。
            if (seq != syncSeq || !started) return@launch
            var lastId = -1L
            val raw = response.data
            if (response.success && raw != null) {
                val snapshot = parsePresenceGet(raw, channel)
                if (snapshot != null) {
                    users = snapshot.users
                    lastId = snapshot.lastMessageId
                }
            } else {
                Timber.i("[JetDo][Presence] /presence/get failed status=%s", response.statusCode)
            }
            // 订阅增量频道（挂进运行中的长轮询循环）。起点 = last_message_id（缺则 -1 只收新）。
            bridgeProvider().subscribeChannel(bus, lastId)
            Timber.i(
                "[JetDo][Presence] synced topicId=%s initialUsers=%d lastId=%d",
                topicId, users.size, lastId
            )
            emit()
        }
    }

    /** 计算展示列表：滤掉自己、截到 [PRESENCE_MAX_SHOWN]，回调页面。 */
    private fun emit() {
        onChange(visiblePresenceUsers(users, currentUserId()))
    }
}

/**
 * 频道路径参数用 RFC3986 百分号编码。
 *
 * 不能用 `URLEncoder.encode`：它按 `application/x-www-form-urlencoded` 把空格编成 `+`，
 * 而 `/` 这类保留字符不能整体编码（频道里就有斜杠）。这里只编码非 unreserved 字符，
 * 与鸿蒙 encodeURIComponent 的语义一致。
 */
internal fun encodeRFC3986(value: String): String {
    val unreserved = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"
    val out = StringBuilder(value.length * 3)
    for (byte in value.toByteArray(Charsets.UTF_8)) {
        val char = byte.toInt().toChar()
        if (char in unreserved) {
            out.append(char)
        } else {
            out.append('%')
            out.append("0123456789ABCDEF"[(byte.toInt() shr 4) and 0xF])
            out.append("0123456789ABCDEF"[byte.toInt() and 0xF])
        }
    }
    return out.toString()
}
