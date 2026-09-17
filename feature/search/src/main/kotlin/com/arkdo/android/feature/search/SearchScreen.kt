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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkdo.android.core.model.HomeCategory
import com.arkdo.android.core.model.HomeUser
import com.arkdo.android.core.model.SearchHit
import com.arkdo.android.core.model.SearchType
import com.arkdo.android.core.designsystem.FaIcon
import com.arkdo.android.core.common.RelativeTime

/**
 * 搜索页(对齐鸿蒙 SearchOverlay)。
 *
 * 结构:顶部搜索条(返回 + 输入框 + 类型切换 + 筛选入口)→ 内容区。
 * 内容区三态互斥:搜索中 / 空态(历史+热门) / 结果列表。
 *
 * 沉浸式:根 Column 顶到屏幕顶,状态栏 inset 由搜索条自己消费一次
 * (与 CfChallengeScreen/WebViewPage 同一套骨架,父子高度是减法关系不会重叠)。
 */
@Composable
fun SearchScreen(
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
    onRetry: () -> Unit,
    tokens: SearchVisualTokens = SearchVisualTokens.Default,
    /** 帖子结果行由宿主提供(复用首页那张卡,视觉才一致)。 */
    topicRow: @Composable (hit: SearchHit, categories: List<HomeCategory>) -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()

    // 进页自动聚焦弹键盘(对齐鸿蒙 focusInput)。
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
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
            onOpenFilterPanel = onOpenFilterPanel,
            onBack = onBack
        )
        HorizontalDivider(thickness = 0.5.dp, color = tokens.border)

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                state.isSearching && state.hits.isEmpty() && state.users.isEmpty() -> {
                    LoadingState(tokens)
                }

                state.error != null -> {
                    ErrorState(
                        message = state.error,
                        onRetry = onRetry,
                        tokens = tokens
                    )
                }

                state.isEmpty -> {
                    EmptyState(tokens)
                }

                !state.hasSearched -> {
                    HistoryAndHints(
                        history = state.history,
                        tokens = tokens,
                        onTermClick = onHistoryTermClick,
                        onRemove = onRemoveHistory,
                        onClearAll = onClearHistory
                    )
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
                        topicRow = topicRow
                    )
                }
            }
        }
    }
}

/** 顶部搜索条:返回 + 输入框 + 类型切换 + 筛选入口。 */
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
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 4.dp, vertical = 8.dp)
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = tokens.textPrimary
            )
        }

        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester),
            placeholder = {
                Text(
                    searchType.placeholder,
                    fontSize = 15.sp,
                    color = tokens.textFaint
                )
            },
            singleLine = true,
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "清空", tint = tokens.textFaint)
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = tokens.fillSubtle,
                unfocusedContainerColor = tokens.fillSubtle,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedTextColor = tokens.textPrimary,
                unfocusedTextColor = tokens.textPrimary
            ),
            shape = RoundedCornerShape(20.dp)
        )

        // 类型切换:帖子 / 用户。两者走两个不同接口(见 core:model SearchType)。
        Box(
            modifier = Modifier
                .padding(start = 4.dp)
                .clip(RoundedCornerShape(14.dp))
                .clickable {
                    onSearchTypeChange(
                        if (searchType == SearchType.TOPICS) SearchType.USERS else SearchType.TOPICS
                    )
                }
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Text(
                text = searchType.label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = tokens.ldAccent
            )
        }

        // 筛选入口只在「帖子」下有意义:用户搜索不走 /search.json 的高级语法。
        if (searchType == SearchType.TOPICS) {
            Box {
                IconButton(onClick = onOpenFilterPanel) {
                    Icon(
                        Icons.Default.Tune,
                        contentDescription = "高级筛选",
                        tint = if (activeFilterCount > 0) tokens.ldAccent else tokens.textSecondary
                    )
                }
                if (activeFilterCount > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 6.dp, end = 6.dp)
                            .size(16.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(tokens.ldAccent),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "$activeFilterCount",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
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
    topicRow: @Composable (hit: SearchHit, categories: List<HomeCategory>) -> Unit
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize()
    ) {
        items(state.hits, key = { it.topic.id }) { hit ->
            Box(modifier = Modifier.clickable { onOpenTopic(hit) }) {
                topicRow(hit, state.categories)
            }
            HorizontalDivider(thickness = 0.5.dp, color = tokens.border)
        }

        if (state.isLoadingMore) {
            item(key = "loading_more") {
                FooterBox { CircularProgressIndicator(modifier = Modifier.size(18.dp)) }
            }
        } else if (state.hasMore) {
            // key 用结果条数:并入一批后换 key 让 LaunchedEffect 重启,连续上拉不断流
            // (与详情页加载更多同一处理)。
            item(key = "load_more_${state.hits.size}") {
                LaunchedEffect(state.hits.size) { onLoadMore() }
                FooterBox {
                    Text("上拉加载更多", fontSize = 13.sp, color = tokens.textFaint)
                }
            }
        } else {
            item(key = "no_more") {
                FooterBox { Text("没有更多了", fontSize = 13.sp, color = tokens.textFaint) }
            }
        }
    }
}

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
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = user.name?.takeIf { it.isNotEmpty() } ?: user.username,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = tokens.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "@${user.username}",
                        fontSize = 13.sp,
                        color = tokens.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                FaIcon("chevron-right", tokens.textFaint, 12.dp)
            }
            HorizontalDivider(thickness = 0.5.dp, color = tokens.border)
        }
    }
}

