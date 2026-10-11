package com.jetdo.android.feature.home

import androidx.compose.ui.res.stringResource
import com.jetdo.android.feature.home.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.PlatformTextStyle
import com.jetdo.android.core.designsystem.FaIcon
import com.jetdo.android.core.designsystem.FabGlassStore
import com.jetdo.android.core.designsystem.GlassFab
import com.jetdo.android.core.designsystem.bottomBarClearance
import com.jetdo.android.core.designsystem.searchIconSharedElement
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.jetdo.android.core.model.HomeCategory
import com.jetdo.android.core.model.HomeQuery
import com.jetdo.android.core.model.HomeTag
import com.jetdo.android.core.model.HomeTopic
import com.jetdo.android.core.model.HomeUser
import com.jetdo.android.core.designsystem.TopicAvatar
import com.jetdo.android.core.designsystem.TopicRow
import com.jetdo.android.core.designsystem.TopicRowColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val BarButtonSize = 42.dp
private val BarHeight = 56.dp
private val FooterHeight = 54.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(
    state: HomeUiState,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onIncomingTopicsClick: () -> Unit,
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
    // 通知未读数：顶栏铃铛角标（真源是 NotificationEventBus，由宿主下发）。
    unreadCount: Int = 0,
    // 「点分类/标签快速筛选」：开关开着时行上的徽章可点（由 HomeRoute 判定并处理）。
    badgesTappable: Boolean = false,
    onBadgeTagClick: (HomeTag) -> Unit = {},
    onBadgeCategoryClick: (HomeCategory) -> Unit = {},
    onFabScroll: (Float, Float) -> Unit = { _, _ -> },
    onIncomingInsertFinished: (Int) -> Unit = {},
    onIncomingHighlightFinished: () -> Unit = {},
    colors: HomeVisualTokens = HomeVisualTokens.Default,
    avatarBaseUrl: String = ""
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val categories = state.categories
    val insertIds = state.incomingInsertIds
    val insertEpoch = state.incomingInsertEpoch
    var insertProgress by remember { mutableFloatStateOf(1f) }
    var insertDistancePx by remember { mutableFloatStateOf(0f) }
    var insertPlayed by remember { mutableStateOf(true) }
    val insertHeights = remember { mutableStateMapOf<Long, Int>() }
    LaunchedEffect(insertEpoch) {
        if (insertIds.isEmpty()) {
            insertProgress = 1f
            insertPlayed = true
            return@LaunchedEffect
        }
        insertHeights.clear()
        insertPlayed = false
        insertProgress = 0f
        insertDistancePx = with(density) { (insertIds.size * INCOMING_INSERT_ROW_ESTIMATE_DP).dp.toPx() }
        withFrameNanos { }
        if (insertIds.all { it in insertHeights }) {
            insertDistancePx = insertHeights.values.sum().toFloat()
        }
        // 新行插在表头，人停在下面时看不见滑入。滚回第一条必须放在插入布局落地之后：
        // LazyColumn 按 key 锚定第一条可见帖，表头插入会把「先滚到 0」顶回原位。
        if (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0) {
            listState.scrollToItem(0)
        }
        insertPlayed = true
        animate(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = tween(durationMillis = INCOMING_INSERT_MS, easing = EaseOut)
        ) { value, _ ->
            insertProgress = value
        }
        onIncomingInsertFinished(insertEpoch)
    }

    val highlightUntil = state.incomingHighlightUntil
    val highlightIds = state.incomingHighlightIds
    val glassEnabled by FabGlassStore.enabled.collectAsState()
    val glassRadius by FabGlassStore.radius.collectAsState()
    // 只在开关打开时录列表。按钮在这层外面取样，避免采到自己所在的祖先层。
    val contentBackdrop = if (glassEnabled) rememberLayerBackdrop() else null
    LaunchedEffect(highlightUntil, highlightIds) {
        if (highlightIds.isEmpty() || highlightUntil <= 0L) return@LaunchedEffect
        val total = (highlightUntil - System.currentTimeMillis()).coerceAtLeast(0L)
        delay(incomingHighlightHoldMs(total) + INCOMING_HIGHLIGHT_FADE_MS)
        onIncomingHighlightFinished()
    }

    var refreshArmed by remember { mutableStateOf(false) }
    LaunchedEffect(state.isRefreshing) {
        if (state.isRefreshing) {
            refreshArmed = true
            return@LaunchedEffect
        }
        if (refreshArmed && state.topics.isNotEmpty() &&
            (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0)
        ) {
            // 刷新是整表替换，key 锚定同样会顶掉刷新期间发出的滚动。等新布局落地再回顶。
            withFrameNanos { }
            listState.scrollToItem(0)
        }
        refreshArmed = false
    }

    LaunchedEffect(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) {
        val layoutInfo = listState.layoutInfo
        if (layoutInfo.visibleItemsInfo.isNotEmpty()) {
            val offset = listState.firstVisibleItemIndex * 1000f + listState.firstVisibleItemScrollOffset
            onFabScroll(offset, layoutInfo.viewportEndOffset.toFloat())
        }
    }

    Scaffold(
        containerColor = colors.pageBg,
        // 悬浮底栏下内容全出血:只保留顶/侧 inset,底部避让由列表 contentPadding 垫。
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Top
        ),
        topBar = {
            HomeTitleBar(
                query = state.query,
                categoryName = state.categories.firstOrNull { it.id == state.query.categoryId }?.name,
                isLoggedIn = isLoggedIn,
                isCheckingSession = isCheckingSession,
                accountUser = accountUser,
                onMenuClick = onMenuClick,
                onSearchClick = onSearchClick,
                onNotificationsClick = onNotificationsClick,
                // 顶栏铃铛的未读角标（对齐鸿蒙 NotificationBell 的 unreadCount @Prop）。
                unreadCount = unreadCount,
                onProfileClick = onProfileClick,
                onDebugClick = onDebugClick,
                colors = colors,
                avatarBaseUrl = avatarBaseUrl
            )
        },
        floatingActionButton = {
            if (state.topics.isNotEmpty()) {
                val backToTop = state.showBackToTopFab
                val iconColor = if (backToTop) colors.fabTopFg else colors.fabComposeBg
                // 底栏是悬浮胶囊:FAB 上移一个避让高度,避免与底栏重叠。
                GlassFab(
                    icon = {
                        FaIcon(
                            iconId = if (backToTop) "arrow-up" else "pen-to-square",
                            color = iconColor,
                            size = 22.dp
                        )
                    },
                    onClick = if (backToTop) {
                        { scope.launch { listState.animateScrollToItem(0) }; Unit }
                    } else onComposeClick,
                    glassEnabled = glassEnabled,
                    glassRadius = glassRadius,
                    backdrop = contentBackdrop,
                    fallbackColor = colors.pageBg,
                    glassTint = colors.fabGlassTint,
                    modifier = Modifier.padding(bottom = bottomBarClearance)
                )
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
                    title = homeTitle(state.query),
                    onRetry = onRetry,
                    colors = colors
                )
            } else {
                val listBackdrop = contentBackdrop
                Column(
                    modifier = if (listBackdrop != null) {
                        Modifier.fillMaxSize().layerBackdrop(listBackdrop)
                    } else {
                        Modifier.fillMaxSize()
                    }
                ) {
                    IncomingTopicsPrompt(
                        visible = state.incomingPromptVisible,
                        count = state.incomingTopicIds.size,
                        loading = state.isLoadingIncoming,
                        onClick = onIncomingTopicsClick,
                        colors = colors
                    )
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        // 内容从悬浮底栏下穿过:滚到底时最后一条要能滑出底栏区域。
                        contentPadding = PaddingValues(bottom = bottomBarClearance)
                    ) {
                        items(state.topics, key = HomeTopic::id) { topic ->
                            val category = homeCategoryForTopic(topic, categories, state.query)
                            val inserting = topic.id in insertIds
                            HomeTopicRow(
                                topic = topic,
                                category = category,
                                parentCategory = category?.parentCategoryId?.let { parentId ->
                                    categories.firstOrNull { it.id == parentId }
                                },
                                onClick = { onTopicClick(topic.id, null) },
                                onUnreadClick = { onTopicClick(topic.id, homeUnreadTarget(topic)) },
                                colors = colors,
                                avatarBaseUrl = avatarBaseUrl,
                                insertProgress = insertProgress,
                                insertDistancePx = if (inserting) 0f else insertDistancePx,
                                insertFadeIn = inserting,
                                highlightUntil = if (topic.id in state.incomingHighlightIds) {
                                    state.incomingHighlightUntil
                                } else {
                                    0L
                                },
                                onInsertMeasured = if (inserting && !insertPlayed) {
                                    { height ->
                                        if (topic.id !in insertHeights) {
                                            insertHeights[topic.id] = height
                                        }
                                    }
                                } else {
                                    null
                                },
                                badgesTappable = badgesTappable,
                                onBadgeTagClick = onBadgeTagClick,
                                onBadgeCategoryClick = onBadgeCategoryClick
                            )
                        }
                        item(key = "home-footer") {
                            LaunchedEffect(state.topics.size, state.hasMore, state.isLoadingMore) {
                                if (state.hasMore && !state.isLoadingMore) onLoadMore()
                            }
                            HomeListFooter(state.isLoadingMore, state.hasMore, colors)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeTitleBar(
    query: HomeQuery,
    categoryName: String?,
    isLoggedIn: Boolean,
    isCheckingSession: Boolean,
    accountUser: HomeUser?,
    onMenuClick: () -> Unit,
    onSearchClick: () -> Unit,
    onNotificationsClick: () -> Unit,
    unreadCount: Int = 0,
    onProfileClick: () -> Unit,
    onDebugClick: () -> Unit,
    colors: HomeVisualTokens,
    avatarBaseUrl: String
) {
    val lead = query.summaryLead()
    val tag = query.summaryTag()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(BarHeight)
            .background(colors.pageBg)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HomeMenuButton(onClick = onMenuClick, colors = colors)
        Column(
            modifier = Modifier.weight(1f).padding(end = 4.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = homeTitle(query, categoryName),
                color = colors.textPrimary,
                fontSize = if (query.isDefaultHomeQuery()) 26.sp else 21.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (lead.isNotEmpty() || tag != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (lead.isNotEmpty()) {
                        Text(
                            text = lead,
                            color = colors.textSecondary,
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                    }
                    if (tag != null) {
                        if (lead.isNotEmpty()) {
                            Text(
                                text = " · ",
                                color = colors.textTertiary,
                                fontSize = 12.sp
                            )
                        }
                        Text(
                            text = tag,
                            color = colors.brand,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        HomeBarButton(onClick = onSearchClick, contentDescription = stringResource(R.string.search_title), endMargin = 4.dp) {
            // 挂共享元素的是图标自身、不是外面 40dp 的圆底：目的端（搜索条）那侧
            // 没有圆底可对应，挂按钮会让整个圆一起飞过去。见 SharedSearchTransition。
            FaIcon(
                "magnifying-glass",
                colors.textPrimary,
                20.dp,
                Modifier.searchIconSharedElement()
            )
        }
        if (isLoggedIn) {
            // 通知铃铛：有未读时挂红色数字角标 + 间歇摇铃（对齐鸿蒙 NotificationBell.ets）。
            // 此前只有一个静态铃铛图标：未读数只喂给了底部 tab，首页顶栏完全看不出有没有未读。
            val bellAngle = remember { androidx.compose.animation.core.Animatable(0f) }
            LaunchedEffect(unreadCount) {
                if (unreadCount <= 0) {
                    bellAngle.animateTo(0f)
                    return@LaunchedEffect
                }
                // 一轮阻尼摇铃 14°→-12°→9°→-6°→3°→0°，之后停顿 3.2s 再下一轮（间歇式）。
                while (true) {
                    bellAngle.animateTo(
                        0f,
                        androidx.compose.animation.core.keyframes {
                            durationMillis = 570
                            14f at 110
                            -12f at 220
                            9f at 320
                            -6f at 410
                            3f at 490
                            0f at 570
                        }
                    )
                    delay(3200)
                }
            }
            HomeBarButton(onClick = onNotificationsClick, contentDescription = stringResource(R.string.home_notifications), endMargin = 4.dp) {
                // 角标要相对铃铛定位，得自己持有一个 BoxScope（HomeBarButton 的 content 是普通 @Composable）。
                Box(contentAlignment = Alignment.Center) {
                    FaIcon("bell", colors.amber, 24.dp, Modifier.rotate(bellAngle.value))
                    if (unreadCount > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 3.dp, y = (-3).dp)
                                .defaultMinSize(minWidth = 15.dp, minHeight = 15.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFF5222D))
                                .padding(horizontal = 3.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (unreadCount > 99) "99+" else unreadCount.toString(),
                                color = Color.White,
                                fontSize = 10.sp,
                                maxLines = 1,
                                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                            )
                        }
                    }
                }
            }
        }
        HomeBarButton(onClick = onProfileClick, contentDescription = stringResource(R.string.profile_center), endMargin = 0.dp) {
            when {
                isCheckingSession -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                isLoggedIn && accountUser != null -> TopicAvatar(accountUser, 34.dp, colors.toTopicRowColors(), avatarBaseUrl)
                else -> Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = stringResource(R.string.profile_center),
                    tint = colors.brand,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
        HomeBarButton(onClick = onDebugClick, contentDescription = stringResource(R.string.debug_share_log), endMargin = 0.dp) {
            Icon(
                imageVector = Icons.Filled.BugReport,
                contentDescription = stringResource(R.string.debug_share_log),
                tint = colors.textTertiary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun HomeMenuButton(onClick: () -> Unit, colors: HomeVisualTokens) {
    Box(
        modifier = Modifier
            .padding(end = 10.dp)
            .size(BarButtonSize)
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
private fun HomeBarButton(
    onClick: () -> Unit,
    contentDescription: String,
    endMargin: androidx.compose.ui.unit.Dp,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .padding(end = endMargin)
            .size(BarButtonSize)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun HomeTopicRow(
    topic: HomeTopic,
    category: HomeCategory?,
    parentCategory: HomeCategory?,
    onClick: () -> Unit,
    onUnreadClick: () -> Unit,
    colors: HomeVisualTokens,
    avatarBaseUrl: String,
    insertProgress: Float = 1f,
    insertDistancePx: Float = 0f,
    insertFadeIn: Boolean = false,
    highlightUntil: Long = 0L,
    onInsertMeasured: ((Int) -> Unit)? = null,
    badgesTappable: Boolean = false,
    onBadgeTagClick: (HomeTag) -> Unit = {},
    onBadgeCategoryClick: (HomeCategory) -> Unit = {}
) {
    val highlight = rememberIncomingHighlight(highlightUntil, colors)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { size -> onInsertMeasured?.invoke(size.height) }
            .graphicsLayer {
                translationY = incomingInsertOffset(insertProgress, insertDistancePx)
                alpha = incomingInsertAlpha(insertProgress, insertFadeIn)
            }
    ) {
        TopicRow(
            topic = topic,
            category = category,
            parentCategory = parentCategory,
            onClick = onClick,
            onUnreadClick = onUnreadClick,
            colors = colors.toTopicRowColors(),
            avatarBaseUrl = avatarBaseUrl,
            badgesTappable = badgesTappable,
            onCategoryClick = onBadgeCategoryClick,
            onTagClick = onBadgeTagClick,
            background = highlight
        )
    }
}

private fun HomeVisualTokens.toTopicRowColors(): TopicRowColors = TopicRowColors(
    pageBg = pageBg,
    textSecondary = textSecondary,
    textBody = textBody,
    brand = brand,
    border = border,
    surfaceMuted = surfaceMuted,
    avatarFg = avatarFg,
    avatarBorder = avatarBorder,
    unread = unread
)

@Composable
private fun rememberIncomingHighlight(until: Long, colors: HomeVisualTokens): Color {
    val shown = until > 0L
    val color by androidx.compose.animation.animateColorAsState(
        targetValue = if (shown) colors.incomingHighlight else colors.pageBg,
        animationSpec = tween(durationMillis = INCOMING_HIGHLIGHT_FADE_MS.toInt(), easing = EaseOut),
        label = "incomingHighlight"
    )
    return color
}

private val PromptTextStyle = TextStyle(
    fontSize = 14.sp,
    fontWeight = FontWeight.Medium,
    lineHeight = 18.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false)
)

@Composable
private fun IncomingTopicsPrompt(
    visible: Boolean,
    count: Int,
    loading: Boolean,
    onClick: () -> Unit,
    colors: HomeVisualTokens
) {
    AnimatedVisibility(
        visible = visible && count > 0,
        enter = expandVertically(animationSpec = tween(240)) + fadeIn(animationSpec = tween(240)),
        exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(animationSpec = tween(180))
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(colors.infoSoftBg)
                    .clickable(enabled = !loading, onClick = onClick)
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = stringResource(R.string.common_view), color = colors.ldAccent, style = PromptTextStyle)
                IncomingCountDigits(count, colors.ldAccent)
                Text(
                    text = stringResource(R.string.announce_topics_suffix),
                    color = colors.ldAccent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = PromptTextStyle
                )
                if (loading) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.ldAccent)
                }
            }
            HorizontalDivider(color = colors.border)
        }
    }
}

private val IncomingDigitHeight = 18.dp
private val IncomingDigitWidth = 9.dp

/** 个数按位滚。旧数字上滑淡出，新数字从下滑入；进位展开，退位收起。 */
@Composable
private fun IncomingCountDigits(count: Int, color: Color) {
    var previous by remember { mutableIntStateOf(count) }
    var shown by remember { mutableIntStateOf(count) }
    var progress by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(count) {
        if (count == shown) return@LaunchedEffect
        previous = shown
        shown = count
        progress = 0f
        animate(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = tween(durationMillis = INCOMING_DIGIT_MS, easing = EaseOut)
        ) { value, _ ->
            progress = value
        }
        previous = shown
    }
    Row(
        modifier = Modifier.padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        incomingDigitColumns(previous, shown).forEachIndexed { index, column ->
            IncomingDigitColumn(column, progress, color, Modifier)
        }
    }
}

@Composable
private fun IncomingDigitColumn(
    column: IncomingDigitColumn,
    progress: Float,
    color: Color,
    modifier: Modifier
) {
    val width = incomingDigitColumnWidth(column.kind, progress, with(LocalDensity.current) {
        IncomingDigitWidth.toPx()
    })
    Box(
        modifier = modifier
            .width(with(LocalDensity.current) { width.toDp() })
            .height(IncomingDigitHeight)
            .clipToBounds(),
        contentAlignment = Alignment.Center
    ) {
        if (column.kind == IncomingDigitKind.STABLE) {
            IncomingDigitText(column.current, color)
        } else {
            if (column.previous.isNotEmpty()) {
                IncomingDigitText(
                    column.previous,
                    color,
                    Modifier.graphicsLayer {
                        translationY = -IncomingDigitHeight.toPx() * progress
                        alpha = 1f - progress
                    }
                )
            }
            if (column.current.isNotEmpty()) {
                IncomingDigitText(
                    column.current,
                    color,
                    Modifier.graphicsLayer {
                        translationY = IncomingDigitHeight.toPx() * (1f - progress)
                        alpha = progress
                    }
                )
            }
        }
    }
}

@Composable
private fun IncomingDigitText(digit: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.height(IncomingDigitHeight),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = digit,
            color = color,
            textAlign = TextAlign.Center,
            style = PromptTextStyle,
            modifier = Modifier.offset(y = with(LocalDensity.current) { 1.toDp() })
        )
    }
}


@Composable
private fun HomeListFooter(isLoading: Boolean, hasMore: Boolean, colors: HomeVisualTokens) {
    Row(
        modifier = Modifier.fillMaxWidth().height(FooterHeight),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isLoading) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.brand)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = when {
                isLoading -> stringResource(R.string.common_loading_more)
                hasMore -> stringResource(R.string.common_pull_load_more)
                else -> stringResource(R.string.common_no_more)
            },
            color = colors.textSecondary,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun EmptyState(
    isWebViewReady: Boolean,
    isLoading: Boolean,
    error: String?,
    title: String,
    onRetry: () -> Unit,
    colors: HomeVisualTokens
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            when {
                !isWebViewReady -> Text(
                    text = stringResource(R.string.composer_preparing),
                    fontSize = 15.sp,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center
                )
                isLoading -> {
                    CircularProgressIndicator(
                        Modifier.size(30.dp),
                        strokeWidth = 3.dp,
                        color = colors.ldAccent
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.detail_loading_title, title),
                        fontSize = 15.sp,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center
                    )
                }
                error != null -> {
                    Text(
                        stringResource(R.string.common_load_failed_network),
                        fontSize = 15.sp,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .height(38.dp)
                            .clip(RoundedCornerShape(19.dp))
                            .background(colors.fillSubtle)
                            .clickable(onClick = onRetry)
                            .padding(horizontal = 18.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(stringResource(R.string.common_retry), fontSize = 15.sp, color = colors.ldAccent)
                    }
                }
                else -> Text(
                    text = stringResource(R.string.common_no_topics),
                    fontSize = 15.sp,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

