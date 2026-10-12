package com.jetdo.android.ui.notification

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.jetdo.android.feature.notification.MessagesRoute
import com.jetdo.android.feature.notification.NotificationColors
import com.jetdo.android.services.session.CurrentUserHolder
import com.jetdo.android.services.session.LoginGate
import com.jetdo.android.ui.theme.jetdoColors

/**
 * 「消息」一级 tab 的宿主入口。
 *
 * 与 [NotificationScreen] 的差别：这里传 username（私信段要用），且不带 showTopBar ——
 * 标题与分段由 [MessagesRoute] 自己画。
 */
@Composable
fun MessagesScreen(
    onOpenTopic: (topicId: Long, slug: String?, title: String?, postNumber: Int?) -> Unit = { _, _, _, _ -> },
    onOpenUser: (username: String, displayName: String?, avatarTemplate: String?) -> Unit = { _, _, _ -> }
) {
    val currentUser by CurrentUserHolder.currentUser.collectAsState()
    MessagesRoute(
        loggedIn = currentUser != null,
        username = currentUser?.username,
        onRequestLogin = LoginGate::requestLogin,
        onOpenTopic = onOpenTopic,
        onOpenUser = onOpenUser,
        colors = notificationColors()
    )
}

/**
 * 主题 token → [NotificationColors] 的映射。通知页与消息页共用，
 * 抽出来避免两处各写一份、日后改色漏掉一处。
 */
@Composable
internal fun notificationColors(): NotificationColors {
    val colors = jetdoColors
    return NotificationColors(
        pageBg = colors.pageBg,
        textPrimary = colors.textPrimary,
        textSecondary = colors.textSecondary,
        accent = colors.ldAccent,
        unreadBg = colors.infoSoftBg,
        divider = colors.border,
        badgeReadBg = colors.surfaceCard,
        badgeReadFg = colors.textPrimary,
        // 分段轨道用 page_bg_gray（亮 #F2F3F5），白胶囊才浮得起来；选中胶囊用白卡底。
        segmentTrack = colors.pageBgGray,
        segmentThumb = colors.surfaceCard
    )
}
