package com.arkdo.android.services.notification

import com.arkdo.android.data.models.User
import com.arkdo.android.services.auth.AuthService
import com.arkdo.android.services.message.MessageBusData
import com.arkdo.android.services.message.MessageBusEvent
import com.arkdo.android.services.message.MessageBusEventBus
import com.arkdo.android.services.network.JsonNetworkClient
import com.arkdo.android.services.network.NetworkResult
import com.arkdo.android.services.session.AppStateEventBus
import com.arkdo.android.services.session.SessionEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

// ==========================================================================
// 与 HarmonyOS 版 services/notification/NotificationService.ets 逐字段对齐。
//
// 头像未读数轮询服务：登录后由 RootTabPage start()，前台时每 POLL_INTERVAL 拉一次
// /session/current.json（复用 AuthService），解析未读并发到 NotificationEventBus。
// 特意"静默"：不经过 RootTabPage.checkCurrentUser 那条会更新 isCheckingSession 的路径。
// 实时通道（MessageBus /notification/<id>）是主路径，轮询只作校正兜底。
//
// 常量：POLL_INTERVAL_BASE_MS=300000、POLL_JITTER_RATIO=0.2、
//       MESSAGE_BUS_REFRESH_DEBOUNCE_MS=1500、NOTIFICATION_CHANNEL_PREFIX='/notification/'
// ==========================================================================

// 私信(6)/邀请进私信(7)属高优先级未读。
private val HIGH_PRIORITY_TYPES = listOf(6, 7)

private const val LOG_TAG = "[ArkDO][Notification]"
private const val POLL_INTERVAL_BASE_MS = 300_000L
private const val POLL_JITTER_RATIO = 0.2
private const val MESSAGE_BUS_REFRESH_DEBOUNCE_MS = 1_500L
private const val NOTIFICATION_CHANNEL_PREFIX = "/notification/"

class NotificationService(
    bridge: JsonNetworkClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
) {
    private val authService = AuthService(bridge)
    private val api = NotificationApi(bridge)

    private var started = false
    private var pollJob: Job? = null
    private var messageBusRefreshJob: Job? = null

    // 请求序号：丢弃过期/stop 后返回的响应。
    private var refreshSequence = 0L

    /** 点系统推送打开帖子时调用：把该帖对应楼层那条未读通知标已读，乐观减铃铛未读。 */
    suspend fun markPushNotificationRead(topicId: Long, postNumber: Int? = null) {
        runCatching {
            val result = api.markReadByTopic(topicId, postNumber)
            if (!result.marked) return
            val highPriority = HIGH_PRIORITY_TYPES.contains(result.notificationType)
            NotificationEventBus.decrementUnread(highPriority)
        }.onFailure {
            Timber.i("$LOG_TAG markPushNotificationRead failed topicId=%d error=%s", topicId, it.message)
        }
    }

    /** 登录成功/会话确认后调用。重复调用安全：已启动则只补拉一次。 */
    fun start() {
        if (started) {
            scope.launch { refresh() }
            return
        }
        started = true
        // 订阅实时通知频道：/notification/<id> 有消息即去抖后拉一次权威未读数。
        MessageBusEventBus.subscribe(::handleMessageBusEvent)
        Timber.i("$LOG_TAG start foreground=%b", AppStateEventBus.isForeground())
        if (AppStateEventBus.isForeground()) {
            scope.launch { refresh() }
            startInterval()
        }
    }

    /** 登出/会话失效时调用：停表、未读清零。 */
    fun stop() {
        if (!started) return
        started = false
        stopInterval()
        refreshSequence++ // 作废进行中的请求
        Timber.i("$LOG_TAG stop")
        NotificationEventBus.publish(NotificationSnapshot(0, 0))
    }

    /** 拉一次未读数并广播。网络/验证失败时保留上次值(不清零)，仅登出才清零。 */
    suspend fun refresh() {
        val seq = ++refreshSequence
        val result: NetworkResult<User?> = authService.getCurrentUser()
        if (seq != refreshSequence || !started) return

        if (!result.success) {
            Timber.i("$LOG_TAG refresh failed status=%d challenge=%s", result.statusCode, result.challengeState)
            return
        }

        if (result.data == null) {
            // 会话已失效(登出)：清零，并通知根页面清掉 currentUser。
            NotificationEventBus.publish(NotificationSnapshot(0, 0))
            SessionEventBus.notifySessionChanged()
            return
        }

        val user = result.data
        val unread = user.unreadNotifications ?: 0
        val highPriority = user.unreadHighPriorityNotifications ?: 0
        NotificationEventBus.publish(
            NotificationSnapshot(
                unread = unread,
                highPriority = highPriority
            )
        )
    }

    /** 实时通知频道事件：只认 /notification/<id> 上的 message，payload 直接带权威未读数。 */
    private fun handleMessageBusEvent(event: MessageBusEvent) {
        if (!started || event.type != "message") return
        val channel = event.channel ?: return
        if (!channel.startsWith(NOTIFICATION_CHANNEL_PREFIX)) return

        val data = event.data
        if (data != null && data.allUnreadNotificationsCount is Int) {
            // 直接采信 payload：作废任何在途的轮询响应，再广播权威值。
            val highPriority = data.unreadHighPriorityNotifications ?: 0
            refreshSequence++
            NotificationEventBus.publish(
                NotificationSnapshot(data.allUnreadNotificationsCount, highPriority)
            )
            Timber.i("$LOG_TAG messageBus notification unread=%d highPriority=%d",
                data.allUnreadNotificationsCount, highPriority)
            return
        }
        // payload 缺总未读字段(异常/旧版)：退回去抖后拉一次权威计数。
        Timber.i("$LOG_TAG messageBus notification without counts, fallback to refresh channel=%s", channel)
        if (messageBusRefreshJob?.isActive == true) return
        messageBusRefreshJob = scope.launch {
            delay(MESSAGE_BUS_REFRESH_DEBOUNCE_MS)
            messageBusRefreshJob = null
            refresh()
        }
    }

    private fun startInterval() {
        if (pollJob?.isActive == true) return
        scheduleNextPoll()
    }

    private fun scheduleNextPoll() {
        pollJob?.cancel()
        pollJob = scope.launch {
            delay(nextPollDelayMs())
            refresh()
            if (isActive) scheduleNextPoll()
        }
    }

    private fun nextPollDelayMs(): Long {
        val jitter = (Math.random() * 2 - 1) * POLL_JITTER_RATIO
        return (POLL_INTERVAL_BASE_MS * (1 + jitter)).toLong()
    }

    private fun stopInterval() {
        pollJob?.cancel()
        pollJob = null
    }
}