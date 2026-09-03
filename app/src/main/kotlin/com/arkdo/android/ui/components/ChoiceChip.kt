package com.arkdo.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkdo.android.ui.theme.arkdoColors

/**
 * 选择胶囊，对齐鸿蒙版 views/components/ChoiceChip.ets 的标准档
 * （compact=false / removable=false / 无图标）。
 *
 * 三态的取色规则不是"选中 + 禁用"叠加：禁用会把高亮整个吃掉，
 * 所以"禁用且选中"在视觉上等同于"禁用未选"。这是鸿蒙的行为，别改成叠加。
 * 另外禁用态**不压 opacity**，只有按下才压到 0.7。
 */
@Composable
fun ChoiceChip(
    label: String,
    selected: Boolean,
    chipDisabled: Boolean = false,
    onTap: () -> Unit,
    onBlockedTap: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val colors = arkdoColors
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    val highlighted = selected && !chipDisabled

    val textColor = when {
        chipDisabled -> colors.chipDisabledText
        highlighted -> colors.brand
        else -> colors.textBody
    }
    val background = if (highlighted) colors.infoSoftBg else colors.surfaceMuted

    Box(
        modifier = modifier
            .padding(end = 8.dp, bottom = 8.dp)
            .height(32.dp)
            // 禁用态不压透明度，只有按下压。
            .alpha(if (pressed && !chipDisabled) 0.7f else 1f)
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { if (chipDisabled) onBlockedTap() else onTap() }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = if (highlighted) FontWeight.Medium else FontWeight.Normal,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
