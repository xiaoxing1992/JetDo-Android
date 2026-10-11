package com.jetdo.android.ui.components

import androidx.compose.ui.res.stringResource
import com.jetdo.android.R
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jetdo.android.data.models.User
import com.jetdo.android.ui.theme.jetdoColors
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ==========================================================================
// 对齐鸿蒙版 views/components/ReplyingPresenceBar.ets + TypingDots.ets。
//
// 详情页底部"正在回复"悬浮条：左侧叠放正在回复者的头像，右侧"正在回复"+三点跳动。
// 仿 Discourse 的 who-is-replying 指示。位置与垂直对齐由外层（详情页）决定，
// 本组件只管内容与样式 —— 与鸿蒙一致。
// ==========================================================================

/** 胶囊固定高度：外层据此把它与 52dp 悬浮回复按钮垂直居中，故写死并导出。 */
val ReplyingPresenceBarHeight = 36.dp

private val AvatarSize = 24.dp
/** 头像左叠量：后一个压在前一个上，形成 Discourse 那种叠放。 */
private val AvatarOverlap = 9.dp

@Composable
fun ReplyingPresenceBar(
    users: List<User>,
    modifier: Modifier = Modifier
) {
    val colors = jetdoColors
    val shape = RoundedCornerShape(ReplyingPresenceBarHeight / 2)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .height(ReplyingPresenceBarHeight)
            // 工程已移除 Haze，这里不做磨砂：用卡片实色 + 细描边 + 投影维持"浮起胶囊"的观感，
            // 半透明底在深色/浅色内容上都压不住下面的正文（顶栏当初也是同一个取舍）。
            .shadow(8.dp, shape)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(0.5.dp, colors.border, shape)
            .padding(start = 6.dp, end = 14.dp)
    ) {
        StackedAvatars(users)

        // 文字与三点底部对齐（对齐 Discourse：句号点落在文字基线上，从底部起跳）。
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.padding(start = 8.dp, end = 6.dp)
        ) {
            Text(stringResource(R.string.detail_replying), fontSize = 13.sp, color = colors.textSecondary)
            // 三点略微离底 2dp，落在文字基线附近而非贴到 descender 底。
            TypingDots(
                color = colors.textSecondary,
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
            )
        }
    }
}

/**
 * 叠放头像行。
 *
 * 每个头像统一左移 [AvatarOverlap] 形成叠放，外层补一个 [AvatarOverlap] 左内边距抵消第一个，
 * 使首个头像齐平（与鸿蒙同一套算法）。
 *
 * 注意收窄那一步：`offset` 只做视觉位移、**不参与测量**，所以左移后 Row 的测量宽度仍是
 * n×头像宽，右侧会凭空空出一截。鸿蒙用的是负 margin（参与布局、宽度天然变窄），
 * Compose 侧靠这个 layout 修饰符把那截宽度收掉，两者最终几何一致。
 */
@Composable
private fun StackedAvatars(users: List<User>) {
    if (users.isEmpty()) return
    val overlapPx = with(LocalDensity.current) { AvatarOverlap.roundToPx() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(start = AvatarOverlap)
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val width = (placeable.width - overlapPx).coerceAtLeast(placeable.height)
                layout(width, placeable.height) { placeable.place(0, 0) }
            }
    ) {
        users.forEach { user ->
            UserAvatar(
                user = user,
                avatarSize = AvatarSize,
                // 角标画在右下角，叠放时会压到后一个头像身上，这里不显示。
                showFlair = false,
                modifier = Modifier.offset(x = -AvatarOverlap)
            )
        }
    }
}

// ---- 三点跳动 ----

/** 单个圆点抬起/落下的时长。 */
private const val DOT_MOVE_MS = 240
/** 相邻圆点的错峰延迟，形成波浪。 */
private const val DOT_STAGGER_MS = 110L
/** 一轮波浪结束后的停顿（≈0.5s）。 */
private const val DOT_REST_MS = 500L

/**
 * 「正在回复」三点跳动指示器。仿 Discourse 打字指示：三点依次抬起→落下，成一道波浪，
 * 然后停约 0.5s 再来一轮。
 *
 * 用 `LaunchedEffect` + 各自独立的 [Animatable] 驱动：协程随组合离开自动取消，
 * 不需要鸿蒙那套 `alive` 闸 + `clearTimeout` 手工停表（那边是因为 setTimeout 自循环不受组件生命周期约束）。
 */
@Composable
fun TypingDots(
    color: Color,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val bouncePx = with(density) { 3.dp.toPx() }
    // 每个点一份位移。初值 0=落下，抬起为负（向上）。
    val offsets = remember { List(3) { Animatable(0f) } }

    LaunchedEffect(Unit) {
        while (true) {
            // 抬起：错峰启动，全部到位后 coroutineScope 才返回。
            coroutineScope {
                offsets.forEachIndexed { index, dot ->
                    launch {
                        delay(index * DOT_STAGGER_MS)
                        dot.animateTo(-bouncePx, tween(DOT_MOVE_MS, easing = EaseInOut))
                    }
                }
            }
            // 落下：同样错峰。
            coroutineScope {
                offsets.forEachIndexed { index, dot ->
                    launch {
                        delay(index * DOT_STAGGER_MS)
                        dot.animateTo(0f, tween(DOT_MOVE_MS, easing = EaseInOut))
                    }
                }
            }
            delay(DOT_REST_MS)
        }
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        offsets.forEach { dot ->
            Box(
                modifier = Modifier
                    .offset { IntOffset(0, dot.value.roundToInt()) }
                    .size(2.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}
