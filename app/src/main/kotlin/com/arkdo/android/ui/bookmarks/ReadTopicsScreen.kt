package com.arkdo.android.ui.bookmarks

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.arkdo.android.common.constants.AppConstants
import com.arkdo.android.core.designsystem.TopicRow
import com.arkdo.android.core.designsystem.TopicRowColors
import com.arkdo.android.core.model.HomeCategory
import com.arkdo.android.core.model.HomeTopic
import com.arkdo.android.data.models.TopicCategory
import com.arkdo.android.ui.theme.ArkdoFixedColors
import com.arkdo.android.ui.theme.arkdoColors

@Composable
fun ReadTopicsScreen(
    onNavigateBack: () -> Unit,
    onOpenTopic: (Long, String?, Int?) -> Unit,
    viewModel: ReadTopicsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val colors = arkdoColors
    val rowColors = TopicRowColors(
        pageBg = colors.pageBg,
        textSecondary = colors.textSecondary,
        textBody = colors.textBody,
        brand = colors.brand,
        border = colors.border,
        surfaceMuted = colors.surfaceMuted,
        avatarFg = colors.avatarFg,
        avatarBorder = ArkdoFixedColors.AvatarBorder,
        unread = ArkdoFixedColors.Unread
    )

    LaunchedEffect(Unit) { viewModel.loadInitial() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.pageBg)
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = colors.textPrimary)
            }
            Text("浏览历史", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
        }
        HorizontalDivider(color = colors.border, thickness = 0.5.dp)

        when {
            state.items.isEmpty() && state.initialLoading -> Centered {
                CircularProgressIndicator(color = colors.ldAccent)
            }
            state.items.isEmpty() && state.error != null -> Centered {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.error ?: "加载失败", color = colors.textSecondary)
                    Text(
                        "重试",
                        color = colors.ldAccent,
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .clickable { viewModel.loadInitial() }
                    )
                }
            }
            state.items.isEmpty() -> Centered {
                Text("还没有读过的帖子", color = colors.textTertiary)
            }
            else -> PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = { viewModel.loadInitial() },
                modifier = Modifier.fillMaxSize()
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp)
                ) {
                    itemsIndexed(state.items, key = { _, topic -> "${topic.id}_${if (topic.bookmarked) 1 else 0}" }) { index, topic ->
                        val category = categoryOf(state.categories, topic.categoryId)
                        val parentId = category?.parentCategoryId
                        TopicRow(
                            topic = topic,
                            category = category,
                            parentCategory = parentId?.let { id -> categoryOf(state.categories, id) },
                            onClick = { onOpenTopic(topic.id, topic.title, null) },
                            onUnreadClick = { onOpenTopic(topic.id, topic.title, topic.firstUnreadPostNumber()) },
                            colors = rowColors,
                            avatarBaseUrl = AppConstants.BASE_URL
                        )
                        if (index == state.items.lastIndex) {
                            LaunchedEffect(state.items.size, state.hasMore) {
                                if (!state.loadingMore && state.hasMore) viewModel.loadMore()
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
                                        color = colors.ldAccent,
                                        strokeWidth = 2.dp
                                    )
                                    !state.hasMore -> Text("没有更多了", fontSize = 13.sp, color = colors.textFaint)
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
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

private fun categoryOf(categories: List<TopicCategory>, id: Long?): HomeCategory? {
    if (id == null) return null
    val category = categories.firstOrNull { it.id == id } ?: return null
    return HomeCategory(
        id = category.id,
        name = category.name,
        color = category.color,
        textColor = category.textColor,
        parentCategoryId = category.parentCategoryId,
        slug = category.slug,
        uploadedLogo = category.uploadedLogo,
        icon = category.icon,
        readRestricted = category.readRestricted == true
    )
}
