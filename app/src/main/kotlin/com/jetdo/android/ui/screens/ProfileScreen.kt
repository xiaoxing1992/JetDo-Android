package com.jetdo.android.ui.screens

import androidx.compose.ui.res.stringResource
import com.jetdo.android.core.data.AppContextHolder
import com.jetdo.android.R
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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.jetdo.android.data.models.User
import com.jetdo.android.data.models.UserActivityItem
import com.jetdo.android.data.models.UserBadgeItem
import com.jetdo.android.services.session.CurrentUserHolder
import com.jetdo.android.core.designsystem.FaIcon
import com.jetdo.android.core.designsystem.bottomBarClearance
import com.jetdo.android.ui.components.InlineText
import com.jetdo.android.ui.components.TopicMetadataBadges
import com.jetdo.android.ui.components.UserAvatar
import com.jetdo.android.ui.qr.QrLoginShowDialog
import com.jetdo.android.ui.theme.jetdoColors
import com.jetdo.android.common.constants.AppConstants
import com.jetdo.android.core.model.RelativeTime
import com.jetdo.android.services.credit.LdcCreditEventBus
import com.jetdo.android.services.credit.LdcCreditSession
import com.jetdo.android.services.credit.LdcEntryEvent
import com.jetdo.android.services.credit.LdcEntryPhase
import com.jetdo.android.services.credit.LdcEntryState
import com.jetdo.android.services.credit.fetchLdcUserInfo
import com.jetdo.android.services.credit.formatLdcAmount
import com.jetdo.android.services.credit.ldcUserInfoEvent
import com.jetdo.android.services.credit.reduceLdcEntry
import com.jetdo.android.services.network.ArkWebJsonExecutor
import com.jetdo.android.ui.credit.LdcCreditWebHost
import kotlinx.coroutines.launch
import com.jetdo.android.ui.theme.isJetDoInDarkTheme
import com.jetdo.android.ui.utils.daysSince
import com.jetdo.android.ui.utils.formatCount
import com.jetdo.android.ui.utils.formatTimeRead
import com.jetdo.android.ui.utils.trustLabelWithTitleOf
import com.jetdo.android.services.content.parseInlines
import com.jetdo.android.services.draft.DraftCountStore
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
private val UserMetaHeight = 26.dp

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
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = jetdoColors.textPrimary)
        }
        Text(
            text = stringResource(R.string.home_profile),
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = jetdoColors.textPrimary,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.common_settings), tint = jetdoColors.textPrimary)
        }
    }
}

/**
 * 沉浸态「我的」页的右上角设置入口。
 *
 * 形态对齐官方 iOS App 的「我的」页：右上角一枚圆底齿轮，**不随登录态显隐**。
 * 只画齿轮不画标题，所以不能复用 [ProfileTopBar]（它左边带返回箭头、中间带标题，
 * 是 push 进来的独立页形态）；齿轮本身也复用同一个 Icons.Filled.Settings，
 * 保证两处入口长得一样。
 */
