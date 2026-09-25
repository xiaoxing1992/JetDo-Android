package com.arkdo.android.ui.topic

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkdo.android.ui.theme.ArkdoFixedColors
import com.arkdo.android.ui.theme.arkdoColors
import kotlin.math.roundToInt

/**
 * 楼层 scrubber（对齐鸿蒙 `views/components/TopicScrubber.ets`）。
 *
 * 帖内右缘一条竖向导轨，配合手柄表达"读到第几楼"，拖动即跳楼。存在的意义是长贴定位：
 * 几百楼的帖子靠手指滚不现实，靠滚动条又拖不准（一像素好几楼）。
 *
 * 三态，与鸿蒙一一对应：
 * - **拖动中**（[dragValue] 非空）：手柄跟手，气泡实时显示目标楼号，轨道加粗高亮。
 *   受控值必须始终跟手——中途重渲染把滑块拽离手指是拖动断触的常见根因。
 * - **跳转中**（[jumpTarget] 非空）：松手后停在目标楼**不回弹**，气泡转 loading 并显示
 *   "跳转到 N 楼"。跳到未加载楼时后台要重开窗口拉一两秒，若滑块此刻弹回原处，
 *   用户会以为松手没反应。loading 何时结束由调用方的 [jumpTarget] 收回决定。
 * - **空闲**：手柄平移到 [currentPostNumber]，透明度降到 0.55，不打扰阅读。
 *
 * 触摸只吃右缘 [TRACK_TOUCH_WIDTH] 那一条，其余区域不拦列表滚动——鸿蒙侧对应
 * `hitTestBehavior(Transparent)` 的同一意图。气泡绘制在轨道内侧（向左展开），
 * 越出触摸条边界是刻意的，不参与命中测试。
 *
 * 轨道**贴屏右缘**（空闲 4dp / 拖动 8dp，见 [TRACK_EDGE_INSET_IDLE]），因此整条 scrubber
 * 落在帖子卡片之外的页边距上，不会压在正文和操作行上——这是鸿蒙贴边几何的要点，
 * 也是"为什么轨道看起来存在感很低"的原因。
 *
 * @param currentPostNumber 当前阅读楼号（视口首楼；钉底时为最高楼号）。
 * @param highestPostNumber 话题最高楼号，作为手柄上限。
 * @param jumpTarget 非空表示正在跳往该楼（气泡转 loading 并停驻），由调用方在滚动
 *   请求被消费后置回 null。
 * @param onJump 松手/点击轨道上抛目标楼号。
 */
