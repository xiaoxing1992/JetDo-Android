package com.arkdo.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.arkdo.android.services.content.CookedHtmlParser
import com.arkdo.android.services.content.RichBlock
import com.arkdo.android.services.content.RichBlockType
import com.arkdo.android.services.content.RichInlineNode
import com.arkdo.android.services.content.RichInlineType
import com.arkdo.android.ui.theme.arkdoColors

// ==========================================================================
// 楼层正文渲染器，对齐鸿蒙 PostRichContent.ets：把 CookedHtmlParser 产出的
// 块/行内节点树渲染成 Compose。样式锚点取自鸿蒙（正文 15/22、行内代码 14、
// 标题 22/20/18、引用左 3dp 边框 + 弱化底色）。
//
// 首版范围：全块类型可渲染；交互按最小可用实现 —— 剧透点击揭示、callout/
// 折叠展开收起、链接走 uri 打开（LinkRouter 迁移后统一接管）、图片点击暂不进
// 灯箱、math 以等宽斜体占位（离屏 KaTeX 出图后续接）、音视频占位链接。
// ==========================================================================

private val BODY_FONT_SIZE = 15.sp
private val BODY_LINE_HEIGHT = 22.sp
private val INLINE_CODE_FONT_SIZE = 14.sp

private fun headingFontSize(level: Int) = when {
    level <= 1 -> 22.sp
    level == 2 -> 20.sp
    else -> 18.sp
}

private fun headingLineHeight(level: Int) = when {
    level <= 1 -> 28.sp
    level == 2 -> 26.sp
    else -> 24.sp
}

@Composable
fun PostRichContent(
    html: String,
    modifier: Modifier = Modifier,
    onLinkClick: (String) -> Unit = {}
) {
    val blocks = remember(html) { CookedHtmlParser.parse(html) }
    // 交互状态（剧透已揭示/折叠已展开）按块 key 记录，内容变化时随 remember 重建。
    val expandedKeys = remember(html) { mutableStateListOf<String>() }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        blocks.forEachIndexed { index, block ->
            BlockView(
                block = block,
                key = "$index",
                expandedKeys = expandedKeys,
                onLinkClick = onLinkClick
            )
        }
    }
}

