package com.arkdo.android.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.arkdo.android.data.models.User
import com.arkdo.android.data.models.UserActivityItem
import com.arkdo.android.data.models.UserBadgeItem
import com.arkdo.android.services.session.CurrentUserHolder
import com.arkdo.android.ui.components.FaIcon
import com.arkdo.android.ui.components.InlineText
import com.arkdo.android.ui.components.TopicMetadataBadges
import com.arkdo.android.ui.components.UserAvatar
import com.arkdo.android.ui.theme.arkdoColors
import com.arkdo.android.ui.utils.RelativeTime
import com.arkdo.android.ui.utils.daysSince
import com.arkdo.android.ui.utils.formatCount
import com.arkdo.android.ui.utils.formatTimeRead
import com.arkdo.android.ui.utils.trustLabelWithTitleOf
import com.arkdo.android.services.content.parseInlines
import androidx.compose.foundation.layout.fillMaxHeight

// ==========================================================================
// 个人中心「我的」页,对齐鸿蒙 views/pages/ProfilePage.ets。
//
// 标题「我的」+ 右上角设置。页面分登录/未登录两块:
//  · 已登录:身份卡(头像56/昵称/@username·信任标/简介/meta/统计4×2)→ 快捷入口
//    (浏览历史·书签·点赞·草稿)→ 徽章卡(3列+全部)→ 最近活动卡 → 退出登录。
//  · 未登录:圆形灰底 person + 「登录 xx」 + 立即登录按钮 + 三条权益说明。
// ==========================================================================

private val PageMargin = 12.dp
private val CardRadius = 16.dp
private val CardPadding = 16.dp
private val CardGap = 12.dp
private val StatsAreaHeight = 126.dp

