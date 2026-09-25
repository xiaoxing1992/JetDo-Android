package com.arkdo.android.feature.notification

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel

@Composable
fun NotificationRoute(
    loggedIn: Boolean,
    onRequestLogin: () -> Unit,
    onOpenTopic: (Long, String?, String?, Int?) -> Unit,
    onOpenUser: (String, String?, String?) -> Unit,
    showTopBar: Boolean = true,
    colors: NotificationColors = NotificationColors.Light,
    viewModel: NotificationViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(loggedIn) {
        if (loggedIn) viewModel.refresh()
    }
    NotificationScreen(
        state = state,
        loggedIn = loggedIn,
        showTopBar = showTopBar,
        onRequestLogin = onRequestLogin,
        onRetry = viewModel::loadInitial,
        onRefresh = viewModel::refresh,
        onLoadMore = viewModel::loadMore,
        onMarkAllRead = viewModel::markAllRead,
        onMarkRead = viewModel::markItemRead,
        onOpenTopic = onOpenTopic,
        onOpenUser = onOpenUser,
        colors = colors
    )
}
