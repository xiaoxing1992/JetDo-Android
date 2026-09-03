package com.arkdo.android.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkdo.android.data.models.Topic
import com.arkdo.android.data.models.TopicCategory
import com.arkdo.android.data.models.TopicTag
import com.arkdo.android.ui.theme.ArkdoFixedColors
import com.arkdo.android.ui.theme.arkdoColors
import com.arkdo.android.ui.utils.RelativeTime

// ==========================================================================
// 对齐鸿蒙版 views/components/TopicListItem.ets。
//
// 几何取自鸿蒙 TopicListStylePrefs 的默认档（该侧这些值用户可在设置里调，
// Android 尚未移植设置页，先按默认值硬编码，字段名保持一致便于后续接上）：
//   标题 17 / 字重 500 / 行高 17*1.35≈23 / 行内边距 8 / 头像 48
//   次要文字 17-4=13 / 未读药丸 17-5=12（圆角 12*0.75≈9）/ 小蓝点 17-9=8
//   头像间距 max(6, 48/6)=8 / meta 行间距 max(2, 8*3/8)=3 / 摘要间距 max(2, 8/2)=4
// ==========================================================================

private const val TITLE_FONT_SIZE = 17
private const val ROW_PADDING = 8
private const val AVATAR_SIZE = 48

private val TITLE_LINE_HEIGHT = (TITLE_FONT_SIZE * 1.35f).toInt().sp   // 23
private val SECONDARY_FONT_SIZE = (TITLE_FONT_SIZE - 4).sp              // 13
private val UNREAD_BADGE_FONT_SIZE = (TITLE_FONT_SIZE - 5).sp           // 12
private val UNREAD_BADGE_RADIUS = 9.dp                                  // round(12 * 0.75)
private val UNSEEN_DOT_FONT_SIZE = (TITLE_FONT_SIZE - 9).sp             // 8
private val INLINE_ICON_SIZE = 15.dp
private val INLINE_ICON_GAP = 5.dp
private val AVATAR_GAP = 8.dp
private val META_ROW_GAP = 3.dp
private val BLURB_GAP = 4.dp

private const val INLINE_BOOKMARK = "bookmark"
private const val INLINE_SOLVED = "solved"
private const val INLINE_UNREAD = "unread"

/**
 * 话题列表项。
 *
 * 标题**不限行数**、不省略号 —— 这是鸿蒙侧的行为，长标题会撑高整行。
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
    contentHorizontalPadding: Int = 12,
    incomingHighlightActive: Boolean = false,
    modifier: Modifier = Modifier
) {
    val colors = arkdoColors
    val hasUnreadBadge = (topic.unreadPosts ?: 0) > 0
    val clickHandler = if (hasUnreadBadge && onUnreadBadgeClick != null) onUnreadBadgeClick else onClick

    // 淡出终点是同色 alpha=0，不是 Transparent —— 否则过渡中途会先褪成灰再消失。
    val bgColor by animateColorAsState(
        targetValue = if (incomingHighlightActive) colors.incomingHighlight else colors.incomingHighlightFaded,
        animationSpec = tween(durationMillis = 600),
        label = "incomingHighlight"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = clickHandler)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(bgColor)
                .padding(
                    start = contentHorizontalPadding.dp,
                    end = contentHorizontalPadding.dp,
                    top = ROW_PADDING.dp,
                    bottom = ROW_PADDING.dp
                ),
            verticalAlignment = Alignment.Top
        ) {
            UserAvatar(
                user = topic.author,
                avatarSize = AVATAR_SIZE.dp,
                showFlair = true
            )

            Spacer(modifier = Modifier.width(AVATAR_GAP))

            Column(modifier = Modifier.weight(1f)) {
                TitleRow(topic = topic, searchHighlights = searchHighlights)

                if (!topic.searchBlurb.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(BLURB_GAP))
                    SearchBlurb(blurb = topic.searchBlurb!!, highlights = searchHighlights)
                }

                // 按格式化后的时间串判空：解析失败时不该留出一条空的 meta 行。
                val relativeTime = RelativeTime.fromNow(topic.lastPostedAt)
                val hasBadges = category != null || topic.tags.isNotEmpty()
                if (hasBadges || relativeTime.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(META_ROW_GAP))
                    MetadataRow(
                        category = category,
                        parentCategory = parentCategory,
                        tags = topic.tags,
                        relativeTime = relativeTime,
                        badgesTappable = badgesTappable,
                        onCategoryClick = onCategoryClick,
                        onTagClick = onTagClick
                    )
                }
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = contentHorizontalPadding.dp),
            color = colors.border,
            thickness = 1.dp
        )
    }
}

/**
 * 标题行：书签 / 已解决图标 → 标题 → 未读药丸或小蓝点，全部内联在一个 Text 里。
 */
