package com.arkdo.android.ui.home

import androidx.compose.runtime.Composable
import com.arkdo.android.feature.home.HomeRoute

/** Compatibility entry point for the modular home feature. */
@Composable
fun HomeFeatureScreen(
    onTopicClick: (Long, Int?) -> Unit,
    onProfileClick: () -> Unit,
    onNotificationsClick: () -> Unit,
    onSearchClick: () -> Unit,
    onDebugClick: () -> Unit,
    onComposeClick: () -> Unit
) {
    HomeRoute(
        onTopicClick = onTopicClick,
        onProfileClick = onProfileClick,
        onNotificationsClick = onNotificationsClick,
        onSearchClick = onSearchClick,
        onDebugClick = onDebugClick,
        onComposeClick = onComposeClick
    )
}
