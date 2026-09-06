package com.arkdo.android.ui.topic

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.arkdo.android.data.models.TopicPost
import com.arkdo.android.data.models.TopicCategory
import com.arkdo.android.data.models.User
import com.arkdo.android.services.site.CategoryIndex
import com.arkdo.android.ui.components.PostRichContent
import com.arkdo.android.ui.components.ImageViewer
import com.arkdo.android.ui.components.LocalAnimatedVisibilityScope
import com.arkdo.android.ui.components.LocalSharedTransitionScope
import com.arkdo.android.ui.components.TopicPostItem
import com.arkdo.android.ui.components.ViewerImage
import com.arkdo.android.ui.components.UserAvatar
import com.arkdo.android.ui.utils.RelativeTime
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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun TopicDetailScreen(
    topicId: Long,
    slug: String? = null,
    title: String? = null,
    targetPostNumber: Int? = null,
    highlightTarget: String? = null,
    onNavigateBack: () -> Unit,
    onNavigateToTopic: (topicId: Long, slug: String?, targetPostNumber: Int?) -> Unit = { _, _, _ -> },
    viewModel: TopicDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val uriHandler = LocalUriHandler.current
    // 链接路由：linux.do 话题链接（/t/{slug}/{id}[/{楼号}]，引用卡是 /t/topic/{id}/{楼号}）
    // 应用内开新详情页；其余链接（站外/非话题）走系统浏览器。
    val topicLinkRegex = Regex("^https?://linux\\.do/t/(?:([^/?#]+)/)?(\\d+)(?:/(\\d+))?")
    // 图片浏览器覆盖层状态（本楼图片组 + 起始下标）。
    var viewerState by remember { mutableStateOf<Pair<List<ViewerImage>, Int>?>(null) }
    val onOpenUri: (String) -> Unit = { url ->
        val match = topicLinkRegex.find(url)
        if (match == null) {
            try {
                uriHandler.openUri(url)
            } catch (_: Exception) {
            }
        } else {
            // slug 为字面量 "topic"（引用卡回跳链接）时不传，让详情页按 id + 当前 slug 开窗。
            val rawSlug = match.groupValues[1]
            val slug = rawSlug.takeUnless { it.isEmpty() || it == "topic" }
            val linkTopicId = match.groupValues[2].toLongOrNull() ?: 0L
            val postNumber = match.groupValues[3].toIntOrNull()
            when {
                linkTopicId <= 0 -> try {
                    uriHandler.openUri(url)
                } catch (_: Exception) {
                }
                // 本帖内跳楼：不压栈，直接滚动/换窗。
                linkTopicId == topicId -> postNumber?.let { viewModel.jumpToPostNumber(it) }
                else -> onNavigateToTopic(linkTopicId, slug, postNumber)
            }
        }
    }

    LaunchedEffect(topicId, targetPostNumber) {
        viewModel.loadTopic(topicId, slug, title, targetPostNumber)
    }

    SharedTransitionLayout {
        AnimatedContent(
            targetState = viewerState,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(220)) },
            label = "imageViewer"
        ) { viewer ->
            CompositionLocalProvider(
                LocalSharedTransitionScope provides this@SharedTransitionLayout,
                LocalAnimatedVisibilityScope provides this
            ) {
                if (viewer == null) {
    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // 对齐鸿蒙 TopicDetailHeader 双层标题：摘要在视口内时显示静态
                    // 「帖子详情」，滑出后切换为帖子标题（避免与摘要卡标题重复）。
                    Text(
                        text = if (listState.firstVisibleItemIndex == 0) {
                            "帖子详情"
                        } else {
                            uiState.topic?.title ?: title ?: "话题详情"
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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
                        listState = listState,
                        topic = uiState.topic!!,
                        onOpenImages = { images, start -> viewerState = images to start },
                        categories = uiState.categories,
                        posts = uiState.posts,
                        isLoadingMore = uiState.isLoadingMore,
                        hasMore = uiState.hasMore,
                        onLoadMore = { viewModel.loadMorePosts() },
                        targetPostNumber = targetPostNumber,
                        flashTargetPostId = uiState.flashTargetPostId,
                        // 跳楼/换窗的一次性滚动请求来自 VM（jumpToPostNumber / rewindow）。
                        scrollRequestPostNumber = uiState.scrollRequestPostNumber,
                        onScrollRequestConsumed = { viewModel.consumeScrollRequest() },
                        hasEarlier = uiState.hasEarlier,
                        earlierCount = uiState.earlierCount,
                        isLoadingEarlier = uiState.isLoadingEarlier,
                        onLoadEarlier = { viewModel.loadEarlierPosts() },
                        onOpenLink = onOpenUri,
                        onToggleReaction = { p, reaction, done -> viewModel.toggleReaction(p, reaction, done) },
                        onToggleBookmark = { p, done -> viewModel.toggleBookmark(p, done) },
                        onCopyPostLink = { p -> viewModel.postLink(p) },
                        onJumpToPost = { postNumber -> viewModel.jumpToPostNumber(postNumber) },
                        onRepliesLoad = { p, done -> viewModel.loadReplies(p, done) }
                    )
                }
            }
        }
    }
}

                } else {
                    ImageViewer(
                        images = viewer.first,
                        startIndex = viewer.second,
                        onDismiss = { viewerState = null }
                    )
                }
            }
        }
    }
}

@Composable
private fun TopicDetailContent(
    listState: androidx.compose.foundation.lazy.LazyListState,
    topic: com.arkdo.android.data.models.TopicDetail,
    categories: List<TopicCategory>,
    posts: List<TopicPost>,
    isLoadingMore: Boolean,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    targetPostNumber: Int? = null,
    flashTargetPostId: Long? = null,
    scrollRequestPostNumber: Int? = null,
    onScrollRequestConsumed: () -> Unit = {},
    hasEarlier: Boolean = false,
    earlierCount: Int = 0,
    isLoadingEarlier: Boolean = false,
    onLoadEarlier: () -> Unit = {},
    onOpenLink: (String) -> Unit = {},
    onOpenImages: (List<ViewerImage>, Int) -> Unit = { _, _ -> },
    onToggleReaction: (TopicPost, String, (Boolean) -> Unit) -> Unit = { _, _, done -> done(false) },
    onToggleBookmark: (TopicPost, (Boolean) -> Unit) -> Unit = { _, done -> done(false) },
    onCopyPostLink: (TopicPost) -> String? = { null },
    onJumpToPost: (Int) -> Unit = {},
    onRepliesLoad: (TopicPost, (List<TopicPost>?) -> Unit) -> Unit = { _, done -> done(null) }
) {
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
            TopicPostItem(
                post = post,
                flashTargetPostId = flashTargetPostId,
                onOpenLink = onOpenLink,
                onOpenImages = onOpenImages,
                onReply = { /* 定向回复输入框：后续切片 */ },
                onJumpToPost = onJumpToPost,
                onToggleReaction = onToggleReaction,
                onToggleBookmark = onToggleBookmark,
                onCopyPostLink = onCopyPostLink,
                onRepliesLoad = onRepliesLoad
            )
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
