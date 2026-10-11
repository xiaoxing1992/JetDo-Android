package com.jetdo.android.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jetdo.android.services.content.MathImage
import com.jetdo.android.services.content.MathRenderService

/**
 * 公式渲染结果:成功出图 / 失败(退回裸 LaTeX)。
 *
 * 渲染是异步的(离屏 WebView + MathJax),所以这里用状态承载三态:
 * 渲染中显示裸 LaTeX 占位、成功换位图、失败保持占位并记一次日志。
 */
internal sealed interface MathState {
    data object Pending : MathState
    data class Ready(val image: MathImage) : MathState
    data object Failed : MathState
}

/**
 * 渲染一条公式,返回当前状态。命中缓存时首帧就是 Ready(不闪占位)。
 *
 * 与鸿蒙 `PostRichContent.prepareMath` 同思路:一次性发起、失败不阻断其余公式。
 * 这里用 Compose 的 `LaunchedEffect + State`,不必像鸿蒙那样靠翻 key 强制重建。
 */
@Composable
internal fun rememberMathImage(latex: String, display: Boolean): MathState {
    // 缓存命中直接同步返回,避免"先占位再换图"的闪烁。
    val cached = remember(latex, display) { MathRenderService.getCached(latex, display) }
    var state by remember(latex, display) {
        mutableStateOf<MathState>(if (cached != null) MathState.Ready(cached) else MathState.Pending)
    }

    LaunchedEffect(latex, display) {
        if (state is MathState.Ready) return@LaunchedEffect
        state = runCatching { MathRenderService.render(latex, display) }
            .fold(
                onSuccess = { MathState.Ready(it) },
                onFailure = { MathState.Failed }
            )
    }
    return state
}

/**
 * 块级公式(`div.math`):出图后居中显示,未就绪/失败退回裸 LaTeX 代码样式。
 *
 * 产物是**黑字透明底**的位图(render.html 里把 SVG 染成 #000),深色主题下要反色,
 * 否则黑字压深底看不见 —— 这是鸿蒙用 `MATH_TINT_MATRIX_DARK` 做的事,Compose 侧
 * 用 `ColorFilter.tint` 的等价做法是直接用 luminance 反相,这里简化成按主题切换滤镜。
 */
@Composable
internal fun MathBlock(latex: String, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val state = rememberMathImage(latex, display = true)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        when (state) {
            is MathState.Ready -> {
                val image = state.image
                Image(
                    bitmap = image.bitmap.asImageBitmap(),
                    contentDescription = latex,
                    modifier = Modifier
                        .width(with(density) { image.cssWidth.toDp() })
                        .height(with(density) { image.cssHeight.toDp() }),
                    colorFilter = mathTint()
                )
            }

            // 未就绪与失败都显示裸 LaTeX:公式即使渲染不出来,用户也能看到原始定义,
            // 比留白或报错更有用。
            else -> MathFallbackBlock(latex)
        }
    }
}

/** 块级公式的裸 LaTeX 占位(与改动前的样式一致)。 */
@Composable
private fun MathFallbackBlock(latex: String) {
    Text(
        text = latex,
        fontFamily = FontFamily.Monospace,
        fontStyle = FontStyle.Italic,
        fontSize = 14.sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
            .padding(8.dp)
    )
}

/**
 * 深色主题下公式位图需要反色:render.html 输出的 SVG 字形恒为黑色(便于原生染色),
 * 深色底上黑字不可见。用 tint 到 onSurface 色等于「按主题上色」,比矩阵反相更可控。
 */
@Composable
private fun mathTint(): ColorFilter? =
    ColorFilter.tint(MaterialTheme.colorScheme.onSurface)

/**
 * 行内公式尺寸,供 `InlineTextContent` 的 Placeholder 使用。
 * 位图未就绪时返回 null,调用方退回裸文本。
 */
@Composable
internal fun inlineMathPlaceholder(latex: String): MathState =
    rememberMathImage(latex, display = false)