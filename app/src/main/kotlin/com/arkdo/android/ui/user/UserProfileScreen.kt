package com.arkdo.android.ui.user

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.arkdo.android.data.models.UserActivityItem
import com.arkdo.android.data.models.UserInteraction
import com.arkdo.android.data.models.UserTopReply
import com.arkdo.android.data.models.UserTopTopic
import com.arkdo.android.services.content.parseInlines
import com.arkdo.android.ui.components.InlineText
import com.arkdo.android.ui.components.UserAvatar
import com.arkdo.android.ui.components.TopicMetadataBadges
import com.arkdo.android.ui.theme.arkdoColors
import com.arkdo.android.ui.utils.RelativeTime
import com.arkdo.android.ui.utils.formatCount
import com.arkdo.android.ui.utils.formatJoinDate
import com.arkdo.android.ui.utils.formatTimeRead
import com.arkdo.android.ui.utils.trustLabelOf

// ==========================================================================
// 他人主页（对齐鸿蒙 views/pages/UserProfilePage.ets）。
//
// 卡片顺序：身份（头像/昵称/信任标签/简介/网站/meta）→ 徽章（3 列网格）→
// 互动圈（常回复/赞过/被赞自，头像 + 次数角标）→ 内容（热门话题/热门回复/最近）。
// 布局度量：页面左右 12、卡片圆角 16、卡片内边距 16、卡片间距 12（鸿蒙 Metrics 同值）。
//
// 帖子里点作者大头像、评论楼层头像、boost 头像统一经 onOpenUser(username...) 进入本页。
// ==========================================================================

private val PageMargin = 12.dp
private val CardRadius = 16.dp
private val CardPadding = 16.dp
private val CardGap = 12.dp

