package com.arkdo.android.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arkdo.android.data.models.Topic

/**
 * 首页 - 话题列表
 *
 * 最小可行版本，核心功能：
 * - 话题列表展示
 * - 下拉刷新
 * - 上拉加载更多
 * - 空态/错误态
 *
 * 暂未实现（后续迭代）：
 * - 侧边抽屉（版块筛选）
 * - 实时话题更新
 * - 手势操作
 * - 发帖浮层
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = viewModel(),
    onTopicClick: (Long) -> Unit = {},
    onProfileClick: () -> Unit = {},
    onNotificationsClick: () -> Unit = {},
    onSearchClick: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("首页") },
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = onSearchClick) {
                        Icon(Icons.Default.Add, contentDescription = "搜索")
                    }
                    IconButton(onClick = onNotificationsClick) {
                        Icon(Icons.Default.Add, contentDescription = "通知")
                    }
                    IconButton(onClick = onProfileClick) {
                        Icon(Icons.Default.Add, contentDescription = "个人中心")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { /* TODO: 打开发帖页 */ }) {
                Icon(Icons.Default.Add, contentDescription = "发帖")
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                uiState.isInitialLoading -> {
                    LoadingView()
                }
                uiState.error != null -> {
                    ErrorView(
                        message = uiState.error!!,
                        onRetry = { viewModel.retry() }
                    )
                }
                uiState.topics.isEmpty() -> {
                    EmptyView()
                }
                else -> {
                    TopicList(
                        topics = uiState.topics,
                        isLoadingMore = uiState.isLoadingMore,
                        hasMore = uiState.hasMore,
                        onTopicClick = onTopicClick,
                        onLoadMore = { viewModel.loadMore() }
                    )
                }
            }
        }
    }
}

@Composable
private fun TopicList(
    topics: List<Topic>,
    isLoadingMore: Boolean,
    hasMore: Boolean,
    onTopicClick: (Long) -> Unit,
    onLoadMore: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(
            items = topics,
            key = { it.id }
        ) { topic ->
            TopicListItem(
                topic = topic,
                onClick = { onTopicClick(topic.id) }
            )
            HorizontalDivider()
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
private fun TopicListItem(
    topic: Topic,
    onClick: () -> Unit
) {
    // 简化版话题卡片，完整实现需添加头像、标签、统计数据等
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = topic.title,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "${topic.postsCount} 回复",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${topic.views} 浏览",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
