package com.arkdo.android.ui.screens

import androidx.compose.ui.res.stringResource
import com.arkdo.android.R
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
import com.arkdo.android.feature.home.BadgeFilterPrefs
import com.arkdo.android.services.avatar.AnimatedAvatarPrefs
import com.arkdo.android.services.realtime.RealtimePostsPrefs
import com.arkdo.android.services.realtime.RealtimeTopicsPrefs
import com.arkdo.android.services.topic.LotteryTipPrefs
import com.arkdo.android.services.topic.TwoPageReadingPrefs
import com.arkdo.android.ui.theme.arkdoColors

private val PageMargin = 12.dp
private val CardPadding = 16.dp
private val CardGap = 12.dp

/**
 * 实验性功能页。六项开关，默认值各不相同，理由都写在各自 Prefs 的注释里：
 * 首页新话题提醒（开）· 帖子内新回复提醒（关）· 抽奖帖提醒（开）· 双页阅读（关）·
 * 点分类/标签快速筛选（关）· 动态头像（开）。
 */
@Composable
fun ExperimentalSettingsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val enabled by RealtimeTopicsPrefs.enabled.collectAsState()
    val realtimePostsEnabled by RealtimePostsPrefs.enabled.collectAsState()
    val lotteryTipEnabled by LotteryTipPrefs.enabled.collectAsState()
    val twoPageReadingEnabled by TwoPageReadingPrefs.enabled.collectAsState()
    val badgeFilterEnabled by BadgeFilterPrefs.enabled.collectAsState()
    val animatedAvatarEnabled by AnimatedAvatarPrefs.enabled.collectAsState()

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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = arkdoColors.textPrimary)
            }
            Text(
                text = stringResource(R.string.settings_experimental),
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
                    Text(stringResource(R.string.home_new_topic_alert), fontSize = 15.sp, color = arkdoColors.textPrimary)
                    Text(
                        stringResource(R.string.home_realtime_topics_desc),
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
            // 帖子内新回复提醒(对齐鸿蒙 ExperimentalSettingsPage 的 RealtimePostsCell,默认关):
            // 开着进帖子会多订一个 /topic/<id> 频道，「正在回复」presence 也一并启用。
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
                        Text(stringResource(R.string.home_in_topic_reply_alert), fontSize = 15.sp, color = arkdoColors.textPrimary)
                        Text(
                            stringResource(R.string.home_realtime_desc),
                            fontSize = 12.sp,
                            color = arkdoColors.textFaint,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Switch(
                        checked = realtimePostsEnabled,
                        onCheckedChange = RealtimePostsPrefs::setEnabled,
                        colors = SwitchDefaults.colors(checkedTrackColor = arkdoColors.ldAccent)
                    )
                }
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
                        Text(stringResource(R.string.lottery_alert), fontSize = 15.sp, color = arkdoColors.textPrimary)
                        Text(
                            stringResource(R.string.lottery_alert_desc),
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
            Spacer(modifier = Modifier.height(28.dp))
            // 双页阅读(实验性,默认关):只有折叠屏「书本姿态」(竖直折痕 + 半开)才进得来。
            // 三折叠不产生 HALF_OPENED、手机与平板也没有这个形态,所以它不该默认开——
            // 关着时详情页一行判定都不会多跑(见 TwoPageReadingPrefs 的注释)。
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
                        Text(stringResource(R.string.detail_two_page_reading), fontSize = 15.sp, color = arkdoColors.textPrimary)
                        Text(
                            stringResource(R.string.detail_two_page_desc),
                            fontSize = 12.sp,
                            color = arkdoColors.textFaint,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Switch(
                        checked = twoPageReadingEnabled,
                        onCheckedChange = TwoPageReadingPrefs::setEnabled,
                        colors = SwitchDefaults.colors(checkedTrackColor = arkdoColors.ldAccent)
                    )
                }
            }
            Spacer(modifier = Modifier.height(28.dp))
            // 点分类/标签快速筛选(对齐鸿蒙 ExperimentalSettingsPage 的 BadgeFilterCell,默认关):
            // 开着时首页帖子行上的分类/标签徽章可点,点了按它筛选列表。默认关的理由见
            // BadgeFilterPrefs —— 徽章抢点击与「整行打开帖子」冲突,误触代价不对称。
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
                        Text(stringResource(R.string.drawer_filter_hint), fontSize = 15.sp, color = arkdoColors.textPrimary)
                        Text(
                            stringResource(R.string.home_badges_tappable_desc),
                            fontSize = 12.sp,
                            color = arkdoColors.textFaint,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Switch(
                        checked = badgeFilterEnabled,
                        onCheckedChange = BadgeFilterPrefs::setEnabled,
                        colors = SwitchDefaults.colors(checkedTrackColor = arkdoColors.ldAccent)
                    )
                }
            }
            Spacer(modifier = Modifier.height(28.dp))
            // 动态头像(对齐鸿蒙 ExperimentalSettingsPage 的 AnimatedAvatarCell,默认开):
            // 关掉后头像与 flair 角标一律取首帧静态显示,不再播放动图 —— 列表里多个
            // 头像同时播动图会让渲染树每帧变脏,这个开关也是排查掉帧的对照工具。
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
                        Text(stringResource(R.string.user_animated_avatar), fontSize = 15.sp, color = arkdoColors.textPrimary)
                        Text(
                            stringResource(R.string.user_animated_avatar_desc),
                            fontSize = 12.sp,
                            color = arkdoColors.textFaint,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Switch(
                        checked = animatedAvatarEnabled,
                        onCheckedChange = AnimatedAvatarPrefs::setEnabled,
                        colors = SwitchDefaults.colors(checkedTrackColor = arkdoColors.ldAccent)
                    )
                }
            }
            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}
