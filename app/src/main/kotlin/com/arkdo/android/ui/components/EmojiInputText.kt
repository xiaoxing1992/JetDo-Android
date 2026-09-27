package com.arkdo.android.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.arkdo.android.services.content.EmojiInputRender
import com.arkdo.android.services.content.renderEmojiInput
import kotlin.math.roundToInt

// ==========================================================================
// 输入框里的表情图片：显示变换 + 覆盖绘制。
//
// 分工：纯逻辑（短码 → 占位字符 + 偏移映射）在 services/content/EmojiInputRender.kt；
// 这里只做两件 Compose 事 —— ① 把渲染结果的显示文本交给 BasicTextField；
// ② 按 TextLayoutResult 量出占位字符的盒子，把表情图片画在上面。
//
// 为什么不用 InlineTextContent（Text 能用的那套）：BasicTextField 没有任何重载接
// inlineContent（2026-09 核对 androidx 源码），可编辑文本里放图片是官方未支持的能力。
// 详见 EmojiInputRender.kt 顶部的说明。
// ==========================================================================

/** 输入框内表情图片的最大边长：占位是全角空格（1em ≈ 16dp），图片不超过它就绝不与相邻文字重叠。 */
private val EMOJI_INPUT_MAX_SIZE = 16.dp

/**
 * 把已知短码显示成占位字符的视觉变换（原文一字不改，只改显示）。
 *
 * 刻意在 [filter] 里用传进来的文本现算，不缓存外面算好的结果：变换与字段的真实文本必须严格
 * 同源，否则映射会对不上（组合期与布局期差一帧时尤其明显）。
 */
@Suppress("DEPRECATION") // VisualTransformation 已废弃，但替代品 OutputTransformation 同样不支持内联图片
class EmojiInputVisualTransformation(
    private val resolveUrl: (String) -> String?
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val render = renderEmojiInput(text.text, resolveUrl)
        return TransformedText(AnnotatedString(render.display), EmojiInputOffsetMapping(render))
    }
}

@Suppress("DEPRECATION")
private class EmojiInputOffsetMapping(private val render: EmojiInputRender) : OffsetMapping {
    override fun originalToTransformed(offset: Int): Int = render.originalToTransformed(offset)
    override fun transformedToOriginal(offset: Int): Int = render.transformedToOriginal(offset)
}

/**
 * 把表情图片画在占位字符上。
 *
 * [layout] 必须是该输入框**经变换后**的排版结果（BasicTextField 的 onTextLayout 给的就是它）：
 * 占位字符的 x/行高都从里面量，所以字体、字号、缩放、换行都不需要自己算 —— 图片必然落在
 * 文字流留给它的那个盒子里，也不会影响输入框自身的高度（图片只做偏移绘制，不参与测量）。
 *
 * 排版结果与当前渲染不是同一帧时（文本刚改、布局还没跟上）直接不画，等下一帧 —— 宁可少一帧，
 * 也不要把图片画在错误的字上。
 */
@Composable
fun EmojiInputOverlay(
    render: EmojiInputRender,
    layout: TextLayoutResult?,
    modifier: Modifier = Modifier
) {
    if (layout == null || render.slots.isEmpty()) return
    if (layout.layoutInput.text.text != render.display) return
    val density = LocalDensity.current

    Box(modifier = modifier) {
        for (slot in render.slots) {
            val start = slot.displayIndex
            if (start < 0 || start + 1 > layout.layoutInput.text.length) continue
            val line = layout.getLineForOffset(start)
            val left = layout.getHorizontalPosition(start, usePrimaryDirection = true)
            val right = layout.getHorizontalPosition(start + 1, usePrimaryDirection = true)
            val top = layout.getLineTop(line)
            val bottom = layout.getLineBottom(line)
            val boxWidth = (right - left).coerceAtLeast(0f)
            val boxHeight = (bottom - top).coerceAtLeast(0f)
            if (boxWidth <= 0f || boxHeight <= 0f) continue

            // 图片不超过占位盒（全角空格 = 1em），否则会压到相邻字符上；再在盒内居中。
            val sizePx = minOf(boxWidth, with(density) { EMOJI_INPUT_MAX_SIZE.toPx() })
            val x = left + (boxWidth - sizePx) / 2f
            val y = top + (boxHeight - sizePx) / 2f

            AsyncImage(
                model = slot.url,
                contentDescription = slot.name,
                modifier = Modifier
                    .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                    .size(with(density) { sizePx.toDp() })
            )
        }
    }
}
