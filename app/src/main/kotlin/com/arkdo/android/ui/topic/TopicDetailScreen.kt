package com.arkdo.android.ui.topic

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.arkdo.android.data.models.TopicPost
import com.arkdo.android.data.models.TopicCategory
import com.arkdo.android.services.site.CategoryIndex
import com.arkdo.android.ui.components.PostRichContent
import com.arkdo.android.ui.components.TopicMetadataBadges

/**
 * 话题详情页
 *
 * 显示话题的完整内容、回复列表和互动功能。
 *
 * 功能：
 * - 话题标题、内容渲染
 * - 回复列表（分页加载）
 * - 点赞、书签、表情回应
 * - 阅读进度追踪
 * - 回复、引用、编辑
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopicDetailScreen(
    topicId: Long,
    slug: String? = null,
    title: String? = null,
    targetPostNumber: Int? = null,
    highlightTarget: String? = null,
    onNavigateBack: () -> Unit,
    viewModel: TopicDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(topicId, targetPostNumber) {
        viewModel.loadTopic(topicId, slug, title, targetPostNumber)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.topic?.title ?: title ?: "话题详情",
                        maxLines = 1
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                uiState.isLoading && uiState.topic == null -> {
                    LoadingView()
                }
                uiState.error != null -> {
                    ErrorView(
                        message = uiState.error!!,
                        onRetry = { viewModel.retry() }
                    )
                }
                uiState.topic != null -> {
                    TopicDetailContent(
                        topic = uiState.topic!!,
                        categories = uiState.categories,
                        posts = uiState.posts,
                        isLoadingMore = uiState.isLoadingMore,
                        hasMore = uiState.hasMore,
                        onLoadMore = { viewModel.loadMorePosts() },
                        targetPostNumber = targetPostNumber,
                        // 跳楼/换窗的一次性滚动请求来自 VM（jumpToPostNumber / rewindow）。
                        scrollRequestPostNumber = uiState.scrollRequestPostNumber,
                        onScrollRequestConsumed = { viewModel.consumeScrollRequest() },
                        flashTargetPostId = uiState.flashTargetPostId,
                        hasEarlier = uiState.hasEarlier,
                        earlierCount = uiState.earlierCount,
                        isLoadingEarlier = uiState.isLoadingEarlier,
                        onLoadEarlier = { viewModel.loadEarlierPosts() }
                    )
                }
            }
        }
    }
}

@Composable
private fun TopicDetailContent(
    topic: com.arkdo.android.data.models.TopicDetail,
    categories: List<TopicCategory>,
    posts: List<TopicPost>,
    isLoadingMore: Boolean,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    targetPostNumber: Int? = null,
    scrollRequestPostNumber: Int? = null,
    onScrollRequestConsumed: () -> Unit = {},
    flashTargetPostId: Long? = null,
    hasEarlier: Boolean = false,
    earlierCount: Int = 0,
    isLoadingEarlier: Boolean = false,
    onLoadEarlier: () -> Unit = {}
) {
    val listState = rememberLazyListState()

    // 未读行跳楼：目标楼层加载出来后滚过去（头部标题占第 0 位，楼层索引 +1）。
    // 目标楼层还没在当前窗口里时不滚，等 loadMore/换窗把它带进来后再滚。
    LaunchedEffect(posts, targetPostNumber) {
        val target = targetPostNumber ?: return@LaunchedEffect
        val index = posts.indexOfFirst { it.postNumber == target }
        if (index >= 0) listState.animateScrollToItem(index + 1)
    }

    // VM 发起的跳楼（引用跳楼/换窗定位）：一次性请求，滚完立刻消费清除。
    LaunchedEffect(scrollRequestPostNumber) {
        val target = scrollRequestPostNumber ?: return@LaunchedEffect
        val index = posts.indexOfFirst { it.postNumber == target }
        if (index >= 0) {
            listState.animateScrollToItem(index + 1)
        }
        onScrollRequestConsumed()
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize()
    ) {
        // 话题标题
        item {
            TopicHeader(topic = topic, categories = categories)
        }

        // 窗口式进入时往上补更早楼层（自动触发：出现在列表顶部即拉）。
        if (hasEarlier) {
            item(key = "earlier_loader") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isLoadingEarlier) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    } else {
                        TextButton(onClick = onLoadEarlier) {
                            Text(if (earlierCount > 0) "加载更早（还有 $earlierCount 楼）" else "加载更早")
                        }
                    }
                }
            }
        }

        // 回复列表
        items(
            items = posts,
            key = { it.id }
        ) { post ->
            PostItem(post = post, highlighted = flashTargetPostId == post.id)
        }

        // 加载更多
        if (isLoadingMore) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        } else if (hasMore) {
            item {
                LaunchedEffect(Unit) {
                    onLoadMore()
                }
            }
        }
    }
}

@Composable
private fun TopicHeader(
    topic: com.arkdo.android.data.models.TopicDetail,
    categories: List<TopicCategory>
) {
    // 1:1 对齐鸿蒙 TopicSummaryCard：标题(22 加粗) → 分类/标签徽章行(8) →
    // 元信息行(12 次要色,8)。全部左对齐，底部 1dp 分隔。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, top = 20.dp, bottom = 16.dp)
    ) {
        Text(
            text = topic.title,
            fontSize = 22.sp,
            lineHeight = 30.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        val category = CategoryIndex.find(categories, topic.categoryId)
        val parentCategory = CategoryIndex.findParent(categories, topic.categoryId)
        if (category != null || topic.tags.isNotEmpty()) {
            TopicMetadataBadges(
                category = category,
                parentCategory = parentCategory,
                tags = topic.tags,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )
        }

        val postsCount = topic.postsCount ?: 0
        val replyCount = topic.replyCount ?: 0
        val likeCount = topic.likeCount ?: 0
        val viewCount = topic.viewCount ?: 0
        Text(
            text = "${postsCount} 帖 · ${replyCount} 回复 · ${likeCount} 赞 · ${viewCount} 浏览",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun PostItem(post: TopicPost, highlighted: Boolean = false) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                else Color.Transparent
            )
            .padding(16.dp)
    ) {
        // 作者信息
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = post.username ?: "Unknown",
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "楼层 ${post.postNumber}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 正文富内容渲染（cooked HTML → 块/行内节点树 → Compose）
        PostRichContent(html = post.cookedHtml)

        Spacer(modifier = Modifier.height(8.dp))

        // 互动按钮（简化版）
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            post.reactions?.let { reactions ->
                if (reactions.isNotEmpty()) {
                    Text(
                        text = reactions.joinToString(" ") { "${it.id} ${it.count}" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Text("重试")
        }
    }
}
