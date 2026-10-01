package com.arkdo.android.common.layout

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 当前窗口是不是双栏（宽屏）布局。
 *
 * 由 [WideLayoutHost] 在导航宿主根部下发一次，整棵树共用。之所以走 CompositionLocal 而不是
 * 逐层传参：读它的地方分散在很深的叶子里（如富文本图片的宽屏规则），沿途每一层都加一个
 * 布尔参数既啰嗦又容易漏。
 */
val LocalWideLayout = staticCompositionLocalOf { false }

/**
 * 布局驱动的断点宿主：用**实际布局尺寸**算宽屏标志，而不是读 Configuration。
 *
 * 为什么不用 `LocalConfiguration.screenWidthDp`：那个值是「屏幕」宽度，左右分屏、自由窗口
 * 拖动时它并不等于本应用窗口的可用宽度，分屏下会误判成宽屏（真机上表现为半屏窗口里硬挤
 * 出双栏）。[BoxWithConstraints] 给的是父容器真正分下来的约束，旋转、分屏、折叠都会重新触发，
 * 与鸿蒙侧用 `onAreaChange` 喂尺寸是同一个思路。
 *
 * @param unfolded 折叠屏是否展开。判定规则见 [WindowBreakpoints.isWideLayout] 第二条路径。
 * @param content 收到算好的 [isWide] 与左栏宽度 [navBarWidth] 后自行决定怎么摆。
 */
@Composable
fun WideLayoutHost(
    modifier: Modifier = Modifier,
    unfolded: Boolean = false,
    content: @Composable (isWide: Boolean, navBarWidth: Dp) -> Unit
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val widthDp = maxWidth.value
        val heightDp = maxHeight.value
        val isWide = WindowBreakpoints.isWideLayout(widthDp, heightDp, unfolded)
        val navBarWidth = WindowBreakpoints.splitNavBarWidth(widthDp, unfolded).dp
        CompositionLocalProvider(LocalWideLayout provides isWide) {
            content(isWide, navBarWidth)
        }
    }
}