/** 未搜索时的空态:历史 + 提示。 */
@Composable
private fun HistoryAndHints(
    history: List<String>,
    tokens: SearchVisualTokens,
    onTermClick: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClearAll: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        if (history.isEmpty()) {
            Text(
                text = "输入关键词开始搜索",
                fontSize = 14.sp,
                color = tokens.textFaint,
                modifier = Modifier.padding(top = 32.dp)
            )
            return@Column
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 8.dp)
        ) {
            Text("搜索历史", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = tokens.textPrimary)
            Box(modifier = Modifier.weight(1f))
            Text(
                "清空",
                fontSize = 13.sp,
                color = tokens.textSecondary,
                modifier = Modifier.clickable { onClearAll() }
            )
        }

        LazyColumn {
            items(history, key = { it }) { term ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onTermClick(term) }
                        .padding(vertical = 10.dp)
                ) {
                    FaIcon("clock-rotate-left", tokens.textFaint, 13.dp)
                    Text(
                        text = term,
                        fontSize = 14.sp,
                        color = tokens.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 10.dp)
                    )
                    Box(modifier = Modifier.clickable { onRemove(term) }) {
                        FaIcon("xmark", tokens.textFaint, 12.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingState(tokens: SearchVisualTokens) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize()
    ) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp), color = tokens.ldAccent, strokeWidth = 2.dp)
        Text(
            "正在搜索…",
            fontSize = 14.sp,
            color = tokens.textSecondary,
            modifier = Modifier.padding(top = 12.dp)
        )
    }
}

@Composable
private fun EmptyState(tokens: SearchVisualTokens) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize()
    ) {
        Text(
            "没有找到相关结果",
            fontSize = 15.sp,
            color = tokens.textSecondary,
            modifier = Modifier.padding(top = 48.dp)
        )
        Text(
            "换个关键词或放宽筛选条件试试",
            fontSize = 13.sp,
            color = tokens.textFaint,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit, tokens: SearchVisualTokens) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize()
    ) {
        Text(
            message,
            fontSize = 14.sp,
            color = tokens.textSecondary,
            modifier = Modifier.padding(top = 48.dp)
        )
        Box(
            modifier = Modifier
                .padding(top = 16.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(tokens.ldAccent)
                .clickable { onRetry() }
                .padding(horizontal = 24.dp, vertical = 8.dp)
        ) {
            Text("重试", fontSize = 14.sp, color = Color.White)
        }
    }
}

@Composable
private fun FooterBox(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}