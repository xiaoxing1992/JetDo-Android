package com.arkdo.android.data.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ==========================================================================
// 与 HarmonyOS 版 models/Notification.ets 逐字段对齐。
// 来源：/notifications.json
// ==========================================================================

/** RawNotification.data 的可选字段。 */
@Serializable
data class RawNotificationData(
    @SerialName("topic_title") val topicTitle: String? = null,
    @SerialName("display_username") val displayUsername: String? = null,
    @SerialName("original_username") val originalUsername: String? = null,
    @SerialName("username") val username: String? = null,
    @SerialName("group_name") val groupName: String? = null,
    @SerialName("badge_name") val badgeName: String? = null,
    @SerialName("badge_slug") val badgeSlug: String? = null,
    @SerialName("badge_id") val badgeId: Long? = null,
    @SerialName("count") val count: Int? = null
)

/** 原始通知。 */
@Serializable
data class RawNotification(
    @SerialName("id") val id: Long? = null,
    @SerialName("notification_type") val notificationType: Int? = null,
    @SerialName("read") val read: Boolean? = null,
    @SerialName("high_priority") val highPriority: Boolean? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("post_number") val postNumber: Int? = null,
    @SerialName("topic_id") val topicId: Long? = null,
    @SerialName("fancy_title") val fancyTitle: String? = null,
    @SerialName("slug") val slug: String? = null,
    @SerialName("acting_user_avatar_template") val actingUserAvatarTemplate: String? = null,
    @SerialName("acting_user_name") val actingUserName: String? = null,
    @SerialName("data") val data: RawNotificationData? = null
)

@Serializable
data class RawNotificationsResponse(
    @SerialName("notifications") val notifications: List<RawNotification> = emptyList(),
    @SerialName("total_rows_notifications") val totalRows: Int? = null,
    @SerialName("seen_notification_id") val seenNotificationId: Long? = null
)

/**
 * 通知的展示模型：解析时已把类型映射成图标/颜色/文案，行组件直接读取。
 */
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
