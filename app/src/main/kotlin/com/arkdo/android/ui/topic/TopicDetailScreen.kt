package com.arkdo.android.ui.topic

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import com.arkdo.android.data.models.TopicCategory
import com.arkdo.android.data.models.TopicPost
import com.arkdo.android.data.models.TopicTag
import com.arkdo.android.data.models.User
import com.arkdo.android.services.content.LinkRoute
import com.arkdo.android.services.content.resolveLinkRoute
import com.arkdo.android.services.site.CategoryIndex
import com.arkdo.android.ui.components.ImageViewer
import com.arkdo.android.ui.components.LocalAnimatedVisibilityScope
import com.arkdo.android.ui.components.LocalSharedTransitionScope
import com.arkdo.android.ui.components.PostRichContent
import com.arkdo.android.ui.components.TopicMetadataBadges
import com.arkdo.android.ui.components.TopicPostItem
import com.arkdo.android.ui.components.ViewerImage
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import timber.log.Timber

/**
 * 话题详情页
 *
 * 结构（对齐鸿蒙 TopicDetailPage）：
 * - 内容层：LazyColumn（hazeSource，滚动内容供顶栏模糊取样）
 * - 顶栏层：渐进式模糊材质 + 双层标题（最后绘制，盖在内容之上）
 * - 图片浏览器：viewerState 非空时全屏覆盖（共享元素转场）
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
    // 单次快照：整个组合只用这一份 TopicDetailUiState（不可变 data class）。
    // 不这样做的话，VM 在桥回调线程写状态时，两次 uiState 读可能看到不同对象——
    // 「when 判完 topic 非空、下一行 topic!! 就 NPE」的崩溃就是这个撕裂读。
    val ui = uiState
    val listState = rememberLazyListState()
    val hazeState = remember { HazeState() }
    val uriHandler = LocalUriHandler.current
    // 图片浏览器覆盖层状态（本楼图片组 + 起始下标）。
    var viewerState by remember { mutableStateOf<Pair<List<ViewerImage>, Int>?>(null) }
    // 链接路由（LinkRouter，对齐鸿蒙四类）：TOPIC → 原生详情页（本帖内则直接定位）；
    // DOWNLOAD / USER / WEB 的原生页面未迁移前暂交系统浏览器，日志留痕。
    val onOpenUri: (String) -> Unit = { url ->
        when (val route = resolveLinkRoute(url)) {
            is LinkRoute.Topic -> {
                if (route.topicId == topicId && route.targetPostNumber != null) {
                    // 本帖内链接：不压栈，直接滚动/换窗定位。
                    viewModel.jumpToPostNumber(route.targetPostNumber)
                } else {
                    onNavigateToTopic(route.topicId, null, route.targetPostNumber)
                }
            }
            is LinkRoute.Download -> {
                Timber.i("[ArkDO][LinkRouter] download url=%s", route.url)
                try {
                    uriHandler.openUri(route.url)
                } catch (_: Exception) {
                }
            }
            is LinkRoute.User -> {
                Timber.i("[ArkDO][LinkRouter] user=%s url=%s", route.username, route.url)
                try {
                    uriHandler.openUri(route.url)
                } catch (_: Exception) {
                }
            }
            is LinkRoute.Web -> {
                Timber.i("[ArkDO][LinkRouter] web url=%s", route.url)
                try {
                    uriHandler.openUri(route.url)
                } catch (_: Exception) {
                }
            }
        }
    }

    // 模糊渐入进度：对齐鸿蒙 blurEffective 0..8vp 区间（滚动 8vp 内材质从透明到全模糊）。
    val blurRampPx = with(LocalDensity.current) { 8.dp.toPx() }
    val headerBlurProgress by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / blurRampPx).coerceIn(0f, 1f)
        }
    }
    val headerHeight = WindowInsets.statusBars
        .add(WindowInsets(bottom = 56.dp))
        .asPaddingValues()
        .calculateTopPadding()

    LaunchedEffect(topicId, targetPostNumber) {
        viewModel.loadTopic(topicId, slug, title, targetPostNumber)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
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
                        when {
                            ui.error != null && ui.topic == null -> {
                                ErrorView(
                                    message = ui.error,
                                    onRetry = { viewModel.retry() }
                                )
                            }
                            // topic 为空且无错误 = 加载中（含首帧 LaunchedEffect 尚未
                            // 触发的空档，避免整屏空白只剩顶栏）。
                            ui.topic == null -> {
                                LoadingView()
                            }
                            else -> ui.topic?.let { topic ->
                                TopicDetailContent(
                                    hazeState = hazeState,
                                    headerTopPadding = headerHeight,
                                    listState = listState,
                                    topic = topic,
                                    onOpenImages = { images, start -> viewerState = images to start },
                                    categories = ui.categories,
                                    posts = ui.posts,
                                    isLoadingMore = ui.isLoadingMore,
                                    hasMore = ui.hasMore,
                                    onLoadMore = { viewModel.loadMorePosts() },
                                    targetPostNumber = targetPostNumber,
                                    flashTargetPostId = ui.flashTargetPostId,
                                    // 跳楼/换窗的一次性滚动请求来自 VM（jumpToPostNumber / rewindow）。
                                    scrollRequestPostNumber = ui.scrollRequestPostNumber,
                                    onScrollRequestConsumed = { viewModel.consumeScrollRequest() },
                                    hasEarlier = ui.hasEarlier,
                                    earlierCount = ui.earlierCount,
                                    isLoadingEarlier = ui.isLoadingEarlier,
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

        // 顶栏最后绘制：盖在共享元素覆盖层之上（zIndex 置顶），模糊材质统一罩住
        // 文字/图片/代码块；图片浏览器打开时隐藏（浏览器自带 ✕/分享顶栏）。
        if (viewerState == null) {
            TopicDetailHeaderBar(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .zIndex(2f),
                summaryVisible = headerBlurProgress < 1f,
                title = ui.topic?.title ?: title ?: "话题详情",
                category = CategoryIndex.find(ui.categories, ui.topic?.categoryId),
                tags = ui.topic?.tags.orEmpty(),
                hazeState = hazeState,
                onBack = onNavigateBack,
                onTitleClick = { viewModel.jumpToPostNumber(1) }
            )
        }
    }
}

@Composable
private fun TopicDetailContent(
    hazeState: HazeState,
    headerTopPadding: Dp,
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
        contentPadding = PaddingValues(top = headerTopPadding),
        modifier = Modifier
            .fillMaxSize()
            .hazeSource(hazeState)
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

/**
 * 详情页顶栏：HDS 渐进式模糊材质 + 双层标题（对齐鸿蒙 TopicDetailHeader.ets）。
 *
 * 图层 A：摘要在视口内时的静态标题「帖子详情」；图层 B：滑出后的帖子标题 + 分类/标签行。
 * 两层常驻，透明度 + 竖向位移（±8dp）随 summaryVisible 切换（250ms EaseInOut）。
 * 图层 B 整行可点：回一楼（跳到第一楼，对齐鸿蒙 jumpToFirstPost）。
 * 材质：backdrop blur 24 + 渐变蒙版（HazeProgressive，越靠下越弱），强度随滚动 0..8vp 渐入。
 */
