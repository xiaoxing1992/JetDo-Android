package com.arkdo.android.core.data.notification

import com.arkdo.android.core.model.NotificationItem

interface NotificationRepository {
    suspend fun load(limit: Int, offset: Int, recent: Boolean): NotificationPageResult
    suspend fun markRead(id: Long): Boolean
    suspend fun markAllRead(): Boolean
}

data class NotificationPage(val items: List<NotificationItem>)

sealed interface NotificationPageResult {
    data class Success(val page: NotificationPage) : NotificationPageResult
    data class Failure(val message: String, val requiresChallenge: Boolean) : NotificationPageResult
}

interface NotificationUnreadStore {
    fun publish(unread: Int, highPriority: Int)
    fun decrement(highPriority: Boolean)
    fun currentHighPriority(): Int
}
