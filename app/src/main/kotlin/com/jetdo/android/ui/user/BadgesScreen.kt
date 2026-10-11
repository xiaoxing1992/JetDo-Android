package com.jetdo.android.ui.user

import androidx.compose.ui.res.stringResource
import com.jetdo.android.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.jetdo.android.data.models.UserBadgeGroup
import com.jetdo.android.data.models.UserBadgeItem
import com.jetdo.android.core.designsystem.FaIcon
import com.jetdo.android.ui.theme.jetdoColors

// ==========================================================================
// 「全部徽章」页,对齐鸿蒙 BadgesPage.ets:
// /user-badges/{u}.json?grouped=true,按金/银/铜分组;分组标题 = 等级色圆点 + 「金牌 · N」;
// 3 列 BadgeTile;点击单枚弹底部详情(图标 48/40 + 名称 + 元信息 + 描述)。
// ==========================================================================

private val BADGES_PAGE_PADDING = 16.dp

@Composable
fun BadgesScreen(
    username: String,
    badgeCount: Int,
    onNavigateBack: () -> Unit,
    viewModel: BadgesViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(username) { viewModel.load(username) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(jetdoColors.pageBg)
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
            }
            Text(
                text = if (badgeCount > 0) stringResource(R.string.drawer_badges_count, badgeCount) else stringResource(R.string.user_badges),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = jetdoColors.textPrimary
            )
        }
        HorizontalDivider(color = jetdoColors.border, thickness = 0.5.dp)

        when {
            state.loading && state.groups.isEmpty() -> {
                BoxCenter { CircularProgressIndicator(color = jetdoColors.ldAccent) }
            }
            state.error != null && state.groups.isEmpty() -> {
                val err = state.error ?: ""
                BoxCenter {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(err, color = MaterialTheme.colorScheme.error)
                        Text(stringResource(R.string.common_retry), color = jetdoColors.ldAccent, modifier = Modifier.padding(top = 12.dp).clickable { viewModel.load(username) })
                    }
                }
            }
            state.groups.isEmpty() -> BoxCenter { Text(stringResource(R.string.user_no_badges), color = jetdoColors.textTertiary) }
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = BADGES_PAGE_PADDING)
                ) {
                    state.groups.forEach { group ->
                        BadgeGroup(group, viewModel::selectBadge)
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }

    // 单枚详情:底部弹层(对齐鸿蒙 bindSheet:bottom sheet、fit content、右上角关闭、可拖动下滑)。
    state.selectedBadge?.let { badge ->
        ModalBottomSheet(
            onDismissRequest = { viewModel.clearSelected() },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = jetdoColors.surfaceCard,
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, bottom = 32.dp)
            ) {
                BadgeLarge(badge)
                Text(badge.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = jetdoColors.textPrimary,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp))
                val meta = badgeDetailMeta(badge)
                if (meta.isNotEmpty()) {
                    Text(meta, fontSize = 12.sp, color = jetdoColors.textFaint, modifier = Modifier.padding(top = 6.dp))
                }
                if (!badge.description.isNullOrEmpty()) {
                    Text(badge.description, fontSize = 14.sp, color = jetdoColors.textSecondary,
                        lineHeight = 21.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp))
                }
            }
        }
    }
}

@Composable
private fun BoxCenter(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
private fun BadgeGroup(group: UserBadgeGroup, onTap: (UserBadgeItem) -> Unit) {
    // 分组标题:等级色圆点 8 + 「金牌 · N」15 Bold。
    val isDark = com.jetdo.android.ui.theme.isJetDoInDarkTheme()
    val dot = when (group.typeId) {
        1 -> if (isDark) Color(0xFFE0B84C) else Color(0xFFD4A017)
        2 -> if (isDark) Color(0xFF9AA6B8) else Color(0xFF9EA7AD)
        else -> if (isDark) Color(0xFFC89066) else Color(0xFFB87333)
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp, bottom = 4.dp)
        ) {
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(dot))
            Text(
                text = "${group.typeName} · ${group.badges.size}",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = jetdoColors.textPrimary,
                modifier = Modifier.padding(start = 6.dp)
            )
        }
        group.badges.chunked(3).forEachIndexed { rowIndex, row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (rowIndex > 0) 8.dp else 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { badge ->
                    BadgeTileInRow(badge = badge, onClick = { onTap(badge) })
                }
                // 末行补位必须用 weight(1f)：Weight 才能参与宽度分配，把剩下的格子压回 1/3。
                // 用 width(0.dp) 占位时它不参与分配，两个徽章会各占半宽、被拉宽。
                repeat(3 - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.BadgeTileInRow(
    badge: UserBadgeItem,
    onClick: () -> Unit
) {
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
            .clickable(onClick = onClick)
            .padding(top = 12.dp, bottom = 10.dp, start = 6.dp, end = 6.dp)
    ) {
        Box(modifier = Modifier.height(32.dp), contentAlignment = Alignment.Center) {
            if (!badge.imageUrl.isNullOrEmpty()) {
                coil3.compose.AsyncImage(
                    model = badge.imageUrl,
                    contentDescription = badge.name,
                    modifier = Modifier.size(32.dp)
                )
            } else {
                FaIcon(badge.icon?.takeIf { it.isNotEmpty() } ?: "certificate", badgeColor, 26.dp)
            }
        }
        Text(
            text = label,
            fontSize = 11.sp,
            color = jetdoColors.textSecondary,
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
private fun BadgeLarge(badge: UserBadgeItem) {
    val isDark = com.jetdo.android.ui.theme.isJetDoInDarkTheme()
    val c = when (badge.badgeTypeId) { 1 -> if (isDark) Color(0xFFE0B84C) else Color(0xFFD4A017); 2 -> if (isDark) Color(0xFF9AA6B8) else Color(0xFF9EA7AD); else -> if (isDark) Color(0xFFC89066) else Color(0xFFB87333) }
    Box(modifier = Modifier.height(48.dp), contentAlignment = Alignment.Center) {
        if (!badge.imageUrl.isNullOrEmpty()) {
            coil3.compose.AsyncImage(model = badge.imageUrl, contentDescription = badge.name, modifier = Modifier.size(48.dp))
        } else {
            FaIcon(badge.icon?.takeIf { it.isNotEmpty() } ?: "certificate", c, 40.dp)
        }
    }
}

/** 详情副信息:获得 N 次 · 授予于 相对时间(对齐 getDetailMeta)。 */
@Composable
private fun badgeDetailMeta(badge: UserBadgeItem): String {
    val parts = mutableListOf<String>()
    if ((badge.grantCount ?: 0) > 1) parts.add(stringResource(R.string.user_badge_grant_count, badge.grantCount ?: 0))
    com.jetdo.android.core.model.RelativeTime.fromNow(badge.grantedAt)
        .takeIf { it.isNotEmpty() }?.let { parts.add(stringResource(R.string.user_badge_granted_on, it)) }
    return parts.joinToString(" · ")
}
