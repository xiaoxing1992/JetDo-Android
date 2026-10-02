package com.arkdo.android.core.designsystem

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.arkdo.android.core.designsystem.FaIcon
import com.arkdo.android.core.designsystem.FaIconRaw
import com.arkdo.android.core.designsystem.FaIconSet
import com.arkdo.android.core.model.HomeCategory
import com.arkdo.android.core.model.HomeTag
import com.arkdo.android.core.model.HomeTopic
import com.arkdo.android.core.model.HomeUser
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.round
import kotlin.math.roundToInt

/**
 * 帖子一行。搜索结果和首页列表共用这一行。
 *
 * 首页独有的插入位移、淡入和高亮不在这里：那是列表动画，由首页在外层套上。
 * 未读帖走 [onUnreadClick]，其余走 [onClick]。
 * 标题栏头像也用 [TopicAvatar]，所以它是公开的。
 */
@Composable
fun TopicRow(
    topic: HomeTopic,
    category: HomeCategory?,
    parentCategory: HomeCategory?,
    onClick: () -> Unit,
    onUnreadClick: () -> Unit,
    colors: TopicRowColors,
    avatarBaseUrl: String,
    searchHighlights: List<String> = emptyList(),
    // 「点分类/标签快速筛选」（对齐鸿蒙 BadgeFilterPrefs，默认关）：开关开着时由首页传入
    // tappable 与两个回调，徽章变成可点；不传（搜索结果等）则整行点击不被截胡。
    badgesTappable: Boolean = false,
    onCategoryClick: ((HomeCategory) -> Unit)? = null,
    onTagClick: ((HomeTag) -> Unit)? = null,
    background: Color = colors.pageBg,
    style: TopicListStyle = TopicListStyleStore.style.collectAsState().value
) {
    val click = if (topicUnreadTarget(topic) != null) onUnreadClick else onClick
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = click)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(background)
                .padding(horizontal = 12.dp, vertical = style.rowPadding.dp),
            verticalAlignment = Alignment.Top
        ) {
            TopicAvatar(topic.author, style.avatarSize.dp, colors, avatarBaseUrl)
            Spacer(Modifier.width(style.avatarGap.dp))
            Column(modifier = Modifier.weight(1f)) {
                TopicTitleRow(topic, colors, searchHighlights, style)
                topic.searchBlurb?.takeIf(String::isNotEmpty)?.let { blurb ->
                    Spacer(Modifier.height(style.blurbGap.dp))
                    TopicSearchBlurb(blurb, colors, searchHighlights, style)
                }
                val postedAt = relativeTime(topic.lastPostedAt)
                if (topicHasMetadata(topic, category, postedAt)) {
                    Spacer(Modifier.height(style.metaRowGap.dp))
                    TopicMetadataRow(
                        category, parentCategory, topic.tags, postedAt, colors, assetBaseUrl = avatarBaseUrl,
                        style = style,
                        badgesTappable = badgesTappable,
                        onCategoryClick = onCategoryClick,
                        onTagClick = onTagClick
                    )
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp), color = colors.border)
    }
}

