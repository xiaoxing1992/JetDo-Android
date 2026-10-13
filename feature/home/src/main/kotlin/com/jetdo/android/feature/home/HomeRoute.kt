package com.jetdo.android.feature.home

import androidx.compose.ui.res.stringResource
import com.jetdo.android.feature.home.R
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.jetdo.android.core.model.HomeCategory
import com.jetdo.android.core.model.HomeQuery
import com.jetdo.android.core.model.HomeTag

data class HomeIncomingTopicEvent(
    val topicId: Long,
    val sequence: Long
)

@Composable
fun HomeRoute(
    query: HomeQuery = HomeQuery(),
    onTopicClick: (topicId: Long, targetPostNumber: Int?) -> Unit,
    onSearchClick: () -> Unit,
    onComposeClick: () -> Unit,
    onMenuClick: () -> Unit = {},
    isWebViewReady: Boolean = true,
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

    // 「点分类/标签快速筛选」：开关开着时首页行上的徽章可点（对齐鸿蒙 HomePage
    // badgesTappable + handleTagClick / handleCategoryClick）。已在按它筛选时静默忽略——
    // 列表不会有任何变化，重发请求纯属浪费，弹「已经在看了」反而像出了错。
    val badgeFilterEnabled by BadgeFilterPrefs.enabled.collectAsState()
    val onBadgeTagClick: (HomeTag) -> Unit = { tag ->
        val query = viewModel.currentQuery
        if (query.tag != tag.name) {
            viewModel.setQuery(query.copy(tag = tag.name, tagId = tag.id, tagSlug = tag.slug))
            // 顶栏此刻多半已随滚动收起，副行看不见，给一句 toast 交代刚发生了什么。
            Toast.makeText(context, context.getString(R.string.search_filtered_tag, tag.name), Toast.LENGTH_SHORT).show()
        }
    }
    val onBadgeCategoryClick: (HomeCategory) -> Unit = { category ->
        val query = viewModel.currentQuery
        if (query.categoryId != category.id) {
            // 徽章上写的是帖子自己的（叶子）分类，在看父版块时点子版块徽章就是收窄到子版块。
            viewModel.setQuery(
                query.copy(
                    categoryId = category.id,
                    categorySlug = category.slug,
                    categoryName = category.name
                )
            )
            Toast.makeText(context, context.getString(R.string.search_filtered_category, category.name), Toast.LENGTH_SHORT).show()
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
        onSearchClick = onSearchClick,
        onComposeClick = onComposeClick,
        isWebViewReady = isWebViewReady,
        colors = colors,
        avatarBaseUrl = avatarBaseUrl,
        badgesTappable = badgeFilterEnabled,
        onBadgeTagClick = onBadgeTagClick,
        onBadgeCategoryClick = onBadgeCategoryClick,
        onFabScroll = viewModel::updateFabMode,
        onIncomingInsertFinished = viewModel::finishIncomingInsert,
        onIncomingHighlightFinished = viewModel::finishIncomingHighlight
    )
}