@Composable
fun UserProfileScreen(
    username: String,
    seedDisplayName: String? = null,
    seedAvatarTemplate: String? = null,
    onNavigateBack: () -> Unit,
    onOpenTopic: (topicId: Long, title: String, postNumber: Int?) -> Unit = { _, _, _ -> },
    onOpenUser: (username: String, displayName: String?, avatarTemplate: String?) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier,
    viewModel: UserProfileViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(username, seedDisplayName, seedAvatarTemplate) {
        viewModel.load(username, seedDisplayName, seedAvatarTemplate)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(arkdoColors.pageBg)
    ) {
        // 顶栏：返回 + 标题「个人资料」（资料回来后昵称优先，对齐鸿蒙 mainTitle 切换）。
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
                text = state.profile?.displayName?.takeIf { it.isNotEmpty() }
                    ?: state.displayName.takeIf { it.isNotEmpty() }
                    ?: "个人资料",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        HorizontalDivider(color = arkdoColors.border, thickness = 0.5.dp)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PageMargin)
        ) {
            Spacer(modifier = Modifier.height(CardGap))
            IdentityCard(state = state)
            if (state.summary != null) {
                BadgesCard(state = state, onAllBadges = {})
                CircleCard(state = state, onOpenUser = onOpenUser)
            }
            ContentCard(
                state = state,
                onOpenTopic = onOpenTopic,
                onSelectSegment = viewModel::selectSegment
            )
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun IdentityCard(state: UserProfileViewModel.UiState) {
    val colors = arkdoColors
    val profile = state.profile

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CardRadius))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // 头像行：头像 64 + 昵称列 + （关注/认可按钮预留）。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = CardPadding, end = CardPadding, top = CardPadding, bottom = 6.dp)
        ) {
            UserAvatar(user = state.avatarUser, avatarSize = 64.dp)
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                val displayName = profile?.displayName?.takeIf { it.isNotEmpty() }
                    ?: state.displayName.takeIf { it.isNotEmpty() }
                    ?: state.username
                Text(
                    text = displayName,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "@${state.username}",
                        fontSize = 13.sp,
                        color = colors.textTertiary
                    )
                    val title = profile?.title
                    if (!title.isNullOrEmpty()) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = title,
                            fontSize = 13.sp,
                            color = colors.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                val trustLabel = trustLabelOf(profile)
                if (trustLabel.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = trustLabel,
                        fontSize = 12.sp,
                        color = colors.textSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(colors.surfaceMuted)
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
            }
        }

        // 简介（最多 2 行）。
        val bio = profile?.bioExcerpt
        if (!bio.isNullOrEmpty()) {
            val inlines = parseInlines(bio)
            InlineText(
                inlines = inlines,
                fontSize = 13.5.sp,
                lineHeight = 20.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textModifier = Modifier
                    .fillMaxWidth()
                    .padding(start = CardPadding, end = CardPadding, top = 2.dp, bottom = 6.dp)
            )
        }

        // meta 行：加入于 · 最近发帖 · 浏览量。
        val meta = buildMetaLine(profile)
        if (meta.isNotEmpty()) {
            Text(
                text = meta,
                fontSize = 12.sp,
                color = colors.textFaint,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = CardPadding, end = CardPadding, top = 4.dp, bottom = 8.dp)
            )
        }

        HorizontalDivider(color = colors.border, thickness = 0.5.dp)

        // 统计网格：2 行 x 4 格。
        val stats = state.summary?.stats
        if (stats != null) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 14.dp)) {
                    StatCell(formatCount(stats.daysVisited), "访问天数")
                    StatCell(formatTimeRead(stats.timeReadSeconds), "阅读时长")
                    StatCell(formatCount(stats.likesReceived), "收到的赞")
                    StatCell(formatCount(stats.likesGiven), "送出的赞")
                }
                Row(modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 16.dp, bottom = CardPadding)) {
                    StatCell(formatCount(stats.topicCount), "创建话题")
                    StatCell(formatCount(stats.postCount), "回复")
                    StatCell(formatCount(stats.topicsEntered), "浏览话题")
                    StatCell(
                        if (stats.solvedCount != null) formatCount(stats.solvedCount) else "—",
                        "解决方案"
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                contentAlignment = Alignment.Center
            ) {
                if (profile != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = colors.ldAccent, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("正在加载论坛数据", fontSize = 13.sp, color = colors.textSecondary)
                    }
                } else {
                    Text("正在加载", fontSize = 13.sp, color = colors.textTertiary)
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.StatCell(value: String, label: String) {
    Column(modifier = Modifier.weight(1f)) {
        Text(
            text = value,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            fontSize = 11.sp,
            color = arkdoColors.textFaint,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
private fun buildMetaLine(profile: com.arkdo.android.data.models.UserProfileDetail?): String {
    if (profile == null) return ""
    val parts = mutableListOf<String>()
    formatJoinDate(profile.createdAt).takeIf { it.isNotEmpty() }?.let { parts.add("加入于 $it") }
    RelativeTime.fromNow(profile.lastPostedAt).takeIf { it.isNotEmpty() }?.let { parts.add("最近发帖 $it") }
    if ((profile.profileViewCount ?: 0) > 0) {
        parts.add("浏览量 ${formatCount(profile.profileViewCount ?: 0)}")
    }
    return parts.joinToString(" · ")
}

@Composable
private fun BadgesCard(state: UserProfileViewModel.UiState, onAllBadges: () -> Unit) {
    val badges = state.summary?.topBadges.orEmpty()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = CardGap)
            .clip(RoundedCornerShape(CardRadius))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        SectionHead(title = if ((state.profile?.badgeCount ?: 0) > 0) "徽章 · ${state.profile?.badgeCount}" else "徽章")
        if (badges.isNotEmpty()) {
            // 固定 3 列网格，末行用透明占位补足等分宽。
            badges.chunked(3).forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = CardPadding, end = CardPadding, top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    row.forEach { badge ->
                        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            BadgeTile(badge = badge)
                        }
                    }
                    repeat(3 - row.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        } else {
            Text(
                text = "暂无徽章",
                fontSize = 13.sp,
                color = arkdoColors.textTertiary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(CardPadding)
            )
        }
    }
}

@Composable
private fun BadgeTile(badge: com.arkdo.android.data.models.UserBadgeItem) {
    val colors = arkdoColors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val bg = when (badge.badgeTypeId) {
            1 -> Color(0xFFF5E6B8) // 金
            2 -> Color(0xFFE0E4E8) // 银
            else -> Color(0xFFE3B7A8) // 铜
        }
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(bg),
            contentAlignment = Alignment.Center
        ) {
            if (!badge.imageUrl.isNullOrEmpty()) {
                coil3.compose.AsyncImage(
                    model = badge.imageUrl,
                    contentDescription = badge.name,
                    modifier = Modifier.size(28.dp)
                )
            } else {
                // FA 名图标染色（金/银/铜等级色）。
                val fg = when (badge.badgeTypeId) {
                    1 -> Color(0xFFB8860B)
                    2 -> Color(0xFF6B7280)
                    else -> Color(0xFF8B4513)
                }
                Text(text = "◆", color = fg, fontSize = 16.sp)
            }
        }
        Text(
            text = badge.name,
            fontSize = 11.sp,
            color = colors.textFaint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun SectionHead(title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = CardPadding, end = CardPadding, top = 14.dp, bottom = 2.dp)
    ) {
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun CircleCard(
    state: UserProfileViewModel.UiState,
    onOpenUser: (String, String?, String?) -> Unit
) {
    val summary = state.summary ?: return
    val rows = listOf(
        "常回复" to summary.mostRepliedToUsers,
        "赞过" to summary.mostLikedUsers,
        "被赞自" to summary.mostLikedByUsers
    ).filter { it.second.isNotEmpty() }
    if (rows.isEmpty()) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = CardGap)
            .clip(RoundedCornerShape(CardRadius))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Text(
            text = "互动圈",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = CardPadding, end = CardPadding, top = 14.dp, bottom = 10.dp)
        )
        rows.forEach { (label, items) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = CardPadding, end = CardPadding, bottom = 12.dp)
            ) {
                Text(
                    text = label,
                    fontSize = 13.sp,
                    color = arkdoColors.textSecondary,
                    modifier = Modifier.width(52.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    items.forEach { item ->
                        Box(contentAlignment = Alignment.BottomEnd) {
                            UserAvatar(user = item.user, avatarSize = 30.dp, modifier = Modifier.padding(end = 4.dp))
                            if (item.count > 1) {
                                Text(
                                    text = "${item.count}",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = arkdoColors.textSecondary,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surface)
                                        .padding(horizontal = 3.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
    }
}

@Composable
private fun ContentCard(
    state: UserProfileViewModel.UiState,
    onOpenTopic: (Long, String, Int?) -> Unit,
    onSelectSegment: (Int) -> Unit = {}
) {
    val colors = arkdoColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = CardGap)
            .clip(RoundedCornerShape(CardRadius))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // 分段行：热门话题 / 热门回复 / 最近。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = CardPadding, end = CardPadding, top = 14.dp)
        ) {
            SegmentTab("热门话题", SEGMENT_TOP_TOPICS, state.selectedSegment) { onSelectSegment(SEGMENT_TOP_TOPICS) }
            SegmentTab("热门回复", SEGMENT_TOP_REPLIES, state.selectedSegment) { onSelectSegment(SEGMENT_TOP_REPLIES) }
            SegmentTab("最近", SEGMENT_RECENT, state.selectedSegment) { onSelectSegment(SEGMENT_RECENT) }
        }
        HorizontalDivider(color = colors.border, thickness = 0.5.dp, modifier = Modifier.padding(top = 10.dp))

        when (state.selectedSegment) {
            SEGMENT_TOP_TOPICS -> {
                val topics = state.summary?.topTopics.orEmpty()
                if (topics.isNotEmpty()) {
                    topics.forEachIndexed { index, item ->
                        if (index > 0) HorizontalDivider(color = colors.border, thickness = 0.5.dp)
                        TopTopicRow(item, state, onOpenTopic)
                    }
                } else if (state.summary != null) {
                    EmptyHint("还没有发过话题")
                } else {
                    EmptyHint("正在加载")
                }
            }
            SEGMENT_TOP_REPLIES -> {
                val replies = state.summary?.topReplies.orEmpty()
                if (replies.isNotEmpty()) {
                    replies.forEachIndexed { index, item ->
                        if (index > 0) HorizontalDivider(color = colors.border, thickness = 0.5.dp)
                        TopReplyRow(item, state, onOpenTopic)
                    }
                } else if (state.summary != null) {
                    EmptyHint("还没有回复过")
                } else {
                    EmptyHint("正在加载")
                }
            }
            else -> {
                if (state.recentActivity.isNotEmpty()) {
                    state.recentActivity.forEachIndexed { index, item ->
                        if (index > 0) HorizontalDivider(color = colors.border, thickness = 0.5.dp)
                        ActivityRow(item, state, onOpenTopic)
                    }
                } else {
                    EmptyHint("最近没有动态")
                }
            }
        }
    }
}

@Composable
private fun SegmentTab(label: String, index: Int, selected: Int, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .padding(end = 18.dp)
            .clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            fontSize = 15.sp,
            fontWeight = if (selected == index) FontWeight.Bold else FontWeight.Normal,
            color = if (selected == index) MaterialTheme.colorScheme.onSurface else arkdoColors.textFaint
        )
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .width((label.length * 15).dp)
                .height(2.dp)
                .background(if (selected == index) arkdoColors.ldAccent else Color.Transparent)
        )
    }
}

@Composable
private fun TopTopicRow(
    item: UserTopTopic,
    state: UserProfileViewModel.UiState,
    onOpenTopic: (Long, String, Int?) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenTopic(item.topicId, item.title, null) }
            .padding(start = CardPadding, end = CardPadding, top = 12.dp, bottom = 11.dp)
    ) {
        Text(
            text = item.title,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 5.dp)
        ) {
            val category = state.categoryFor(item.categoryId)
            if (category != null) {
                TopicMetadataBadges(
                    category = category,
                    parentCategory = state.parentFor(item.categoryId),
                    tags = emptyList(),
                    modifier = Modifier.weight(1f)
                )
            }
            if (item.likeCount > 0) {
                Text(
                    text = "♥ ${formatCount(item.likeCount)}",
                    fontSize = 12.sp,
                    color = arkdoColors.textFaint
                )
            }
            if (!item.createdAt.isNullOrEmpty()) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = RelativeTime.fromNow(item.createdAt), fontSize = 12.sp, color = arkdoColors.textFaint)
            }
        }
    }
}

