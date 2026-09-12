package com.arkdo.android.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
@Composable
fun HomeRoute(
    onTopicClick: (topicId: Long, targetPostNumber: Int?) -> Unit,
    onProfileClick: () -> Unit,
    onNotificationsClick: () -> Unit,
    onSearchClick: () -> Unit,
    onDebugClick: () -> Unit,
    onComposeClick: () -> Unit,
    onMenuClick: () -> Unit = {},
    isWebViewReady: Boolean = true,
    isLoggedIn: Boolean = false,
    isCheckingSession: Boolean = false,
    accountUser: com.arkdo.android.core.model.HomeUser? = null,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(isWebViewReady) {
        if (isWebViewReady) viewModel.loadInitial()
    }

    HomeScreen(
        state = state,
        onRetry = viewModel::retry,
        onRefresh = viewModel::refresh,
        onLoadMore = viewModel::loadMore,
        onTopicClick = onTopicClick,
        onMenuClick = onMenuClick,
        onProfileClick = onProfileClick,
        onNotificationsClick = onNotificationsClick,
        onSearchClick = onSearchClick,
        onDebugClick = onDebugClick,
        onComposeClick = onComposeClick,
        isWebViewReady = isWebViewReady,
        isLoggedIn = isLoggedIn,
        isCheckingSession = isCheckingSession,
        accountUser = accountUser,
        onFabScroll = viewModel::updateFabMode
    )
}