@Composable
private fun TopicTitleRow(
    topic: HomeTopic,
    colors: TopicRowColors,
    searchHighlights: List<String>,
    style: TopicListStyle
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val iconSize = style.inlineIconSize.dp
    val iconGap = style.inlineIconGap.dp
    val iconSlot = with(density) { (iconSize + iconGap).toSp() }
    val iconSp = with(density) { iconSize.toSp() }
    val badgeSize = style.unreadBadgeFontSize.sp
    val unreadCount = topic.unreadPosts
    val pillLabel = "\u00a0$unreadCount\u00a0"
    val pillWidth = if (unreadCount > 0) {
        with(density) {
            measurer.measure(
                AnnotatedString(pillLabel),
                TextStyle(fontSize = badgeSize, fontWeight = FontWeight.Medium)
            ).size.width.toSp()
        }
    } else 0.sp
    val title = buildAnnotatedString {
        if (topic.bookmarked) appendInlineContent("bookmark", "bookmark")
        if (topic.hasAcceptedAnswer) appendInlineContent("solved", "solved")
        // 标题命中只改颜色。字重保持整行的设置值，和鸿蒙 titleFontWeight 落在每一段上一样。
        appendSearchText(topic.title, searchHighlights, colors.brand, emphasizeHit = false)
        if (unreadCount > 0) {
            withStyle(SpanStyle(fontSize = badgeSize)) { append("  ") }
            appendInlineContent("unread", pillLabel)
        } else if (topic.unseen) {
            withStyle(SpanStyle(fontSize = style.unseenDotFontSize.sp, color = colors.unread)) { append(" ●") }
        }
    }
    val inline = buildMap {
        put("bookmark", InlineTextContent(Placeholder(iconSlot, iconSp, PlaceholderVerticalAlign.Center)) {
            Row {
                FaIconRaw("bookmark-gray.svg", Modifier.size(iconSize))
                Spacer(Modifier.width(iconGap))
            }
        })
        put("solved", InlineTextContent(Placeholder(iconSlot, iconSp, PlaceholderVerticalAlign.Center)) {
            Row {
                FaIconRaw("far-square-check-green.svg", Modifier.size(iconSize))
                Spacer(Modifier.width(iconGap))
            }
        })
        if (unreadCount > 0) {
            put(
                "unread",
                InlineTextContent(
                    Placeholder(pillWidth, style.titleFontSize.sp, PlaceholderVerticalAlign.TextCenter)
                ) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(style.unreadBadgeRadius.dp))
                                .background(colors.unread)
                                .padding(vertical = 1.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                pillLabel,
                                fontSize = badgeSize,
                                fontWeight = FontWeight.Medium,
                                color = Color.White,
                                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                            )
                        }
                    }
                }
            )
        }
    }
    Text(
        text = title,
        inlineContent = inline,
        fontSize = style.titleFontSize.sp,
        fontWeight = style.titleWeight,
        lineHeight = style.titleLineHeight.sp,
        style = TextStyle(
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.None
            )
        ),
        color = colors.textBody,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun TopicSearchBlurb(
    blurb: String,
    colors: TopicRowColors,
    searchHighlights: List<String>,
    style: TopicListStyle
) {
    val text = buildAnnotatedString {
        // 摘要非命中是常规字重，命中词升到 Medium 并染品牌色。
        appendSearchText(blurb, searchHighlights, colors.brand, emphasizeHit = true)
    }
    Text(
        text = text,
        fontSize = style.secondaryFontSize.sp,
        color = colors.textSecondary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth()
    )
}

private fun AnnotatedString.Builder.appendSearchText(
    text: String,
    highlights: List<String>,
    hitColor: Color,
    emphasizeHit: Boolean
) {
    for (segment in searchTextSegments(text, highlights)) {
        if (!segment.hit) {
            append(segment.text)
            continue
        }
        val style = if (emphasizeHit) {
            SpanStyle(color = hitColor, fontWeight = FontWeight.Medium)
        } else {
            SpanStyle(color = hitColor)
        }
        withStyle(style) { append(segment.text) }
    }
}