@Composable
private fun ImmersiveSettingsEntry(onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = 4.dp, end = 4.dp),
        horizontalArrangement = Arrangement.End
    ) {
        IconButton(onClick = onOpenSettings) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = stringResource(R.string.common_settings),
                tint = jetdoColors.textPrimary
            )
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
    // 未登录态下方的备用登录方式：扫码登录（扫入端）→ 相机扫码页；
    // 站点授权登录 → App 内 WebView 承载的授权页（BrowserAuthWebPage）。
    onScanQr: () -> Unit = {},
    onSiteAuth: () -> Unit = {},
    onOpenWeb: (String, String?) -> Unit = { _, _ -> },
    // 最近活动行点击 → 原生详情页并定位楼层（对齐鸿蒙 ProfilePage 的 onOpenTopic）。
    onOpenTopic: (topicId: Long, title: String, postNumber: Int?) -> Unit = { _, _, _ -> },
    onOpenActivity: (String, String, String) -> Unit = { _, _, _ -> },
    onOpenBadges: (String, Int) -> Unit = { _, _ -> },
    onOpenDrafts: () -> Unit = {},
    onOpenBookmarks: () -> Unit = {},
    onOpenReadTopics: () -> Unit = {},
    onOpenLevelProgress: () -> Unit = {},
    onOpenLdcCredit: (Boolean) -> Unit = {},
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
    var showQrLogin by remember { mutableStateOf(false) }
    val ldcVersion by LdcCreditEventBus.version.collectAsState()
    var ldc by remember { mutableStateOf(LdcEntryState()) }
    var ldcExecutor by remember { mutableStateOf<ArkWebJsonExecutor?>(null) }
    val scope = rememberCoroutineScope()

    fun refreshLdc() {
        ldc = reduceLdcEntry(
            ldc,
            LdcEntryEvent.Refresh(
                hasSession = LdcCreditSession.hasSession(),
                cached = LdcCreditSession.getCachedInfo(),
                cacheFresh = LdcCreditSession.isCacheFresh()
            )
        )
    }

    fun applyLdcEvent(event: LdcEntryEvent) {
        if (event is LdcEntryEvent.Loaded) LdcCreditSession.setCachedInfo(event.info)
        if (event is LdcEntryEvent.Unauthorized) LdcCreditSession.clearCache()
        ldc = reduceLdcEntry(ldc, event)
    }

    LaunchedEffect(accountUser.username, ldcVersion) {
        if (accountUser.username.isNotEmpty()) refreshLdc() else ldc = reduceLdcEntry(ldc, LdcEntryEvent.Reset)
    }

    // 进页/账号变化时按 username 加载资料(对齐鸿蒙 handleAccountChanged)。
    LaunchedEffect(accountUser.username) {
        if (accountUser.username.isNotEmpty()) {
            viewModel.load(accountUser.username)
        }
    }

    // 状态栏条区:Tab 沉浸视图顶到状态栏,条区颜色由这里接管(pageBgGray:亮 #F2F3F5,暗 #000)。
    // 用 Box 把灰色垫在内容底下,再正常 padding 内容避开状态栏。独立页走系统条默认色。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(jetdoColors.pageBgGray)
    ) {
        if (ldc.hostActive) {
            LdcCreditWebHost(
                initialUrl = AppConstants.CREDIT_BOOTSTRAP_URL,
                navTarget = "",
                interactive = false,
                onReady = {},
                onLoadFailed = { applyLdcEvent(LdcEntryEvent.LoadFailed) },
                onPageSettled = { url ->
                    if (!url.startsWith(AppConstants.CREDIT_BASE_URL)) return@LdcCreditWebHost
                    val exec = ldcExecutor ?: return@LdcCreditWebHost
                    scope.launch { applyLdcEvent(ldcUserInfoEvent(fetchLdcUserInfo(exec))) }
                },
                onExecutor = { ldcExecutor = it },
                modifier = Modifier.fillMaxSize()
            )
        }
        // 顶栏与内容必须竖向排列:同处一个 Box 时,fillMaxSize 的内容层会叠在顶栏之上
        // (Box 内后声明的子节点画在上层),独立页(push 进来)的返回栏会被第一张卡片整个盖住,
        // 表现成「没有标题栏」。改用外层 Column,内容从顶栏下方开始排。
        Column(modifier = Modifier.fillMaxSize()) {
            if (showTopBar) {
                ProfileTopBar(onNavigateBack = onNavigateBack, onOpenSettings = onOpenSettings)
                HorizontalDivider(color = jetdoColors.border, thickness = 0.5.dp)
            } else if (immersive) {
                // 沉浸态:不画整条标题栏(内容顶到状态栏),但右上角**常驻设置齿轮**。
                // 「我的」是底栏一级页、无返回箭头,设置是它唯一通向全局配置的出口;
                // 沿用「登录后才可用」的旧写法会让未登录用户在这里彻底够不到设置 ——
                // 而外观/网络/推送这些开关本就该在登录前可改。
                ImmersiveSettingsEntry(onOpenSettings = onOpenSettings)
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = PageMargin)
                    // 底栏是悬浮胶囊:滚动内容末尾垫一个避让高度,滚到底不被遮。
                    // 根导航上的独立个人主页没有底栏,LocalBottomBarClearance 默认 0,不多垫。
                    .padding(bottom = bottomBarClearance)
            ) {
                Spacer(modifier = Modifier.height(CardGap))
                if (isLoggedIn && accountUser.username.isNotEmpty()) {
                    LoggedInContent(
                        state = state,
                        accountUser = accountUser,
                        onOpenWeb = onOpenWeb,
                        onOpenTopic = onOpenTopic,
                        onOpenActivity = onOpenActivity,
                        onOpenBadges = onOpenBadges,
                        onOpenDrafts = onOpenDrafts,
                        onOpenBookmarks = onOpenBookmarks,
                        onOpenReadTopics = onOpenReadTopics,
                        onOpenLevelProgress = onOpenLevelProgress,
                        onOpenLdcCredit = onOpenLdcCredit,
                        ldc = ldc,
                        onRetryLdc = { refreshLdc() },
                        onRequestLogout = { showLogoutDialog = true },
                        onShowQrLogin = { showQrLogin = true }
                    )
                } else {
                    // 未登录占位:沉浸态下只垫了状态栏高度,没有额外垂直 padding,
                    // 第一条内容的圆角/边框会顶到状态栏区,这里再补一段状态栏高度 + 留白。
                    Spacer(
                        modifier = Modifier
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .padding(top = 8.dp)
                    )
                    AnonymousContent(
                        username = accountUser.username,
                        onLoginClick = onOpenLogin
                    )
                    AnonymousAuthOptions(onScanQr = onScanQr, onSiteAuth = onSiteAuth)
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // 退出登录二次确认(对齐鸿蒙 confirmLogout)。
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text(stringResource(R.string.login_logout), color = jetdoColors.textPrimary) },
            text = { Text(stringResource(R.string.logout_confirm), color = jetdoColors.textSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutDialog = false
                    viewModel.logout { ok ->
                        Toast.makeText(
                            context,
                            if (ok) context.getString(R.string.login_logged_out) else context.getString(R.string.logout_failed),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }) {
                    Text(stringResource(R.string.common_exit), color = jetdoColors.ldDanger)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text(stringResource(R.string.common_cancel), color = jetdoColors.textSecondary)
                }
            }
        )
    }
    // 扫码登录·出示端：已登录设备生成一次性二维码给另一台 JetDo 扫（设备间迁移登录）。
    if (showQrLogin && isLoggedIn && accountUser.username.isNotEmpty()) {
        QrLoginShowDialog(
            username = accountUser.username,
            onDismiss = { showQrLogin = false }
        )
    }
}

