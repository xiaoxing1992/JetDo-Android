package com.arkdo.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkdo.android.services.realtime.RealtimeTopicsPrefs
import com.arkdo.android.services.topic.LotteryTipPrefs
import com.arkdo.android.ui.theme.arkdoColors

private val PageMargin = 12.dp
private val CardPadding = 16.dp
private val CardGap = 12.dp

/** 实验性功能。目前只有首页新话题提醒，默认开。 */
@Composable
fun ExperimentalSettingsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val enabled by RealtimeTopicsPrefs.enabled.collectAsState()
    val lotteryTipEnabled by LotteryTipPrefs.enabled.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(arkdoColors.pageBgGray)
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = arkdoColors.textPrimary)
            }
            Text(
                text = "实验性功能",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = arkdoColors.textPrimary
            )
        }
        HorizontalDivider(color = arkdoColors.border, thickness = 0.5.dp)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PageMargin)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(top = CardGap)
                    .fillMaxWidth()
                    .background(arkdoColors.surfaceCard, androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                    .padding(horizontal = CardPadding, vertical = 12.dp)
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("首页新话题提醒", fontSize = 15.sp, color = arkdoColors.textPrimary)
                    Text(
                        "首页顶部提示有新话题，点一下插进列表",
                        fontSize = 12.sp,
                        color = arkdoColors.textFaint,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = RealtimeTopicsPrefs::setEnabled,
                    colors = SwitchDefaults.colors(checkedTrackColor = arkdoColors.ldAccent)
                )
            }
            Spacer(modifier = Modifier.height(28.dp))
            // 抽奖帖提醒(对齐鸿蒙 ExperimentalSettingsPage:97,默认开):
            // 提醒挡下的是「永久禁言」这种不可逆后果,默认关等于没做。
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = arkdoColors.surfaceCard,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(16.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("抽奖帖提醒", fontSize = 15.sp, color = arkdoColors.textPrimary)
                        Text(
                            "抽奖帖回复前提示官方规则，避免被永久禁言",
                            fontSize = 12.sp,
                            color = arkdoColors.textFaint,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Switch(
                        checked = lotteryTipEnabled,
                        onCheckedChange = LotteryTipPrefs::setEnabled,
                        colors = SwitchDefaults.colors(checkedTrackColor = arkdoColors.ldAccent)
                    )
                }
            }
            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}
