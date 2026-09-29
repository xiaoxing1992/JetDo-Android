package com.arkdo.android.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkdo.android.core.designsystem.FaIcon
import com.arkdo.android.ui.theme.arkdoColors

// 抽奖帖提醒卡,对齐鸿蒙 LotteryNoticeCard.ets(官方公告帖 1993635 的规则摘要):
// 详情页摘要(标题/标签/统计)下方、一楼正文上方的一张琥珀色卡片,跟着列表滚走,不做常驻悬浮。
// 每进一个抽奖帖都是展开态,不记「看过了」:被提醒挡下的是永久禁言这种不可逆后果,
// 记忆一次就沉默的代价不对称。收起只在本次浏览有效,彻底不看走实验性设置里的总开关。
@Composable
fun LotteryNoticeCard(
    onOpenAnnouncement: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = arkdoColors
    var collapsed by remember { mutableStateOf(false) }

    // 外层只负责左右留白:左右 10 与摘要卡、楼层正文的横向内边距对齐,视觉上是同一列。
    Column(modifier = modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 12.dp, bottom = 4.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.lotteryNoticeBg, RoundedCornerShape(8.dp))
                .border(1.dp, colors.lotteryNoticeBorder, RoundedCornerShape(8.dp))
                .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp)
                .animateContentSize()
        ) {
            // 标题行整行可点:展开态点它收起,收起态点它展开。收起后标题本身换成一句提示,
            // 让这一行在两种状态下都有信息量,而不是一个空壳把手。
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { collapsed = !collapsed }
            ) {
                FaIcon("gift", colors.warning, 14.dp, Modifier.padding(end = 8.dp))
                Text(
                    text = if (collapsed) "抽奖帖 · 回复请说明你为什么需要这个奖品" else "抽奖帖提醒",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.warning,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                FaIcon(
                    if (collapsed) "angle-down" else "angle-up",
                    colors.warning,
                    14.dp,
                    Modifier.padding(start = 8.dp)
                )
            }

            if (!collapsed) {
                Text(
                    text = "回复被举报后会被删除并永久禁言的三种情况：",
                    fontSize = 13.sp,
                    color = colors.textBody,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                )
                LotteryBullet("纯凑字数（符号、单字、英文占位）")
                LotteryBullet("复读或复制别人的回复")
                LotteryBullet("与奖品无关的内容")

                Text(
                    text = "永久禁言后仍可登录浏览、私信，但不能再回复、发帖和参与抽奖。如有异议可私信管理申诉。",
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = colors.textSecondary,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                )
                Text(
                    text = "官方建议：非必要不参与抽奖。确实需要，就说说你为什么需要它。",
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = colors.textBody,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                )

                // 「查看官方公告」做成一行文字链而非按钮:这张卡的主角是那三条红线,
                // 加个实心按钮会把视觉重心抢到跳转上。
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .clickable(onClick = onOpenAnnouncement)
                ) {
                    Text(
                        text = "查看官方公告",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.ldAccent
                    )
                    FaIcon("arrow-right", colors.ldAccent, 11.dp, Modifier.padding(start = 5.dp))
                }
            }
        }
    }
}

@Composable
private fun LotteryBullet(text: String) {
    val colors = arkdoColors
    Row(modifier = Modifier.padding(top = 2.dp)) {
        Text(text = "·", fontSize = 13.sp, lineHeight = 20.sp, color = colors.textSecondary)
        Text(
            text = text,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = colors.textBody,
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}
