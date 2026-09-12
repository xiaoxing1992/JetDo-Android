package com.arkdo.android.ui.notification

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.arkdo.android.data.models.NotificationItem
import com.arkdo.android.data.models.User
import com.arkdo.android.services.session.CurrentUserHolder
import com.arkdo.android.services.session.LoginGate
import com.arkdo.android.ui.components.FaIcon
import com.arkdo.android.ui.components.FaIconSet
import com.arkdo.android.ui.components.UserAvatar
import com.arkdo.android.ui.theme.ArkdoFixedColors
import com.arkdo.android.ui.theme.arkdoColors
import com.arkdo.android.ui.utils.RelativeTime

// ==========================================================================
// 通知中心(对齐鸿蒙 NotificationPage + NotificationListItem)。
//
// 规格(逐条对齐 ets):
// · 顶栏:HdsNavigation MINI 模式,主标题「通知」,仅返回按钮;有未读时菜单出现
//   「全部已读」checkmark 按钮,一键 markAllRead。
// · 列表:ListItemGroup + startMargin 70 的 divider;标题栏透冠在根宿主上由自绘顶栏占位,
//   这里列表直接顶到内容区(下拉刷新时首行不会多出一段白条)。触底 onReachEnd 加载更多。
// · 未读铃铛红点数:首次/刷新的 recent=true 成功后,bus 清零(私信 highPriority 保留)。
// · 单行(row=42 头像,space 12,左右 16/上下 13):
//   · 有发起人:头像 + 右下角类型角标;角标未读=品牌蓝底白图标(brand/白),
//     已读=白底深灰图标(#FFFFFF/#222)。系统类:42 纯色图标圈(iconColor 底白图标)。
//   · 发起人(加粗 15)+ 动作文案(一行 ellipsis);标题 13.5 一行,已读 secondary、
//     未读 primary,上边距 3。
//   · 右侧:相对时间 12(secondary),其下未读时加 9 圆点(brand);列尾对齐 End,间距 8。
//   · 整行 padding;已读 page_bg、未读 info_soft_bg(淡蓝底)。
// · 加载/加载更多/到底:页脚 54、loading=18 ld_accent、文案 13 「上拉加载更多 /
//   「已到底」;空>失败(加载中 center 30 ld_accent / 失败 secondary + 重试胶囊) / 无数据
//   「暂无通知」。全部 center,失败重试按钮 fill_subtle 底 + ld_accent 字 + 38 高 19 圆角。
// ==========================================================================

private const val PAGE_SIZE = 30
private val PAGE_HORIZONTAL_PADDING = 16.dp
private val ROW_VERTICAL_PADDING = 13.dp
private val ROW_SPACE = 12.dp
private val AVATAR_SIZE = 42.dp
private val ICON_BADGE_SIZE = 20.dp
private val FOOTER_HEIGHT = 54.dp
private val UNREAD_CIRCLE_SIZE = 9.dp

private val BadgeBgUnread = Color(0xFF0088CC) // brand(bright) 与鸿蒙 BRAND_BLUE 固定值一致
private val BadgeFgUnread = Color.White
private val BadgeBgRead = Color(0xFFFFFFFF)
private val BadgeFgRead = Color(0xFF222222)

private fun systemBadgeColor(iconColor: String): Color = parseHexColor(iconColor) ?: Color(0xFF9CA3AF)

@Composable
fun NotificationScreen(
    showTopBar: Boolean = true,
    onOpenTopic: (topicId: Long, slug: String?, title: String?, postNumber: Int?) -> Unit = { _, _, _, _ -> },
    onOpenUser: (username: String, displayName: String?, avatarTemplate: String?) -> Unit = { _, _, _ -> },
    viewModel: NotificationViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val loggedIn = CurrentUserHolder.currentUser.collectAsState().value != null

    // 每次页面生命周期回到 RESUMED(首次进入/切回来/回前台)就重拉列表。
    // 订阅键用 loggedIn:首次进页若会话还没来得及核对完(loggedIn=false),这次组合不发请求,
    // 等登录态确认后键一变,effect 重跑再按 RESUMED 触发 —— 不会出现"页面空着且永不再拉"。
    LaunchedEffect(loggedIn) {
        if (!loggedIn) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.refresh()
        }
    }

    if (!loggedIn) {
        NotLoggedInPrompt()
        return
    }

    if (state.items.isEmpty()) {
        NotificationEmptyState(
            isInitialLoading = state.initialLoading,
            message = (state.error?.takeIf { it.isNotBlank() } ?: "暂无通知"),
            showRetry = state.error != null,
            onRetry = { viewModel.loadInitial() },
            showTopBar = showTopBar
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(arkdoColors.pageBg)
    ) {
        if (showTopBar) {
            NotificationTitleBar(
                unreadCount = state.items.count { !it.read },
                onBack = {},
                onMarkAllRead = { viewModel.markAllRead() }
            )
            HorizontalDivider(color = arkdoColors.border, thickness = 0.5.dp)
        }

        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(state.items) { index, item ->
                    NotificationRow(
                        item = item,
                        onClick = {
                            viewModel.markItemRead(item)
                            val topicId = item.topicId
                            if (topicId != null) {
                                onOpenTopic(topicId, item.slug, item.title, item.postNumber)
                            }
                        }
                    )
                    if (index < state.items.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(
                                start = 70.dp,
                                end = 0.dp
                            ),
                            color = arkdoColors.border,
                            thickness = 1.dp
                        )
                    }
                }
                item(key = "footer") {
                    LaunchedEffect(state.items.size) {
                        if (!state.loadingMore && state.hasMore) {
                            viewModel.loadMore()
                        }
                    }
                    NotificationListFooter(
                        isLoading = state.loadingMore,
                        hasMore = state.hasMore,
                        endText = "已到底",
                        accentColor = arkdoColors.ldAccent,
                        textColor = arkdoColors.textFaint
                    )
                }
            }
        }
    }
}

