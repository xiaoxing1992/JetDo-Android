package com.arkdo.android.ui.user

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.arkdo.android.services.session.LoginGate
import com.arkdo.android.data.models.UserActivityItem
import com.arkdo.android.data.models.UserInteraction
import com.arkdo.android.data.models.UserTopReply
import com.arkdo.android.data.models.UserTopTopic
import com.arkdo.android.services.content.parseInlines
import com.arkdo.android.core.designsystem.FaIcon
import com.arkdo.android.ui.components.InlineText
import com.arkdo.android.ui.components.UserAvatar
import com.arkdo.android.ui.components.TopicMetadataBadges
import com.arkdo.android.ui.theme.arkdoColors
import com.arkdo.android.core.common.RelativeTime
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
    onOpenBadges: (username: String, badgeCount: Int) -> Unit = { _, _ -> },
    onOpenActivity: (username: String, filter: String, title: String) -> Unit = { _, _, _ -> },
    onOpenWeb: (url: String, title: String?) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    viewModel: UserProfileViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(username, seedDisplayName, seedAvatarTemplate) {
        viewModel.load(username, seedDisplayName, seedAvatarTemplate)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
              .background(arkdoColors.pageBgGray)
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
                color = arkdoColors.textPrimary,
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
            IdentityCard(state = state, viewModel = viewModel, onOpenWeb = onOpenWeb)
            if (state.summary != null) {
                BadgesCard(state = state, onAllBadges = { onOpenBadges(state.username, state.profile?.badgeCount ?: 0) })
                CircleCard(state = state, onOpenUser = onOpenUser)
            }
            ContentCard(
                state = state,
                onOpenTopic = onOpenTopic,
                onSelectSegment = viewModel::selectSegment,
                onOpenActivity = onOpenActivity
            )
            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // 认可弹窗(对齐鸿蒙 EndorseSheet.bindSheet:标题「{名字} 的优点是什么?」+ 分类多选
    // + 「认可」主按钮 + 「今天还剩 N 个认可」,右上角自带 ✕)。
    if (state.endorseSheetVisible) {
        EndorseSheetContent(
            state = state,
            onToggle = viewModel::toggleEndorseSelection,
            onSubmit = viewModel::submitEndorse,
            onDismiss = viewModel::dismissEndorseSheet
        )
    }
}

@Composable
private fun EndorseSheetContent(
    state: UserProfileViewModel.UiState,
    onToggle: (Long, Boolean) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = arkdoColors
    val displayName = state.profile?.displayName?.takeIf { it.isNotEmpty() }
        ?: state.displayName.takeIf { it.isNotEmpty() }
        ?: state.displayUsername
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceCard
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp)
        ) {
            Text(
                text = "$displayName 的优点是什么?",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            )
            when {
                state.endorseLoading -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = colors.ldAccent, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("加载可认可的类别", fontSize = 14.sp, color = colors.textSecondary)
                    }
                }
                state.endorsableCategories.isEmpty() -> {
                    Text(
                        text = "暂无可认可的类别",
                        fontSize = 14.sp,
                        color = colors.textFaint,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .wrapContentHeight(Alignment.CenterVertically),
                        textAlign = TextAlign.Center
                    )
                }
                else -> {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        state.endorsableCategories.forEach { category ->
                            val checked = category.id in state.endorseSelectedIds
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { onToggle(category.id, !checked) }
                                    .padding(top = 6.dp, bottom = 6.dp)
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { onToggle(category.id, it) },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = colors.ldAccent,
                                        checkmarkColor = Color.White
                                    )
                                )
                                Text(
                                    text = category.name,
                                    fontSize = 15.sp,
                                    color = colors.textPrimary,
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp)
                    ) {
                        val enabled = state.endorseSelectedIds.isNotEmpty() && !state.endorseSubmitting
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(19.dp))
                                .background(colors.ldAccent.copy(alpha = if (enabled) 1f else 0.5f))
                                .clickable(enabled = enabled, onClick = onSubmit)
                                .padding(horizontal = 28.dp, vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "认可",
                                fontSize = 15.sp,
                                color = Color.White
                            )
                        }
                        Text(
                            text = "今天还剩 ${state.remainingEndorsements} 个认可",
                            fontSize = 13.sp,
                            color = colors.textFaint,
                            modifier = Modifier.padding(start = 14.dp)
                        )
                    }
                }
            }
        }
    }
}