@Composable
private fun TopicDetailHeaderBar(
    modifier: Modifier = Modifier,
    summaryVisible: Boolean,
    title: String,
    category: TopicCategory?,
    tags: List<TopicTag>,
    hazeState: HazeState,
    onBack: () -> Unit,
    onTitleClick: () -> Unit
) {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val headerHeight = statusBarPadding + 56.dp
    val layerAlphaA by animateFloatAsState(
        targetValue = if (summaryVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 250, easing = EaseInOut),
        label = "titleLayerA"
    )
    val layerAlphaB by animateFloatAsState(
        targetValue = if (summaryVisible) 0f else 1f,
        animationSpec = tween(durationMillis = 250, easing = EaseInOut),
        label = "titleLayerB"
    )
    val density = LocalDensity.current
    val translateA = with(density) { (if (summaryVisible) 0.dp else (-8).dp).toPx() }
    val translateB = with(density) { (if (summaryVisible) 8.dp else 0.dp).toPx() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(headerHeight)
    ) {
        // 材质层：backdrop 高斯模糊常驻（半径 24 均匀）。
        // 说明：渐进蒙版需要 AGSL 着色器（API 33+），本机 Android 12 上 Haze 1.6.1
        // 会降级/不渲染（多效果节点共享 HazeState 亦不可靠）——均匀模糊是 API 31
        // 上 Haze 的能力上限，等目标平台升级或自写 AGSL 再上渐进。
        Box(
            modifier = Modifier
                .matchParentSize()
                .hazeEffect(state = hazeState) {
                    blurRadius = 24.dp
                    noiseFactor = 0f
                }
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 12.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Box(modifier = Modifier.weight(1f).height(56.dp)) {
                // 图层 A：静态「帖子详情」。
                Text(
                    text = "帖子详情",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .graphicsLayer { alpha = layerAlphaA; translationY = translateA }
                )
                // 图层 B：帖子标题 + 分类/标签行，整行可点回一楼。
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .graphicsLayer { alpha = layerAlphaB; translationY = translateB }
                        .clickable(enabled = layerAlphaB > 0.5f, onClick = onTitleClick)
                ) {
                    Text(
                        text = title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (category != null || tags.isNotEmpty()) {
                        TopicMetadataBadges(
                            category = category,
                            tags = tags,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
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