/**
 * 顶部栏:HdsNavigation MINI 等价。tab 内无返回按钮;有未读时右侧出现
 * 「全部已读」checkmark 入口(对齐鸿蒙 menu)。
 */
@Composable
private fun NotificationTitleBar(
    unreadCount: Int,
    onBack: () -> Unit,
    onMarkAllRead: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(56.dp)
            .background(arkdoColors.pageBg)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        // 与鸿蒙 HdsNavigation MINI 一致:标题 17 居中,菜单项在右侧。
        Text(
            text = "通知",
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            color = arkdoColors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.Center)
        )
        if (unreadCount > 0) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                FaIcon(
                    iconId = "check",
                    color = arkdoColors.brand,
                    size = 18.dp
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = "全部已读",
                    fontSize = 14.sp,
                    color = arkdoColors.brand,
                    modifier = Modifier
                        .clickable(onClick = onMarkAllRead)
                        .padding(vertical = 8.dp)
                )
            }
        }
    }
}

/** 未登录:通知需要登录态,提示引导登录。 */
@Composable
private fun NotLoggedInPrompt() {
    Box(
        Modifier.fillMaxSize().background(arkdoColors.pageBg),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "登录后查看通知",
                fontSize = 15.sp,
                color = arkdoColors.textSecondary
            )
            Text(
                text = "立即登录",
                fontSize = 15.sp,
                color = ArkdoFixedColors.BrandBlue,
                modifier = Modifier
                    .padding(top = 14.dp)
                    .clip(RoundedCornerShape(19.dp))
                    .background(arkdoColors.fillSubtle)
                    .padding(horizontal = 18.dp, vertical = 9.dp)
                    .clickable { LoginGate.requestLogin() }
            )
        }
    }
}

/**
 * 空态/加载态/失败态总入口(对齐 LoadStateView:居中,四态互斥)。
 * 空列表时:加载中(30 ld_accent) > 失败(secondary + 重试胶囊) > 无数据「暂无通知」。
 */
@Composable
private fun NotificationEmptyState(
    isInitialLoading: Boolean,
    message: String,
    showRetry: Boolean,
    onRetry: () -> Unit,
    showTopBar: Boolean
) {
    if (showTopBar) {
        NotificationTitleBar(unreadCount = 0, onBack = {}, onMarkAllRead = {})
        HorizontalDivider(color = arkdoColors.border, thickness = 0.5.dp)
    }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        when {
            isInitialLoading -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(30.dp),
                    color = arkdoColors.ldAccent,
                    strokeWidth = 3.dp
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "通知加载中",
                    fontSize = 15.sp,
                    color = arkdoColors.textSecondary
                )
            }
            showRetry -> {
                Text(
                    text = message,
                    fontSize = 15.sp,
                    color = arkdoColors.textSecondary
                )
                Text(
                    text = "重试",
                    fontSize = 15.sp,
                    color = arkdoColors.ldAccent,
                    modifier = Modifier
                        .padding(top = 16.dp)
                        .clip(RoundedCornerShape(19.dp))
                        .background(arkdoColors.fillSubtle)
                        .padding(horizontal = 18.dp, vertical = 9.dp)
                        .clickable(onClick = onRetry)
                )
            }
            else -> {
                Text(
                    text = message,
                    fontSize = 15.sp,
                    color = arkdoColors.textSecondary
                )
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

/** 列表尾行(对齐 ListFooter):加载中 18 转圈 + 「上拉加载更多 / 已到底」。 */
@Composable
private fun NotificationListFooter(
    isLoading: Boolean,
    hasMore: Boolean,
    endText: String,
    accentColor: androidx.compose.ui.graphics.Color,
    textColor: androidx.compose.ui.graphics.Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(FOOTER_HEIGHT),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = accentColor,
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = if (hasMore) "上拉加载更多" else endText,
            fontSize = 13.sp,
            color = textColor
        )
    }
}

