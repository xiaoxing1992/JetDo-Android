package com.jetdo.android.feature.notification

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 胶囊分段控件：灰底胶囊条 + 一枚白色选中胶囊在其上滑动。
 *
 * 形态对齐官方 linux.do iOS App 的「通知 / 聊天 / 私信」分段（截图实测）：
 * 容器是浅灰胶囊，选中项是浮在其上的白胶囊（带极轻阴影），未选中项是透明底。
 *
 * 为什么不用 M3 的 TabRow：它的指示条是贴在容器底边的一条横线（或整块 primary 色），
 * 与「白胶囊浮起」的形制不同，改它的 indicator 参数比自己画更绕。
 *
 * 选中态用 animateDpAsState 平移胶囊偏移（而不是给每一项各画一个背景）：
 * 这样切换时胶囊是**滑过去**的，而不是旧的高亮突然消失、新的突然出现。
 */
@Composable
internal fun MessagesSegmentedControl(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    colors: NotificationColors,
    modifier: Modifier = Modifier,
    containerColor: Color? = null,
    selectedColor: Color? = null
) {
    if (labels.isEmpty()) return

    val track = containerColor ?: colors.segmentTrack
    val thumb = selectedColor ?: colors.segmentThumb

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(SegmentHeight)
            .clip(RoundedCornerShape(percent = 50))
            .background(track)
            .padding(SegmentPadding)
    ) {
        val slotWidth: Dp = maxWidth / labels.size
        val index = selectedIndex.coerceIn(0, labels.lastIndex)
        val thumbX by animateDpAsState(
            targetValue = slotWidth * index,
            // 与底栏胶囊同一量级的弹性，避免"啪"地跳过去。
            animationSpec = spring(dampingRatio = 1f, stiffness = 500f),
            label = "messagesSegmentThumbX"
        )

        Box(
            modifier = Modifier
                .width(slotWidth)
                .fillMaxHeight()
                .offset(x = thumbX)
                .shadow(
                    elevation = 1.dp,
                    shape = RoundedCornerShape(percent = 50),
                    // 阴影色用中性半透明黑：feature 模块拿不到 app 的固定色 token
                    // （依赖方向是 app → feature），而这层影只起"浮起"作用，不承担品牌色。
                    ambientColor = Color.Black.copy(alpha = 0.08f),
                    spotColor = Color.Black.copy(alpha = 0.08f)
                )
                .clip(RoundedCornerShape(percent = 50))
                .background(thumb)
        )

        Row(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
            labels.forEachIndexed { i, label ->
                val active = i == index
                val textColor by animateColorAsState(
                    targetValue = if (active) colors.textPrimary else colors.textSecondary,
                    animationSpec = tween(SegmentSwitchMs),
                    label = "messagesSegmentText"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(percent = 50))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            // 不叠 ripple：胶囊本身是选中反馈，再加一层水波会脏。
                            indication = null
                        ) { onSelect(i) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = textColor,
                        fontSize = 13.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

private val SegmentHeight = 32.dp
private val SegmentPadding = 3.dp
private const val SegmentSwitchMs = 180
