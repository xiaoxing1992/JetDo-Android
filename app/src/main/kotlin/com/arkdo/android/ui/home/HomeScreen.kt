package com.arkdo.android.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.runtime.remember
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.arkdo.android.data.models.Topic
import com.arkdo.android.data.models.TopicCategory
import com.arkdo.android.data.models.TopicTag
import com.arkdo.android.services.site.CategoryIndex
import com.arkdo.android.services.topic.isDefault
import com.arkdo.android.services.topic.topicListPrimaryTitle
import com.arkdo.android.core.designsystem.FaIcon
import com.arkdo.android.core.designsystem.bottomBarClearance
import com.arkdo.android.ui.components.TopicListItem as EnhancedTopicListItem
import com.arkdo.android.ui.components.UserAvatar
import com.arkdo.android.ui.theme.ArkdoFixedColors
import com.arkdo.android.ui.theme.arkdoColors
import kotlinx.coroutines.launch

// ==========================================================================
// 对齐鸿蒙版 views/pages/HomePage.ets 的首页骨架。
//
// 已对齐：顶栏构成与尺寸、新话题提示条、双态 FAB、列表页脚、各加载/空/错误态。
// 尚未移植（鸿蒙侧独立组件，属另一批工作）：
//   · 版本公告 bindSheet
//   · 沉浸式标题栏（列表穿到标题栏下方 + 渐变模糊）
// ==========================================================================

