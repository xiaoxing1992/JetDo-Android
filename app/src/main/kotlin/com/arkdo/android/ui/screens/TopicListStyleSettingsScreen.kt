package com.arkdo.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkdo.android.core.designsystem.AVATAR_SIZE_MAX
import com.arkdo.android.core.designsystem.AVATAR_SIZE_MIN
import com.arkdo.android.core.designsystem.LINE_HEIGHT_RATIO_MAX
import com.arkdo.android.core.designsystem.LINE_HEIGHT_RATIO_MIN
import com.arkdo.android.core.designsystem.LINE_HEIGHT_RATIO_STEP
import com.arkdo.android.core.designsystem.ROW_PADDING_MAX
import com.arkdo.android.core.designsystem.ROW_PADDING_MIN
import com.arkdo.android.core.designsystem.TITLE_FONT_SIZE_MAX
import com.arkdo.android.core.designsystem.TITLE_FONT_SIZE_MIN
import com.arkdo.android.core.designsystem.TITLE_FONT_WEIGHT_MEDIUM
import com.arkdo.android.core.designsystem.TITLE_FONT_WEIGHT_REGULAR
import com.arkdo.android.core.designsystem.TITLE_FONT_WEIGHT_SEMI_BOLD
import com.arkdo.android.core.designsystem.TopicListStyle
import com.arkdo.android.core.designsystem.TopicListStyleStore
import com.arkdo.android.core.designsystem.TopicRow
import com.arkdo.android.core.designsystem.TopicRowColors
import com.arkdo.android.core.model.HomeCategory
import com.arkdo.android.core.model.HomeTag
import com.arkdo.android.core.model.HomeTopic
import com.arkdo.android.core.model.HomeUser
import com.arkdo.android.ui.theme.arkdoColors
import java.util.Locale

private val PageMargin = 12.dp
private val CardRadius = 16.dp
private val CardPadding = 16.dp
private val CardGap = 12.dp

private val WeightOptions = listOf(
    "常规" to TITLE_FONT_WEIGHT_REGULAR,
    "中等" to TITLE_FONT_WEIGHT_MEDIUM,
    "加粗" to TITLE_FONT_WEIGHT_SEMI_BOLD
)

/**
 * 帖子列表样式。预览就是首页和搜索用的那一行，拖动即时反映，松手才写盘。
 * 已读列表 Android 还没有，说明只提首页和搜索。
 */
@Composable
fun TopicListStyleSettingsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val style by TopicListStyleStore.style.collectAsState()
    val colors = arkdoColors
    val rowColors = remember(colors) {
        TopicRowColors(
            pageBg = colors.surfaceCard,
            textSecondary = colors.textSecondary,
            textBody = colors.textBody,
            brand = colors.brand,
            border = colors.border,
            surfaceMuted = colors.surfaceMuted,
            avatarFg = colors.avatarFg,
            avatarBorder = colors.border,
            unread = colors.ldAccent
        )
    }
    val preview = remember { previewTopics() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.pageBgGray)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(56.dp)
                .padding(horizontal = 4.dp)
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = colors.textPrimary)
            }
            Text(
                text = "帖子列表样式",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary
            )
        }
        HorizontalDivider(color = colors.border, thickness = 0.5.dp)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = PageMargin)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            Column(
                modifier = Modifier
                    .padding(top = CardGap)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(CardRadius))
                    .background(colors.surfaceCard)
            ) {
                preview.forEachIndexed { index, topic ->
                    TopicRow(
                        topic = topic,
                        category = if (index == 0) previewCategory() else null,
                        parentCategory = null,
                        onClick = {},
                        onUnreadClick = {},
                        colors = rowColors,
                        avatarBaseUrl = "",
                        style = style
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                Column(
                    modifier = Modifier
                        .padding(top = CardGap)
                        .fillMaxWidth()
                        .background(colors.surfaceCard, RoundedCornerShape(CardRadius))
                ) {
                    StyleSlider(
                        label = "标题字号",
                        valueLabel = style.titleFontSize.toString(),
                        value = style.titleFontSize.toFloat(),
                        min = TITLE_FONT_SIZE_MIN.toFloat(),
                        max = TITLE_FONT_SIZE_MAX.toFloat(),
                        step = 1f,
                        onChange = TopicListStyleStore::setTitleFontSize,
                        onDone = TopicListStyleStore::flush
                    )
                    Hairline()
                    FontWeightRow(style)
                    Hairline()
                    StyleSlider(
                        label = "行高",
                        valueLabel = String.format(Locale.US, "%.2f", style.lineHeightRatio),
                        value = style.lineHeightRatio,
                        min = LINE_HEIGHT_RATIO_MIN,
                        max = LINE_HEIGHT_RATIO_MAX,
                        step = LINE_HEIGHT_RATIO_STEP,
                        onChange = TopicListStyleStore::setLineHeightRatio,
                        onDone = TopicListStyleStore::flush
                    )
                    Hairline()
                    StyleSlider(
                        label = "行间距",
                        valueLabel = style.rowPadding.toString(),
                        value = style.rowPadding.toFloat(),
                        min = ROW_PADDING_MIN.toFloat(),
                        max = ROW_PADDING_MAX.toFloat(),
                        step = 1f,
                        onChange = TopicListStyleStore::setRowPadding,
                        onDone = TopicListStyleStore::flush
                    )
                    Hairline()
                    StyleSlider(
                        label = "头像大小",
                        valueLabel = style.avatarSize.toString(),
                        value = style.avatarSize.toFloat(),
                        min = AVATAR_SIZE_MIN.toFloat(),
                        max = AVATAR_SIZE_MAX.toFloat(),
                        step = 1f,
                        onChange = TopicListStyleStore::setAvatarSize,
                        onDone = TopicListStyleStore::flush
                    )
                }
                Text(
                    text = "设置对首页和搜索结果同时生效。",
                    fontSize = 12.sp,
                    color = colors.textFaint,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = CardPadding, vertical = 10.dp)
                )
                Text(
                    text = "恢复默认",
                    fontSize = 15.sp,
                    color = colors.textPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(colors.surfaceCard, RoundedCornerShape(CardRadius))
                        .clip(RoundedCornerShape(CardRadius))
                        .clickable { TopicListStyleStore.resetDefaults() }
                        .padding(vertical = 14.dp)
                )
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

@Composable
private fun StyleSlider(
    label: String,
    valueLabel: String,
    value: Float,
    min: Float,
    max: Float,
    step: Float,
    onChange: (Float) -> Unit,
    onDone: () -> Unit
) {
    val steps = ((max - min) / step).toInt() - 1
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = CardPadding, end = CardPadding, top = 12.dp, bottom = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 15.sp, color = arkdoColors.textPrimary, modifier = Modifier.weight(1f))
            Text(valueLabel, fontSize = 13.sp, color = arkdoColors.textFaint)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            BoundLabel(formatBound(min, step))
            Slider(
                value = value,
                onValueChange = onChange,
                onValueChangeFinished = onDone,
                valueRange = min..max,
                steps = steps.coerceAtLeast(0),
                colors = SliderDefaults.colors(
                    thumbColor = arkdoColors.ldAccent,
                    activeTrackColor = arkdoColors.ldAccent,
                    inactiveTrackColor = arkdoColors.surfaceMuted
                ),
                modifier = Modifier.weight(1f)
            )
            BoundLabel(formatBound(max, step))
        }
    }
}

