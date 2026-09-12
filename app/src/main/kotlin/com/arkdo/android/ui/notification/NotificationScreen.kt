package com.arkdo.android.ui.notification

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.arkdo.android.feature.notification.NotificationRoute
import com.arkdo.android.services.session.CurrentUserHolder
import com.arkdo.android.services.session.LoginGate

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
        showTopBar = showTopBar
    )
}
