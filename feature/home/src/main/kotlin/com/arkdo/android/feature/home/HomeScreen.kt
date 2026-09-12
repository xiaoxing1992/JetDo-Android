package com.arkdo.android.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkdo.android.core.designsystem.NotificationAvatar
import com.arkdo.android.core.model.HomeCategory
import com.arkdo.android.core.model.HomeFeed
import com.arkdo.android.core.model.HomeTag
import com.arkdo.android.core.model.HomeTopic
import com.arkdo.android.core.model.HomeUser
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToLong

private val BarButtonSize = 42.dp
private val BarHeight = 56.dp
private val FabSize = 52.dp
private val FooterHeight = 54.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onTopicClick: (Long, Int?) -> Unit,
    onMenuClick: () -> Unit,
    onProfileClick: () -> Unit,
    onNotificationsClick: () -> Unit,
    onSearchClick: () -> Unit,
    onDebugClick: () -> Unit,
    onComposeClick: () -> Unit,
    isWebViewReady: Boolean = true,
    isLoggedIn: Boolean = false,
    isCheckingSession: Boolean = false,
    accountUser: HomeUser? = null,
    onFabScroll: (Float, Float) -> Unit = { _, _ -> }
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) {
        val layoutInfo = listState.layoutInfo
        if (layoutInfo.visibleItemsInfo.isNotEmpty()) {
            val offset = listState.firstVisibleItemIndex * 1000f + listState.firstVisibleItemScrollOffset
            onFabScroll(offset, layoutInfo.viewportEndOffset.toFloat())
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            HomeTitleBar(
                feed = state.query.feed,
                isLoggedIn = isLoggedIn,
                isCheckingSession = isCheckingSession,
                accountUser = accountUser,
                onMenuClick = onMenuClick,
                onSearchClick = onSearchClick,
                onNotificationsClick = onNotificationsClick,
                onProfileClick = onProfileClick,
                onDebugClick = onDebugClick
            )
        },
        floatingActionButton = {
            if (state.topics.isNotEmpty()) {
                FloatingActionButton(
                    onClick = if (state.showBackToTopFab) {
                        { scope.launch { listState.animateScrollToItem(0) }; Unit }
                    } else onComposeClick,
                    modifier = Modifier.size(FabSize),
                    shape = CircleShape
                ) {
                    Icon(
                        imageVector = if (state.showBackToTopFab) Icons.Default.ArrowUpward else Icons.Default.Add,
                        contentDescription = if (state.showBackToTopFab) "回到顶部" else "发布主题"
                    )
                }
            }
        }
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize().padding(paddingValues)
        ) {
            if (state.topics.isEmpty()) {
                EmptyState(
                    isWebViewReady = isWebViewReady,
                    isLoading = state.isInitialLoading,
                    error = state.error,
                    feed = state.query.feed,
                    onRetry = onRetry
                )
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(state.topics, key = HomeTopic::id) { topic ->
                        HomeTopicRow(
                            topic = topic,
                            onClick = { onTopicClick(topic.id, null) },
                            onUnreadClick = { onTopicClick(topic.id, topic.firstUnreadPostNumber()) }
                        )
                    }
                    item(key = "home-footer") {
                        LaunchedEffect(state.topics.size, state.hasMore, state.isLoadingMore) {
                            if (state.hasMore && !state.isLoadingMore) onLoadMore()
                        }
                        HomeListFooter(state.isLoadingMore, state.hasMore)
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeTitleBar(
    feed: HomeFeed,
    isLoggedIn: Boolean,
    isCheckingSession: Boolean,
    accountUser: HomeUser?,
    onMenuClick: () -> Unit,
    onSearchClick: () -> Unit,
    onNotificationsClick: () -> Unit,
    onProfileClick: () -> Unit,
    onDebugClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(BarHeight)
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HomeBarButton(onClick = onMenuClick, contentDescription = "打开分类") {
            Icon(Icons.Default.Menu, contentDescription = null, modifier = Modifier.size(24.dp))
        }
        Text(
            text = if (feed == HomeFeed.LATEST) "ArkDO" else feed.label,
            modifier = Modifier.weight(1f).padding(end = 4.dp),
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = if (feed == HomeFeed.LATEST) 26.sp else 21.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        HomeBarButton(onClick = onSearchClick, contentDescription = "搜索") {
            Icon(Icons.Default.Search, contentDescription = null)
        }
        if (isLoggedIn) {
            HomeBarButton(onClick = onNotificationsClick, contentDescription = "通知") {
                Icon(Icons.Default.NotificationsNone, contentDescription = null)
            }
        }
        HomeBarButton(onClick = onProfileClick, contentDescription = "个人中心") {
            when {
                isCheckingSession -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                accountUser != null -> NotificationAvatar(
                    name = accountUser.name ?: accountUser.username,
                    username = accountUser.username,
                    avatarTemplate = accountUser.avatarTemplate,
                    size = 34.dp
                )
                else -> Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(28.dp))
            }
        }
        HomeBarButton(onClick = onDebugClick, contentDescription = "分享日志") {
            Icon(Icons.Default.BugReport, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun HomeBarButton(
    onClick: () -> Unit,
    contentDescription: String,
    content: @Composable () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(BarButtonSize)
    ) {
        content()
    }
}

@Composable
private fun HomeTopicRow(
    topic: HomeTopic,
    onClick: () -> Unit,
    onUnreadClick: () -> Unit
) {
    val unread = topic.unreadPosts > 0
    val click = if (unread && topic.firstUnreadPostNumber() != null) onUnreadClick else onClick
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = click)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top
        ) {
            NotificationAvatar(
                name = topic.author?.name ?: topic.author?.username.orEmpty(),
                username = topic.author?.username.orEmpty(),
                avatarTemplate = topic.author?.avatarTemplate,
                size = 48.dp
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = buildTitle(topic),
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 23.sp
                    )
                    if (unread) {
                        Text(
                            text = " ${topic.unreadPosts} ",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .padding(start = 6.dp)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(9.dp))
                                .padding(horizontal = 2.dp, vertical = 1.dp)
                        )
                    } else if (topic.unseen) {
                        Text(" •", color = MaterialTheme.colorScheme.primary, fontSize = 17.sp)
                    }
                }
                val hasMetadata = topic.category != null || topic.tags.isNotEmpty() || !topic.lastPostedAt.isNullOrBlank()
                if (hasMetadata) {
                    Spacer(Modifier.height(3.dp))
                    HomeMetadataRow(topic.category, topic.tags, topic.lastPostedAt)
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
    }
}

private fun buildTitle(topic: HomeTopic): String = buildString {
    if (topic.bookmarked) append("[书签] ")
    if (topic.hasAcceptedAnswer) append("[已解决] ")
    append(topic.title)
}

@Composable
private fun HomeMetadataRow(category: HomeCategory?, tags: List<HomeTag>, postedAt: String?) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            category?.let { HomeBadge(it.name, MaterialTheme.colorScheme.secondaryContainer) }
            tags.take(2).forEach { HomeBadge("#${it.name}", MaterialTheme.colorScheme.surfaceVariant) }
        }
        relativeTime(postedAt)?.let {
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
    }
}