/** 单行(对齐 NotificationListItem):已读白底 / 未读淡蓝底;整行 16/13 边距;右侧时间列。 */
@Composable
private fun NotificationRow(
    item: NotificationItem,
    onClick: () -> Unit
) {
    val rowBg = if (item.read) arkdoColors.pageBg else arkdoColors.infoSoftBg

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg)
            .clickable(onClick = onClick)
            .padding(
                start = PAGE_HORIZONTAL_PADDING,
                end = PAGE_HORIZONTAL_PADDING,
                top = ROW_VERTICAL_PADDING,
                bottom = ROW_VERTICAL_PADDING
            ),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(ROW_SPACE)
    ) {
        AvatarOrSystemIcon(item = item)

        Column(
            modifier = Modifier
                .weight(1f)
                .align(Alignment.Top),
            horizontalAlignment = Alignment.Start
        ) {
            if (item.actorName.isNotEmpty()) {
                Text(
                    text = item.actorName,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = arkdoColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                if (item.actionText.isNotEmpty()) {
                    Text(
                        text = item.actionText,
                        fontSize = 15.sp,
                        color = arkdoColors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                Text(
                    text = item.actionText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = arkdoColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (item.title.isNotEmpty()) {
                Text(
                    text = item.title,
                    fontSize = 13.5.sp,
                    color = if (item.read) arkdoColors.textSecondary else arkdoColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.align(Alignment.Top)
        ) {
            Text(
                text = RelativeTime.fromNow(item.createdAt),
                fontSize = 12.sp,
                color = arkdoColors.textSecondary
            )
            if (!item.read) {
                Box(
                    modifier = Modifier
                        .size(UNREAD_CIRCLE_SIZE)
                        .clip(CircleShape)
                        .background(arkdoColors.brand)
                )
            }
        }
    }
}

/**
 * 左侧列(对齐 NotificationListItem.Avatar):
 * 有发起人 → 头像(42)+ 右下角类型角标;系统类 → 42 纯色图标圈。
 * 角标:未读=brand 蓝底白图标;已读=白底 #222 图标。角标压头像右下,外凸 -3,-3。
 */
@Composable
private fun AvatarOrSystemIcon(
    item: NotificationItem
) {
    if (item.actorName.isNotEmpty()) {
        val avatarUser = remember(item.actorUsername, item.actorName, item.avatarTemplate) {
            User(
                id = 0,
                username = item.actorUsername.ifEmpty { "?" },
                name = item.actorName,
                avatarTemplate = item.avatarTemplate
            )
        }
        Box(modifier = Modifier.size(AVATAR_SIZE), contentAlignment = Alignment.BottomEnd) {
            UserAvatar(
                user = avatarUser,
                avatarSize = AVATAR_SIZE,
                showFlair = false
            )
            // 角标垫在头像右下,外凸 -3。
            if (FaIconSet.has(LocalContext.current, item.iconId)) {
                Box(
                    modifier = Modifier
                        .size(ICON_BADGE_SIZE)
                        .clip(CircleShape)
                        .background(if (item.read) BadgeBgRead else BadgeBgUnread)
                        .padding(vertical = 4.dp)
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    FaIcon(
                        iconId = item.iconId,
                        color = if (item.read) BadgeFgRead else BadgeFgUnread,
                        size = 11.dp
                    )
                }
            }
        }
    } else {
        // 系统类:iconColor 纯色底 + 白图标,无角标。
        Box(
            modifier = Modifier
                .size(AVATAR_SIZE)
                .clip(CircleShape)
                .background(systemBadgeColor(item.iconColor)),
            contentAlignment = Alignment.Center
        ) {
            FaIcon(
                iconId = item.iconId,
                color = Color.White,
                size = 18.dp
            )
        }
    }
}

private fun parseHexColor(hex: String): Color? {
    if (hex.isBlank()) return null
    return runCatching {
        val clean = hex.removePrefix("#")
        if (clean.length == 6) {
            Color(
                clean.substring(0, 2).toInt(16),
                clean.substring(2, 4).toInt(16),
                clean.substring(4, 6).toInt(16)
            )
        } else {
            null
        }
    }.getOrNull()
}