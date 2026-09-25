package com.arkdo.android.feature.home

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
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
    // 通知未读数：透传给顶栏铃铛角标。
    unreadCount: Int = 0,
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
    // 列表非空时的失败（典型：新话题拉取失败 / 拉到空结果）也必须让用户看见。
    // 鸿蒙走 toast（HomePage.ets:1845-1856）；此前 Android 的 error 只在空列表里渲染
    // （HomeScreen.kt 的 EmptyState），一旦列表有内容，这类失败就完全静默——
    // 用户点了「N 条新话题」提示条没反应，还以为真没有新话题。
    val context = LocalContext.current
    LaunchedEffect(state.error) {
        val message = state.error ?: return@LaunchedEffect
        if (state.topics.isNotEmpty()) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.consumeError()
        }
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
        unreadCount = unreadCount,
        colors = colors,
        avatarBaseUrl = avatarBaseUrl,
        onFabScroll = viewModel::updateFabMode,
        onIncomingInsertFinished = viewModel::finishIncomingInsert,
        onIncomingHighlightFinished = viewModel::finishIncomingHighlight
    )
}
