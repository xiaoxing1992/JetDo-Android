package com.jetdo.android.feature.notification

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel

/** 消息页的分段。顺序即分段控件里的排列顺序。 */
internal enum class MessagesSegment(val label: String) {
    NOTIFICATION("通知"),
    PRIVATE_MESSAGE("私信")
}

/**
 * 「消息」一级 tab 的容器：大标题 + 分段控件（通知 / 私信）+ 内容区。
 *
 * 形态对齐官方 linux.do iOS App 的消息页（截图实测）：标题恒为「消息」，
 * 下方是灰底胶囊分段，两段各自承载完整内容。**没有返回箭头** —— 它是 tab 内容，
 * 不是压在主栈上的二级页（这也是与改造前 NotificationScreen 顶栏的关键差别）。
 *
 * 未登录时两段各自显示自己的登录引导（「登录后查看通知」/「登录后查看私信」），
 * 分段控件仍然可见可点，与官方行为一致。
 */
@Composable
fun MessagesRoute(
    loggedIn: Boolean,
    username: String?,
    onRequestLogin: () -> Unit,
    onOpenTopic: (Long, String?, String?, Int?) -> Unit,
    onOpenUser: (String, String?, String?) -> Unit,
    colors: NotificationColors = NotificationColors.Light,
    notificationViewModel: NotificationViewModel = hiltViewModel(),
    privateMessagesViewModel: PrivateMessagesViewModel = hiltViewModel()
) {
    var segment by rememberSaveable { mutableIntStateOf(0) }
    val active = MessagesSegment.entries.getOrElse(segment) { MessagesSegment.NOTIFICATION }

    // 通知段：进页刷新一次（与改造前 NotificationRoute 的行为一致，保证深链切过来能看到最新）。
    LaunchedEffect(loggedIn, active) {
        if (loggedIn && active == MessagesSegment.NOTIFICATION) notificationViewModel.refresh()
    }
    // 私信段：用户名到位（或换号）才拉。setUsername 内部做去重，重复调用无副作用。
    LaunchedEffect(loggedIn, active, username) {
        if (loggedIn && active == MessagesSegment.PRIVATE_MESSAGE) {
            privateMessagesViewModel.setUsername(username)
        }
    }

    Column(Modifier.fillMaxSize().background(colors.pageBg)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = "消息",
                color = colors.textPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp, bottom = 10.dp)
            )
            MessagesSegmentedControl(
                labels = MessagesSegment.entries.map { it.label },
                selectedIndex = segment,
                onSelect = { segment = it },
                colors = colors,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        when (active) {
            MessagesSegment.NOTIFICATION -> {
                val notificationState by notificationViewModel.uiState.collectAsState()
                NotificationScreen(
                    state = notificationState,
                    loggedIn = loggedIn,
                    // 标题与分段由本容器画，内层不再重复一条「通知」顶栏。
                    showTopBar = false,
                    onRequestLogin = onRequestLogin,
                    onRetry = notificationViewModel::loadInitial,
                    onRefresh = notificationViewModel::refresh,
                    onLoadMore = notificationViewModel::loadMore,
                    onMarkAllRead = notificationViewModel::markAllRead,
                    onMarkRead = notificationViewModel::markItemRead,
                    onOpenTopic = onOpenTopic,
                    onOpenUser = onOpenUser,
                    colors = colors,
                    loginPromptTitle = "登录后查看通知",
                    emptyText = "暂无通知"
                )
            }
            MessagesSegment.PRIVATE_MESSAGE -> {
                val pmState by privateMessagesViewModel.uiState.collectAsState()
                PrivateMessagesScreen(
                    state = pmState,
                    loggedIn = loggedIn,
                    onRequestLogin = onRequestLogin,
                    onRetry = privateMessagesViewModel::loadInitial,
                    onRefresh = privateMessagesViewModel::refresh,
                    onLoadMore = privateMessagesViewModel::loadMore,
                    onOpenTopic = onOpenTopic,
                    colors = colors
                )
            }
        }
    }
}
