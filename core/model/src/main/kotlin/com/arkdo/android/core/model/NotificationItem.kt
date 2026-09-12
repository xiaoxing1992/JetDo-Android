package com.arkdo.android.core.model

/** Presentation-neutral notification model shared by data and notification UI. */
data class NotificationItem(
    val id: Long,
    val type: Int,
    val read: Boolean,
    val createdAt: String,
    val actorName: String,
    val actorUsername: String,
    val avatarTemplate: String? = null,
    val actionText: String,
    val title: String,
    val topicId: Long? = null,
    val postNumber: Int? = null,
    val slug: String? = null,
    val iconId: String,
    val iconColor: String
)
