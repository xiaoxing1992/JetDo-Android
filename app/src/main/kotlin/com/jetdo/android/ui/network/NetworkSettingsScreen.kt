package com.jetdo.android.ui.network

import androidx.compose.ui.res.stringResource
import com.jetdo.android.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jetdo.android.services.challenge.ChallengeShortcutPrefs
import com.jetdo.android.services.network.DohManager
import com.jetdo.android.ui.theme.jetdoColors
import androidx.compose.ui.platform.LocalContext

// ==========================================================================
// 「网络与连接」页，对齐鸿蒙 views/pages/NetworkSettingsPage.ets。
//
// 两张卡片：DoH 配置（后端早已完整，见 DohSettingsScreen 的说明）+
// 「帖子页显示过盾入口」开关（ChallengeShortcutPrefs，默认关）。
// ==========================================================================

private val PageMargin = 12.dp
private val CardRadius = 16.dp
private val CardPadding = 16.dp
private val CardGap = 12.dp

@Composable
fun NetworkSettingsScreen(
    onNavigateBack: () -> Unit,
    onOpenDoh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // DoH 摘要要在从 DoH 配置页 pop 回来时重算：本页 entry 会重新走到 RESUMED，
    // 而组合本身是复用的（NavHost 保留回退栈上的组合），所以不能指望 remember 重跑。
    var dohSummary by remember {
        mutableStateOf(if (DohManager.isEnabled(context)) DohManager.getActiveServer(context).name else context.getString(R.string.detail_closed))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                dohSummary = if (DohManager.isEnabled(context)) {
                    DohManager.getActiveServer(context).name
                } else {
                    context.getString(R.string.detail_closed)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(jetdoColors.pageBgGray)
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
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.common_back),
                    tint = jetdoColors.textPrimary
                )
            }
            Text(
                text = stringResource(R.string.settings_network),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = jetdoColors.textPrimary
            )
        }
        HorizontalDivider(color = jetdoColors.border, thickness = 0.5.dp)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PageMargin)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = CardGap)
                    .background(jetdoColors.surfaceCard, RoundedCornerShape(CardRadius))
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenDoh)
                        .padding(horizontal = CardPadding, vertical = 14.dp)
                ) {
                    Icon(
                        Icons.Outlined.Security,
                        contentDescription = null,
                        tint = jetdoColors.textSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = stringResource(R.string.doh_config),
                        fontSize = 15.sp,
                        color = jetdoColors.textPrimary,
                        modifier = Modifier
                            .padding(start = 14.dp)
                            .weight(1f)
                    )
                    if (dohSummary.isNotEmpty()) {
                        Text(
                            text = dohSummary,
                            fontSize = 13.sp,
                            color = jetdoColors.textFaint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .widthIn(max = 180.dp)
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = jetdoColors.textFaint,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Text(
                text = stringResource(R.string.doh_desc) +
                    stringResource(R.string.pdns_scope_note3),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = jetdoColors.textFaint,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = CardPadding, end = CardPadding, top = 14.dp)
            )

            // 帖子页过盾快捷入口开关（对齐鸿蒙 NetworkSettingsPage 的 ChallengeShortcutCell）。
            // 默认关：多数人从不撞盾，常驻入口只会碍眼；打开也不等于常驻——
            // 还要真的探测到阅读上报被 CF 拦下才亮（ChallengeShortcutEventBus）。
            val challengeShortcutEnabled by ChallengeShortcutPrefs.enabled.collectAsState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 28.dp)
                    .background(jetdoColors.surfaceCard, RoundedCornerShape(CardRadius))
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = CardPadding, vertical = 12.dp)
                ) {
                    Icon(
                        Icons.Outlined.Shield,
                        contentDescription = null,
                        tint = jetdoColors.textSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 14.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.settings_challenge_shortcut),
                            fontSize = 15.sp,
                            color = jetdoColors.textPrimary
                        )
                        Text(
                            text = stringResource(R.string.detail_challenge_shortcut_desc),
                            fontSize = 12.sp,
                            color = jetdoColors.textFaint,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Switch(
                        checked = challengeShortcutEnabled,
                        onCheckedChange = ChallengeShortcutPrefs::setEnabled,
                        colors = SwitchDefaults.colors(checkedTrackColor = jetdoColors.ldAccent)
                    )
                }
            }
            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}