// ==================== 已登录内容 ====================

@Composable
private fun LoggedInContent(
    state: ProfileViewModel.UiState,
    accountUser: User,
    onOpenWeb: (String, String?) -> Unit,
    onOpenTopic: (Long, String, Int?) -> Unit,
    onOpenActivity: (String, String, String) -> Unit,
    onOpenBadges: (String, Int) -> Unit,
    onOpenDrafts: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenReadTopics: () -> Unit,
    onOpenLevelProgress: () -> Unit,
    onOpenLdcCredit: (Boolean) -> Unit,
    ldc: LdcEntryState,
    onRetryLdc: () -> Unit,
    onRequestLogout: () -> Unit,
    onShowQrLogin: () -> Unit
) {
    val colors = jetdoColors
    val username = accountUser.username
    val profile = state.profile
    // 草稿角标真源：会话复查/登出/删草稿三处写入 DraftCountStore，这里只订阅。
    // store 为 null 表示「还没被权威结果写过」，回退到登录快照 accountUser.draftCount
    // （对齐鸿蒙 ProfilePage 的 @StorageProp + RootTabPage 的 storedCount 回退写法）。
    val draftCountByStore by DraftCountStore.count.collectAsState()
    val draftBadgeCount = draftCountByStore ?: accountUser.draftCount ?: 0
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 3.dp)
                ) {
                    if (username.isNotEmpty()) {
                        Text("@$username", fontSize = 13.sp, color = colors.textSecondary)
                    }
                    val trust = trustLabelWithTitleOf(profile)
                    if (trust.isNotEmpty()) {
                        if (username.isNotEmpty()) {
                            Text(" · ", fontSize = 13.sp, color = colors.textSecondary)
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.surfaceMuted)
                                .clickable(onClick = onOpenLevelProgress)
                                .padding(start = 8.dp, end = 6.dp, top = 2.dp, bottom = 2.dp)
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
            }
            // 扫码登录·出示端入口（对齐 FluxDO `_ProfileHeader`）：昵称区之后、右箭头之前，
            // 一枚 32dp 圆底二维码图标。FluxDO 用的是 Symbols.qr_code_rounded，这里对应
            // Icons.Rounded.QrCode2。
            //
            // 为什么单独给一份 clickable：外层没有整行点击（头像/昵称/右箭头各自绑 openProfileWeb），
            // 所以这里是平级节点、不存在手势竞技场争抢——FluxDO 那边要在整行 GestureDetector 里
            // 额外嵌一层内层手势才压得住冒泡，我们不需要。
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceMuted)
                    .clickable(onClick = onShowQrLogin),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.QrCode2,
                    contentDescription = stringResource(R.string.auth_qr_show_entry),
                    tint = colors.textSecondary,
                    modifier = Modifier.size(17.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            FaIcon(
                "chevron-right",
                colors.textFaint,
                14.dp,
                Modifier.clickable(onClick = openProfileWeb)
            )
        }
        val meta = buildProfileMeta(profile)
        Text(
            text = meta,
            fontSize = 12.sp,
            color = colors.textFaint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .height(UserMetaHeight)
                .padding(start = CardPadding, end = CardPadding)
                .wrapContentHeight(Alignment.CenterVertically)
        )
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
                Text(stringResource(R.string.drawer_loading_forum), fontSize = 13.sp, color = colors.textSecondary)
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
        QuickEntryNative(icon = "clock-rotate-left", label = stringResource(R.string.user_browse_history), love = false) {
            onOpenReadTopics()
        }
        QuickEntryNative(icon = "bookmark", label = stringResource(R.string.bookmarks_title), love = false) {
            onOpenBookmarks()
        }
        QuickEntryNative(icon = "heart", label = stringResource(R.string.user_likes_given), love = true) {
            onOpenActivity(username, "1", AppContextHolder.getString(R.string.user_likes_given))
        }
        QuickEntryNative(icon = "pen-to-square", label = stringResource(R.string.drafts_title), badgeCount = draftBadgeCount) {
            onOpenDrafts()
        }
    }

    LdcEntryCard(state = ldc, onOpen = onOpenLdcCredit, onRetry = onRetryLdc)

    // 扫码登录·出示端入口已上移到身份卡头部的二维码图标（对齐 FluxDO），这里不再重复给一行，
    // 否则同一个弹窗会有两个入口。

    // 卡片 3:徽章
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = CardGap)
            .clip(RoundedCornerShape(CardRadius))
            .background(colors.surfaceCard)
    ) {
        SectionHeadProfile(
            title = if ((profile?.badgeCount ?: 0) > 0) stringResource(R.string.drawer_badges_profile_count, profile?.badgeCount ?: 0) else stringResource(R.string.user_badges),
            onMore = { onOpenBadges(username, profile?.badgeCount ?: 0) }
        )
        if (state.topBadges.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = CardPadding, end = CardPadding, top = 10.dp, bottom = CardPadding)
            ) {
                state.topBadges.chunked(3).forEachIndexed { index, row ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = if (index > 0) 8.dp else 0.dp)
                    ) {
                        row.forEach { badge -> ProfileBadgeTile(badge) }
                        repeat(3 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                    }
                }
            }
        } else {
            Text(
                text = stringResource(R.string.user_no_badges),
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
            title = stringResource(R.string.user_recent_activity),
            onMore = { onOpenActivity(username, "4,5", AppContextHolder.getString(R.string.user_recent_activity)) }
        )
        if (state.recentActivity.isNotEmpty()) {
            state.recentActivity.forEachIndexed { index, item ->
                if (index > 0) {
                    HorizontalDivider(color = colors.border, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = CardPadding))
                }
                ProfileActivityRow(item, state, onOpenTopic)
            }
            // 底部「我的话题 N › · 我的回复 N ›」。
            val topicsLabel = state.stats?.let { stringResource(R.string.drawer_my_topics_count, formatCount(it.topicCount)) } ?: stringResource(R.string.drawer_my_topics)
            val repliesLabel = state.stats?.let { stringResource(R.string.drawer_my_replies_count, formatCount(it.postCount)) } ?: stringResource(R.string.drawer_my_replies)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = CardPadding, end = CardPadding, top = 8.dp, bottom = 14.dp)
            ) {
                Text(topicsLabel, fontSize = 13.sp, color = colors.ldAccent,
                    modifier = Modifier.clickable { onOpenActivity(username, "4", AppContextHolder.getString(R.string.user_my_topics)) })
                Text("·", fontSize = 13.sp, color = colors.textFaint,
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp))
                Text(repliesLabel, fontSize = 13.sp, color = colors.ldAccent,
                    modifier = Modifier.clickable { onOpenActivity(username, "5", AppContextHolder.getString(R.string.user_my_replies)) })
            }
        } else {
            Text(
                text = stringResource(R.string.user_no_posts),
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
        Text(stringResource(R.string.login_logout), fontSize = 15.sp, color = colors.ldDanger)
    }
}

