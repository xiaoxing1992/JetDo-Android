package com.arkdo.android.services.notification

import timber.log.Timber

// ==========================================================================
// 与 HarmonyOS 版 services/notification/NotificationEventBus.ets 逐字段对齐。
// 未读数事件总线：通知数据源（core/data）与 RootTabPage(会话事件)都发到这里，
// HomePage 订阅后驱动铃铛。保留 last 值，新订阅者可立即拿到当前数。
// ==========================================================================

private const val LOG_TAG = "[JetDo][NotificationBus]"

data class NotificationSnapshot(
    val unread: Int,
    val highPriority: Int
)

typealias NotificationListener = (NotificationSnapshot) -> Unit

object NotificationEventBus {
    private val listeners = mutableMapOf<Long, NotificationListener>()
    private var nextListenerId = 0L
    @Volatile
    private var last = NotificationSnapshot(0, 0)

    fun subscribe(listener: NotificationListener): () -> Unit {
        val id = nextListenerId++
        listeners[id] = listener
        // 新订阅者立即拿到当前数，避免首帧空窗。
        listener(last)
        return { listeners.remove(id) }
    }

    fun publish(snapshot: NotificationSnapshot) {
        last = snapshot
        val snapshot2 = listeners.values.toList()
        Timber.i("$LOG_TAG publish unread=%d highPriority=%d listeners=%d",
            snapshot.unread, snapshot.highPriority, snapshot2.size)
        snapshot2.forEach { l -> runCatching { l(snapshot) } }
    }

    fun current(): NotificationSnapshot = last

    /**
     * 标记已读一条通知后的乐观递减：仅当当前未读数 >0 才减 1。
     * 未读为 0（或轮询尚未拿到真实值的空窗期）时一律不动，宁等下次轮询权威值覆盖。
     */
    fun decrementUnread(highPriority: Boolean) {
        val cur = last
        if (cur.unread <= 0) return
        val nextHigh = if (highPriority && cur.highPriority > 0) cur.highPriority - 1 else cur.highPriority
        publish(NotificationSnapshot(cur.unread - 1, nextHigh))
    }
}