@Composable
private fun BlockView(
    block: RichBlock,
    key: String,
    expandedKeys: MutableList<String>,
    onLinkClick: (String) -> Unit
) {
    when (block.type) {
        RichBlockType.PARAGRAPH -> InlineText(
            inlines = block.inlines.orEmpty(),
            onLinkClick = onLinkClick
        )

        RichBlockType.HEADING -> InlineText(
            inlines = block.inlines.orEmpty(),
            fontSize = headingFontSize(block.level ?: 2),
            lineHeight = headingLineHeight(block.level ?: 2),
            fontWeight = FontWeight.Bold,
            forcePrimary = true,
            onLinkClick = onLinkClick
        )

        RichBlockType.QUOTE -> QuoteBlock(block, key, expandedKeys, onLinkClick)

        RichBlockType.TOPIC_QUOTE -> TopicQuoteBlock(block, key, onLinkClick)

        RichBlockType.CALLOUT -> CalloutBlock(block, key, expandedKeys, onLinkClick)

        RichBlockType.CODE -> CodeBlock(block)

        RichBlockType.IMAGE -> ImageBlock(block, onLinkClick)

        RichBlockType.LIST -> ListBlock(block, key, expandedKeys, onLinkClick)

        RichBlockType.TABLE -> TableBlock(block)

        RichBlockType.SPOILER -> SpoilerBlock(block, key, expandedKeys, onLinkClick)

        RichBlockType.DISCLOSURE -> DisclosureBlock(block, key, expandedKeys, onLinkClick)

        RichBlockType.DIVIDER -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        RichBlockType.POLL -> Text(
            text = "投票 · ${block.pollName ?: "poll"}",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                .padding(12.dp)
        )

        RichBlockType.ONEBOX -> OneboxBlock(block, onLinkClick)

        RichBlockType.MATH -> Text(
            text = block.text.orEmpty(),
            fontFamily = FontFamily.Monospace,
            fontStyle = FontStyle.Italic,
            fontSize = 14.sp,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                .padding(8.dp)
        )

        RichBlockType.AUDIO, RichBlockType.VIDEO -> Text(
            text = (if (block.type == RichBlockType.VIDEO) "视频" else "音频") + "：" + block.src.orEmpty(),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                .padding(12.dp)
        )

        RichBlockType.UNSUPPORTED -> Text(
            text = block.text.orEmpty(),
            fontSize = 14.sp,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 引用：左侧 3dp 竖线 + 弱化底色，内部递归渲染子块。 */
@Composable
private fun QuoteBlock(
    block: RichBlock,
    key: String,
    expandedKeys: MutableList<String>,
    onLinkClick: (String) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(modifier = Modifier.width(3.dp).fillMaxHeight().background(arkdoColors.border))
        Column(
            modifier = Modifier
                .weight(1f)
                .background(arkdoColors.surfaceMuted)
                .padding(10.dp)
        ) {
            block.blocks.orEmpty().forEachIndexed { index, inner ->
                BlockView(inner, "$key-q$index", expandedKeys, onLinkClick)
            }
        }
    }
}

/** 话题/帖子引用卡：头像 + 标题 + 分类徽章头部，正文预览递归渲染，整卡可点跳转。 */
@Composable
private fun TopicQuoteBlock(
    block: RichBlock,
    key: String,
    onLinkClick: (String) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(modifier = Modifier.width(3.dp).fillMaxHeight().background(arkdoColors.border))
        Column(
            modifier = Modifier
                .weight(1f)
                .background(arkdoColors.surfaceMuted)
                .clickable { block.href?.let(onLinkClick) }
                .padding(10.dp)
        ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            block.quoteAvatarUrl?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    contentScale = ContentScale.Crop
                )
            }
            Column(modifier = Modifier.padding(start = 8.dp)) {
                block.quoteTitle?.let {
                    Text(it, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                block.quoteCategory?.let { category ->
                    Text(
                        category.name,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
            // 截断预览：引用 aside 内 blockquote 的内部 HTML，递归解析渲染。
            if (!block.text.isNullOrEmpty()) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    CookedHtmlParser.parse(block.text).forEachIndexed { index, inner ->
                        BlockView(inner, "$key-p$index", mutableStateListOf(), onLinkClick)
                    }
                }
            }
        }
    }
}

/** callout：主色左框 + 图标标题行（可折叠），正文递归渲染。 */
@Composable
private fun CalloutBlock(
    block: RichBlock,
    key: String,
    expandedKeys: MutableList<String>,
    onLinkClick: (String) -> Unit
) {
    val expanded = if (!block.collapsible) true else if (key in expandedKeys) !block.defaultExpanded else block.defaultExpanded
    val accent = parseCalloutColor(block.calloutColor)
    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(modifier = Modifier.width(3.dp).fillMaxHeight().background(accent))
        Column(
            modifier = Modifier
                .weight(1f)
                .background(accent.copy(alpha = 0.08f))
                .padding(10.dp)
        ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (block.collapsible) Modifier.clickable { toggle(expandedKeys, key) } else Modifier)
        ) {
            block.calloutIcon?.let { iconId ->
                FaIcon(iconId, accent, 16.dp, Modifier.padding(end = 6.dp))
            }
            InlineText(
                inlines = block.titleInlines.orEmpty(),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fallbackText = block.calloutKind,
                onLinkClick = onLinkClick
            )
        }
            if (expanded) {
                Column(modifier = Modifier.padding(top = 6.dp)) {
                    block.blocks.orEmpty().forEachIndexed { index, inner ->
                        BlockView(inner, "$key-c$index", expandedKeys, onLinkClick)
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(block: RichBlock) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        block.language?.let {
            Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            text = block.text.orEmpty(),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun ImageBlock(block: RichBlock, onLinkClick: (String) -> Unit) {
    AsyncImage(
        model = block.src,
        contentDescription = block.alt,
        contentScale = ContentScale.FillWidth,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onLinkClick(block.href ?: block.src.orEmpty()) }
    )
}

@Composable
private fun ListBlock(
    block: RichBlock,
    key: String,
    expandedKeys: MutableList<String>,
    onLinkClick: (String) -> Unit
) {
    Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
        block.items.orEmpty().forEachIndexed { index, item ->
            Row {
                Text(
                    text = if (block.ordered) "${index + 1}." else "•",
                    fontSize = BODY_FONT_SIZE,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 6.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    if (item.blocks != null) {
                        // 列表项含块级图：文字成段落、图片各自成块级 IMAGE。
                        InlineText(item.inlines, onLinkClick = onLinkClick)
                        item.blocks.forEachIndexed { bi, inner ->
                            BlockView(inner, "$key-l$index-$bi", expandedKeys, onLinkClick)
                        }
                    } else {
                        InlineText(item.inlines, onLinkClick = onLinkClick)
                    }
                }
            }
        }
    }
}

@Composable
private fun TableBlock(block: RichBlock) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
    ) {
        block.tableRows.orEmpty().forEachIndexed { rowIndex, row ->
            Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                row.cells.forEachIndexed { cellIndex, cell ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(8.dp)
                    ) {
                        InlineText(
                            inlines = cell.inlines,
                            fontSize = 13.sp,
                            fontWeight = if (row.header || cell.header) FontWeight.Bold else FontWeight.Normal,
                            lineHeight = 18.sp
                        )
                    }
                    if (cellIndex < row.cells.lastIndex) {
                        Box(
                            Modifier
                                .width(1.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant)
                        )
                    }
                }
            }
            if (rowIndex < block.tableRows.orEmpty().lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

/** 剧透：整块遮蔽，点击揭示（不可恢复遮蔽语义：揭示后保持可见，对齐 web 端）。 */
@Composable
private fun SpoilerBlock(
    block: RichBlock,
    key: String,
    expandedKeys: MutableList<String>,
    onLinkClick: (String) -> Unit
) {
    val revealed = key in expandedKeys
    if (revealed) {
        Column {
            block.blocks.orEmpty().forEachIndexed { index, inner ->
                BlockView(inner, "$key-s$index", expandedKeys, onLinkClick)
            }
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                .clickable { toggle(expandedKeys, key) }
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("已遮蔽内容，点击显示", fontSize = 13.sp, color = MaterialTheme.colorScheme.surface)
        }
    }
}

@Composable
private fun DisclosureBlock(
    block: RichBlock,
    key: String,
    expandedKeys: MutableList<String>,
    onLinkClick: (String) -> Unit
) {
    val expanded = toggleState(expandedKeys, key)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(arkdoColors.surfaceMuted, RoundedCornerShape(8.dp))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { toggle(expandedKeys, key) }
                .padding(10.dp)
        ) {
            Text(
                text = block.title ?: "详情",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Text(if (expanded) "收起" else "展开", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        }
        if (expanded) {
            Column(modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) {
                block.blocks.orEmpty().forEachIndexed { index, inner ->
                    BlockView(inner, "$key-d$index", expandedKeys, onLinkClick)
                }
            }
        }
    }
}

@Composable
private fun OneboxBlock(block: RichBlock, onLinkClick: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(arkdoColors.surfaceMuted, RoundedCornerShape(8.dp))
            .clickable { block.href?.let(onLinkClick) }
            .padding(12.dp)
    ) {
        block.domain?.let { domain ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                block.faviconUrl?.let { favicon ->
                    AsyncImage(
                        model = favicon,
                        contentDescription = null,
                        modifier = Modifier
                            .size(16.dp)
                            .padding(end = 0.dp)
                    )
                }
                Text(
                    domain,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
        block.title?.let {
            Text(
                it,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        block.description?.let {
            Text(
                it,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        block.thumbnailUrl?.let {
            AsyncImage(
                model = it,
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            )
        }
    }
}

private fun toggle(expandedKeys: MutableList<String>, key: String) {
    if (key in expandedKeys) expandedKeys.remove(key) else expandedKeys.add(key)
}

private fun toggleState(expandedKeys: MutableList<String>, key: String): Boolean = key in expandedKeys

private fun parseCalloutColor(color: String?): Color {
    if (color.isNullOrBlank()) return Color(0xFF027AFF)
    return runCatching {
        Color(android.graphics.Color.parseColor(color))
    }.getOrDefault(Color(0xFF027AFF))
}

// ==========================================================================
// 行内渲染：TEXT/LINK/CODE/MATH 进 AnnotatedString；行内图片（emoji 等）
// 走 inlineContent 占位以 ImageSpan 等价物内联。
// ==========================================================================

@Composable
private fun InlineText(
    inlines: List<RichInlineNode>,
    fontSize: androidx.compose.ui.unit.TextUnit = BODY_FONT_SIZE,
    lineHeight: androidx.compose.ui.unit.TextUnit = BODY_LINE_HEIGHT,
    fontWeight: FontWeight? = null,
    forcePrimary: Boolean = false,
    fallbackText: String? = null,
    onLinkClick: (String) -> Unit = {}
) {
    val colors = arkdoColors
    val uriHandler = LocalUriHandler.current

    val imageContents = mutableMapOf<String, InlineTextContent>()
    val text = buildAnnotatedString {
        if (inlines.isEmpty() && !fallbackText.isNullOrEmpty()) {
            append(fallbackText)
            return@buildAnnotatedString
        }
        inlines.forEachIndexed { index, token ->
            val base = SpanStyle(
                color = when {
                    token.type == RichInlineType.CODE || token.code -> colors.textBody
                    forcePrimary -> MaterialTheme.colorScheme.onSurface
                    else -> colors.textBody
                },
                fontWeight = when {
                    token.bold -> FontWeight.Bold
                    fontWeight != null -> fontWeight
                    else -> null
                },
                fontStyle = if (token.italic) FontStyle.Italic else null,
                background = if (token.type == RichInlineType.CODE || token.code) {
                    colors.surfaceMuted
                } else {
                    Color.Unspecified
                },
                fontFamily = if (token.type == RichInlineType.CODE || token.code) FontFamily.Monospace else null
            )
            when (token.type) {
                RichInlineType.LINK -> withLink(
                    LinkAnnotation.Clickable(
                        tag = token.href ?: "",
                        styles = TextLinkStyles(base.copy(color = colors.brand)),
                        linkInteractionListener = { token.href?.let(onLinkClick) }
                    )
                ) { append(token.text) }

                RichInlineType.CODE, RichInlineType.TEXT -> withStyle(base) {
                    append(token.text)
                }

                RichInlineType.MATH -> withStyle(
                    base.copy(fontFamily = FontFamily.Monospace, fontStyle = FontStyle.Italic, fontSize = fontSize * 0.9f)
                ) { append(token.text) }

                RichInlineType.LINE_BREAK -> append(token.text)

                RichInlineType.IMAGE -> {
                    val placeholderId = "img-$index"
                    val size = 18.sp
                    imageContents[placeholderId] = InlineTextContent(
                        androidx.compose.ui.text.Placeholder(size, size, androidx.compose.ui.text.PlaceholderVerticalAlign.Center)
                    ) {
                        AsyncImage(
                            model = token.src,
                            contentDescription = token.alt,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    appendInlineContent(placeholderId, token.text)
                }
            }
        }
    }

    Text(
        text = text,
        fontSize = fontSize,
        lineHeight = lineHeight,
        inlineContent = imageContents,
        modifier = Modifier.fillMaxWidth()
    )
}