/** 顶栏三枚图标按钮统一规格。 */
private val BAR_BUTTON_SIZE = 42.dp
private val BAR_HEIGHT = 56.dp
private val FAB_SIZE = 52.dp
private val FOOTER_HEIGHT = 54.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    isWebViewReady: Boolean = false,
    viewModel: HomeViewModel = hiltViewModel(),
    onTopicClick: (topicId: Long, targetPostNumber: Int?) -> Unit = { _, _ -> },
    onProfileClick: () -> Unit = {},
    onNotificationsClick: () -> Unit = {},
    onSearchClick: () -> Unit = {},
    onComposeClick: () -> Unit = {},
    onDebugClick: () -> Unit = {},
    isLoggedIn: Boolean = false,
    isCheckingSession: Boolean = false,
    accountUser: com.arkdo.android.data.models.User? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val colors = arkdoColors

    LaunchedEffect(isWebViewReady) {
        // 版块单独加载:卡片上的版块徽章要靠它解析,和话题列表是否已有数据无关。
        viewModel.loadSiteMetadataIfNeeded(isWebViewReady)
        if (isWebViewReady && uiState.topics.isEmpty() && !uiState.isInitialLoading) {
            viewModel.loadInitial()
        }
    }

    LaunchedEffect(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) {
        val layoutInfo = listState.layoutInfo
        if (layoutInfo.visibleItemsInfo.isNotEmpty()) {
            val totalOffset = listState.firstVisibleItemIndex * 1000f + listState.firstVisibleItemScrollOffset
            viewModel.updateFabMode(totalOffset, layoutInfo.viewportEndOffset.toFloat())
        }
    }

    Scaffold(
        containerColor = colors.pageBg,
        // 底栏改成悬浮胶囊后,页面内容要能从底栏下方穿过:这里只保留顶/侧 inset,
        // 底部避让由列表 contentPadding 垫 bottomBarClearance,否则两层 inset 叠加会重复垫。
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Top
        ),
        topBar = {
            HomeTitleBar(
                uiState = uiState,
                isLoggedIn = isLoggedIn,
                isCheckingSession = isCheckingSession,
                accountUser = accountUser,
                onMenuClick = { viewModel.openDrawer() },
                onSearchClick = onSearchClick,
                onNotificationsClick = onNotificationsClick,
                onProfileClick = onProfileClick,
                onDebugClick = onDebugClick
            )
        },
        floatingActionButton = {
            // 只在有数据时挂载，与鸿蒙一致（空列表页面不该浮着一个发帖按钮）。
            if (uiState.topics.isNotEmpty()) {
                // 底栏是悬浮胶囊:FAB 上移一个避让高度,否则会和底栏叠在一起。
                Box(modifier = Modifier.padding(bottom = bottomBarClearance)) {
                    HomeFab(
                        showBackToTop = uiState.showBackToTopFab,
                        onCompose = onComposeClick,
                        onBackToTop = { coroutineScope.launch { listState.animateScrollToItem(0) } }
                    )
                }
            }
        }
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (uiState.topics.isEmpty()) {
                EmptyStateView(
                    isWebViewReady = isWebViewReady,
                    isInitialLoading = uiState.isInitialLoading,
                    error = uiState.error,
                    feedTitle = feedTitle(uiState),
                    onRetry = { viewModel.retry() }
                )
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    IncomingTopicsPrompt(
                        visible = uiState.incomingPromptVisible,
                        count = uiState.incomingTopicIds.size,
                        loading = uiState.isLoadingIncoming,
                        onClick = { viewModel.loadIncomingTopics() }
                    )

                    TopicList(
                        listState = listState,
                        topics = uiState.topics,
                        categories = uiState.categories,
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

/** 当前 feed 标题。默认条件下主标题为空，鸿蒙侧回退文案就是 'ArkDO'。 */
private fun feedTitle(uiState: HomeUiState): String =
    topicListPrimaryTitle(uiState.listQuery).ifEmpty { "ArkDO" }

// ========== 顶栏 ==========

@Composable
private fun HomeTitleBar(
    uiState: HomeUiState,
    isLoggedIn: Boolean,
    isCheckingSession: Boolean,
    accountUser: com.arkdo.android.data.models.User?,
    onMenuClick: () -> Unit,
    onSearchClick: () -> Unit,
    onNotificationsClick: () -> Unit,
    onProfileClick: () -> Unit,
    onDebugClick: () -> Unit
) {
    val colors = arkdoColors
    val isDefaultQuery = uiState.listQuery.isDefault()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 状态栏 inset 由这里垫(根 Scaffold 已关掉系统栏排布,内容顶到屏幕顶)。
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(BAR_HEIGHT)
            .background(colors.pageBg)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MenuButton(onClick = onMenuClick)

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 4.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = feedTitle(uiState),
                // 默认条件下是大标题；带筛选时缩到 21，给副行让位。
                fontSize = if (isDefaultQuery) 26.sp else 21.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        BarIconButton(onClick = onSearchClick, endMargin = 4.dp) {
            FaIcon("magnifying-glass", colors.textPrimary, 20.dp)
        }

        // 通知铃铛只在登录后显示(对齐鸿蒙:未登录无通知入口)。
        if (isLoggedIn) {
            BarIconButton(onClick = onNotificationsClick, endMargin = 4.dp) {
                FaIcon("bell", ArkdoFixedColors.Amber, 24.dp)
            }
        }

        // 账号入口(对齐鸿蒙 AccountEntry):会话检查中转圈;已登录显示头像(34);
        // 未登录显示 person 图标(brand 色,28)。点击恒定进入个人中心。
        BarIconButton(onClick = onProfileClick, endMargin = 0.dp) {
            when {
                isCheckingSession -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = colors.brand,
                        strokeWidth = 2.dp
                    )
                }
                isLoggedIn && accountUser != null -> {
                    UserAvatar(user = accountUser, avatarSize = 34.dp)
                }
                else -> {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = "个人中心",
                        tint = colors.brand,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        // 调试入口(排障用):一键把日志缓冲落盘并调起系统分享面板,不依赖登录态。
        BarIconButton(onClick = onDebugClick, endMargin = 0.dp) {
            Icon(
                imageVector = Icons.Filled.BugReport,
                contentDescription = "分享日志",
                tint = colors.textTertiary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}/** 汉堡按钮：三条 18×2 的横线，间距 4。 */
@Composable
private fun MenuButton(onClick: () -> Unit) {
    val colors = arkdoColors
    Box(
        modifier = Modifier
            .padding(end = 10.dp)
            .size(BAR_BUTTON_SIZE)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(3) {
                HorizontalDivider(
                    modifier = Modifier.width(18.dp),
                    thickness = 2.dp,
                    color = colors.textPrimary
                )
            }
        }
    }
}

@Composable
private fun BarIconButton(
    onClick: () -> Unit,
    endMargin: androidx.compose.ui.unit.Dp,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .padding(end = endMargin)
            .size(BAR_BUTTON_SIZE)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

// ========== 新话题提示条 ==========

@Composable
private fun IncomingTopicsPrompt(
    visible: Boolean,
    count: Int,
    loading: Boolean,
    onClick: () -> Unit
) {
    val colors = arkdoColors

    AnimatedVisibility(
        visible = visible && count > 0,
        enter = expandVertically(animationSpec = tween(HomeConstants.INCOMING_PROMPT_ENTER_MS.toInt())) +
            fadeIn(animationSpec = tween(HomeConstants.INCOMING_PROMPT_ENTER_MS.toInt())),
        exit = shrinkVertically(animationSpec = tween(HomeConstants.INCOMING_PROMPT_EXIT_MS.toInt())) +
            fadeOut(animationSpec = tween(HomeConstants.INCOMING_PROMPT_EXIT_MS.toInt()))
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(HomeConstants.INCOMING_PROMPT_HEIGHT)
                    .background(colors.infoSoftBg)
                    .clickable(enabled = !loading, onClick = onClick)
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "查看 $count 个新的或更新的话题",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = ArkdoFixedColors.BrandBlue,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (loading) {
                    Spacer(modifier = Modifier.width(8.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = ArkdoFixedColors.BrandBlue
                    )
                }
            }
            HorizontalDivider(thickness = 1.dp, color = colors.border)
        }
    }
}

// ========== 双态 FAB ==========

@Composable
private fun HomeFab(
    showBackToTop: Boolean,
    onCompose: () -> Unit,
    onBackToTop: () -> Unit
) {
    val colors = arkdoColors

    FloatingActionButton(
        onClick = if (showBackToTop) onBackToTop else onCompose,
        modifier = Modifier.size(FAB_SIZE),
        containerColor = colors.pageBg,
        contentColor = if (showBackToTop) colors.fabTopFg else colors.fabComposeBg,
        shape = CircleShape
    ) {
        FaIcon(
            iconId = if (showBackToTop) "arrow-up" else "pen-to-square",
            color = if (showBackToTop) colors.fabTopFg else colors.fabComposeBg,
            size = 22.dp
        )
    }
}

// ========== 列表 ==========

@Composable
private fun TopicList(
    listState: androidx.compose.foundation.lazy.LazyListState,
    topics: List<Topic>,
    categories: List<TopicCategory>,
    isLoadingMore: Boolean,
    hasMore: Boolean,
    onTopicClick: (topicId: Long, targetPostNumber: Int?) -> Unit,
    onLoadMore: () -> Unit
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        // 内容从悬浮底栏下穿过:滚到底时最后一条要能滑出底栏区域。
        contentPadding = PaddingValues(bottom = bottomBarClearance)
    ) {
        items(items = topics, key = { it.id }) { topic ->
            EnhancedTopicListItem(
                topic = topic,
                // 对齐鸿蒙 HomePage.ets:707-708，版块徽章靠这两个查出来
                category = CategoryIndex.find(categories, topic.categoryId),
                parentCategory = CategoryIndex.findParent(categories, topic.categoryId),
                onClick = { onTopicClick(topic.id, null) },
                // 对齐鸿蒙 handleUnreadBadgeClick：未读行点击直跳第一条未读楼，
                // 不是和普通行一样从顶部打开 —— 之前这两个 lambda 传的是同一个，
                // 鸿蒙的跳楼语义整条丢失。
                onUnreadBadgeClick = { onTopicClick(topic.id, topic.firstUnreadPostNumber()) }
                // 版块/标签徽标不可点：对齐鸿蒙 TopicListItem（列表徽标无点击事件），
                // 点徽标就是点整行进详情。
            )
        }

        item {
            LaunchedEffect(hasMore, isLoadingMore) {
                if (hasMore && !isLoadingMore) onLoadMore()
            }
            ListFooter(isLoadingMore = isLoadingMore, hasMore = hasMore)
        }
    }
}

/** 列表页脚，对齐鸿蒙 ListFooter.ets。 */
@Composable
private fun ListFooter(isLoadingMore: Boolean, hasMore: Boolean) {
    val colors = arkdoColors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(FOOTER_HEIGHT),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isLoadingMore) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = colors.brand
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = when {
                isLoadingMore -> "正在加载更多"
                hasMore -> "上拉加载更多"
                else -> "没有更多了"
            },
            fontSize = 13.sp,
            color = colors.textSecondary
        )
    }
}

// ========== 空 / 加载 / 错误态 ==========

@Composable
private fun EmptyStateView(
    isWebViewReady: Boolean,
    isInitialLoading: Boolean,
    error: String?,
    feedTitle: String,
    onRetry: () -> Unit
) {
    val colors = arkdoColors

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            when {
                !isWebViewReady -> {
                    Text(
                        text = "准备环境中...",
                        fontSize = 15.sp,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center
                    )
                }

                isInitialLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(30.dp),
                        strokeWidth = 3.dp,
                        color = colors.ldAccent
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "正在加载$feedTitle",
                        fontSize = 15.sp,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center
                    )
                }

                error != null -> {
                    Text(
                        text = "加载失败，请检查网络后重试",
                        fontSize = 15.sp,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .height(38.dp)
                            .clip(RoundedCornerShape(19.dp))
                            .background(colors.fillSubtle)
                            .clickable(onClick = onRetry)
                            .padding(horizontal = 18.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "重试", fontSize = 15.sp, color = colors.ldAccent)
                    }
                }

                else -> {
                    Text(
                        text = "暂无帖子数据",
                        fontSize = 15.sp,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