@Composable
private fun buildProfileMeta(profile: com.jetdo.android.data.models.UserProfileDetail?): String {
    if (profile == null) return ""
    val parts = mutableListOf<String>()
    daysSince(profile.createdAt).takeIf { it > 0 }?.let { parts.add(stringResource(R.string.user_joined_days, it)) }
    RelativeTime.fromNow(profile.lastPostedAt).takeIf { it.isNotEmpty() }?.let { parts.add(stringResource(R.string.composer_recent_posts, it)) }
    if ((profile.profileViewCount ?: 0) > 0) {
        parts.add(stringResource(R.string.user_profile_views, formatCount(profile.profileViewCount ?: 0)))
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
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = jetdoColors.textPrimary)
        Spacer(modifier = Modifier.weight(1f))
        Text(stringResource(R.string.common_all_more), fontSize = 12.sp, color = jetdoColors.ldAccent,
            modifier = Modifier.clickable(onClick = onMore))
    }
}

@Composable
private fun StatsGrid(stats: com.jetdo.android.data.models.UserSummaryStats) {
    val colors = jetdoColors
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 14.dp)) {
            StatCellProfile(formatCount(stats.daysVisited), stringResource(R.string.user_days_visited))
            StatCellProfile(formatTimeRead(stats.timeReadSeconds), stringResource(R.string.user_time_read))
            StatCellProfile(formatCount(stats.likesReceived), stringResource(R.string.user_likes_received))
            StatCellProfile(formatCount(stats.likesGiven), stringResource(R.string.user_likes_given2))
        }
        Row(modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 16.dp, bottom = CardPadding)) {
            StatCellProfile(formatCount(stats.topicCount), stringResource(R.string.composer_new_topic))
            StatCellProfile(formatCount(stats.postCount), stringResource(R.string.common_reply))
            StatCellProfile(formatCount(stats.topicsEntered), stringResource(R.string.action_browse_topics))
            StatCellProfile(
                if (stats.solvedCount != null) formatCount(stats.solvedCount) else "—",
                stringResource(R.string.detail_solution)
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.StatCellProfile(value: String, label: String) {
    Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = jetdoColors.textPrimary)
        Text(label, fontSize = 11.sp, color = jetdoColors.textFaint, modifier = Modifier.padding(top = 2.dp))
    }
}

