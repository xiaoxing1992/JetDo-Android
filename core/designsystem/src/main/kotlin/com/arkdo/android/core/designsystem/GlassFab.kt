package com.arkdo.android.core.designsystem

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

/** 浅色蒙版，20% 的 #F2F3F5。对齐鸿蒙 hds_floating_action_bg。 */
val FabGlassTintLight = Color(0x33F2F3F5)

/** 深色蒙版，20% 的 #2A2E33。 */
val FabGlassTintDark = Color(0x332A2E33)

/** 半透明白描边，两态都画。对齐鸿蒙 hds_floating_action_highlight。 */
val FabGlassHighlight = Color(0x59FFFFFF)

/** 液态玻璃的边缘折射，和底栏同一档。滑块只调模糊，不推这个值。 */
private val FabLiquidLens = 24.dp

/**
 * 圆形液态玻璃。开关关着、没有取样层、或系统低于 API 33 时什么都不加，调用方走自己的实心底。
 * 打开时先提饱和，再按 [radiusDp] 模糊，边缘用固定 24dp 折射。透镜着色器要 API 33。
 */
fun Modifier.glassCircle(
    enabled: Boolean,
    radiusDp: Int,
    backdrop: LayerBackdrop?,
    tint: Color
): Modifier {
    if (!enabled || backdrop == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return this
    return drawBackdrop(
        backdrop = backdrop,
        shape = { CircleShape },
        effects = {
            vibrancy()
            blur(radiusDp.dp.toPx())
            lens(FabLiquidLens.toPx(), FabLiquidLens.toPx())
        },
        highlight = { null },
        shadow = { null },
        onDrawSurface = { drawCircle(tint) }
    )
}

/**
 * 首页和详情页右下角的 52dp 圆按钮。玻璃关着时是 [fallbackColor] 实心圆；
 * 打开时从 [backdrop] 取景做液态玻璃，滑块控制模糊，边缘折射固定 24dp。
 */
@Composable
fun GlassFab(
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    glassEnabled: Boolean,
    glassRadius: Int,
    backdrop: LayerBackdrop?,
    fallbackColor: Color,
    glassTint: Color,
    borderColor: Color = FabGlassHighlight,
    borderWidth: Dp = 0.5.dp,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp
) {
    val frosted = glassEnabled && backdrop != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .shadow(6.dp, CircleShape)
            .clip(CircleShape)
            .then(
                if (frosted) {
                    Modifier.glassCircle(true, glassRadius, backdrop, glassTint)
                } else {
                    Modifier.background(fallbackColor)
                }
            )
            .border(borderWidth, borderColor, CircleShape)
            .semantics { role = Role.Button }
            .clickable(onClick = onClick)
    ) {
        icon()
    }
}