/** 顶栏:返回 + 「我的」 + 设置。 */
@Composable
private fun ProfileTopBar(onNavigateBack: () -> Unit, onOpenSettings: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(56.dp)
            .padding(horizontal = 4.dp)
    ) {
        IconButton(onClick = onNavigateBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        Text(
            text = "我的",
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = arkdoColors.textPrimary,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Filled.Settings, contentDescription = "设置")
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    // 独立页(push 进根导航)时传 null:顶栏只有返回箭头,无设置;底部 Tab 视图传空实现
    // 并在 onOpenSettings/onShowSettings 给出设置入口。见 ProfileScaffold 的 showBar 参数。
    onNavigateBack: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenLogin: () -> Unit,
    onOpenWeb: (String, String?) -> Unit = { _, _ -> },
    onOpenActivity: (String, String, String) -> Unit = { _, _, _ -> },
    onOpenBadges: (String, Int) -> Unit = { _, _ -> },
    showTopBar: Boolean = true,
    immersive: Boolean = false,
    modifier: Modifier = Modifier,
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val currentUser by CurrentUserHolder.currentUser.collectAsState()
    val isLoggedIn = currentUser != null
    val accountUser: User = currentUser ?: User(id = 0, username = "")
    val context = LocalContext.current
    var showLogoutDialog by remember { mutableStateOf(false) }

    // 进页/账号变化时按 username 加载资料(对齐鸿蒙 handleAccountChanged)。
    LaunchedEffect(accountUser.username) {
        if (accountUser.username.isNotEmpty()) {
            viewModel.load(accountUser.username)
        }
    }

    // 状态栏条区:Tab 沉浸视图顶到状态栏,条区颜色由这里接管(亮 #F2F3F5 同 pageBgGray)。
    // 用 Box 把灰色垫在内容底下,再正常 padding 内容避开状态栏。独立页走系统条默认色。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                if (immersive && !showTopBar) {
                    com.arkdo.android.ui.theme.ArkdoFixedColors.ProfileStatusBarBg
                } else {
                    arkdoColors.pageBgGray
                }
            )
    ) {
        if (showTopBar) {
            ProfileTopBar(onNavigateBack = onNavigateBack, onOpenSettings = onOpenSettings)
            HorizontalDivider(color = arkdoColors.border, thickness = 0.5.dp)
        } else if (immersive) {
            // 内容与状态栏条同灰:直接顶到屏幕顶,状态栏透明并透出本 Box 的灰底。
            Spacer(modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars))
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PageMargin)
        ) {
            Spacer(modifier = Modifier.height(CardGap))
            if (isLoggedIn && accountUser.username.isNotEmpty()) {
                LoggedInContent(
                    state = state,
                    accountUser = accountUser,
                    onOpenWeb = onOpenWeb,
                    onOpenActivity = onOpenActivity,
                    onOpenBadges = onOpenBadges,
                    onRequestLogout = { showLogoutDialog = true }
                )
            } else {
                AnonymousContent(
                    username = accountUser.username,
                    onLoginClick = onOpenLogin
                )
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // 退出登录二次确认(对齐鸿蒙 confirmLogout)。
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("退出登录") },
            text = { Text("确定要退出当前账号吗?") },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutDialog = false
                    viewModel.logout {
                        Toast.makeText(context, if (isLoggedIn) "已退出登录" else "退出登录失败,请稍后再试", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text("退出", color = arkdoColors.ldDanger)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

// ==================== 已登录内容 ====================

@Composable
private fun LoggedInContent(
    state: ProfileViewModel.UiState,
    accountUser: User,
    onOpenWeb: (String, String?) -> Unit,
    onOpenActivity: (String, String, String) -> Unit,
    onOpenBadges: (String, Int) -> Unit,
    onRequestLogout: () -> Unit
) {
    val colors = arkdoColors
    val username = accountUser.username
    val profile = state.profile
    val displayName = profile?.displayName?.takeIf { it.isNotEmpty() }
        ?: accountUser.name?.takeIf { it.isNotEmpty() }
        ?: username
    val openProfileWeb = {
        onOpenWeb("https://linux.do/u/$username", displayName)
    }

    // 卡片 1:身份 + 论坛数据
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CardRadius))
            .background(colors.surfaceCard)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = CardPadding, end = CardPadding, top = CardPadding, bottom = 6.dp)
        ) {
            UserAvatar(
                user = accountUser,
                avatarSize = 56.dp,
                modifier = Modifier.clickable(onClick = openProfileWeb)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 14.dp)
            ) {
                Text(
                    text = displayName,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(onClick = openProfileWeb)
                )
                val trust = trustLabelWithTitleOf(profile)
                if (trust.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(top = 3.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.surfaceMuted)
                            .padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 2.dp)
                    ) {
                        Text(
                            text = trust,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.ldAccent
                        )
                        FaIcon("chevron-right", colors.ldAccent, 11.dp, Modifier.padding(start = 1.dp))
                    }
                }
            }
            FaIcon(
                "chevron-right",
                colors.textFaint,
                14.dp,
                Modifier.clickable(onClick = openProfileWeb)
            )
        }
        val meta = buildProfileMeta(profile)
        if (meta.isNotEmpty()) {
            Text(
                text = meta,
                fontSize = 12.sp,
                color = colors.textFaint,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = CardPadding, end = CardPadding, top = 4.dp, bottom = 2.dp)
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        HorizontalDivider(color = colors.border, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = CardPadding))
        if (state.stats != null) {
            StatsGrid(state.stats)
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(StatsAreaHeight),
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = colors.ldAccent, strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text("正在加载论坛数据", fontSize = 13.sp, color = colors.textSecondary)
            }
        }
    }

    // 卡片 2:快捷入口
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = CardGap)
            .clip(RoundedCornerShape(CardRadius))
            .background(colors.surfaceCard)
            .padding(top = CardPadding, bottom = 14.dp)
    ) {
        QuickEntryNative(icon = "clock-rotate-left", label = "浏览历史", love = false) {
            onOpenWeb("https://linux.do/read", "浏览历史")
        }
        QuickEntryNative(icon = "bookmark", label = "我的书签", love = false) {
            onOpenWeb("https://linux.do/u/$username/activity/bookmarks", "我的书签")
        }
        QuickEntryNative(icon = "heart", label = "我的点赞", love = true) {
            onOpenActivity(username, "1", "我的点赞")
        }
        QuickEntryNative(icon = "pen-to-square", label = "我的草稿") {
            onOpenWeb("https://linux.do/u/$username/activity/drafts", "我的草稿")
        }
    }

    // 卡片 3:徽章
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = CardGap)
            .clip(RoundedCornerShape(CardRadius))
            .background(colors.surfaceCard)
    ) {
        SectionHeadProfile(
            title = if ((profile?.badgeCount ?: 0) > 0) "徽章 · ${profile?.badgeCount}" else "徽章",
            onMore = { onOpenBadges(username, profile?.badgeCount ?: 0) }
        )
        if (state.topBadges.isNotEmpty()) {
            state.topBadges.chunked(3).forEach { row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = CardPadding, end = CardPadding, top = 10.dp)
                ) {
                    row.forEach { badge ->
                        ProfileBadgeTile(badge)
                    }
                    repeat(3 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                }
            }
            Spacer(modifier = Modifier.height(CardPadding))
        } else {
            Text(
                text = "暂无徽章",
                fontSize = 13.sp,
                color = colors.textFaint,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = CardPadding, top = 10.dp, bottom = CardPadding)
            )
        }
    }

    // 卡片 4:最近活动
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = CardGap)
            .clip(RoundedCornerShape(CardRadius))
            .background(colors.surfaceCard)
    ) {
        SectionHeadProfile(
            title = "最近活动",
            onMore = { onOpenActivity(username, "4,5", "最近活动") }
        )
        if (state.recentActivity.isNotEmpty()) {
            state.recentActivity.forEachIndexed { index, item ->
                if (index > 0) {
                    HorizontalDivider(color = colors.border, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = CardPadding))
                }
                ProfileActivityRow(item, state)
            }
            // 底部「我的话题 N › · 我的回复 N ›」。
            val topicsLabel = state.stats?.let { "我的话题 ${formatCount(it.topicCount)} ›" } ?: "我的话题 ›"
            val repliesLabel = state.stats?.let { "我的回复 ${formatCount(it.postCount)} ›" } ?: "我的回复 ›"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = CardPadding, end = CardPadding, top = 8.dp, bottom = 14.dp)
            ) {
                Text(topicsLabel, fontSize = 13.sp, color = colors.ldAccent,
                    modifier = Modifier.clickable { onOpenActivity(username, "4", "我的话题") })
                Text("·", fontSize = 13.sp, color = colors.textFaint,
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp))
                Text(repliesLabel, fontSize = 13.sp, color = colors.ldAccent,
                    modifier = Modifier.clickable { onOpenActivity(username, "5", "我的回复") })
            }
        } else {
            Text(
                text = "最近没有发帖",
                fontSize = 13.sp,
                color = colors.textFaint,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = CardPadding, top = 10.dp, bottom = CardPadding)
            )
        }
    }

    // 卡片 5:退出登录
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = CardGap)
            .clip(RoundedCornerShape(CardRadius))
            .background(colors.surfaceCard)
            .clickable(onClick = onRequestLogout)
            .padding(top = 14.dp, bottom = 14.dp)
    ) {
        Text("退出登录", fontSize = 15.sp, color = colors.ldDanger)
    }
}