@Composable
private fun HomeBadge(text: String, background: Color) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .background(background, RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp)
    )
}

@Composable
private fun HomeListFooter(isLoading: Boolean, hasMore: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().height(FooterHeight),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isLoading) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = when {
                isLoading -> "正在加载更多"
                hasMore -> "上拉加载更多"
                else -> "没有更多了"
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun EmptyState(
    isWebViewReady: Boolean,
    isLoading: Boolean,
    error: String?,
    feed: HomeFeed,
    onRetry: () -> Unit
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            when {
                !isWebViewReady -> Text("准备环境中...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                isLoading -> {
                    CircularProgressIndicator(Modifier.size(30.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("正在加载${feed.label}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                error != null -> {
                    Text(
                        "加载失败，请检查网络后重试",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(12.dp))
                    IconButton(onClick = onRetry) {
                        Icon(Icons.Default.Refresh, contentDescription = "重试")
                    }
                }
                else -> Text("暂无帖子数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun relativeTime(iso: String?): String? {
    val then = runCatching { Instant.parse(iso) }.getOrNull() ?: return null
    val minutes = Duration.between(then, Instant.now()).toMinutes().coerceAtLeast(0)
    return when {
        minutes < 1 -> "刚刚"
        minutes <= 44 -> "$minutes 分钟"
        minutes <= 89 -> "1 小时"
        minutes <= 1_409 -> "${(minutes / 60.0).roundToLong()} 小时"
        minutes <= 2_519 -> "1 天"
        minutes <= 20_160 -> "${(minutes / 1_440.0).roundToLong()} 天"
        else -> iso?.take(10)?.replace('-', '/')
    }
}