@Composable
private fun TitleRow(
    topic: Topic,
    searchHighlights: List<String>
) {
    val colors = arkdoColors
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()

    val iconSp = with(density) { INLINE_ICON_SIZE.toSp() }
    // 占位要把图标和它右侧的间距一起算进去，所以先在 Dp 上相加再换算。
    val iconSlotSp = with(density) { (INLINE_ICON_SIZE + INLINE_ICON_GAP).toSp() }

    val unreadCount = topic.unreadPosts ?: 0
    // 药丸内部用不换行空格，保证换行时不会被从中间劈开、留半截背景在上一行。
    val pillLabel = " $unreadCount "
    val pillWidthSp = if (unreadCount > 0) {
        val measured = measurer.measure(
            AnnotatedString(pillLabel),
            TextStyle(fontSize = UNREAD_BADGE_FONT_SIZE, fontWeight = FontWeight.Medium)
        ).size.width
        with(density) { measured.toSp() }
    } else {
        0.sp
    }

    val titleText = buildAnnotatedString {
        if (topic.bookmarked == true) appendInlineContent(INLINE_BOOKMARK, "🔖")
        if (topic.hasAcceptedAnswer == true) appendInlineContent(INLINE_SOLVED, "✔")

        if (searchHighlights.isNotEmpty()) {
            highlightText(topic.title, searchHighlights, colors.brand)
        } else {
            append(topic.title)
        }

        if (unreadCount > 0) {
            // 两个普通空格是整条标题里唯一允许在药丸前断行的位置。
            withStyle(SpanStyle(fontSize = UNREAD_BADGE_FONT_SIZE)) { append("  ") }
            appendInlineContent(INLINE_UNREAD, pillLabel)
        } else if (topic.unseen == true) {
            withStyle(SpanStyle(fontSize = UNSEEN_DOT_FONT_SIZE, color = ArkdoFixedColors.Unread)) {
                append(" ●")
            }
        }
    }

    val inlineContent = buildMap {
        put(
            INLINE_BOOKMARK,
            InlineTextContent(
                Placeholder(iconSlotSp, iconSp, PlaceholderVerticalAlign.Center)
            ) {
                Row {
                    // SVG 里已烘死 #919191，不再染色。
                    FaIconRaw("bookmark-gray.svg", INLINE_ICON_SIZE)
                    Spacer(Modifier.width(INLINE_ICON_GAP))
                }
            }
        )
        put(
            INLINE_SOLVED,
            InlineTextContent(
                Placeholder(iconSlotSp, iconSp, PlaceholderVerticalAlign.Center)
            ) {
                Row {
                    FaIconRaw("far-square-check-green.svg", INLINE_ICON_SIZE)
                    Spacer(Modifier.width(INLINE_ICON_GAP))
                }
            }
        )
        if (unreadCount > 0) {
            put(
                INLINE_UNREAD,
                InlineTextContent(
                    Placeholder(pillWidthSp, UNREAD_BADGE_FONT_SIZE, PlaceholderVerticalAlign.Center)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(UNREAD_BADGE_RADIUS))
                            .background(ArkdoFixedColors.Unread),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = pillLabel,
                            fontSize = UNREAD_BADGE_FONT_SIZE,
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                    }
                }
            )
        }
    }

    Text(
        text = titleText,
        inlineContent = inlineContent,
        fontSize = TITLE_FONT_SIZE.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = TITLE_LINE_HEIGHT,
        color = colors.textBody,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun SearchBlurb(
    blurb: String,
    highlights: List<String>
) {
    val colors = arkdoColors
    val blurbText = buildAnnotatedString {
        if (highlights.isNotEmpty()) highlightText(blurb, highlights, colors.brand) else append(blurb)
    }

    Text(
        text = blurbText,
        fontSize = SECONDARY_FONT_SIZE,
        color = colors.textSecondary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun MetadataRow(
    category: TopicCategory?,
    parentCategory: TopicCategory?,
    tags: List<TopicTag>,
    relativeTime: String,
    badgesTappable: Boolean,
    onCategoryClick: ((TopicCategory) -> Unit)?,
    onTagClick: ((TopicTag) -> Unit)?
) {
    val colors = arkdoColors

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
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

        if (relativeTime.isNotEmpty()) {
            Text(
                text = relativeTime,
                fontSize = SECONDARY_FONT_SIZE,
                color = colors.textSecondary,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

/**
 * 高亮命中词。字重显式回填 Medium，与标题非命中段保持一致。
 */
private fun AnnotatedString.Builder.highlightText(
    text: String,
    highlights: List<String>,
    highlightColor: Color
) {
    if (highlights.isEmpty()) {
        append(text)
        return
    }

    val lowerText = text.lowercase()
    val lowerHighlights = highlights.map { it.lowercase() }
    var cursor = 0

    while (cursor < text.length) {
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
            append(text.substring(cursor))
            break
        }

        if (bestPos > cursor) append(text.substring(cursor, bestPos))

        withStyle(SpanStyle(color = highlightColor, fontWeight = FontWeight.Medium)) {
            append(text.substring(bestPos, bestPos + bestLen))
        }

        cursor = bestPos + bestLen
    }
}
