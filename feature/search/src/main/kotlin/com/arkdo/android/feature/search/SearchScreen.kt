package com.arkdo.android.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkdo.android.core.designsystem.FaIcon
import com.arkdo.android.core.designsystem.NotificationAvatar
import com.arkdo.android.core.designsystem.TopicRow
import com.arkdo.android.core.model.HomeCategory
import com.arkdo.android.core.model.HomeUser
import com.arkdo.android.core.model.SearchHit
import com.arkdo.android.core.model.SearchType

/**
 * 搜索页(对齐鸿蒙 SearchOverlay)。
 *
 * 结构:顶部搜索条(放大镜 + 输入框 + 清除 + 筛选入口 + 类型下拉 + 取消)→ 内容区。
 * 内容区按鸿蒙的互斥态渲染:搜索中 / 错误 / 空态 / 历史 / 结果。
 * 搜索条是一根 40vp 高的胶囊容器,「取消」在容器右侧;类型切换是下拉菜单,
 * 点条目勾选当前项 —— 全部照搬 SearchOverlay.SearchBarRow 的排布。
 */
@Composable
internal fun SearchScreen(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onSearchTypeChange: (SearchType) -> Unit,
    onOpenFilterPanel: () -> Unit,
    onBack: () -> Unit,
    onOpenTopic: (SearchHit) -> Unit,
    onOpenUser: (HomeUser) -> Unit,
    onHistoryTermClick: (String) -> Unit,
    onRemoveHistory: (String) -> Unit,
    onClearHistory: () -> Unit,
    onLoadMore: () -> Unit,
    tokens: SearchVisualTokens = SearchVisualTokens.Default,
    avatarBaseUrl: String
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()

    // 进页自动聚焦弹键盘(对齐鸿蒙 focusInput)。
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    // 开始滑动结果列表就收起键盘,别挡着看结果(对齐鸿蒙 onScrollStart → hideKeyboard)。
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) keyboard?.hide()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.pageBg)
            .imePadding()
    ) {
        SearchBar(
            query = state.query,
            searchType = state.searchType,
            activeFilterCount = state.activeFilterCount,
            tokens = tokens,
            focusRequester = focusRequester,
            onQueryChange = onQueryChange,
            onSubmit = {
                keyboard?.hide()
                onSubmit()
            },
            onSearchTypeChange = onSearchTypeChange,
            onOpenFilterPanel = {
                // 进面板前收起键盘(对齐鸿蒙漏斗点击里的 hideKeyboard)。
                keyboard?.hide()
                onOpenFilterPanel()
            },
            onBack = {
                keyboard?.hide()
                onBack()
            }
        )
        HorizontalDivider(thickness = 0.5.dp, color = tokens.border)

        Box(modifier = Modifier.fillMaxSize()) {
            // 与鸿蒙 ResultsView 同一套互斥与遮挡规则:错误/加载/空态只在**没有结果**时
            // 盖在列表上;有结果时失败不清结果,仅收加载态。历史页只在没输入时出现。
            val resultCount =
                if (state.searchType == SearchType.USERS) state.users.size else state.hits.size
            when {
                !state.canSearch -> {
                    HistoryAndHints(
                        history = state.history,
                        tokens = tokens,
                        onTermClick = onHistoryTermClick,
                        onRemove = onRemoveHistory,
                        onClearAll = onClearHistory
                    )
                }

                resultCount == 0 && state.isSearching -> {
                    LoadingState(tokens)
                }

                resultCount == 0 && state.error != null -> {
                    ErrorState(message = state.error, tokens = tokens)
                }

                state.isEmpty -> {
                    EmptyState(searchType = state.searchType, tokens = tokens)
                }

                state.searchType == SearchType.USERS -> {
                    UserResults(
                        users = state.users,
                        onOpenUser = onOpenUser,
                        tokens = tokens
                    )
                }

                else -> {
                    TopicResults(
                        state = state,
                        listState = listState,
                        tokens = tokens,
                        onOpenTopic = onOpenTopic,
                        onLoadMore = onLoadMore,
                        avatarBaseUrl = avatarBaseUrl
                    )
                }
            }
        }
    }
}

/**
 * 顶部搜索条(对齐鸿蒙 SearchBarRow)。
 *
 * 一根 40vp 胶囊容器装下:放大镜 / 输入框 / 清除 / 竖线 / 筛选入口(仅帖子)/ 类型入口;
 * 「取消」独立在容器右侧。筛选条件不在结果页顶部铺胶囊条,要改就再进面板。
 */
