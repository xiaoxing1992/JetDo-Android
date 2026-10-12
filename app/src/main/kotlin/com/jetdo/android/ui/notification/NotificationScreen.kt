package com.jetdo.android.ui.notification

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.jetdo.android.feature.notification.NotificationColors
import com.jetdo.android.feature.notification.NotificationRoute
import com.jetdo.android.services.session.CurrentUserHolder
import com.jetdo.android.services.session.LoginGate
import com.jetdo.android.ui.theme.jetdoColors

/** Compatibility entry point retained for the existing top-level navigation. */
@Composable
fun NotificationScreen(
    showTopBar: Boolean = true,
    onOpenTopic: (topicId: Long, slug: String?, title: String?, postNumber: Int?) -> Unit = { _, _, _, _ -> },
    onOpenUser: (username: String, displayName: String?, avatarTemplate: String?) -> Unit = { _, _, _ -> }
) {
    val currentUser by CurrentUserHolder.currentUser.collectAsState()
    NotificationRoute(
        loggedIn = currentUser != null,
        onRequestLogin = LoginGate::requestLogin,
        onOpenTopic = onOpenTopic,
        onOpenUser = onOpenUser,
        showTopBar = showTopBar,
        colors = notificationColors()
    )
}