@Composable
private fun buildProfileMeta(profile: com.arkdo.android.data.models.UserProfileDetail?): String {
    if (profile == null) return ""
    val parts = mutableListOf<String>()
    daysSince(profile.createdAt).takeIf { it > 0 }?.let { parts.add("加入 $it 天") }
    RelativeTime.fromNow(profile.lastPostedAt).takeIf { it.isNotEmpty() }?.let { parts.add("最近发帖 $it") }
    if ((profile.profileViewCount ?: 0) > 0) {
        parts.add("浏览量 ${formatCount(profile.profileViewCount ?: 0)}")
    }
    return parts.joinToString(" · ")
}

@Composable
private fun SectionHeadProfile(title: String, onMore: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = CardPadding, end = CardPadding, top = 14.dp, bottom = 4.dp)
    ) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = arkdoColors.textPrimary)
        Spacer(modifier = Modifier.weight(1f))
        Text("全部 ›", fontSize = 12.sp, color = arkdoColors.ldAccent,
            modifier = Modifier.clickable(onClick = onMore))
    }
}

@Composable
private fun StatsGrid(stats: com.arkdo.android.data.models.UserSummaryStats) {
    val colors = arkdoColors
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 14.dp)) {
            StatCellProfile(formatCount(stats.daysVisited), "访问天数")
            StatCellProfile(formatTimeRead(stats.timeReadSeconds), "阅读时长")
            StatCellProfile(formatCount(stats.likesReceived), "收到的赞")
            StatCellProfile(formatCount(stats.likesGiven), "送出的赞")
        }
        Row(modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 16.dp, bottom = CardPadding)) {
            StatCellProfile(formatCount(stats.topicCount), "创建话题")
            StatCellProfile(formatCount(stats.postCount), "回复")
            StatCellProfile(formatCount(stats.topicsEntered), "浏览话题")
            StatCellProfile(
                if (stats.solvedCount != null) formatCount(stats.solvedCount) else "—",
                "解决方案"
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.StatCellProfile(value: String, label: String) {
    Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = arkdoColors.textPrimary)
        Text(label, fontSize = 11.sp, color = arkdoColors.textFaint, modifier = Modifier.padding(top = 2.dp))
    }
}

