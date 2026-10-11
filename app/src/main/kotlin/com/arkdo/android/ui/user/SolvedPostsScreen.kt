package com.arkdo.android.ui.user

import androidx.compose.ui.res.stringResource
import com.arkdo.android.R
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.arkdo.android.core.model.RelativeTime
import com.arkdo.android.data.models.SolvedPostItem
import com.arkdo.android.data.models.TopicCategory
import com.arkdo.android.services.site.CategoryIndex
import com.arkdo.android.ui.components.TopicMetadataBadges
import com.arkdo.android.ui.theme.arkdoColors
import com.arkdo.android.ui.theme.isJetDoInDarkTheme

// ==========================================================================
// 解决方案列表（对齐鸿蒙 SolvedPostsPage.ets）。
//
// 数据源 /solution/by_user.json，按 offset 翻页。整页主语已经是目标用户，
// 行内不再挂头像。点击落到被采纳的那一楼，不是话题首楼。
// ==========================================================================

private val SOLVED_PAGE_PADDING = 16.dp

@Composable
fun SolvedPostsScreen(
    username: String,
    solvedCount: Int = 0,
    onNavigateBack: () -> Unit,
    onOpenTopic: (Long, String, Int) -> Unit = { _, _, _ -> },
    viewModel: SolvedViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val success = if (isJetDoInDarkTheme()) Color(0xFF4CAF50) else Color(0xFF009900)
    val title = if (solvedCount > 0) stringResource(R.string.drawer_solutions_count, solvedCount) else stringResource(R.string.detail_solution)

    LaunchedEffect(username) {
        viewModel.loadInitial(username)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(arkdoColors.pageBg)
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
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = arkdoColors.textPrimary)
        }
        HorizontalDivider(color = arkdoColors.border, thickness = 0.5.dp)
        Spacer(modifier = Modifier.height(8.dp))

        when {
            state.items.isEmpty() && state.initialLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.common_loading_ellipsis), fontSize = 14.sp, color = arkdoColors.textSecondary)
                }
            }
            state.items.isEmpty() && state.error != null -> {
                val err = state.error ?: stringResource(R.string.home_load_failed_retry)
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(err, color = arkdoColors.textSecondary)
                        Text(
                            stringResource(R.string.common_retry),
                            color = arkdoColors.ldAccent,
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .clickable { viewModel.loadInitial(username) }
                        )
                    }
                }
            }
            state.items.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.user_no_solutions), color = arkdoColors.textTertiary)
                }
            }
            else -> {
                PullToRefreshBox(
                    isRefreshing = state.refreshing,
                    onRefresh = { viewModel.loadInitial(username) },
                    modifier = Modifier.fillMaxSize()
                ) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(
                            state.items,
                            key = { index, item -> "${item.topicId}_${item.postNumber}_$index" }
                        ) { index, item ->
                            SolvedRow(item, state.categories, success, onOpenTopic)
                            if (index < state.items.lastIndex) {
                                HorizontalDivider(color = arkdoColors.border, thickness = 1.dp)
                            }
                        }
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
                                        color = arkdoColors.ldAccent,
                                        strokeWidth = 2.dp
                                    )
                                    !state.hasMore -> Text(stringResource(R.string.common_no_more), fontSize = 13.sp, color = arkdoColors.textFaint)
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
private fun SolvedRow(
    item: SolvedPostItem,
    categories: List<TopicCategory>,
    success: Color,
    onOpenTopic: (Long, String, Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenTopic(item.topicId, item.topicTitle, item.postNumber) }
            .padding(start = SOLVED_PAGE_PADDING, end = SOLVED_PAGE_PADDING, top = 12.dp, bottom = 13.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = success,
            modifier = Modifier
                .padding(top = 2.dp, end = 6.dp)
                .size(15.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.topicTitle,
                fontSize = 16.sp,
                color = arkdoColors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 5.dp)
            ) {
                val category = CategoryIndex.find(categories, item.categoryId)
                val parent = CategoryIndex.findParent(categories, item.categoryId)
                if (category != null) {
                    TopicMetadataBadges(
                        category = category,
                        parentCategory = parent,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                if (!item.createdAt.isNullOrEmpty()) {
                    Text(
                        text = RelativeTime.fromNow(item.createdAt),
                        fontSize = 13.sp,
                        color = arkdoColors.textSecondary,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
            if (item.excerpt.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .padding(end = 10.dp)
                            .width(2.dp)
                            .heightIn(min = 21.dp)
                            .background(success)
                            .align(Alignment.CenterVertically)
                    )
                    Text(
                        text = item.excerpt,
                        fontSize = 14.sp,
                        color = arkdoColors.textBody,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 21.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