@Composable
private fun TopReplyRow(
    item: UserTopReply,
    state: UserProfileViewModel.UiState,
    onOpenTopic: (Long, String, Int?) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenTopic(item.topicId, item.topicTitle, item.postNumber) }
            .padding(start = CardPadding, end = CardPadding, top = 12.dp, bottom = 11.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(text = "↩", fontSize = 13.sp, color = arkdoColors.textFaint, modifier = Modifier.padding(top = 3.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = item.topicTitle,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 5.dp)
        ) {
            val category = state.categoryFor(item.categoryId)
            if (category != null) {
                TopicMetadataBadges(
                    category = category,
                    parentCategory = state.parentFor(item.categoryId),
                    tags = emptyList(),
                    modifier = Modifier.weight(1f)
                )
            }
            if (item.likeCount > 0) {
                Text(
                    text = "♥ ${formatCount(item.likeCount)}",
                    fontSize = 12.sp,
                    color = arkdoColors.textFaint
                )
            }
            if (!item.createdAt.isNullOrEmpty()) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = RelativeTime.fromNow(item.createdAt), fontSize = 12.sp, color = arkdoColors.textFaint)
            }
        }
    }
}

@Composable
private fun ActivityRow(
    item: UserActivityItem,
    state: UserProfileViewModel.UiState,
    onOpenTopic: (Long, String, Int?) -> Unit
) {
    val colors = arkdoColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenTopic(item.topicId, item.title, item.postNumber) }
            .padding(start = CardPadding, end = CardPadding, top = 12.dp, bottom = 11.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            if (item.actionType == 5) {
                Text(text = "↩", fontSize = 13.sp, color = colors.textFaint, modifier = Modifier.padding(top = 3.dp))
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = item.title,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 5.dp)
        ) {
            val category = state.categoryFor(item.categoryId)
            if (category != null) {
                TopicMetadataBadges(
                    category = category,
                    parentCategory = state.parentFor(item.categoryId),
                    tags = item.tags,
                    modifier = Modifier.weight(1f)
                )
            }
            if (!item.createdAt.isNullOrEmpty()) {
                Text(text = RelativeTime.fromNow(item.createdAt), fontSize = 12.sp, color = colors.textFaint)
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        color = arkdoColors.textTertiary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(CardPadding)
    )
}