/** LINUX DO Credit 入口行。有会话就自己取余额；失败时有旧数字就继续显示，没有才给「重试」。 */
@Composable
private fun LdcEntryCard(state: LdcEntryState, onOpen: (Boolean) -> Unit, onRetry: () -> Unit) {
    val colors = jetdoColors
    val anonymous = state.phase == LdcEntryPhase.ANON
    val gold = if (isJetDoInDarkTheme()) Color(0xFFE0B84C) else Color(0xFFD4A017)
    val subtitle = when (state.phase) {
        LdcEntryPhase.ANON -> stringResource(R.string.credit_login_to_view)
        LdcEntryPhase.ERROR -> stringResource(R.string.credit_balance_unavailable)
        else -> stringResource(R.string.credit_recent_records)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = CardGap)
            .clip(RoundedCornerShape(CardRadius))
            .background(colors.surfaceCard)
            .clickable {
                if (state.phase == LdcEntryPhase.ERROR) onRetry() else onOpen(anonymous)
            }
            .padding(horizontal = CardPadding, vertical = 14.dp)
    ) {
        FaIcon("coins", gold, 20.dp)
        Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
            Text("LINUX DO Credit", fontSize = 15.sp, color = colors.textPrimary)
            Text(subtitle, fontSize = 12.sp, color = colors.textFaint, modifier = Modifier.padding(top = 2.dp))
        }
        when {
            state.phase == LdcEntryPhase.LOADING && state.info == null -> {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.ldAccent)
            }
            state.info != null -> {
                Text(formatLdcAmount(state.info.availableBalance), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary)
            }
            else -> {
                Text(if (anonymous) stringResource(R.string.login_go) else stringResource(R.string.common_retry), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = colors.ldAccent)
            }
        }
        FaIcon("chevron-right", colors.textFaint, 14.dp, Modifier.padding(start = 6.dp))
    }
}