@Composable
internal fun TopicScrubber(
    currentPostNumber: Int,
    highestPostNumber: Int,
    jumpTarget: Int?,
    onJump: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    // 上限至少为 2：Slider 语义上 min==max 无从拖动，鸿蒙同款钳位。
    val max = highestPostNumber.coerceAtLeast(2)
    // 拖动中的本地值。非空即拖动中：手柄必须跟手，不能被 currentPostNumber 拽走。
    var dragValue by remember { mutableStateOf<Int?>(null) }

    // 显示值优先级：拖动中 > 跳转中（停驻）> 当前阅读楼。
    val shown = (dragValue ?: jumpTarget ?: currentPostNumber).coerceIn(1, max)
    val jumping = jumpTarget != null
    val active = dragValue != null || jumping
    val colors = arkdoColors

    BoxWithConstraints(
        modifier = modifier
            .width(SCRUBBER_WIDTH)
            // 垂直居中、约占屏高 55%（对齐鸿蒙轨道盒 height('55%')）。
            // 外圈满高再居中的写法会让手柄被父 Stack 的 BottomEnd 对齐贴到底部——
            // 鸿蒙侧踩过这个坑，这里直接给 55% 高度 + 外部 CenterEnd 对齐。
            .fillMaxHeight(0.55f)
    ) {
        val density = LocalDensity.current
        val trackHeightPx = with(density) { maxHeight.toPx() }
        val thumbHeightPx = with(density) { THUMB_HEIGHT.toPx() }
        val bubbleHeightPx = with(density) { BUBBLE_HEIGHT.toPx() }
        // 手柄中心纵坐标：value=1 在顶、=max 在底，按填充比例在 [thumb/2, H-thumb/2] 线性插值。
        val thumbCenterY = thumbHeightPx / 2f + fraction(shown, max) * (trackHeightPx - thumbHeightPx)
        // 空闲态只给**轨道**降透明，**手柄保持不透明**——这是本实现唯一刻意偏离鸿蒙的一处。
        //
        // 鸿蒙写的是 opacity(isActive() ? 1 : 0.55)，作用于整个 Slider（轨道与手柄一起淡）。
        // 但 #66CCFF 降到 0.55 后混在浅色页底 #F2F3F5 上，混合色约 (171,227,255)，
        // 与页底色差只有 71/16/10 → 对比度约 1.2:1（真机截图逐像素取证），肉眼等同于消失。
        // 表现就是"空闲时最右边看不到蓝条，一按下才冒出来，松手又不见，滑动也不见"。
        // 轨道是装饰、淡掉无妨；手柄是"读到第几楼"的唯一指示，必须常驻可见。
        val trackAlpha = if (active) 1f else 0.55f

        // 视觉轴心距屏右缘的距离。鸿蒙 Slider 组件右缘与屏右缘齐平、整体外推 8vp，
        // 组件内轨道水平居中 → 空闲（组件宽 24vp）轴心落在屏内 4vp，拖动（宽 32vp）时 8vp。
        val axisInset = if (active) TRACK_EDGE_INSET_ACTIVE else TRACK_EDGE_INSET_IDLE

        // 触摸层：只占右缘一条，命中区内按下即生效（无需先超过 touch slop），
        // 与鸿蒙原生 Slider 的"按下/点击轨道即刻取值"手感一致。
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(TRACK_TOUCH_WIDTH)
                .fillMaxHeight()
                .pointerInput(max, thumbHeightPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val height = size.height.toFloat()
                        var value = postNumberAt(down.position.y, height, thumbHeightPx, max)
                        dragValue = value
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            value = postNumberAt(change.position.y, height, thumbHeightPx, max)
                            dragValue = value
                            // 消费事件：列表此时不该跟着滚。下层 scrollable 检查 consumed 后会放弃。
                            change.consume()
                        }
                        dragValue = null
                        onJump(value)
                    }
                }
                .semantics {
                    contentDescription = "楼层跳转"
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        current = shown.toFloat(),
                        range = 1f..max.toFloat(),
                        steps = (max - 1).coerceAtLeast(0)
                    )
                }
        ) {
            // 视觉轴心：一个只有 6dp 宽的窄盒，贴屏右缘再往内让出 axisInset。
            // 轨道与手柄都挂在这条轴上，于是整条 scrubber 落在卡片外的页边距里，不压正文。
            // 偏移量写成 (手柄半宽 - axisInset)：贴边时中心正好落在 axisInset 处。
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = THUMB_WIDTH / 2 - axisInset)
                    .width(THUMB_WIDTH)
                    .fillMaxHeight()
            ) {
                // 轨道：竖线，拖动/跳转时略加粗。
                // ⚠️ alpha 必须排在 background 之前：graphicsLayer 只作用于它**右侧**（更内层）
                // 的绘制，写在 background 之后对那块底色完全无效——空闲态会一直是全不透明的
                // 实心 #E5E7EB 竖线，看起来像常驻分隔线（这里踩过）。
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .alpha(trackAlpha)
                        .width(if (active) 5.dp else 3.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(percent = 50))
                        .background(colors.border)
                )
                // 手柄：竖向圆角长条（鸿蒙 Slider blockStyle SHAPE 6×28），用未读小蓝点同色。
                // 不参与上面的空闲态降透明——理由见 trackAlpha 的注释：0.55 的 #66CCFF
                // 在浅色页底上实测对比度 1.2:1，用户会认为"这个条根本不存在"。
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset { IntOffset(0, (thumbCenterY - thumbHeightPx / 2f).roundToInt()) }
                        .width(THUMB_WIDTH)
                        .height(THUMB_HEIGHT)
                        .clip(RoundedCornerShape(3.dp))
                        .background(ArkdoFixedColors.Unread)
                )
            }
        }

        // 气泡：贴轨道内侧（向左展开），纵向对齐手柄中心。拖动显示楼号，跳转显示 loading。
        if (active) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    // 间隙 10dp + 触摸条宽，与鸿蒙 bubbleAnchorX（盒宽 - 滑块宽 - 10）同值。
                    .padding(end = TRACK_TOUCH_WIDTH + 10.dp)
                    .offset { IntOffset(0, (thumbCenterY - bubbleHeightPx / 2f).roundToInt()) }
                    .height(BUBBLE_HEIGHT)
                    // 阴影色对齐鸿蒙 SCRIM(#33000000)，而非 Compose 默认的纯黑。
                    .shadow(
                        elevation = 8.dp,
                        shape = RoundedCornerShape(16.dp),
                        ambientColor = ArkdoFixedColors.Scrim,
                        spotColor = ArkdoFixedColors.Scrim
                    )
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.brand)
                    .padding(horizontal = 12.dp)
            ) {
                if (jumping) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = ArkdoFixedColors.OnDarkFg
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = if (jumping) "跳转到 ${jumpTarget} 楼" else "$shown 楼",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = ArkdoFixedColors.OnDarkFg,
                    maxLines = 1
                )
            }
        }
    }
}

