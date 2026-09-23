package com.arkdo.android.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.arkdo.android.core.model.HomeQuery

data class HomeIncomingTopicEvent(
    val topicId: Long,
    val sequence: Long
)

@Composable
fun HomeRoute(
    query: HomeQuery = HomeQuery(),
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
    colors: HomeVisualTokens = HomeVisualTokens.Default,
    avatarBaseUrl: String = "",
    incomingTopicEvent: HomeIncomingTopicEvent? = null,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(query) {
        viewModel.setQuery(query)
    }
    LaunchedEffect(isWebViewReady) {
        if (isWebViewReady) {
            viewModel.loadInitial()
            viewModel.ensureCategoriesLoaded()
        }
    }
    LaunchedEffect(incomingTopicEvent?.sequence) {
        incomingTopicEvent?.let { viewModel.addIncomingTopic(it.topicId) }
    }

    HomeScreen(
        state = state,
        onRetry = viewModel::retry,
        onRefresh = viewModel::refresh,
        onIncomingTopicsClick = viewModel::loadIncomingTopics,
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
        colors = colors,
        avatarBaseUrl = avatarBaseUrl,
        onFabScroll = viewModel::updateFabMode,
        onIncomingInsertFinished = viewModel::finishIncomingInsert,
        onIncomingHighlightFinished = viewModel::finishIncomingHighlight
    )
}