/** 快捷入口一格(对齐鸿蒙 QuickEntryNative)。 */
@Composable
private fun androidx.compose.foundation.layout.RowScope.QuickEntryNative(
    icon: String,
    label: String,
    love: Boolean = false,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .weight(1f)
            .clickable(onClick = onClick)
    ) {
        FaIcon(icon, if (love) Color(0xFFFA6C8D) else arkdoColors.textPrimary, 24.dp)
        Text(label, fontSize = 12.sp, color = arkdoColors.textPrimary, modifier = Modifier.padding(top = 7.dp))
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ProfileBadgeTile(badge: UserBadgeItem) {
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val gold = if (isDark) Color(0xFFE0B84C) else Color(0xFFD4A017)
    val silver = if (isDark) Color(0xFF9AA6B8) else Color(0xFF9EA7AD)
    val bronze = if (isDark) Color(0xFFC89066) else Color(0xFFB87333)
    val band = if (isDark) Color(0xFF101114) else Color(0xFFF6F6F6)
    val badgeColor = when (badge.badgeTypeId) { 1 -> gold; 2 -> silver; else -> bronze }
    val label = if ((badge.grantCount ?: 0) > 1) "${badge.name} ×${badge.grantCount}" else badge.name
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(band)
            .padding(top = 12.dp, bottom = 10.dp, start = 6.dp, end = 6.dp)
    ) {
        Box(modifier = Modifier.height(32.dp), contentAlignment = Alignment.Center) {
            if (!badge.imageUrl.isNullOrEmpty()) {
                coil3.compose.AsyncImage(model = badge.imageUrl, contentDescription = badge.name, modifier = Modifier.size(32.dp))
            } else {
                FaIcon(badge.icon?.takeIf { it.isNotEmpty() } ?: "certificate", badgeColor, 26.dp)
            }
        }
        Text(label, fontSize = 11.sp, color = arkdoColors.textSecondary, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 7.dp))
    }
}

@Composable
private fun ProfileActivityRow(item: UserActivityItem, state: ProfileViewModel.UiState) {
    val colors = arkdoColors
    Row(modifier = Modifier.fillMaxWidth().padding(start = CardPadding, end = CardPadding, top = 12.dp, bottom = 11.dp),
        verticalAlignment = Alignment.Top) {
        if (item.actionType == com.arkdo.android.data.models.UserActionType.REPLY.value) {
            FaIcon("reply", colors.textFaint, 13.dp, Modifier.padding(top = 3.dp))
        }
        Text(item.title, fontSize = 15.sp, color = colors.textPrimary, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = if (item.actionType == com.arkdo.android.data.models.UserActionType.REPLY.value) 6.dp else 0.dp))
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = CardPadding, end = CardPadding)) {
        val cat = state.categories.firstOrNull { it.id == item.categoryId }
        if (cat != null) {
            TopicMetadataBadges(category = cat, tags = item.tags, modifier = Modifier.weight(1f))
        } else if (item.tags.isNotEmpty()) {
            TopicMetadataBadges(tags = item.tags, modifier = Modifier.weight(1f))
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }
        RelativeTime.fromNow(item.createdAt).takeIf { it.isNotEmpty() }?.let {
            Text(it, fontSize = 12.sp, color = colors.textFaint, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

// ==================== 未登录内容 ====================

@Composable
private fun AnonymousContent(username: String, onLoginClick: () -> Unit) {
    val colors = arkdoColors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CardRadius))
            .background(colors.surfaceCard)
            .padding(top = 32.dp, bottom = 26.dp)
    ) {
        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .border(1.dp, colors.border, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            FaIcon("user", colors.textFaint, 32.dp)
        }
        Text(
            text = "登录 Linux.do",
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 16.dp, start = CardPadding, end = CardPadding)
        )
        Text(
            text = "账号由该论坛提供,登录后即可参与讨论。登录在论坛自己的页面完成,ArkDO 不经手你的密码。",
            fontSize = 13.sp,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp, start = CardPadding, end = CardPadding)
        )
        Box(
            modifier = Modifier
                .padding(top = 22.dp)
                .clip(RoundedCornerShape(21.dp))
                .background(colors.ldAccent)
                .clickable(onClick = onLoginClick)
                .padding(horizontal = 44.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("立即登录", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 16.dp)
        ) {
            listOf("参与回帖、点赞与投票", "私信与实时通知提醒", "跨设备同步阅读进度").forEachIndexed { index, line ->
                Text(
                    text = line,
                    fontSize = 13.sp,
                    color = colors.textFaint,
                    modifier = Modifier.padding(top = if (index > 0) 6.dp else 0.dp)
                )
            }
        }
    }
}
