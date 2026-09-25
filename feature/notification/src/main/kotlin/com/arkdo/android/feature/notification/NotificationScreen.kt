package com.arkdo.android.feature.notification

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkdo.android.core.designsystem.bottomBarClearance
import com.arkdo.android.core.common.RelativeTime
import com.arkdo.android.core.designsystem.FaIcon
import com.arkdo.android.core.designsystem.NotificationAvatar
import com.arkdo.android.core.model.NotificationItem

/**
 * 通知页色板，由宿主按当前主题注入。默认值是浅色，避免预览和漏传时退回白底黑字。
 * 深浅色对齐鸿蒙 page_bg / text_primary / text_secondary / info_soft_bg / ld_accent。
 */
@androidx.compose.runtime.Immutable
data class NotificationColors(
    val pageBg: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    val unreadBg: Color,
    val divider: Color,
    val badgeReadBg: Color,
    val badgeReadFg: Color
) {
    companion object {
        val Light = NotificationColors(
            pageBg = Color.White,
            textPrimary = Color(0xFF111827),
            textSecondary = Color(0xFF6B7280),
            accent = Color(0xFF0088CC),
            unreadBg = Color(0xFFE8F1FE),
            divider = Color(0xFFE5E7EB),
            badgeReadBg = Color.White,
            badgeReadFg = Color(0xFF222222)
        )
    }
}

@Composable
internal fun NotificationScreen(
    state: NotificationUiState,
    loggedIn: Boolean,
    showTopBar: Boolean,
    onRequestLogin: () -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onMarkAllRead: () -> Unit,
    onMarkRead: (NotificationItem) -> Unit,
    onOpenTopic: (Long, String?, String?, Int?) -> Unit,
    onOpenUser: (String, String?, String?) -> Unit,
    colors: NotificationColors = NotificationColors.Light
) {
    if (!loggedIn) {
        CenterMessage("登录后查看通知", "立即登录", onRequestLogin, colors)
        return
    }

    Column(Modifier.fillMaxSize().background(colors.pageBg)) {
        if (showTopBar) {
            Row(
                modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)
                    .height(56.dp).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("通知", color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                if (state.items.any { !it.read }) {
                    Text("全部已读", color = colors.accent, modifier = Modifier.clickable(onClick = onMarkAllRead))
                }
            }
            HorizontalDivider(color = colors.divider)
        }

        if (state.items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when {
                    state.initialLoading -> CircularProgressIndicator(color = colors.accent)
                    state.error != null -> CenterMessage(state.error, "重试", onRetry, colors)
                    else -> Text("暂无通知", color = colors.textSecondary)
                }
            }
            return@Column
        }

        val listState = rememberLazyListState()
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                // 内容从悬浮底栏下穿过:滚到底时最后一条要能滑出底栏区域。
                contentPadding = PaddingValues(bottom = bottomBarClearance)
            ) {
                itemsIndexed(state.items, key = { _, item -> item.id }) { index, item ->
                    NotificationRow(item, colors, onMarkRead, onOpenTopic, onOpenUser)
                    if (index < state.items.lastIndex) {
                        HorizontalDivider(modifier = Modifier.padding(start = 70.dp), color = colors.divider)
                    }
                }
                item(key = "footer") {
                    LaunchedEffect(state.items.size) {
                        if (state.hasMore && !state.loadingMore) onLoadMore()
                    }
                    Row(
                        Modifier.fillMaxWidth().height(54.dp)
                            .clickable(enabled = state.hasMore, onClick = onLoadMore),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (state.loadingMore) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = colors.accent, strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (state.hasMore) "上拉加载更多" else "已到底", fontSize = 13.sp, color = colors.textSecondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(
    item: NotificationItem,
    colors: NotificationColors,
    onMarkRead: (NotificationItem) -> Unit,
    onOpenTopic: (Long, String?, String?, Int?) -> Unit,
    onOpenUser: (String, String?, String?) -> Unit
) {
    val topicId = item.topicId
    val open = {
        onMarkRead(item)
        if (topicId != null) onOpenTopic(topicId, item.slug, item.title, item.postNumber)
        else if (item.actorUsername.isNotEmpty()) onOpenUser(item.actorUsername, item.actorName, item.avatarTemplate)
    }
    Row(
        Modifier.fillMaxWidth().background(if (item.read) colors.pageBg else colors.unreadBg)
            .clickable(onClick = open).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (item.actorName.isNotEmpty()) {
            Box(Modifier.size(42.dp), contentAlignment = Alignment.BottomEnd) {
                NotificationAvatar(item.actorName, item.actorUsername, item.avatarTemplate, 42.dp)
                Box(
                    Modifier.size(20.dp).clip(CircleShape)
                        .background(if (item.read) colors.badgeReadBg else colors.accent),
                    contentAlignment = Alignment.Center
                ) {
                    FaIcon(item.iconId, if (item.read) colors.badgeReadFg else Color.White, 11.sp)
                }
            }
        } else {
            Box(Modifier.size(42.dp).clip(CircleShape).background(parseHexColor(item.iconColor) ?: Color(0xFF9CA3AF)), contentAlignment = Alignment.Center) {
                FaIcon(item.iconId, Color.White, 18.sp)
            }
        }
        Column(Modifier.weight(1f)) {
            if (item.actorName.isNotEmpty()) {
                Text(item.actorName, color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (item.actionText.isNotEmpty()) Text(item.actionText, color = colors.textSecondary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Text(item.actionText, color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (item.title.isNotEmpty()) {
                Text(
                    item.title,
                    color = if (item.read) colors.textSecondary else colors.textPrimary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(RelativeTime.fromNow(item.createdAt), color = colors.textSecondary, fontSize = 12.sp)
            if (!item.read) Box(Modifier.size(9.dp).clip(CircleShape).background(colors.accent))
        }
    }
}

@Composable
private fun CenterMessage(
    message: String,
    action: String?,
    onAction: (() -> Unit)?,
    colors: NotificationColors
) {
    Column(
        Modifier.fillMaxSize().background(colors.pageBg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(message, color = colors.textSecondary)
        if (action != null && onAction != null) {
            Text(
                action,
                color = colors.accent,
                modifier = Modifier.padding(top = 16.dp).clip(RoundedCornerShape(19.dp))
                    .clickable(onClick = onAction).padding(horizontal = 18.dp, vertical = 9.dp)
            )
        }
    }
}

private fun parseHexColor(hex: String): Color? = runCatching {
    val clean = hex.removePrefix("#")
    if (clean.length != 6) return null
    Color(clean.substring(0, 2).toInt(16), clean.substring(2, 4).toInt(16), clean.substring(4, 6).toInt(16))
}.getOrNull()
