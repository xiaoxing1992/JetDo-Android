package com.arkdo.android.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.arkdo.android.data.models.Topic
import com.arkdo.android.data.models.TopicCategory
import com.arkdo.android.data.models.TopicTag
import com.arkdo.android.ui.components.TopicListItem as EnhancedTopicListItem
import kotlinx.coroutines.launch

/**
 * 首页 - 话题列表
 *
 * 1:1 对齐鸿蒙版 HomePage.ets：
 * - 沉浸式标题栏（可折叠）
 * - 侧边版块筛选抽屉（边缘右滑手势）
 * - 实时新话题提醒（顶部提示条）
 * - 智能 FAB（发帖 ↔ 回到顶部双态切换）
 * - 发帖浮层（全屏编辑器）
 * - 左滑拉出个人中心手势
 * - 版本公告弹窗
 *
 * 当前 MVP 版本已实现：
 * - ✅ 话题列表展示
 * - ✅ 下拉刷新
 * - ✅ 上拉加载更多
 * - ✅ 空态/错误态
 * - ✅ 智能 FAB（发帖 ↔ 回到顶部）
 * - ✅ 多维度筛选（版块/标签）
 * - ✅ 实时新话题提醒
 * - ✅ 会话变化自动刷新
 *
 * 待后续迭代：
 * - ⏳ 侧边抽屉（版块树展开/折叠）
 * - ⏳ 发帖浮层（Markdown 编辑器）
 * - ⏳ 手势操作（边缘右滑、左滑转场）
 * - ⏳ 版本公告
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    isWebViewReady: Boolean = false,
    viewModel: HomeViewModel = hiltViewModel(),
    onTopicClick: (Long) -> Unit = {},
    onProfileClick: () -> Unit = {},
    onNotificationsClick: () -> Unit = {},
    onSearchClick: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // 当 WebView bridge 就绪且列表为空时，触发初始加载
    LaunchedEffect(isWebViewReady) {
        if (isWebViewReady && uiState.topics.isEmpty() && !uiState.isInitialLoading) {
            viewModel.loadInitial()
        }
    }

    // 滚动监听：更新 FAB 模式（发帖 ↔ 回到顶部）
    LaunchedEffect(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) {
        val layoutInfo = listState.layoutInfo
        if (layoutInfo.visibleItemsInfo.isNotEmpty()) {
            val totalOffset = listState.firstVisibleItemIndex * 1000f + listState.firstVisibleItemScrollOffset
            val viewportHeight = layoutInfo.viewportEndOffset.toFloat()
            viewModel.updateFabMode(totalOffset, viewportHeight)
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            HomeTitleBar(
                scrollBehavior = scrollBehavior,
                uiState = uiState,
                onMenuClick = { /* TODO: 打开抽屉 */ },
                onSearchClick = onSearchClick,
                onNotificationsClick = onNotificationsClick,
                onProfileClick = onProfileClick
            )
        },
        floatingActionButton = {
            HomeFloatingActionButton(
                showBackToTop = uiState.showBackToTopFab,
                onCompose = { /* TODO: 打开发帖浮层 */ },
                onBackToTop = {
                    coroutineScope.launch {
                        listState.animateScrollToItem(0)
                    }
                }
            )
        }
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    uiState.isInitialLoading -> {
                        LoadingView()
                    }
                    uiState.error != null -> {
                        ErrorView(
                            message = uiState.error ?: "未知错误",
                            onRetry = { viewModel.retry() }
                        )
                    }
                    uiState.topics.isEmpty() -> {
                        EmptyView()
                    }
                    else -> {
                        Column {
                            // 实时新话题提醒条
                            IncomingTopicsPrompt(
                                visible = uiState.incomingPromptVisible,
                                count = uiState.incomingTopicIds.size,
                                loading = uiState.isLoadingIncoming,
                                onClick = { viewModel.loadIncomingTopics() }
                            )

                            // 话题列表
                            TopicList(
                                listState = listState,
                                topics = uiState.topics,
                                isLoadingMore = uiState.isLoadingMore,
                                hasMore = uiState.hasMore,
                                onTopicClick = onTopicClick,
                                onLoadMore = { viewModel.loadMore() },
                                onCategoryClick = { category ->
                                    viewModel.selectCategory(category)
                                },
                                onTagClick = { tag ->
                                    viewModel.selectTag(tag)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ========== 顶部标题栏 ==========

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTitleBar(
    scrollBehavior: TopAppBarScrollBehavior,
    uiState: HomeUiState,
    onMenuClick: () -> Unit,
    onSearchClick: () -> Unit,
    onNotificationsClick: () -> Unit,
    onProfileClick: () -> Unit
) {
    TopAppBar(
        title = {
            Column {
                // 主标题：版块名 / 标签名 / "首页"
                Text(
                    text = when {
                        uiState.selectedCategory != null -> uiState.selectedCategory.name
                        uiState.selectedTag != null -> "#${uiState.selectedTag.name}"
                        else -> "首页"
                    },
                    style = MaterialTheme.typography.titleLarge
                )

                // 副行：筛选条件摘要（有筛选时才显示）
                if (uiState.selectedCategory != null || uiState.selectedTag != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        if (uiState.selectedTag != null) {
                            FilterChip(
                                label = uiState.selectedTag.name,
                                onClick = { /* 点击回抽屉 */ }
                            )
                        }
                    }
                }
            }
        },
        scrollBehavior = scrollBehavior,
        navigationIcon = {
            IconButton(onClick = onMenuClick) {
                Icon(Icons.Default.Menu, contentDescription = "菜单")
            }
        },
        actions = {
            IconButton(onClick = onSearchClick) {
                Icon(Icons.Default.Search, contentDescription = "搜索")
            }
            IconButton(onClick = onNotificationsClick) {
                Icon(Icons.Default.Notifications, contentDescription = "通知")
            }
            IconButton(onClick = onProfileClick) {
                Icon(Icons.Default.Person, contentDescription = "个人中心")
            }
        }
    )
}

@Composable
private fun FilterChip(
    label: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.height(20.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

// ========== 实时新话题提醒 ==========

@Composable
private fun IncomingTopicsPrompt(
    visible: Boolean,
    count: Int,
    loading: Boolean,
    onClick: () -> Unit
) {
    AnimatedVisibility(
        visible = visible && count > 0,
        enter = slideInVertically() + fadeIn(),
        exit = slideOutVertically() + fadeOut()
    ) {
        Surface(
            onClick = onClick,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = "有 $count 条新话题",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

// ========== 智能 FAB（发帖 ↔ 回到顶部） ==========

@Composable
private fun HomeFloatingActionButton(
    showBackToTop: Boolean,
    onCompose: () -> Unit,
    onBackToTop: () -> Unit
) {
    // FAB 图标/文字随模式切换，带 morph 动画
    FloatingActionButton(
        onClick = if (showBackToTop) onBackToTop else onCompose
    ) {
        Icon(
            imageVector = if (showBackToTop) Icons.Default.KeyboardArrowUp else Icons.Default.Add,
            contentDescription = if (showBackToTop) "回到顶部" else "发帖"
        )
    }
}

// ========== 话题列表 ==========

@Composable
private fun TopicList(
    listState: androidx.compose.foundation.lazy.LazyListState,
    topics: List<Topic>,
    isLoadingMore: Boolean,
    hasMore: Boolean,
    onTopicClick: (Long) -> Unit,
    onLoadMore: () -> Unit,
    onCategoryClick: (TopicCategory) -> Unit,
    onTagClick: (TopicTag) -> Unit
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize()
    ) {
        items(
            items = topics,
            key = { it.id }
        ) { topic ->
            EnhancedTopicListItem(
                topic = topic,
                category = null, // TODO: 从 categories map 查找
                parentCategory = null,
                onClick = { onTopicClick(topic.id) },
                onUnreadBadgeClick = { onTopicClick(topic.id) },
                onCategoryClick = onCategoryClick,
                onTagClick = onTagClick,
                badgesTappable = true
            )
        }

        // 加载更多指示器
        if (hasMore) {
            item {
                LaunchedEffect(Unit) {
                    if (!isLoadingMore) {
                        onLoadMore()
                    }
                }
                if (isLoadingMore) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingView() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorView(
    message: String,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(text = message)
            Button(onClick = onRetry) {
                Text("重试")
            }
        }
    }
}

@Composable
private fun EmptyView() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text("暂无话题")
    }
}