/** 快捷入口一格(对齐鸿蒙 QuickEntryNative)。 */
@Composable
private fun androidx.compose.foundation.layout.RowScope.QuickEntryNative(
    icon: String,
    label: String,
    love: Boolean = false,
    badgeCount: Int = 0,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .weight(1f)
            .clickable(onClick = onClick)
    ) {
        Box {
            FaIcon(icon, if (love) Color(0xFFFA6C8D) else jetdoColors.textPrimary, 24.dp)
            if (badgeCount > 0) {
                val count = if (badgeCount > 99) "99+" else badgeCount.toString()
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 8.dp, y = (-6).dp)
                        .height(16.dp)
                        .defaultMinSize(minWidth = 16.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(jetdoColors.ldAccent)
                        .padding(horizontal = 4.dp)
                ) {
                    Text(
                        text = count,
                        fontSize = 10.sp,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        style = TextStyle(
                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                            lineHeight = 10.sp
                        )
                    )
                }
            }
        }
        Text(label, fontSize = 12.sp, color = jetdoColors.textPrimary, modifier = Modifier.padding(top = 7.dp))
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ProfileBadgeTile(badge: UserBadgeItem) {
    val isDark = com.jetdo.android.ui.theme.isJetDoInDarkTheme()
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
        Text(label, fontSize = 11.sp, color = jetdoColors.textSecondary, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 7.dp))
    }
}

