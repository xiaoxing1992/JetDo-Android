package com.arkdo.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkdo.android.data.models.TopicCategory
import com.arkdo.android.data.models.TopicTag

/**
 * 话题元数据徽章 - 1:1 对齐鸿蒙版 TopicBadges.ets
 *
 * 显示分类和标签徽章，支持横向滚动和点击筛选
 */
@Composable
fun TopicMetadataBadges(
    category: TopicCategory? = null,
    parentCategory: TopicCategory? = null,
    tags: List<TopicTag> = emptyList(),
    badgesTappable: Boolean = false,
    onCategoryClick: ((TopicCategory) -> Unit)? = null,
    onTagClick: ((TopicTag) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // 确保标签图标已加载
    TagIconRegistry.ensureTagIconsLoaded(context)

    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 父分类徽章（如果存在）
        if (parentCategory != null) {
            CategoryBadge(
                category = parentCategory,
                isParent = true,
                tappable = badgesTappable,
                onClick = onCategoryClick
            )
        }

        // 子分类徽章
        if (category != null) {
            CategoryBadge(
                category = category,
                isParent = false,
                tappable = badgesTappable,
                onClick = onCategoryClick
            )
        }

        // 标签徽章
        tags.forEach { tag ->
            TagBadge(
                tag = tag,
                tappable = badgesTappable,
                onClick = onTagClick
            )
        }
    }
}

/**
 * 分类徽章
 */
@Composable
private fun CategoryBadge(
    category: TopicCategory,
    isParent: Boolean,
    tappable: Boolean,
    onClick: ((TopicCategory) -> Unit)?
) {
    val backgroundColor = try {
        Color(android.graphics.Color.parseColor("#${category.color}"))
    } catch (e: Exception) {
        MaterialTheme.colorScheme.secondaryContainer
    }

    val textColor = try {
        Color(android.graphics.Color.parseColor("#${category.textColor}"))
    } catch (e: Exception) {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    val clickModifier = if (tappable && onClick != null) {
        Modifier.clickable { onClick(category) }
    } else {
        Modifier
    }

    Text(
        text = category.name,
        modifier = clickModifier
            .clip(RoundedCornerShape(4.dp))
            .background(backgroundColor)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        fontSize = 12.sp,
        fontWeight = if (isParent) FontWeight.Normal else FontWeight.Medium,
        color = textColor
    )
}

/**
 * 标签徽章 - 带图标和颜色
 */
@Composable
private fun TagBadge(
    tag: TopicTag,
    tappable: Boolean,
    onClick: ((TopicTag) -> Unit)?
) {
    val clickModifier = if (tappable && onClick != null) {
        Modifier.clickable { onClick(tag) }
    } else {
        Modifier
    }

    // 获取标签的图标样式
    val iconStyle = TagIconRegistry.getTagIconStyle(tag.name)
    val icon = iconStyle?.let { TagIconRegistry.mapIconIdToMaterialIcon(it.iconId) }
    val color = iconStyle?.let { TagIconRegistry.parseColor(it.color) }
        ?: MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = clickModifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 显示图标（如果有）
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = color
            )
        }

        Text(
            text = tag.name,
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