@Composable
private fun SearchBar(
    query: String,
    searchType: SearchType,
    activeFilterCount: Int,
    tokens: SearchVisualTokens,
    focusRequester: FocusRequester,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onSearchTypeChange: (SearchType) -> Unit,
    onOpenFilterPanel: () -> Unit,
    onBack: () -> Unit
) {
    var typeMenuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = 16.dp, bottom = 12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
        ) {
            // 输入容器:放大镜 + 输入框 + 清除 + 竖线 + 筛选 + 类型。
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(tokens.fillSubtle)
                    .padding(start = 12.dp, end = 4.dp)
            ) {
                FaIcon("magnifying-glass", tokens.textFaint, 16.dp)

                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                        .padding(horizontal = 8.dp),
                    textStyle = TextStyle(fontSize = 15.sp, color = tokens.textPrimary),
                    cursorBrush = SolidColor(tokens.ldAccent),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                    decorationBox = { innerField ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                Text(
                                    text = searchType.placeholder,
                                    fontSize = 15.sp,
                                    color = tokens.textFaint,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            innerField()
                        }
                    }
                )

                if (query.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clickable { onQueryChange("") },
                        contentAlignment = Alignment.Center
                    ) {
                        FaIcon("xmark", tokens.textFaint, 14.dp)
                    }
                }

                // 竖线分隔输入区与功能热区(对齐鸿蒙 Divider.vertical height=18 margin 2)。
                Box(
                    modifier = Modifier
                        .padding(horizontal = 2.dp)
                        .width(1.dp)
                        .height(18.dp)
                        .background(tokens.border)
                )

                // 高级筛选入口(仅「帖子」有意义:用户搜索不走 /search.json 的高级语法)。
                if (searchType == SearchType.TOPICS) {
                    Box(
                        modifier = Modifier
                            .size(width = 30.dp, height = 34.dp)
                            .clickable(onClick = onOpenFilterPanel),
                        contentAlignment = Alignment.Center
                    ) {
                        FaIcon(
                            "filter",
                            if (activeFilterCount > 0) tokens.ldAccent else tokens.textSecondary,
                            17.dp
                        )
                        if (activeFilterCount > 0) {
                            // 角标由中心向右上偏移(对齐鸿蒙 offset x:10 y:-8)。
                            Box(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .offset(x = 10.dp, y = (-8).dp)
                                    .widthIn(min = 13.dp)
                                    .height(13.dp)
                                    .clip(RoundedCornerShape(6.5.dp))
                                    .background(tokens.ldAccent),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "$activeFilterCount",
                                    fontSize = 9.sp,
                                    color = Color.White,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 3.dp)
                                )
                            }
                        }
                    }
                }

                // 类型入口:label + 下拉箭头,点开菜单选类型(对齐鸿蒙 bindMenu)。
                Box {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .height(34.dp)
                            .clickable { typeMenuOpen = true }
                            .padding(start = 2.dp, end = 6.dp)
                    ) {
                        Text(
                            text = searchType.label,
                            fontSize = 13.sp,
                            color = tokens.textSecondary
                        )
                        FaIcon("chevron-down", tokens.textFaint, 11.dp)
                    }
                    DropdownMenu(
                        expanded = typeMenuOpen,
                        onDismissRequest = { typeMenuOpen = false }
                    ) {
                        SearchType.entries.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = option.label,
                                        fontSize = 14.sp,
                                        color = tokens.textPrimary
                                    )
                                },
                                trailingIcon = if (option == searchType) {
                                    { Icon(Icons.Default.Check, contentDescription = null, tint = tokens.ldAccent) }
                                } else {
                                    null
                                },
                                onClick = {
                                    typeMenuOpen = false
                                    onSearchTypeChange(option)
                                }
                            )
                        }
                    }
                }
            }

            // 「取消」:关闭搜索页(对齐鸿蒙 onClose;系统返回同样能退)。
            Text(
                text = "取消",
                fontSize = 15.sp,
                color = tokens.ldAccent,
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(start = 12.dp, top = 8.dp, bottom = 8.dp)
            )
        }
    }
}