/** 对齐鸿蒙 openSegmentAll():热门话题只看话题(4),热门回复只看回复(5),最近看全量(4,5)。 */
private fun SegmentAllFilterAndTitle(segment: Int, nameLabel: String): Pair<String, String> =
    when (segment) {
        SEGMENT_TOP_TOPICS -> "4" to "$nameLabel 的话题"
        SEGMENT_TOP_REPLIES -> "5" to "$nameLabel 的回复"
        else -> "4,5" to "$nameLabel 的动态"
    }

@Composable
private fun IdentityCard(
    state: UserProfileViewModel.UiState,
    viewModel: UserProfileViewModel,
    onOpenWeb: (String, String?) -> Unit = { _, _ -> }
) {
    val colors = arkdoColors
    val profile = state.profile
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CardRadius))
              .background(arkdoColors.surfaceCard)
    ) {
        // 头像行:头像 64 + 昵称列 + 右侧私信小按钮(与头像垂直居中)。
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
                    color = arkdoColors.textPrimary,
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

            // 私信小按钮:与头像垂直居中;图标 13 + 文字 13,白字 ld_accent 胶囊,右对齐。
            // 恒显(鸿蒙对私信无字段条件),点开站内 new-message(对齐 openPrivateMessage)。
            Box(
                modifier = Modifier
                    .height(32.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(arkdoColors.ldAccent)
                    .clickable {
                        onOpenWeb(
                            "https://linux.do/new-message?username=${state.username}",
                            "私信 ${state.displayUsername}"
                        )
                    }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FaIcon("envelope", Color.White, 13.dp)
                    Text(
                        text = "私信",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White,
                        modifier = Modifier.padding(start = 5.dp)
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
                    .padding(start = CardPadding, end = CardPadding, top = 4.dp, bottom = 2.dp)
            )
        }

        // 操作区:认可 + 关注同一行,各半宽;只渲染可用的(对齐鸿蒙 ActionButtons)。
        // 鸿蒙顺序:meta → ActionButtons → Hairline(分割线)→ 统计区,按钮在分割线上方。
        val canEndorse = state.canEndorse
        val canFollow = profile?.canFollow == true
        if (canEndorse || canFollow) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = CardPadding, end = CardPadding, top = 2.dp, bottom = 14.dp)
            ) {
                if (canEndorse) {
                    ActionButton(
                        onClick = { if (LoginGate.ensureLoggedIn(context)) viewModel.openEndorseSheet() },
                        leadingIcon = if (state.endorsedCount > 0) "circle-check" else null,
                        accent = state.endorsedCount > 0,
                        loading = false,
                        text = if (state.endorsedCount > 0) "已认可 · ${state.endorsedCount}" else "认可",
                        modifier = Modifier.weight(1f)
                    )
                }
                if (canFollow) {
                    ActionButton(
                        onClick = { if (LoginGate.ensureLoggedIn(context)) viewModel.toggleFollow() },
                        leadingIcon = if (state.isFollowing) "user-check" else "user-plus",
                        accent = state.isFollowing,
                        loading = state.followPending,
                        text = if (state.isFollowing) "已关注" else "关注",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        } else {
            // 无操作可显示时留一点底部间距,避免统计区贴着 meta 行。
            Spacer(modifier = Modifier.height(8.dp))
        }

        // 操作区下方的分割线(对齐鸿蒙 HairlineInset:左右各留 CARD_PADDING)。
        HorizontalDivider(
            color = colors.border,
            thickness = 0.5.dp,
            modifier = Modifier.padding(horizontal = CardPadding)
        )

        // 统计网格:2 行 x 4 格。
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
                    // 对齐鸿蒙 SolvedCell:唯一可点的统计格,数值绿色 + 右侧绿色 chevron。
                    // solvedCount>0 才走绿色箭头入口;0/未知则普通灰格。
                    if ((stats.solvedCount ?: 0) > 0) {
                        SolvedCell(formatCount(stats.solvedCount ?: 0))
                    } else {
                        StatCell(
                            if (stats.solvedCount != null) formatCount(stats.solvedCount) else "—",
                            "解决方案"
                        )
                    }
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
private fun androidx.compose.foundation.layout.RowScope.SolvedCell(value: String) {
    // 对齐鸿蒙 SolvedCell:数值 17 Bold 绿色 + chevron_right 11 绿色(间距1),垂直居中,
    // 整格可点进「解决方案」页(页面未迁移,onClick 预留)。
    val success = if (com.arkdo.android.ui.theme.isArkdoInDarkTheme()) Color(0xFF4CAF50) else Color(0xFF009900)
    Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = success
            )
            Text(
                text = "›",
                fontSize = 13.sp,
                color = success,
                modifier = Modifier.padding(start = 1.dp)
            )
        }
        Text(
            text = "解决方案",
            fontSize = 11.sp,
            color = arkdoColors.textFaint,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ActionButton(
    onClick: () -> Unit,
    text: String,
    leadingIcon: String?,
    accent: Boolean,
    loading: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = arkdoColors
    val fg = if (accent) colors.ldAccent else colors.textBody
    Box(
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(colors.surfaceCard)
            .border(1.dp, colors.border, RoundedCornerShape(19.dp))
            .clickable(enabled = !loading, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = fg,
                    strokeWidth = 2.dp
                )
            } else if (leadingIcon != null) {
                FaIcon(leadingIcon, fg, 15.dp)
            }
            if (loading || leadingIcon != null) {
                Spacer(modifier = Modifier.width(7.dp))
            }
            Text(
                text = text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.StatCell(value: String, label: String) {
    // ArkUI Column 子元素默认水平居中(HorizontalAlign.Center)——
    // 每个 quarter 格内的数值/标签都居中,不是贴左。
    Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = arkdoColors.textPrimary
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
              .background(arkdoColors.surfaceCard)
    ) {
        SectionHead(
            title = if ((state.profile?.badgeCount ?: 0) > 0) "徽章 · ${state.profile?.badgeCount}" else "徽章",
            more = "全部 ›",
            onMore = onAllBadges
        )
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
                        BadgeTile(badge = badge)
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
private fun androidx.compose.foundation.layout.RowScope.BadgeTile(badge: com.arkdo.android.data.models.UserBadgeItem) {
    val isDark = com.arkdo.android.ui.theme.isArkdoInDarkTheme()
    val gold = if (isDark) Color(0xFFE0B84C) else Color(0xFFD4A017)
    val silver = if (isDark) Color(0xFF9AA6B8) else Color(0xFF9EA7AD)
    val bronze = if (isDark) Color(0xFFC89066) else Color(0xFFB87333)
    val band = if (isDark) Color(0xFF101114) else Color(0xFFF6F6F6)
    val colors = arkdoColors
    val badgeColor = when (badge.badgeTypeId) {
        1 -> gold
        2 -> silver
        else -> bronze
    }
    val label = if ((badge.grantCount ?: 0) > 1) "${badge.name} ×${badge.grantCount}" else badge.name

    // 徽章小卡片(对齐鸿蒙 BadgeTile.ets):整格 surface_band 底圆角 12,不是彩色砖。
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(band)
            .padding(top = 12.dp, bottom = 10.dp, start = 6.dp, end = 6.dp)
    ) {
        // 图标区:高 32、垂直居中;图片徽章原图 32,FA 图标徽章 26 等级色。
        Box(modifier = Modifier.height(32.dp), contentAlignment = Alignment.Center) {
            if (!badge.imageUrl.isNullOrEmpty()) {
                coil3.compose.AsyncImage(
                    model = badge.imageUrl,
                    contentDescription = badge.name,
                    modifier = Modifier.size(32.dp)
                )
            } else {
                val icon = badge.icon?.takeIf { it.isNotEmpty() } ?: "certificate"
                FaIcon(icon, badgeColor, 26.dp)
            }
        }
        Text(
            text = label,
            fontSize = 11.sp,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 7.dp)
        )
    }
}

@Composable
private fun SectionHead(title: String, more: String? = null, onMore: (() -> Unit)? = null) {
    // 对齐鸿蒙 SectionHead:左标题 15 Bold + 右侧「全部 ›」12 ld_accent(点击)。
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = CardPadding, end = CardPadding, top = 14.dp, bottom = 4.dp)
    ) {
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = arkdoColors.textPrimary
        )
        Spacer(modifier = Modifier.weight(1f))
        if (more != null && onMore != null) {
            Text(
                text = more,
                fontSize = 12.sp,
                color = arkdoColors.ldAccent,
                modifier = Modifier.clickable(onClick = onMore)
            )
        }
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
              .background(arkdoColors.surfaceCard)
    ) {
        Text(
            text = "互动圈",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = arkdoColors.textPrimary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = CardPadding, end = CardPadding, top = 14.dp, bottom = 10.dp)
        )
        rows.forEach { (label, items) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = CardPadding, end = CardPadding, top = 8.dp, bottom = 8.dp)
            ) {
                Text(
                    text = label,
                    fontSize = 13.sp,
                    color = arkdoColors.textSecondary,
                    modifier = Modifier.width(52.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    items.forEach { item ->
                        // 对齐鸿蒙 CircleRow:Stack(BottomEnd){ UserAvatar(30); 徽标 offset(3,3) }。
                        // 外层不裁切(Stack 不裁,徽标越界保留);数字独立成高 15 的居中盒,保证垂直居中
                        // (ArkUI Text 在 15 内自带垂直居中,Compose Text 需要盒子代劳)。
                        Box(
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .size(30.dp)
                                .clickable {
                                    val u = item.user
                                    if (u.username.isNotEmpty()) {
                                        onOpenUser(u.username, u.name, u.avatarTemplate)
                                    }
                                },
                            contentAlignment = Alignment.BottomEnd
                        ) {
                            UserAvatar(user = item.user, avatarSize = 30.dp)
                            if (item.count > 1) {
                                Box(
                                    modifier = Modifier
                                        .offset(x = 3.dp, y = 3.dp)
                                        .heightIn(min = 15.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(arkdoColors.surfaceCard)
                                        .border(0.5.dp, arkdoColors.border, RoundedCornerShape(8.dp))
                                        .padding(horizontal = 3.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "${item.count}",
                                        // 不用固定 height(15)+小行高:9sp 字形自然行高约 10-11sp,
                                        // 行高压到 9 会让字形溢出行盒顶部,被胶囊圆角裁成"显示不全"。
                                        // 行高给 12(略高于字形),胶囊用 minHeight 保住鸿蒙的 15dp,
                                        // 文本小则胶囊仍 15、绝不裁剪;Center 负责真正的垂直居中。
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = arkdoColors.textSecondary,
                                        maxLines = 1,
                                        softWrap = false,
                                        style = TextStyle(
                                            lineHeight = 12.sp,
                                            platformStyle = PlatformTextStyle(includeFontPadding = false)
                                        )
                                    )
                                }
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
    onSelectSegment: (Int) -> Unit = {},
    onOpenActivity: (String, String, String) -> Unit = { _, _, _ -> }
) {
    val colors = arkdoColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = CardGap)
            .clip(RoundedCornerShape(CardRadius))
              .background(arkdoColors.surfaceCard)
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
            Spacer(modifier = Modifier.weight(1f))
            // 「全部 ›」对齐鸿蒙 openSegmentAll():不切分段,按当前分段选动态过滤器——
            // 热门话题=只看话题(4),热门回复=只看回复(5),最近=全量(4,5)。
            // 鸿蒙 onClick 的 Column 无 click 阻断,不会误触身后 tab,这里不用 onSelectSegment。
            Text(
                text = "全部 ›",
                fontSize = 13.sp,
                color = arkdoColors.ldAccent,
                modifier = Modifier
                    .padding(start = 14.dp, end = 2.dp, bottom = 7.dp)
                    .clickable {
                        // 不再本地切 segment —— 只按当前分段选过滤器进动态页(对齐鸿蒙)。
                        val (filter, title) = SegmentAllFilterAndTitle(state.selectedSegment, state.displayUsername)
                        onOpenActivity(state.username, filter, title)
                    }
            )
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
    // 对齐鸿蒙 TopTopicRow:标题 15 primary 最多2行;meta 行(分类徽章 + 实心红心赞数 + 相对时间)。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenTopic(item.topicId, item.title, null) }
            .padding(start = CardPadding, end = CardPadding, top = 12.dp, bottom = 11.dp)
    ) {
        Text(
            text = item.title,
            fontSize = 15.sp,
            color = arkdoColors.textPrimary,
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
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
            if (item.likeCount > 0) {
                LikeBadge(item.likeCount)
            }
            if (!item.createdAt.isNullOrEmpty()) {
                Text(
                    text = RelativeTime.fromNow(item.createdAt),
                    fontSize = 12.sp,
                    color = arkdoColors.textFaint,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun LikeBadge(count: Int) {
    // 实心 heart(与帖子详情同款 FA 图标 + 红)+ 数字(对齐鸿蒙 LikeBadge:icon 12 红,字 12 text_faint)。
    Row(verticalAlignment = Alignment.CenterVertically) {
        com.arkdo.android.core.designsystem.FaIcon("heart", Color(0xFFEF4444), 12.dp)
        Text(
            text = com.arkdo.android.ui.utils.formatCount(count),
            fontSize = 12.sp,
            color = arkdoColors.textFaint,
            modifier = Modifier.padding(start = 3.dp)
        )
    }
}


@Composable
private fun TopReplyRow(
    item: UserTopReply,
    state: UserProfileViewModel.UiState,
    onOpenTopic: (Long, String, Int?) -> Unit
) {
    // 对齐鸿蒙 TopReplyRow:首行 reply 图标(text_faint,13,顶部 3)垫高 + 标题 15 primary;
    // meta 行同话题行。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenTopic(item.topicId, item.topicTitle, item.postNumber) }
            .padding(start = CardPadding, end = CardPadding, top = 12.dp, bottom = 11.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            com.arkdo.android.core.designsystem.FaIcon("reply", arkdoColors.textFaint, 13.dp, Modifier.padding(top = 3.dp))
            Text(
                text = item.topicTitle,
                fontSize = 15.sp,
                color = arkdoColors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 6.dp)
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
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
            if (item.likeCount > 0) {
                LikeBadge(item.likeCount)
            }
            if (!item.createdAt.isNullOrEmpty()) {
                Text(
                    text = RelativeTime.fromNow(item.createdAt),
                    fontSize = 12.sp,
                    color = arkdoColors.textFaint,
                    modifier = Modifier.padding(start = 8.dp)
                )
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
                com.arkdo.android.core.designsystem.FaIcon("reply", colors.textFaint, 13.dp, Modifier.padding(top = 3.dp))
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = item.title,
                fontSize = 15.sp,
                color = arkdoColors.textPrimary,
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
                Text(
                    text = RelativeTime.fromNow(item.createdAt),
                    fontSize = 12.sp,
                    color = colors.textFaint,
                    modifier = Modifier.padding(start = 8.dp)
                )
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