/** 手柄宽度（对齐鸿蒙 blockSize 6×28）。 */
private val THUMB_WIDTH = 6.dp

/** 手柄高度。 */
private val THUMB_HEIGHT = 28.dp

/**
 * 右缘触摸条宽度：只有这一条吃手势，其余放行给列表滚动。
 *
 * 取 24dp——鸿蒙 Slider 拖动态宽 32vp、其中 8vp 在屏外，实际可触区正好 24vp；空闲态
 * 组件 24vp / 可触 16vp。取两者中的大值，手指在离边缘十几 dp 处按下也能抓到，
 * 不必去挤系统返回手势那最外一条。
 */
private val TRACK_TOUCH_WIDTH = 24.dp

/** 空闲态视觉轴心距屏右缘：鸿蒙 Slider 宽 24vp、右缘外推 8vp → 轴心 4vp。 */
private val TRACK_EDGE_INSET_IDLE = 4.dp

/** 拖动/跳转态轴心距屏右缘：鸿蒙 Slider 变宽到 32vp，轴心随之到 8vp。 */
private val TRACK_EDGE_INSET_ACTIVE = 8.dp

/** 气泡固定高度：免去测量即可把气泡中心对齐手柄中心。 */
private val BUBBLE_HEIGHT = 32.dp

/**
 * 覆盖层总宽，与鸿蒙 SCRUBBER_STACK_WIDTH(200) 同值。
 *
 * 定这个宽不是为了轨道（轨道只占最右一条），而是给气泡留足单行空间：
 * 200 - 触摸条 24 - 间隙 10 = 166dp 可用，与鸿蒙 bubbleAnchorX 反推的余量一致。
 */
private val SCRUBBER_WIDTH = 200.dp

/** 填充比例：value=1 → 0（顶部），value=max → 1（底部）。 */
private fun fraction(value: Int, max: Int): Float {
    if (max <= 1) return 0f
    return ((value - 1).toFloat() / (max - 1)).coerceIn(0f, 1f)
}

/**
 * 纵坐标 → 楼号。
 *
 * 映射区间与手柄中心的行程一致（都是 [thumb/2, H-thumb/2]），否则按下时手柄与手指
 * 会有半个手柄高度的错位。滑块类控件"手指按在哪、手柄就在哪"是基本预期。
 */
private fun postNumberAt(y: Float, trackHeight: Float, thumbHeight: Float, max: Int): Int {
    val travel = trackHeight - thumbHeight
    if (travel <= 0f) return 1
    val f = ((y - thumbHeight / 2f) / travel).coerceIn(0f, 1f)
    return (1f + f * (max - 1)).roundToInt().coerceIn(1, max)
}
