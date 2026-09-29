package com.arkdo.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkdo.android.ui.theme.arkdoColors

// ==========================================================================
// 对齐鸿蒙版 TopicDetailPage.ets:854-890 的「N 条新回复」提示条。
//
// 用户不在列表底部时，实时新楼累计在这里；点按跳到首条新楼。
// 在底部时新楼直接追加、不计数（那时这条不显示，见 ViewModel.flushCreatedPosts）。
// ==========================================================================

/** 提示条固定高度：外层据此把它与悬浮回复按钮垂直居中，故写死。 */
val IncomingPostsBannerHeight = 34.dp

@Composable
fun IncomingPostsBanner(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = arkdoColors
    val shape = RoundedCornerShape(IncomingPostsBannerHeight / 2)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .height(IncomingPostsBannerHeight)
            // 与「正在回复」胶囊同一套材质（实色 + 细描边 + 投影，工程已移除 Haze）。
            .shadow(8.dp, shape)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(0.5.dp, colors.border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp)
    ) {
        // 用字符而非图标：鸿蒙侧就是 '↓'，且 FaIcon 对不在图标集里的名字会静默不画。
        Text("↓", fontSize = 13.sp, color = colors.brand, modifier = Modifier.padding(end = 6.dp))
        Text("$count 条新回复", fontSize = 13.sp, color = colors.brand)
    }
}
