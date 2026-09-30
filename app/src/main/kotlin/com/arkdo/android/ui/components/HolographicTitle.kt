package com.arkdo.android.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlin.math.floor

/**
 * linux.do「全息称号」的原生还原，对齐鸿蒙 `views/components/HolographicText.ets`。
 *
 * 站点本身是纯 CSS 实现的：`linear-gradient(135deg, 品红→青→黄→品红→青)` +
 * `background-size: 400%` + `background-clip: text` 把渐变裁进字形，再用
 * `@keyframes holographic` 15s 无限平移 `background-position`，于是颜色在字里流动。
 *
 * Compose 有 `TextStyle(brush = …)`，等价于 `background-clip: text`，**不需要**鸿蒙那套
 * 「Stack + 超大渐变层 + BlendMode.SRC_IN 离屏合成」，一行 style 就够了。剩下的只是把
 * 「4 倍尺寸的渐变层按关键帧平移」翻译成 brush 的起止点：
 *
 *  · 渐变层是文字的 [GRADIENT_SCALE] 倍，可平移量 = (4-1) × 文字尺寸；
 *  · `background-position` 的 5 个关键帧（0%→25%→50%→75%→100%）逐段换算成左上角偏移，
 *    每段用 smoothstep 更像 CSS/ArkUI 的 ease-in-out；
 *  · 首尾关键帧同为 (0%, 50%)，所以 15s 走完一轮再接上下一轮是**无缝**的。
 *
 * 尺寸必须实测（`onGloballyPositioned`）：偏移量是按文字尺寸算的，拿不到尺寸就先按纯品红显示，
 * 避免第一帧闪一下透明/黑色。
 */
private val HOLOGRAPHIC_TITLES = setOf("Premium", "种子用户", "破界者")

/** 是否属于会走全息效果的称号。 */
internal fun isHolographicTitle(title: String?): Boolean =
    title != null && title in HOLOGRAPHIC_TITLES

/** 全息渐变色标，与站点 CSS 一致：品红 / 青 / 黄 / 品红 / 青。 */
private val HOLO_STOPS = arrayOf(
    0.00f to Color(0xFFFF00FF),
    0.25f to Color(0xFF00FFFF),
    0.50f to Color(0xFFFFFF00),
    0.75f to Color(0xFFFF00FF),
    1.00f to Color(0xFF00FFFF),
)

/** `background-size: 400%`。 */
private const val GRADIENT_SCALE = 4f

/** 一整轮 15s（对齐 CSS 的 `15s linear infinite`）。 */
private const val LOOP_MS = 15_000

/**
 * 按关键帧路径取当前的 `background-position`（百分比）。
 * 5 个关键帧：(0,50) → (100,0) → (100,100) → (0,100) → (0,50)。
 */
private fun backgroundPositionAt(phase: Float): Pair<Float, Float> {
    val scaled = (phase * 4f).coerceIn(0f, 4f)
    val segment = floor(scaled).toInt().coerceIn(0, 3)
    val raw = scaled - segment
    // smoothstep 近似 ease-in-out：位移的头尾更缓，中间快。
    val f = raw * raw * (3f - 2f * raw)
    val frames = arrayOf(0f to 50f, 100f to 0f, 100f to 100f, 0f to 100f, 0f to 50f)
    val from = frames[segment]
    val to = frames[segment + 1]
    return (from.first + (to.first - from.first) * f) to (from.second + (to.second - from.second) * f)
}

@Composable
internal fun HolographicTitle(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 13.sp,
    fontWeight: FontWeight = FontWeight.Medium,
) {
    var measured by remember { mutableStateOf(IntSize.Zero) }
    val transition = rememberInfiniteTransition(label = "holo")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(LOOP_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "holoPhase",
    )

    val brush: Brush = run {
        val w = measured.width.toFloat()
        val h = measured.height.toFloat()
        // 尺寸未知：先用品红顶上（站点 CSS 在 blend 失效时的兜底色同款），一帧后就换成真渐变。
        if (w <= 0f || h <= 0f) return@run SolidColor(HOLO_STOPS[0].second)
        val span = GRADIENT_SCALE - 1f
        val (px, py) = backgroundPositionAt(phase)
        val startX = -(px / 100f) * span * w
        val startY = -(py / 100f) * span * h
        // 135°：对角方向，长度取 4 倍尺寸在该方向上的投影。
        val diagonal = 0.70710678f
        Brush.linearGradient(
            colorStops = HOLO_STOPS,
            start = Offset(startX, startY),
            end = Offset(
                startX + GRADIENT_SCALE * w * diagonal,
                startY + GRADIENT_SCALE * h * diagonal,
            ),
        )
    }

    Text(
        text = text,
        modifier = modifier.onGloballyPositioned { measured = it.size },
        style = TextStyle(fontSize = fontSize, fontWeight = fontWeight, brush = brush),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * 称号文本：白名单内走 [HolographicTitle] 的流动渐变，其余保持普通字色。
 *
 * 之所以做成一个入口而不是让调用方自己判：白名单是**站点侧的产品约定**，散在多个页面里
 * 迟早会漂移（鸿蒙那边就是 TopicPostItem 与 UserProfilePage 各存了一份 `Set`）。
 */
@Composable
internal fun UserTitleText(
    title: String,
    color: Color,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 13.sp,
) {
    if (isHolographicTitle(title)) {
        HolographicTitle(text = title, modifier = modifier, fontSize = fontSize)
    } else {
        Text(
            text = title,
            modifier = modifier,
            fontSize = fontSize,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
