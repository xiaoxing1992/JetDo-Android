package com.arkdo.android.feature.home

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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.PlatformTextStyle
import com.arkdo.android.core.designsystem.FaIcon
import com.arkdo.android.core.designsystem.bottomBarClearance
import com.arkdo.android.core.designsystem.FaIconRaw
import com.arkdo.android.core.designsystem.FaIconSet
import com.arkdo.android.core.model.HomeCategory
import com.arkdo.android.core.model.HomeFeed
import com.arkdo.android.core.model.HomeQuery
import com.arkdo.android.core.model.HomeTag
import com.arkdo.android.core.model.HomeTopic
import com.arkdo.android.core.model.HomeUser
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.round
import kotlin.math.roundToInt

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
        // 新行插在表头。人停在下面时看不见滑入，先回到第一条再播。
        if (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0) {
            listState.scrollToItem(0)
        }
        insertDistancePx = with(density) { (insertIds.size * INCOMING_INSERT_ROW_ESTIMATE_DP).dp.toPx() }
        withFrameNanos { }
        if (insertIds.all { it in insertHeights }) {
            insertDistancePx = insertHeights.values.sum().toFloat()
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
                isLoggedIn = isLoggedIn,
                isCheckingSession = isCheckingSession,
                accountUser = accountUser,
                onMenuClick = onMenuClick,
                onSearchClick = onSearchClick,
                onNotificationsClick = onNotificationsClick,
                onProfileClick = onProfileClick,
                onDebugClick = onDebugClick,
                colors = colors,
                avatarBaseUrl = avatarBaseUrl
            )
        },
        floatingActionButton = {
            if (state.topics.isNotEmpty()) {
                // 底栏是悬浮胶囊:FAB 上移一个避让高度,避免与底栏重叠。
                Box(modifier = Modifier.padding(bottom = bottomBarClearance)) {
                FloatingActionButton(
                    onClick = if (state.showBackToTopFab) {
                        { scope.launch { listState.animateScrollToItem(0) }; Unit }
                    } else onComposeClick,
                    modifier = Modifier.size(FabSize),
                    shape = CircleShape,
                    containerColor = colors.pageBg,
                    contentColor = if (state.showBackToTopFab) colors.fabTopFg else colors.fabComposeBg
                ) {
                    FaIcon(
                        iconId = if (state.showBackToTopFab) "arrow-up" else "pen-to-square",
                        color = if (state.showBackToTopFab) colors.fabTopFg else colors.fabComposeBg,
                        size = 22.dp
                    )
                }
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
                    title = homeTitle(state.query),
                    onRetry = onRetry,
                    colors = colors
                )
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
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
                                }
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
    isLoggedIn: Boolean,
    isCheckingSession: Boolean,
    accountUser: HomeUser?,
    onMenuClick: () -> Unit,
    onSearchClick: () -> Unit,
    onNotificationsClick: () -> Unit,
    onProfileClick: () -> Unit,
    onDebugClick: () -> Unit,
    colors: HomeVisualTokens,
    avatarBaseUrl: String
) {
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
                text = homeTitle(query),
                color = colors.textPrimary,
                fontSize = if (query.isDefaultHomeQuery()) 26.sp else 21.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        HomeBarButton(onClick = onSearchClick, contentDescription = "搜索", endMargin = 4.dp) {
            FaIcon("magnifying-glass", colors.textPrimary, 20.dp)
        }
        if (isLoggedIn) {
            HomeBarButton(onClick = onNotificationsClick, contentDescription = "通知", endMargin = 4.dp) {
                FaIcon("bell", colors.amber, 24.dp)
            }
        }
        HomeBarButton(onClick = onProfileClick, contentDescription = "个人中心", endMargin = 0.dp) {
            when {
                isCheckingSession -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                isLoggedIn && accountUser != null -> HomeAvatar(accountUser, 34.dp, colors, avatarBaseUrl)
                else -> Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = "个人中心",
                    tint = colors.brand,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
        HomeBarButton(onClick = onDebugClick, contentDescription = "分享日志", endMargin = 0.dp) {
            Icon(
                imageVector = Icons.Filled.BugReport,
                contentDescription = "分享日志",
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
fun HomeTopicRow(
    topic: HomeTopic,
    category: HomeCategory?,
    parentCategory: HomeCategory?,
    onClick: () -> Unit,
    onUnreadClick: () -> Unit,
    colors: HomeVisualTokens,
    avatarBaseUrl: String,
    searchHighlights: List<String> = emptyList(),
    insertProgress: Float = 1f,
    insertDistancePx: Float = 0f,
    insertFadeIn: Boolean = false,
    highlightUntil: Long = 0L,
    onInsertMeasured: ((Int) -> Unit)? = null
) {
    val click = if (homeUnreadTarget(topic) != null) onUnreadClick else onClick
    val highlight = rememberIncomingHighlight(highlightUntil, colors)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { size -> onInsertMeasured?.invoke(size.height) }
            .graphicsLayer {
                translationY = incomingInsertOffset(insertProgress, insertDistancePx)
                alpha = incomingInsertAlpha(insertProgress, insertFadeIn)
            }
            .clickable(onClick = click)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(highlight)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top
        ) {
            HomeAvatar(topic.author, 48.dp, colors, avatarBaseUrl)
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                HomeTitleRow(topic, colors, searchHighlights)
                topic.searchBlurb?.takeIf(String::isNotEmpty)?.let { blurb ->
                    Spacer(Modifier.height(4.dp))
                    HomeSearchBlurb(blurb, colors, searchHighlights)
                }
                val postedAt = relativeTime(topic.lastPostedAt)
                if (homeHasMetadata(topic, category, postedAt)) {
                    Spacer(Modifier.height(3.dp))
                    HomeMetadataRow(category, parentCategory, topic.tags, postedAt, colors, avatarBaseUrl)
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp), color = colors.border)
    }
}

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

@Composable
private fun HomeTitleRow(
    topic: HomeTopic,
    colors: HomeVisualTokens,
    searchHighlights: List<String>
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val iconSize = 15.dp
    val iconSlot = with(density) { (iconSize + 5.dp).toSp() }
    val iconSp = with(density) { iconSize.toSp() }
    val unreadCount = topic.unreadPosts
    val pillLabel = "\u00a0$unreadCount\u00a0"
    val pillWidth = if (unreadCount > 0) {
        with(density) {
            measurer.measure(
                AnnotatedString(pillLabel),
                TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium)
            ).size.width.toSp()
        }
    } else 0.sp
    val title = buildAnnotatedString {
        if (topic.bookmarked) appendInlineContent("bookmark", "bookmark")
        if (topic.hasAcceptedAnswer) appendInlineContent("solved", "solved")
        // 标题命中只改颜色。字重保持整行 Medium，和鸿蒙 titleFontWeight 落在每一段上一样。
        appendHomeSearchText(topic.title, searchHighlights, colors.brand, emphasizeHit = false)
        if (unreadCount > 0) {
            withStyle(SpanStyle(fontSize = 12.sp)) { append("  ") }
            appendInlineContent("unread", pillLabel)
        } else if (topic.unseen) {
            withStyle(SpanStyle(fontSize = 8.sp, color = colors.unread)) { append(" ●") }
        }
    }
    val inline = buildMap {
        put("bookmark", InlineTextContent(Placeholder(iconSlot, iconSp, PlaceholderVerticalAlign.Center)) {
            Row {
                FaIconRaw("bookmark-gray.svg", Modifier.size(iconSize))
                Spacer(Modifier.width(5.dp))
            }
        })
        put("solved", InlineTextContent(Placeholder(iconSlot, iconSp, PlaceholderVerticalAlign.Center)) {
            Row {
                FaIconRaw("far-square-check-green.svg", Modifier.size(iconSize))
                Spacer(Modifier.width(5.dp))
            }
        })
        if (unreadCount > 0) {
            put("unread", InlineTextContent(Placeholder(pillWidth, 12.sp, PlaceholderVerticalAlign.Center)) {
                Box(
                    modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(colors.unread),
                    contentAlignment = Alignment.Center
                ) {
                    Text(pillLabel, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color.White)
                }
            })
        }
    }
    Text(
        text = title,
        inlineContent = inline,
        fontSize = 17.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 23.sp,
        color = colors.textBody,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun HomeSearchBlurb(
    blurb: String,
    colors: HomeVisualTokens,
    searchHighlights: List<String>
) {
    val text = buildAnnotatedString {
        // 摘要非命中是常规字重，命中词升到 Medium 并染品牌色。
        appendHomeSearchText(blurb, searchHighlights, colors.brand, emphasizeHit = true)
    }
    Text(
        text = text,
        fontSize = 13.sp,
        color = colors.textSecondary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth()
    )
}

private fun AnnotatedString.Builder.appendHomeSearchText(
    text: String,
    highlights: List<String>,
    hitColor: Color,
    emphasizeHit: Boolean
) {
    for (segment in homeSearchSegments(text, highlights)) {
        if (!segment.hit) {
            append(segment.text)
            continue
        }
        val style = if (emphasizeHit) {
            SpanStyle(color = hitColor, fontWeight = FontWeight.Medium)
        } else {
            SpanStyle(color = hitColor)
        }
        withStyle(style) { append(segment.text) }
    }
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
                Text(text = "查看", color = colors.ldAccent, style = PromptTextStyle)
                IncomingCountDigits(count, colors.ldAccent)
                Text(
                    text = "个新的或更新的话题",
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

private fun buildTitle(topic: HomeTopic): String = topic.title

@Composable
private fun HomeMetadataRow(
    category: HomeCategory?,
    parentCategory: HomeCategory?,
    tags: List<HomeTag>,
    postedAt: String,
    colors: HomeVisualTokens,
    assetBaseUrl: String
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (category != null || tags.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(20.dp)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                category?.let { HomeCategoryBadge(it, parentCategory, colors, assetBaseUrl) }
                val context = LocalContext.current
                val tagStyles = remember(context) { loadHomeTagStyles(context) }
                tags.forEach { tag -> HomeTagBadge(tag, tagStyles[tag.name.trim().lowercase()], colors) }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (postedAt.isNotEmpty()) {
            Text(
                postedAt,
                color = colors.textSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

@Composable
private fun HomeCategoryBadge(
    category: HomeCategory,
    parentCategory: HomeCategory?,
    colors: HomeVisualTokens,
    assetBaseUrl: String
) {
    val context = LocalContext.current
    val iconId = homeCategoryIcon(context, category, parentCategory)
    val logo = category.uploadedLogo?.takeIf(String::isNotBlank)
        ?: parentCategory?.uploadedLogo?.takeIf(String::isNotBlank)
    Row(
        modifier = Modifier.height(18.dp).padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when {
            iconId != null -> FaIcon(iconId, parseHexColor(category.color) ?: Color(0xFF6B7280), 10.sp, Modifier.padding(end = 3.dp))
            logo != null -> AsyncImage(
                model = resolveHomeAsset(logo, assetBaseUrl),
                contentDescription = null,
                modifier = Modifier.padding(end = 3.dp).size(10.dp),
                contentScale = ContentScale.Fit
            )
            category.readRestricted -> FaIcon("lock", parseHexColor(category.color) ?: Color(0xFF6B7280), 10.sp, Modifier.padding(end = 3.dp))
            else -> Box(
                modifier = Modifier
                    .padding(end = 5.dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(parseHexColor(category.color) ?: Color(0xFF6B7280))
            )
        }
        Text(
            text = category.name,
            color = colors.textSecondary,
            fontSize = 14.sp,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
        )
    }
}

private data class HomeTagStyle(val iconId: String, val color: Color)

@Composable
private fun HomeTagBadge(tag: HomeTag, style: HomeTagStyle?, colors: HomeVisualTokens) {
    Row(
        modifier = Modifier
            .padding(end = 7.dp)
            .height(20.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(colors.surfaceMuted)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (style != null && FaIconSet.has(LocalContext.current, style.iconId)) {
            FaIcon(style.iconId, style.color, 10.dp, Modifier.padding(end = 4.dp))
        }
        Text(
            text = tag.name,
            color = colors.textSecondary,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
        )
    }
}

private fun homeCategoryIcon(
    context: android.content.Context,
    category: HomeCategory,
    parentCategory: HomeCategory?
): String? {
    fun find(value: String?): String? {
        val normalized = normalizeHomeIcon(value)
        if (normalized.isEmpty()) return null
        if (FaIconSet.has(context, normalized)) return normalized
        val mapped = when (normalized) {
            "file-code" -> "code"
            "award", "medal", "trophy" -> "trophy"
            "book-open-reader", "book-open", "book" -> "book-open-reader"
            "seedling", "leaf" -> "seedling"
            "file-lines", "doc", "newspaper" -> "file-lines"
            "clone", "database", "server" -> "hard-drive"
            "users", "user-group", "user" -> "users"
            "megaphone" -> "bullhorn"
            "comment" -> "comments"
            else -> null
        }
        return mapped?.takeIf { FaIconSet.has(context, it) }
    }
    return find(category.icon) ?: find(parentCategory?.icon)
}

private fun normalizeHomeIcon(value: String?): String {
    if (value.isNullOrBlank()) return ""
    var result = ""
    value.trim().lowercase().split(Regex("\\s+")).forEach { token ->
        if (token !in setOf("fa-solid", "fa-regular", "fa-brands", "fas", "far", "fab", "fa")) {
            result = if (token.startsWith("fa-")) token.substring(3) else token
        }
    }
    return result
}

private fun loadHomeTagStyles(context: android.content.Context): Map<String, HomeTagStyle> = runCatching {
    val resourceId = context.resources.getIdentifier("linuxdo_tag_icons", "raw", context.packageName)
    if (resourceId == 0) return@runCatching emptyMap()
    val json = context.resources.openRawResource(resourceId).bufferedReader().use { it.readText() }
    val array = org.json.JSONArray(json)
    buildMap {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val name = item.optString("tagName").trim().lowercase()
            val icon = item.optString("iconId")
            val color = parseHexColor(item.optString("color")) ?: Color(0xFF6B7280)
            if (name.isNotEmpty() && icon.isNotEmpty()) put(name, HomeTagStyle(icon, color))
        }
    }
}.getOrDefault(emptyMap())

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
                isLoading -> "正在加载更多"
                hasMore -> "上拉加载更多"
                else -> "没有更多了"
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
                    text = "准备环境中...",
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
                        text = "正在加载$title",
                        fontSize = 15.sp,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center
                    )
                }
                error != null -> {
                    Text(
                        "加载失败，请检查网络后重试",
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
                        Text("重试", fontSize = 15.sp, color = colors.ldAccent)
                    }
                }
                else -> Text(
                    text = "暂无帖子数据",
                    fontSize = 15.sp,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun HomeAvatar(
    user: HomeUser?,
    size: androidx.compose.ui.unit.Dp,
    colors: HomeVisualTokens,
    avatarBaseUrl: String
) {
    val shape = if (user?.id == 1L) RoundedCornerShape(size * 0.1f) else CircleShape
    val initial = (user?.username ?: user?.name).orEmpty().firstOrNull()?.uppercase() ?: "?"
    val candidates = remember(user, avatarBaseUrl) { homeAvatarCandidates(user, avatarBaseUrl) }
    var candidateIndex by androidx.compose.runtime.remember(candidates) {
        androidx.compose.runtime.mutableIntStateOf(0)
    }
    var showInitial by androidx.compose.runtime.remember(candidates) {
        androidx.compose.runtime.mutableStateOf(candidates.isEmpty())
    }
    val avatarUrl = candidates.getOrNull(candidateIndex)

    Box(
        modifier = Modifier.size(size),
        // Keep the flair anchor identical to the legacy UserAvatar. The
        // badge is offset from this bottom-end anchor and may overhang.
        contentAlignment = Alignment.BottomEnd
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(shape)
                .background(colors.surfaceMuted)
                .border(1.dp, colors.avatarBorder, shape),
            contentAlignment = Alignment.Center
        ) {
            if (showInitial) {
                Text(
                    text = initial,
                    color = colors.avatarFg,
                    fontSize = (size.value * 0.4f).coerceAtLeast(12f).sp,
                    fontWeight = FontWeight.Medium
                )
            }
            if (avatarUrl != null) {
                AsyncImage(
                    model = avatarUrl,
                    contentDescription = null,
                    modifier = Modifier.size(size).clip(shape),
                    contentScale = ContentScale.Crop,
                    onState = { state ->
                        if (state is AsyncImagePainter.State.Error) {
                            if (candidateIndex + 1 < candidates.size) candidateIndex++ else showInitial = true
                        }
                    }
                )
            }
        }
        val context = LocalContext.current
        val flairUrl = user?.flairUrl.orEmpty()
        val flairIsImage = flairUrl.startsWith("http://") ||
            flairUrl.startsWith("https://") || flairUrl.startsWith("//")
        val flairIsIcon = !flairIsImage && FaIconSet.has(context, flairUrl)
        val flairUser = user
        if (flairUser != null && size >= 32.dp && (flairIsImage || flairIsIcon)) {
            val badgeSize = maxOf(14.dp, size * 0.42f)
            val overhang = (badgeSize.value * 0.24f).roundToInt().dp
            val hasBackground = !flairUser.flairBgColor.isNullOrBlank()
            val background = if (hasBackground) {
                parseHexColor(flairUser.flairBgColor.orEmpty()) ?: Color(0xFFBE123C)
            } else {
                Color.Transparent
            }
            val shape = if (hasBackground) {
                CircleShape
            } else {
                RoundedCornerShape((badgeSize.value * 0.18f).roundToInt().dp)
            }
            Box(
                modifier = Modifier
                    .size(badgeSize)
                    .offset(x = overhang, y = overhang)
                    .clip(shape)
                    .background(background),
                contentAlignment = Alignment.Center
            ) {
                if (flairIsImage) {
                    AsyncImage(
                        model = resolveHomeAsset(flairUrl, avatarBaseUrl),
                        contentDescription = flairUser.flairName,
                        modifier = Modifier
                            .size(badgeSize)
                            .padding(if (hasBackground) (badgeSize.value * 0.16f).roundToInt().dp else 0.dp),
                        contentScale = if (hasBackground) ContentScale.Fit else ContentScale.Crop
                    )
                } else {
                    FaIcon(
                        iconId = flairUrl,
                        color = parseHexColor(flairUser.flairColor.orEmpty()) ?: Color.White,
                        size = (badgeSize.value * 0.58f).roundToInt().dp
                    )
                }
            }
        }
    }
}

private fun parseHexColor(value: String): Color? = runCatching {
    val hex = value.removePrefix("#")
    if (hex.length != 6) return@runCatching null
    Color(
        red = hex.substring(0, 2).toInt(16),
        green = hex.substring(2, 4).toInt(16),
        blue = hex.substring(4, 6).toInt(16)
    )
}.getOrNull()

private fun relativeTime(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    val match = Regex("^(\\d{4})-(\\d{2})-(\\d{2})[T ](\\d{2}):(\\d{2}):(\\d{2})").find(iso)
        ?: return ""
    val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(
            match.groupValues[1].toInt(),
            match.groupValues[2].toInt() - 1,
            match.groupValues[3].toInt(),
            match.groupValues[4].toInt(),
            match.groupValues[5].toInt(),
            match.groupValues[6].toInt()
        )
    }
    val minutes = round((System.currentTimeMillis() - calendar.timeInMillis) / 60_000.0)
        .toLong()
        .coerceAtLeast(0)
    return when {
        minutes < 1 -> "刚刚"
        minutes <= 44 -> "$minutes 分钟"
        minutes <= 89 -> "1 小时"
        minutes <= 1_409 -> "${round(minutes / 60.0).toLong()} 小时"
        minutes <= 2_519 -> "1 天"
        minutes <= 20_160 -> "${round(minutes / 1_440.0).toLong()} 天"
        else -> {
            val year = match.groupValues[1].toInt()
            val month = match.groupValues[2].toInt()
            val day = match.groupValues[3].toInt()
            if (year == Calendar.getInstance().get(Calendar.YEAR)) {
                "${month}月${day}日"
            } else {
                "${year}年${month}月${day}日"
            }
        }
    }
}