@Composable
private fun TopicResults(
    state: SearchUiState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    tokens: SearchVisualTokens,
    onOpenTopic: (SearchHit) -> Unit,
    onLoadMore: () -> Unit,
    avatarBaseUrl: String
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize()
    ) {
        // TopicListItem 自带底部分隔线,这里不再另画(画了就是双线)。
        // 点击走行组件自己的 onClick/onUnreadBadgeClick(其根部 clickable 会吃掉
        // 外层包装的点击)。鸿蒙搜索结果列表自带水平留白 16,行组件内已是 12,外层补 4。
        items(state.hits, key = { it.topic.id }) { hit ->
            val category = hit.topic.category ?: state.categories.firstOrNull { it.id == hit.topic.categoryId }
            val parent = category?.parentCategoryId?.let { parentId ->
                state.categories.firstOrNull { it.id == parentId }
            }
            Box(modifier = Modifier.padding(horizontal = 4.dp)) {
                TopicRow(
                    topic = hit.topic,
                    category = category,
                    parentCategory = parent,
                    // 未读行和普通行都进同一个 handler,对齐鸿蒙 handleResultClick。
                    onClick = { onOpenTopic(hit) },
                    onUnreadClick = { onOpenTopic(hit) },
                    colors = tokens.toTopicRowColors(),
                    avatarBaseUrl = avatarBaseUrl,
                    searchHighlights = state.highlights
                )
            }
        }

        if (state.hits.isNotEmpty()) {
            // 底部状态条:加载中转圈,否则按 hasMore 给「上拉加载更多/没有更多了」。
            // key 用结果条数:并入一批后换 key 让 LaunchedEffect 重启,连续上拉不断流。
            item(key = "footer_${state.hits.size}") {
                LaunchedEffect(state.hits.size) {
                    if (state.hasMore && !state.isLoadingMore) onLoadMore()
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                ) {
                    if (state.isLoadingMore) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = tokens.ldAccent,
                            strokeWidth = 2.dp
                        )
                    }
                    Text(
                        text = if (state.hasMore) "上拉加载更多" else "没有更多了",
                        fontSize = 13.sp,
                        color = tokens.textSecondary
                    )
                }
            }
        }
    }
}

/** 「用户」类型结果行:头像 + 用户名 + 显示名(对齐鸿蒙 UserResultRow)。 */
@Composable
private fun UserResults(
    users: List<HomeUser>,
    onOpenUser: (HomeUser) -> Unit,
    tokens: SearchVisualTokens
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(users, key = { it.id }) { user ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenUser(user) }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                NotificationAvatar(
                    name = user.name.orEmpty(),
                    username = user.username,
                    avatarTemplate = user.avatarTemplate,
                    size = 36.dp
                )
                Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(
                        text = user.username,
                        fontSize = 15.sp,
                        color = tokens.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val displayName = user.name
                    if (!displayName.isNullOrEmpty()) {
                        Text(
                            text = displayName,
                            fontSize = 12.sp,
                            color = tokens.textFaint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/** 未搜索时的空态:历史列表;无历史时一句居中提示(对齐鸿蒙 HistoryView)。 */
@Composable
private fun HistoryAndHints(
    history: List<String>,
    tokens: SearchVisualTokens,
    onTermClick: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClearAll: () -> Unit
) {
    if (history.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "输入关键词搜索帖子",
                fontSize = 15.sp,
                color = tokens.textFaint
            )
        }
        return
    }

    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    // 滑动历史列表也收起键盘(对齐鸿蒙 onScrollStart)。
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) keyboard?.hide()
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        // 标题行是列表第一项,能随条目一起滚进搜索栏后方(对齐鸿蒙 HistoryView)。
        item(key = "header") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)
            ) {
                Text(
                    text = "搜索历史",
                    fontSize = 13.sp,
                    color = tokens.textSecondary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "清空",
                    fontSize = 13.sp,
                    color = tokens.textFaint,
                    modifier = Modifier.clickable(onClick = onClearAll)
                )
            }
        }

        items(history, key = { it }) { term ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clickable {
                        // 点历史词已选定目标,收起键盘让位给结果(对齐鸿蒙 applyHistoryTerm)。
                        keyboard?.hide()
                        onTermClick(term)
                    }
                    .padding(horizontal = 16.dp)
            ) {
                FaIcon("magnifying-glass", tokens.textFaint, 14.dp)
                Text(
                    text = term,
                    fontSize = 15.sp,
                    color = tokens.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp)
                )
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clickable { onRemove(term) },
                    contentAlignment = Alignment.Center
                ) {
                    FaIcon("xmark", tokens.textFaint, 13.dp)
                }
            }
        }
    }
}

/** 首次搜索加载中(尚无结果):只有转圈(对齐鸿蒙,无文案)。 */
@Composable
private fun LoadingState(tokens: SearchVisualTokens) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            color = tokens.ldAccent,
            strokeWidth = 2.dp
        )
    }
}

/** 空态文案按类型区分(对齐鸿蒙 emptyResultText)。 */
@Composable
private fun EmptyState(searchType: SearchType, tokens: SearchVisualTokens) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = if (searchType == SearchType.USERS) "未找到相关用户" else "未找到相关帖子",
            fontSize = 15.sp,
            color = tokens.textSecondary,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 错误态:一句警示色文案,居中(对齐鸿蒙 errorMessage 用 warning 色,无重试按钮;
 * 重搜由改词/重提交/就绪自动补发兜底)。
 */
@Composable
private fun ErrorState(message: String, tokens: SearchVisualTokens) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            fontSize = 15.sp,
            color = tokens.warning,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
    }
}
