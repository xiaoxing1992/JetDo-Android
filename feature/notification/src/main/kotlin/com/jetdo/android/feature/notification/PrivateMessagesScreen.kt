package com.jetdo.android.feature.notification

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jetdo.android.core.designsystem.bottomBarClearance
import com.jetdo.android.core.designsystem.NotificationAvatar
import com.jetdo.android.core.model.HomeTopic
import com.jetdo.android.core.model.RelativeTime

/**
 * 私信列表（收件箱）。
 *
 * 形态参考官方 linux.do iOS App 的私信段：一行 = 标题（可能为空）+ 参与者头像昵称 + 时间，
 * 有回复数的再带一个气泡计数。
 *
 * 为什么不直接用首页的 TopicRow：私信的两点与普通帖子列表不同 ——
 *   1. 标题**经常为空**（Discourse 允许无标题私信），TopicRow 的排版假设标题存在；
 *   2. 私信看的是"跟谁在聊"，作者头像/昵称的位置更靠前。
 * 所以单独一行，但数据模型与点击出口完全复用（都是 HomeTopic → TopicDetail）。
 */
@Composable
internal fun PrivateMessagesScreen(
    state: PrivateMessagesUiState,
    loggedIn: Boolean,
    onRequestLogin: () -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenTopic: (Long, String?, String?, Int?) -> Unit,
    colors: NotificationColors = NotificationColors.Light
) {
    if (!loggedIn) {
        CenterMessage("登录后查看私信", "立即登录", onRequestLogin, colors)
        return
    }

    Column(Modifier.fillMaxSize().background(colors.pageBg)) {
        if (state.items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when {
                    state.initialLoading -> CircularProgressIndicator(color = colors.accent)
                    state.error != null -> CenterMessage(state.error, "重试", onRetry, colors)
                    else -> Text("暂无私信", color = colors.textSecondary)
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
                // 内容从悬浮底栏下穿过：滚到底时最后一条要能滑出底栏区域。
                contentPadding = PaddingValues(bottom = bottomBarClearance)
            ) {
                itemsIndexed(state.items, key = { _, item -> item.id }) { index, item ->
                    PrivateMessageRow(item, colors) {
                        // 私信详情就是普通话题详情：带 slug 与楼层（未读锚点）走同一入口。
                        onOpenTopic(item.id, item.slug, item.title, item.firstUnreadPostNumber())
                    }
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
                        Text(
                            if (state.hasMore) "上拉加载更多" else "已到底",
                            fontSize = 13.sp,
                            color = colors.textSecondary
                        )
                    }
                }
            }
        }
    }
}

/**
 * 一行私信。
 *
 * 标题为空时退回「(无标题)」而不是留白 —— 留白会让整行看起来像渲染失败的空白行，
 * 而私信确实允许无标题。
 */
@Composable
private fun PrivateMessageRow(
    topic: HomeTopic,
    colors: NotificationColors,
    onClick: () -> Unit
) {
    val title = topic.title.ifBlank { "(无标题)" }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = title,
            color = colors.textPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            NotificationAvatar(
                name = topic.author?.name ?: topic.author?.username.orEmpty(),
                username = topic.author?.username.orEmpty(),
                avatarTemplate = topic.author?.avatarTemplate,
                size = 18.dp
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = topic.author?.username ?: topic.author?.name.orEmpty(),
                color = colors.textSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(Modifier.width(8.dp))
            // 回复数只有 >0 才画，0 和缺失不画（避免一排"0"）。
            val replies = topic.replyCount ?: 0
            if (replies > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("💬", fontSize = 11.sp)
                    Spacer(Modifier.width(2.dp))
                    Text("$replies", color = colors.textSecondary, fontSize = 12.sp)
                }
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = RelativeTime.fromNow(topic.lastPostedAt ?: topic.createdAt),
                color = colors.textSecondary,
                fontSize = 12.sp,
                maxLines = 1
            )
        }
    }
}