@Composable
private fun ProfileActivityRow(
    item: UserActivityItem,
    state: ProfileViewModel.UiState,
    onOpenTopic: (Long, String, Int?) -> Unit
) {
    val colors = jetdoColors
    val isReply = item.actionType == com.jetdo.android.data.models.UserActionType.REPLY.value
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 对齐鸿蒙 ProfilePage.ets:676-678：整行进详情页并定位到这条动态所在的楼层。
            // 此前整块没有 clickable，看着是普通列表项、点了没有任何反应。
            .clickable { onOpenTopic(item.topicId, item.title, item.postNumber) }
            .padding(start = CardPadding, end = CardPadding, top = 12.dp, bottom = 11.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            if (isReply) {
                FaIcon("reply", colors.textFaint, 13.dp, Modifier.padding(top = 3.dp))
            }
            Text(
                item.title,
                fontSize = 15.sp,
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = if (isReply) 6.dp else 0.dp)
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 5.dp)
        ) {
            val cat = state.categories.firstOrNull { it.id == item.categoryId }
            val parent = cat?.parentCategoryId?.let { id -> state.categories.firstOrNull { it.id == id } }
            if (cat != null || item.tags.isNotEmpty()) {
                TopicMetadataBadges(
                    category = cat,
                    parentCategory = parent,
                    tags = item.tags,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
            RelativeTime.fromNow(item.createdAt).takeIf { it.isNotEmpty() }?.let {
                Text(it, fontSize = 12.sp, color = colors.textFaint, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

// ==================== 未登录内容 ====================

@Composable
private fun AnonymousContent(username: String, onLoginClick: () -> Unit) {
    val colors = jetdoColors
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
            text = stringResource(R.string.login_linuxdo),
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 16.dp, start = CardPadding, end = CardPadding)
        )
        Text(
            text = stringResource(R.string.login_account_note),
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
            Text(stringResource(R.string.login_now), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 16.dp)
        ) {
            listOf(stringResource(R.string.login_benefit_engage), stringResource(R.string.login_benefit_pm), stringResource(R.string.login_benefit_sync)).forEachIndexed { index, line ->
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

// 未登录态下方的备用登录方式：站点授权登录（App 内 WebView 承载授权页）+ 扫码登录（扫入端）。
// 主入口仍是上方「立即登录」走站内 WebView 表单；这两条都不经过站内表单，故独立成卡。
@Composable
private fun AnonymousAuthOptions(onScanQr: () -> Unit, onSiteAuth: () -> Unit) {
    val colors = jetdoColors
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.auth_other_methods),
            fontSize = 13.sp,
            color = colors.textFaint,
            modifier = Modifier.padding(start = 4.dp, top = CardGap, bottom = 8.dp)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(CardRadius))
                .background(colors.surfaceCard)
        ) {
            // 站点授权登录：App 内 WebView 打开 /user-api-key/new，确认后深链回程兑换 _t；
            // 成功与否由会话总线广播，这里只负责导航（兑换后 Cookie 落在全局 CookieManager）。
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onSiteAuth)
                    .padding(horizontal = CardPadding, vertical = 14.dp)
            ) {
                FaIcon("globe", colors.ldAccent, 16.dp)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.auth_browser_entry),
                        fontSize = 15.sp,
                        color = colors.textPrimary
                    )
                    Text(
                        text = stringResource(R.string.auth_browser_hint),
                        fontSize = 12.sp,
                        color = colors.textFaint,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                FaIcon("chevron-right", colors.textFaint, 12.dp)
            }

            HorizontalDivider(
                color = colors.border,
                thickness = 0.5.dp,
                modifier = Modifier.padding(horizontal = CardPadding)
            )

            // 扫码登录（扫入端）：扫另一台已登录 JetDo 出示的码，兑换其一次性 OTP。
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onScanQr)
                    .padding(horizontal = CardPadding, vertical = 14.dp)
            ) {
                FaIcon("camera", colors.ldAccent, 16.dp)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.auth_qr_scan_title),
                        fontSize = 15.sp,
                        color = colors.textPrimary
                    )
                    Text(
                        text = stringResource(R.string.auth_qr_scan_hint),
                        fontSize = 12.sp,
                        color = colors.textFaint,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                FaIcon("chevron-right", colors.textFaint, 12.dp)
            }
        }
    }
}
