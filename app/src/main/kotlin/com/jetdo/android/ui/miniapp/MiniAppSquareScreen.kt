package com.jetdo.android.ui.miniapp

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.jetdo.android.R
import com.jetdo.android.core.designsystem.bottomBarClearance
import com.jetdo.android.core.model.MiniAppSummary
import com.jetdo.android.ui.theme.jetdoColors

/**
 * 微应用广场。
 *
 * 数据源是站点 `/apps` 的对应 JSON 接口；接口路径未在公开文档固化，
 * 由 repository 按候选列表依次尝试。
 */
@Composable
fun MiniAppSquareScreen(
    onOpenApp: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MiniAppViewModel = hiltViewModel()
) {
    val state by viewModel.squareState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.loadSquare()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(jetdoColors.pageBg)
    ) {
        // 顶部沉浸式：与「消息」Tab 同一范式 —— 标题栏自己垫 statusBars inset，
        // 并把状态栏区域刷成页面底色（Edge-to-Edge 下内容顶到状态栏后面）。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = "微应用",
                color = jetdoColors.textPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp, bottom = 10.dp)
            )
        }
        when {
            state.loading && state.apps.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = jetdoColors.ldAccent)
                }
            }

            state.error != null && state.apps.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            state.error.orEmpty(),
                            color = jetdoColors.textTertiary,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "点击重试",
                            color = jetdoColors.ldAccent,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .clickable { viewModel.loadSquare(force = true) }
                        )
                    }
                }
            }

            state.apps.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无微应用", color = jetdoColors.textTertiary, fontSize = 14.sp)
                }
            }

            else -> {
                PullToRefreshBox(
                    isRefreshing = state.refreshing,
                    onRefresh = { viewModel.loadSquare(force = true) },
                    modifier = Modifier.fillMaxSize()
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        // 底栏是悬浮胶囊：内容从它下穿过，但滚到底时最后一条必须能滑出底栏，
                        // 否则最后一张卡片被挡住。沿用根导航下发的 bottomBarClearance
                        // （容器高 + 底部间隙 + 手势区），与首页/消息列表同一机制。
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 12.dp,
                            top = 8.dp,
                            end = 12.dp,
                            bottom = bottomBarClearance
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(state.apps, key = { it.id }) { app ->
                            MiniAppCard(app = app, onClick = { onOpenApp(app.id) })
                        }
                        item(key = "miniapp-footer") {
                            // 滚到底自动请求下一页；hasMore=false 时 footer 显示"没有更多了"。
                            LaunchedEffect(state.page, state.hasMore, state.isLoadingMore) {
                                if (state.hasMore && !state.isLoadingMore) viewModel.loadMore()
                            }
                            MiniAppListFooter(state.isLoadingMore, state.hasMore)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniAppCard(
    app: MiniAppSummary,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(jetdoColors.surfaceCard)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MiniAppIcon(app.iconUrl)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = app.name.ifBlank { "未命名应用" },
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = jetdoColors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!app.description.isNullOrBlank()) {
                Text(
                    // description 来自 core:model（跨模块公开属性），不能直接 smart cast，
                    // 先收进局部变量再判空。
                    text = app.description.orEmpty(),
                    fontSize = 12.sp,
                    color = jetdoColors.textTertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (app.tags.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    app.tags.take(3).forEach { tag ->
                        Text(
                            text = tag,
                            fontSize = 11.sp,
                            color = jetdoColors.ldAccent,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(jetdoColors.fillSubtle)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniAppListFooter(isLoading: Boolean, hasMore: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().height(48.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = jetdoColors.ldAccent
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = stringResource(
                when {
                    isLoading -> R.string.common_loading_more
                    hasMore -> R.string.common_pull_load_more
                    else -> R.string.common_no_more
                }
            ),
            color = jetdoColors.textTertiary,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun MiniAppIcon(iconUrl: String?) {
    val shape = RoundedCornerShape(12.dp)
    if (iconUrl.isNullOrBlank()) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(shape)
                .background(jetdoColors.fillSubtle),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Apps,
                contentDescription = null,
                tint = jetdoColors.textTertiary,
                modifier = Modifier.size(24.dp)
            )
        }
        return
    }
    AsyncImage(
        model = iconUrl,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(48.dp)
            .clip(shape)
            .background(jetdoColors.fillSubtle)
    )
}
