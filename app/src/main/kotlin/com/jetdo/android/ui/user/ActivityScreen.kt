package com.jetdo.android.ui.user

import androidx.compose.ui.res.stringResource
import com.jetdo.android.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import com.jetdo.android.data.models.TopicCategory
import com.jetdo.android.data.models.UserActivityItem
import com.jetdo.android.ui.components.UserAvatar
import com.jetdo.android.ui.components.TopicMetadataBadges
import com.jetdo.android.ui.theme.jetdoColors
import com.jetdo.android.core.model.RelativeTime

// ==========================================================================
// 个人动态列表(对齐鸿蒙 ActivityListPage.ets)。
//
// 数据源 /user_actions.json?filter=4,5&offset=,按 offset 翻页。
// 单行 = 作者头像(40) + 标题 / 分类徽标 + 时间,摘要另起一段;整行可点跳详情。
// 下拉刷新 = 重拉第 0 页;滚动到底自动加载更多(hasMore = 满一页 30 条)。
// ==========================================================================

private val PAGE_PADDING = 16.dp
private val AVATAR_SIZE = 40.dp

@Composable
fun ActivityScreen(
    username: String,
    filter: String = "4,5",
    pageTitle: String = stringResource(R.string.user_activity),
    onNavigateBack: () -> Unit,
    onOpenTopic: (Long, String, Int?) -> Unit = { _, _, _ -> },
    onOpenUser: (String, String?, String?) -> Unit = { _, _, _ -> },
    viewModel: ActivityViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(username, filter) {
        viewModel.loadInitial(username, filter)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(jetdoColors.pageBg)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(56.dp)
                .padding(horizontal = 4.dp)
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
            }
            Text(pageTitle, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = jetdoColors.textPrimary)
        }
        HorizontalDivider(color = jetdoColors.border, thickness = 0.5.dp)

        when {
            state.items.isEmpty() && state.initialLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = jetdoColors.ldAccent)
                }
            }
            state.items.isEmpty() && state.error != null -> {
                val err = state.error ?: ""
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(err, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                        Text(stringResource(R.string.common_retry), color = jetdoColors.ldAccent,
                            modifier = Modifier.padding(top = 12.dp).clickable { viewModel.loadInitial(username, filter) })
                    }
                }
            }
            state.items.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.common_empty_here), color = jetdoColors.textTertiary)
                }
            }
            else -> {
                PullToRefreshBox(
                    isRefreshing = state.refreshing,
                    onRefresh = { viewModel.loadInitial(username, filter) },
                    modifier = Modifier.fillMaxSize()
                ) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(state.items) { index, item ->
                            ActivityRow(item, state.categories, onOpenTopic, onOpenUser)
                            if (index < state.items.lastIndex) {
                                HorizontalDivider(color = jetdoColors.border, thickness = 1.dp)
                            }
                        }
                        // 触底加载更多(对齐鸿蒙 onReachEnd)。
                        item(key = "footer") {
                            LaunchedEffect(state.items.size) {
                                if (!state.loadingMore && state.hasMore) {
                                    viewModel.loadMore()
                                }
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                when {
                                    state.loadingMore -> CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = jetdoColors.ldAccent,
                                        strokeWidth = 2.dp
                                    )
                                    !state.hasMore -> Text(stringResource(R.string.common_no_more), fontSize = 13.sp, color = jetdoColors.textFaint)
                                    else -> {}
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityRow(
    item: UserActivityItem,
    categories: List<TopicCategory>,
    onOpenTopic: (Long, String, Int?) -> Unit,
    onOpenUser: (String, String?, String?) -> Unit
) {
    // 对齐鸿蒙 ActivityRow:头像40 + (标题 + 徽标/时间)列;有摘要时另起一段横跨整行。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenTopic(item.topicId, item.title, item.postNumber) }
            .padding(start = PAGE_PADDING, end = PAGE_PADDING, top = 12.dp, bottom = 13.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            val author = item.author
            UserAvatar(
                user = author,
                avatarSize = AVATAR_SIZE,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .clickable {
                        author?.let {
                            if (it.username.isNotEmpty()) onOpenUser(it.username, it.name, it.avatarTemplate)
                        }
                    }
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    fontSize = 16.sp,
                    color = jetdoColors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 5.dp)
                ) {
                    val category = com.jetdo.android.services.site.CategoryIndex.find(categories, item.categoryId)
                    val parent = com.jetdo.android.services.site.CategoryIndex.findParent(categories, item.categoryId)
                    if (category != null || item.tags.isNotEmpty()) {
                        TopicMetadataBadges(
                            category = category,
                            parentCategory = parent,
                            tags = item.tags,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    if (!item.createdAt.isNullOrEmpty()) {
                        Text(
                            text = RelativeTime.fromNow(item.createdAt),
                            fontSize = 13.sp,
                            color = jetdoColors.textSecondary,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        }
        if (item.excerpt.isNotEmpty()) {
            Text(
                text = item.excerpt,
                fontSize = 14.sp,
                color = jetdoColors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 21.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )
        }
    }
}
