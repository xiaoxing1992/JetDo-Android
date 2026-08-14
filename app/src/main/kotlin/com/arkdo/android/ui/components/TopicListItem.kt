package com.arkdo.android.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkdo.android.data.models.Topic
import com.arkdo.android.data.models.TopicCategory
import com.arkdo.android.data.models.TopicTag
import com.arkdo.android.data.models.User
import com.arkdo.android.ui.utils.RelativeTime

/**
 * 话题列表项组件 - 1:1 对齐鸿蒙版 TopicListItem.ets
 *
 * 核心功能：
 * - 用户头像 + 用户名
 * - 标题（支持已读/未读状态、收藏标记、已解决标记）
 * - 未读回复徽章 / 未读小圆点
 * - 分类和标签徽章（可点击筛选）
 * - 相对时间显示
 * - 搜索结果高亮摘要
 * - 增量插入动画（首页实时新话题）
 */
@Composable
fun TopicListItem(
    topic: Topic,
    category: TopicCategory? = null,
    parentCategory: TopicCategory? = null,
    onClick: () -> Unit,
    onUnreadBadgeClick: (() -> Unit)? = null,
    onCategoryClick: ((TopicCategory) -> Unit)? = null,
    onTagClick: ((TopicTag) -> Unit)? = null,
    badgesTappable: Boolean = false,
    searchHighlights: List<String> = emptyList(),
    contentHorizontalPadding: Int = 16,
    incomingHighlightActive: Boolean = false,
    modifier: Modifier = Modifier
) {
    val hasUnreadBadge = (topic.unreadPosts ?: 0) > 0
    val clickHandler = if (hasUnreadBadge && onUnreadBadgeClick != null) {
        onUnreadBadgeClick
    } else {
        onClick
    }

    // 增量插入高亮背景色动画
    val backgroundColor by animateDpAsState(
        targetValue = if (incomingHighlightActive) 2.dp else 0.dp,
        animationSpec = tween(durationMillis = 600),
        label = "incomingHighlight"
    )

    val bgColor = if (incomingHighlightActive) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
    } else {
        Color.Transparent
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(bgColor)
            .clickable(onClick = clickHandler)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = contentHorizontalPadding.dp,
                    end = contentHorizontalPadding.dp,
                    top = 12.dp,
                    bottom = 12.dp
                ),
            verticalAlignment = Alignment.Top
        ) {
            // 用户头像（ID=1 用户自动使用方形，其余用户使用圆形）
            UserAvatar(
                user = topic.author,
                avatarSize = 40.dp,
                showFlair = true
            )

            Spacer(modifier = Modifier.width(12.dp))

            // 右侧内容区
            Column(
                modifier = Modifier.weight(1f)
            ) {
                // 标题行（含状态图标和未读徽章）
                TitleRow(
                    topic = topic,
                    searchHighlights = searchHighlights
                )

                // 搜索摘要（仅搜索结果）
                if (!topic.searchBlurb.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    SearchBlurb(
                        blurb = topic.searchBlurb!!,
                        highlights = searchHighlights
                    )
                }

                // 元数据行：分类/标签 + 时间
                if (category != null || topic.tags.isNotEmpty() || topic.lastPostedAt != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    MetadataRow(
                        category = category,
                        parentCategory = parentCategory,
                        tags = topic.tags,
                        lastPostedAt = topic.lastPostedAt,
                        badgesTappable = badgesTappable,
                        onCategoryClick = onCategoryClick,
                        onTagClick = onTagClick
                    )
                }
            }
        }

        // 分隔线
        Divider(
            modifier = Modifier.padding(horizontal = contentHorizontalPadding.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
            thickness = 0.5.dp
        )
    }
}

/**
 * 标题行：状态图标 + 标题文本 + 未读徽章/小圆点
 */
@Composable
private fun TitleRow(
    topic: Topic,
    searchHighlights: List<String>
) {
    val titleText = buildAnnotatedString {
        // 收藏图标
        if (topic.bookmarked == true) {
            append("📑 ")
        }

        // 已解决图标
        if (topic.hasAcceptedAnswer == true) {
            append("✅ ")
        }

        // 标题（搜索场景高亮）
        if (searchHighlights.isNotEmpty()) {
            highlightText(topic.title, searchHighlights)
        } else {
            append(topic.title)
        }

        // 未读徽章
        val unreadCount = topic.unreadPosts ?: 0
        if (unreadCount > 0) {
            append("  ")
            withStyle(
                style = SpanStyle(
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    background = Color(0xFF66CCFF)
                )
            ) {
                append(" $unreadCount ")
            }
        } else if (topic.unseen == true) {
            // 未读小圆点
            append(" ")
            withStyle(
                style = SpanStyle(
                    color = Color(0xFF66CCFF),
                    fontSize = 10.sp
                )
            ) {
                append("●")
            }
        }
    }

    Text(
        text = titleText,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

/**
 * 搜索摘要：高亮命中词
 */
@Composable
private fun SearchBlurb(
    blurb: String,
    highlights: List<String>
) {
    val blurbText = buildAnnotatedString {
        if (highlights.isNotEmpty()) {
            highlightText(blurb, highlights)
        } else {
            append(blurb)
        }
    }

    Text(
        text = blurbText,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

/**
 * 元数据行：分类/标签徽章 + 相对时间
 */
@Composable
private fun MetadataRow(
    category: TopicCategory?,
    parentCategory: TopicCategory?,
    tags: List<TopicTag>,
    lastPostedAt: String?,
    badgesTappable: Boolean,
    onCategoryClick: ((TopicCategory) -> Unit)?,
    onTagClick: ((TopicTag) -> Unit)?
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 分类和标签徽章（占剩余宽度，可横向滚动）
        if (category != null || tags.isNotEmpty()) {
            TopicMetadataBadges(
                category = category,
                parentCategory = parentCategory,
                tags = tags,
                badgesTappable = badgesTappable,
                onCategoryClick = onCategoryClick,
                onTagClick = onTagClick,
                modifier = Modifier.weight(1f)
            )
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }

        // 相对时间
        val relativeTime = RelativeTime.fromNow(lastPostedAt)
        if (relativeTime.isNotEmpty()) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = relativeTime,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 高亮文本中的关键词
 */
private fun androidx.compose.ui.text.AnnotatedString.Builder.highlightText(
    text: String,
    highlights: List<String>
) {
    if (highlights.isEmpty()) {
        append(text)
        return
    }

    val lowerText = text.lowercase()
    val lowerHighlights = highlights.map { it.lowercase() }
    var cursor = 0

    while (cursor < text.length) {
        // 找到最早出现的高亮词
        var bestPos = -1
        var bestLen = 0
        for (term in lowerHighlights) {
            if (term.isEmpty()) continue
            val pos = lowerText.indexOf(term, cursor)
            if (pos >= 0 && (bestPos < 0 || pos < bestPos)) {
                bestPos = pos
                bestLen = term.length
            }
        }

        if (bestPos < 0) {
            // 没有更多高亮词，追加剩余文本
            append(text.substring(cursor))
            break
        }

        // 追加普通文本
        if (bestPos > cursor) {
            append(text.substring(cursor, bestPos))
        }

        // 追加高亮文本
        withStyle(
            style = SpanStyle(
                color = Color(0xFFC4612F), // 品牌色 terracotta
                fontWeight = FontWeight.Medium
            )
        ) {
            append(text.substring(bestPos, bestPos + bestLen))
        }

        cursor = bestPos + bestLen
    }
}