@Composable
private fun TopicMetadataRow(
    category: HomeCategory?,
    parentCategory: HomeCategory?,
    tags: List<HomeTag>,
    postedAt: String,
    colors: TopicRowColors,
    assetBaseUrl: String,
    style: TopicListStyle,
    badgesTappable: Boolean,
    onCategoryClick: ((HomeCategory) -> Unit)?,
    onTagClick: ((HomeTag) -> Unit)?
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (category != null || tags.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(20.dp)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                category?.let {
                    TopicCategoryBadge(it, parentCategory, colors, assetBaseUrl, style, badgesTappable, onCategoryClick)
                }
                val context = LocalContext.current
                val tagStyles = remember(context) { loadTagStyles(context) }
                tags.forEach { tag ->
                    TopicTagBadge(tag, tagStyles[tag.name.trim().lowercase()], colors, style, badgesTappable, onTagClick)
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (postedAt.isNotEmpty()) {
            Text(
                postedAt,
                color = colors.textSecondary,
                fontSize = style.secondaryFontSize.sp,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

@Composable
private fun TopicCategoryBadge(
    category: HomeCategory,
    parentCategory: HomeCategory?,
    colors: TopicRowColors,
    assetBaseUrl: String,
    style: TopicListStyle,
    tappable: Boolean,
    onClick: ((HomeCategory) -> Unit)?
) {
    val context = LocalContext.current
    val iconId = topicCategoryIcon(context, category, parentCategory)
    val logo = category.uploadedLogo?.takeIf(String::isNotBlank)
        ?: parentCategory?.uploadedLogo?.takeIf(String::isNotBlank)
    // 按下反馈与 TopicMetadataBadges 一致：整体 opacity 0.6，不是 Material ripple。
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .height(18.dp)
            .alpha(if (tappable && pressed) 0.6f else 1f)
            .then(
                if (tappable && onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null
                    ) { onClick(category) }
                } else {
                    Modifier
                }
            )
            .padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when {
            iconId != null -> FaIcon(iconId, parseTopicHexColor(category.color) ?: Color(0xFF6B7280), 10.sp, Modifier.padding(end = 3.dp))
            logo != null -> AsyncImage(
                model = resolveAsset(logo, assetBaseUrl),
                contentDescription = null,
                modifier = Modifier.padding(end = 3.dp).size(10.dp),
                contentScale = ContentScale.Fit
            )
            category.readRestricted -> FaIcon("lock", parseTopicHexColor(category.color) ?: Color(0xFF6B7280), 10.sp, Modifier.padding(end = 3.dp))
            else -> Box(
                modifier = Modifier
                    .padding(end = 5.dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(parseTopicHexColor(category.color) ?: Color(0xFF6B7280))
            )
        }
        Text(
            text = category.name,
            color = colors.textSecondary,
            fontSize = style.secondaryFontSize.sp,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
        )
    }
}

private data class TagStyle(val iconId: String, val color: Color)

@Composable
private fun TopicTagBadge(
    tag: HomeTag,
    tagStyle: TagStyle?,
    colors: TopicRowColors,
    style: TopicListStyle,
    tappable: Boolean,
    onClick: ((HomeTag) -> Unit)?
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .padding(end = 7.dp)
            .height(20.dp)
            .alpha(if (tappable && pressed) 0.6f else 1f)
            .clip(RoundedCornerShape(2.dp))
            .background(colors.surfaceMuted)
            .then(
                if (tappable && onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null
                    ) { onClick(tag) }
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (tagStyle != null && FaIconSet.has(LocalContext.current, tagStyle.iconId)) {
            FaIcon(tagStyle.iconId, tagStyle.color, 10.dp, Modifier.padding(end = 4.dp))
        }
        Text(
            text = tag.name,
            color = colors.textSecondary,
            fontSize = style.secondaryFontSize.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
        )
    }
}

internal fun topicCategoryIcon(
    context: Context,
    category: HomeCategory,
    parentCategory: HomeCategory?
): String? {
    fun find(value: String?): String? {
        val normalized = normalizeTopicIcon(value)
        if (normalized.isEmpty()) return null
        if (FaIconSet.has(context, normalized)) return normalized
        val mapped = when (normalized) {
            "file-code" -> "code"
            "award", "medal", "trophy" -> "trophy"
            "book-open-reader", "book-open", "book" -> "book-open-reader"
            "seedling", "leaf" -> "seedling"
            "file-lines", "doc", "newspaper" -> "file-lines"
            "clone", "database", "server" -> "hard-drive"
            "users", "user-group", "user" -> "users"
            "megaphone" -> "bullhorn"
            "comment" -> "comments"
            else -> null
        }
        return mapped?.takeIf { FaIconSet.has(context, it) }
    }
    return find(category.icon) ?: find(parentCategory?.icon)
}

internal fun normalizeTopicIcon(value: String?): String {
    if (value.isNullOrBlank()) return ""
    var result = ""
    value.trim().lowercase().split(Regex("\\s+")).forEach { token ->
        if (token !in setOf("fa-solid", "fa-regular", "fa-brands", "fas", "far", "fab", "fa")) {
            result = if (token.startsWith("fa-")) token.substring(3) else token
        }
    }
    return result
}

private fun loadTagStyles(context: Context): Map<String, TagStyle> = runCatching {
    val resourceId = context.resources.getIdentifier("linuxdo_tag_icons", "raw", context.packageName)
    if (resourceId == 0) return@runCatching emptyMap()
    val json = context.resources.openRawResource(resourceId).bufferedReader().use { it.readText() }
    val array = org.json.JSONArray(json)
    buildMap {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val name = item.optString("tagName").trim().lowercase()
            val icon = item.optString("iconId")
            val color = parseTopicHexColor(item.optString("color")) ?: Color(0xFF6B7280)
            if (name.isNotEmpty() && icon.isNotEmpty()) put(name, TagStyle(icon, color))
        }
    }
}.getOrDefault(emptyMap())

@Composable
fun TopicAvatar(
    user: HomeUser?,
    size: Dp,
    colors: TopicRowColors,
    avatarBaseUrl: String
) {
    val shape = if (user?.id == 1L) RoundedCornerShape(size * 0.1f) else CircleShape
    val initial = (user?.username ?: user?.name).orEmpty().firstOrNull()?.uppercase() ?: "?"
    // 动态头像开关（app 根部提供，默认开）：关闭时跳过动图候选 + 请求强制首帧解码。
    val animatedEnabled = LocalAnimatedAvatarEnabled.current
    val candidates = remember(user, avatarBaseUrl, animatedEnabled) {
        topicAvatarCandidates(user, avatarBaseUrl, includeAnimated = animatedEnabled)
    }
    var candidateIndex by remember(candidates) { mutableIntStateOf(0) }
    var showInitial by remember(candidates) { mutableStateOf(candidates.isEmpty()) }
    val avatarUrl = candidates.getOrNull(candidateIndex)

    Box(
        modifier = Modifier.size(size),
        // 徽章从右下角锚点外伸，和旧头像一致。
        contentAlignment = Alignment.BottomEnd
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(shape)
                .background(colors.surfaceMuted)
                .border(1.dp, colors.avatarBorder, shape),
            contentAlignment = Alignment.Center
        ) {
            if (showInitial) {
                Text(
                    text = initial,
                    color = colors.avatarFg,
                    fontSize = (size.value * 0.4f).coerceAtLeast(12f).sp,
                    fontWeight = FontWeight.Medium
                )
            }
            if (avatarUrl != null) {
                AsyncImage(
                    model = rememberAvatarImageModel(avatarUrl),
                    contentDescription = null,
                    modifier = Modifier.size(size).clip(shape),
                    contentScale = ContentScale.Crop,
                    onState = { state ->
                        if (state is AsyncImagePainter.State.Error) {
                            if (candidateIndex + 1 < candidates.size) candidateIndex++ else showInitial = true
                        }
                    }
                )
            }
        }
        val context = LocalContext.current
        val flairUrl = user?.flairUrl.orEmpty()
        val flairIsImage = flairUrl.startsWith("http://") ||
            flairUrl.startsWith("https://") || flairUrl.startsWith("//")
        val flairIsIcon = !flairIsImage && FaIconSet.has(context, flairUrl)
        val flairUser = user
        if (flairUser != null && size >= 32.dp && (flairIsImage || flairIsIcon)) {
            val badgeSize = maxOf(14.dp, size * 0.42f)
            val overhang = (badgeSize.value * 0.24f).roundToInt().dp
            val hasBackground = !flairUser.flairBgColor.isNullOrBlank()
            val background = if (hasBackground) {
                parseTopicHexColor(flairUser.flairBgColor.orEmpty()) ?: Color(0xFFBE123C)
            } else {
                Color.Transparent
            }
            val shape = if (hasBackground) {
                CircleShape
            } else {
                RoundedCornerShape((badgeSize.value * 0.18f).roundToInt().dp)
            }
            Box(
                modifier = Modifier
                    .size(badgeSize)
                    .offset(x = overhang, y = overhang)
                    .clip(shape)
                    .background(background),
                contentAlignment = Alignment.Center
            ) {
                if (flairIsImage) {
                    AsyncImage(
                        model = rememberAvatarImageModel(resolveAsset(flairUrl, avatarBaseUrl)),
                        contentDescription = flairUser.flairName,
                        modifier = Modifier
                            .size(badgeSize)
                            .padding(if (hasBackground) (badgeSize.value * 0.16f).roundToInt().dp else 0.dp),
                        contentScale = if (hasBackground) ContentScale.Fit else ContentScale.Crop
                    )
                } else {
                    FaIcon(
                        iconId = flairUrl,
                        color = parseTopicHexColor(flairUser.flairColor.orEmpty()) ?: Color.White,
                        size = (badgeSize.value * 0.58f).roundToInt().dp
                    )
                }
            }
        }
    }
}

fun parseTopicHexColor(value: String): Color? = runCatching {
    val hex = value.removePrefix("#")
    if (hex.length != 6) return@runCatching null
    Color(
        red = hex.substring(0, 2).toInt(16),
        green = hex.substring(2, 4).toInt(16),
        blue = hex.substring(4, 6).toInt(16)
    )
}.getOrNull()

private fun relativeTime(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    val match = Regex("^(\\d{4})-(\\d{2})-(\\d{2})[T ](\\d{2}):(\\d{2}):(\\d{2})").find(iso)
        ?: return ""
    val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(
            match.groupValues[1].toInt(),
            match.groupValues[2].toInt() - 1,
            match.groupValues[3].toInt(),
            match.groupValues[4].toInt(),
            match.groupValues[5].toInt(),
            match.groupValues[6].toInt()
        )
    }
    val minutes = round((System.currentTimeMillis() - calendar.timeInMillis) / 60_000.0)
        .toLong()
        .coerceAtLeast(0)
    return when {
        minutes < 1 -> "刚刚"
        minutes <= 44 -> "$minutes 分钟"
        minutes <= 89 -> "1 小时"
        minutes <= 1_409 -> "${round(minutes / 60.0).toLong()} 小时"
        minutes <= 2_519 -> "1 天"
        minutes <= 20_160 -> "${round(minutes / 1_440.0).toLong()} 天"
        else -> {
            val year = match.groupValues[1].toInt()
            val month = match.groupValues[2].toInt()
            val day = match.groupValues[3].toInt()
            if (year == Calendar.getInstance().get(Calendar.YEAR)) {
                "${month}月${day}日"
            } else {
                "${year}年${month}月${day}日"
            }
        }
    }
}