@Composable
private fun BoundLabel(text: String) {
    Text(text, fontSize = 11.sp, color = arkdoColors.textFaint)
}

@Composable
private fun FontWeightRow(style: TopicListStyle) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = CardPadding, vertical = 12.dp)
    ) {
        Text("标题字重", fontSize = 15.sp, color = arkdoColors.textPrimary)
        Row(modifier = Modifier.padding(top = 8.dp)) {
            WeightOptions.forEach { (label, weight) ->
                val selected = style.titleFontWeight == weight
                Text(
                    text = label,
                    fontSize = 14.sp,
                    fontWeight = when (weight) {
                        TITLE_FONT_WEIGHT_REGULAR -> FontWeight.Normal
                        TITLE_FONT_WEIGHT_SEMI_BOLD -> FontWeight.SemiBold
                        else -> FontWeight.Medium
                    },
                    color = if (selected) arkdoColors.ldAccent else arkdoColors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) arkdoColors.surfaceMuted else arkdoColors.surfaceCard)
                        .clickable {
                            TopicListStyleStore.setTitleFontWeight(weight)
                            TopicListStyleStore.flush()
                        }
                        .padding(vertical = 7.dp)
                )
            }
        }
    }
}

@Composable
private fun Hairline() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = CardPadding),
        thickness = 0.5.dp,
        color = arkdoColors.border
    )
}

private fun formatBound(value: Float, step: Float): String =
    if (step < 1f) String.format(Locale.US, "%.2f", value) else value.toInt().toString()

private fun previewCategory() = HomeCategory(id = 1, name = "资源分享", color = "0088CC")

private fun previewTopics(): List<HomeTopic> {
    val now = System.currentTimeMillis()
    return listOf(
        HomeTopic(
            id = -1,
            title = "这是一条用来预览的帖子标题，它足够长，好让你看清标题折行之后的行高与整体密度",
            categoryId = 1,
            tags = listOf(HomeTag("教程"), HomeTag("实用工具")),
            lastPostedAt = java.time.Instant.ofEpochMilli(now - 15 * 60_000).toString(),
            unreadPosts = 12,
            author = HomeUser(id = 10000, username = "demo_user", name = "示例用户")
        ),
        HomeTopic(
            id = -2,
            title = "一条较短的帖子标题",
            lastPostedAt = java.time.Instant.ofEpochMilli(now - 3 * 60 * 60_000).toString(),
            bookmarked = true,
            hasAcceptedAnswer = true,
            unseen = true,
            author = HomeUser(id = 10001, username = "arkdo", name = "ArkDO")
        )
    )
}
