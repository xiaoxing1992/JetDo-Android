package com.arkdo.android.feature.search

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.arkdo.android.core.model.HomeCategory

/**
 * 搜索页的对外入口。
 *
 * 与 `HomeRoute`/`NotificationRoute` 同一套写法:Route 负责把 ViewModel 的状态与回调
 * 接给 Screen,并把宿主(app 层)注入的视觉 token 透传下去。
 * 帖子行由搜索模块自己画,头像和资源走 [avatarBaseUrl]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchRoute(
    isWebViewReady: Boolean,
    tokens: SearchVisualTokens,
    categories: List<HomeCategory>,
    onBack: () -> Unit,
    onOpenTopic: (topicId: Long, targetPostNumber: Int?) -> Unit,
    onOpenUser: (username: String, userId: Long) -> Unit,
    avatarBaseUrl: String,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    // 分类树由宿主注入(结果行渲染分类徽标用),feature 层不自己拉站点元数据。
    LaunchedEffect(categories) {
        viewModel.setCategories(categories)
    }

    // WebView 就绪状态由宿主注入(对齐鸿蒙 isArkWebReady):未就绪时拦下发请求。
    LaunchedEffect(isWebViewReady) {
        viewModel.setHostReady(isWebViewReady)
    }

    SearchScreen(
        state = state,
        tokens = tokens,
        avatarBaseUrl = avatarBaseUrl,
        onQueryChange = viewModel::setQuery,
        onSubmit = viewModel::submit,
        onSearchTypeChange = viewModel::setSearchType,
        onOpenFilterPanel = viewModel::openFilterPanel,
        onBack = onBack,
        onOpenTopic = { hit ->
            viewModel.onResultOpened()
            // 从顶部打开详情,不带跳楼目标(对齐鸿蒙 SearchFlow.openTopic)。
            onOpenTopic(hit.topic.id, null)
        },
        onOpenUser = { user ->
            viewModel.onResultOpened()
            // userId 透传：他人主页的头像方圆按 id==1 判，种子缺 id 会闪一下（S7）。
            onOpenUser(user.username, user.id)
        },
        onHistoryTermClick = viewModel::applyHistoryTerm,
        onRemoveHistory = viewModel::removeHistory,
        onClearHistory = viewModel::clearHistory,
        onLoadMore = viewModel::loadMore
    )

    if (state.showFilterPanel) {        ModalBottomSheet(
            onDismissRequest = viewModel::dismissFilterPanel,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            SearchFilterPanel(
                initial = state.filters,
                categories = state.categories,
                tokens = tokens,
                tagQuery = state.tagQuery,
                tagSuggestions = state.tagSuggestions,
                isTagLoading = state.isTagLoading,
                onTagQueryChange = viewModel::onTagQueryChange,
                onResetTags = viewModel::resetTagSuggestions,
                onApply = viewModel::applyFilters,
                onDismiss = viewModel::dismissFilterPanel
            )
        }
    }
}