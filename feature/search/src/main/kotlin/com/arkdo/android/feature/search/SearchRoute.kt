package com.arkdo.android.feature.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.arkdo.android.core.model.HomeCategory
import com.arkdo.android.core.model.SearchFilters
import com.arkdo.android.core.model.SearchHit
import com.arkdo.android.core.model.SearchType

/**
 * 搜索页的对外入口。
 *
 * 与 `HomeRoute`/`NotificationRoute` 同一套写法:Route 负责把 ViewModel 的状态与回调
 * 接给 Screen,并把宿主(app 层)注入的依赖(视觉 token、主题色、帖子行渲染器)透传下去。
 * feature 模块不依赖 app 主题,颜色由启动处映射。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchRoute(
    tokens: SearchVisualTokens,
    categories: List<HomeCategory>,
    onBack: () -> Unit,
    onOpenTopic: (topicId: Long, targetPostNumber: Int?) -> Unit,
    onOpenUser: (username: String) -> Unit,
    topicRow: @Composable (hit: SearchHit, categories: List<HomeCategory>) -> Unit,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    // 分类树由宿主注入(结果行渲染分类徽标用),feature 层不自己拉站点元数据。
    LaunchedEffect(categories) {
        viewModel.setCategories(categories)
    }

    SearchScreen(
        state = state,
        tokens = tokens,
        topicRow = topicRow,
        onQueryChange = viewModel::setQuery,
        onSubmit = viewModel::submit,
        onSearchTypeChange = viewModel::setSearchType,
        onOpenFilterPanel = viewModel::openFilterPanel,
        onBack = onBack,
        onOpenTopic = { hit ->
            viewModel.onResultOpened()
            onOpenTopic(hit.topic.id, firstUnreadOrNull(hit))
        },
        onOpenUser = { user ->
            viewModel.onResultOpened()
            onOpenUser(user.username)
        },
        onHistoryTermClick = viewModel::applyHistoryTerm,
        onRemoveHistory = viewModel::removeHistory,
        onClearHistory = viewModel::clearHistory,
        onLoadMore = viewModel::loadMore,
        onRetry = viewModel::retry
    )

    if (state.showFilterPanel) {
        ModalBottomSheet(
            onDismissRequest = viewModel::dismissFilterPanel,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            SearchFilterPanel(
                initial = state.filters,
                categories = state.categories,
                tokens = tokens,
                onApply = viewModel::applyFilters,
                onDismiss = viewModel::dismissFilterPanel
            )
        }
    }
}

/**
 * 点开搜索结果时的跳楼目标:未读行带楼层号就滚到那儿,否则落在顶部(一楼)。
 * 与首页列表点击的语义一致。
 */
private fun firstUnreadOrNull(hit: SearchHit): Int? =
    hit.topic.unreadPosts.takeIf { it > 0 }?.let { hit.topic.firstUnreadPostNumber() }