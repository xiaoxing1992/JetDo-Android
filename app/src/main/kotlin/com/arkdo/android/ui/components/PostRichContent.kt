package com.arkdo.android.ui.components

import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
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
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.ExperimentalSharedTransitionApi
import coil3.compose.AsyncImage
import com.arkdo.android.data.models.User
import com.arkdo.android.services.content.CookedHtmlParser
import com.arkdo.android.services.topic.clicksForHref
import com.arkdo.android.services.content.RichBlock
import com.arkdo.android.services.content.RichBlockType
import com.arkdo.android.services.content.RichInlineNode
import com.arkdo.android.services.content.RichInlineType
import com.arkdo.android.ui.theme.arkdoColors
import com.arkdo.android.ui.theme.isArkdoInDarkTheme
import com.arkdo.android.core.designsystem.FaIcon

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
    linkClicks: Map<String, Int>? = null,
    onOpenImages: (List<ViewerImage>, Int) -> Unit = { _, _ -> },
    onLinkClick: (String) -> Unit = {}
) {
    val blocks = remember(html) { CookedHtmlParser.parse(html) }
    // 本楼全部图片（原图 url + 优化图兜底），供图片浏览器整组翻页。
    val viewerImages = remember(blocks) { collectViewerImages(blocks) }
    // 链接点击数（随 post JSON 变动的动态数据，不参与块缓存）。
    // 交互状态（剧透已揭示/折叠已展开）按块 key 记录，内容变化时随 remember 重建。
    val expandedKeys = remember(html) { mutableStateListOf<String>() }
    // 有公式才挂离屏 MathJax 引擎(对应鸿蒙 MATH_HOST_ACTIVE_KEY 门控):
    // 无公式的楼不该白挂一个 WebView。
    val hasMath = remember(blocks) { blocks.any(::containsMath) }

    // 离屏引擎按楼门控;渲染请求由 MathBlock/行内公式各自异步发起,产物走全局缓存。
    MathHostGate(hasMath = hasMath)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        blocks.forEachIndexed { index, block ->
            BlockView(
                block = block,
                key = "$index",
                expandedKeys = expandedKeys,
                linkClicks = linkClicks,
                viewerImages = viewerImages,
                onOpenImages = onOpenImages,
                onLinkClick = onLinkClick
            )
        }
    }
}

/**
 * 归一化行内文本:把行内链路上仅剩的直引号实体还原成用户手打的直引号。
 *
 * 成因:markdown-it 会把正文里的 " 统一转义成 &quot; 塞进 HTML(测试见 markdown-it 产物),
 * 而 InlineHtmlParser 遵循「jsoup 已解码、不再二次解码」的原则,TextNode 里保留下来的就是
 * 字面 &quot; —— 在标题/正文/链接文本里原样显示成 "双引号"。这里在渲染出口兜底还原。
 *
 * 只动直引号与撇号这一个子集:中文语境最常踩的就是「引号显示成双引号」;nbsp、&lt;/&gt;/&amp;
 * 保持实体语义不动(它们应在更早的解析层按各自的语义消费)。
 */
private fun normalizeTextQuotes(raw: String): String {
    if (raw.isEmpty()) return raw
    return raw
        .replace("&quot;", "\"")
        .replace("&#34;", "\"")
        .replace("\u201C", "\"")
        .replace("\u201D", "\"")
        .replace("\u2018", "'")
        .replace("\u2019", "'")
}

/**
 * 行内文本统一入口:所有从富文本/源码来的文本(RichInlineNode.text、纯文本兜底、代码块、
 * onebox 标题/摘要等)先过 [normalizeTextQuotes] 再做 Compose 文本,避免渲染链路各层各写一遍。
 */
internal fun plainNodeText(raw: String): String = normalizeTextQuotes(raw)

@Composable
private fun BlockView(
    block: RichBlock,
    key: String,
    expandedKeys: MutableList<String>,
    linkClicks: Map<String, Int>? = null,
    viewerImages: List<ViewerImage> = emptyList(),
    onOpenImages: (List<ViewerImage>, Int) -> Unit = { _, _ -> },
    onLinkClick: (String) -> Unit
) {
    when (block.type) {
        RichBlockType.PARAGRAPH -> InlineText(
            inlines = block.inlines.orEmpty(),
            linkClicks = linkClicks,
            onLinkClick = onLinkClick
        )

        RichBlockType.HEADING -> InlineText(
            inlines = block.inlines.orEmpty(),
            fontSize = headingFontSize(block.level ?: 2),
            lineHeight = headingLineHeight(block.level ?: 2),
            fontWeight = FontWeight.Bold,
            forcePrimary = true,
            linkClicks = linkClicks,
            onLinkClick = onLinkClick
        )

        RichBlockType.QUOTE -> QuoteBlock(block, key, expandedKeys, linkClicks, viewerImages, onOpenImages, onLinkClick)

        RichBlockType.TOPIC_QUOTE -> TopicQuoteBlock(block, key, onLinkClick)

        RichBlockType.CALLOUT -> CalloutBlock(block, key, expandedKeys, linkClicks, viewerImages, onOpenImages, onLinkClick)

        RichBlockType.CODE -> CodeBlock(block)

        RichBlockType.IMAGE -> ImageBlock(block, viewerImages, onOpenImages)

        RichBlockType.LIST -> ListBlock(block, key, expandedKeys, linkClicks, viewerImages, onOpenImages, onLinkClick)

        RichBlockType.TABLE -> TableBlock(block)

        RichBlockType.SPOILER -> SpoilerBlock(block, key, expandedKeys, linkClicks, viewerImages, onOpenImages, onLinkClick)

        RichBlockType.DISCLOSURE -> DisclosureBlock(block, key, expandedKeys, linkClicks, viewerImages, onOpenImages, onLinkClick)

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

        // discourse-math 块级公式:离屏 MathJax 出图后居中;未就绪/失败退回裸 LaTeX。
        RichBlockType.MATH -> MathBlock(latex = block.text.orEmpty())

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
            text = plainNodeText(block.text.orEmpty()),
            fontSize = 14.sp,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 引用：左侧 3dp 竖线 + 弱化底色，内部递归渲染子块。 */
/** 引用:左侧 3dp 竖线 + 弱化底色,内部递归渲染子块。对齐鸿蒙 QuoteBlock:背景 post_content_surface_bg
 *  (亮 #E6E8EB / 暗 #23262C),左条 post_content_border(更深一档)。此前误用 surfaceMuted(浅)+border(更浅)。 */
@Composable
private fun QuoteBlock(
    block: RichBlock,
    key: String,
    expandedKeys: MutableList<String>,
    linkClicks: Map<String, Int>? = null,
    viewerImages: List<ViewerImage> = emptyList(),
    onOpenImages: (List<ViewerImage>, Int) -> Unit = { _, _ -> },
    onLinkClick: (String) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(QuoteAccent(bar = true))
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .background(QuoteAccent(bar = false))
                .padding(10.dp)
        ) {
            block.blocks.orEmpty().forEachIndexed { index, inner ->
                BlockView(inner, "$key-q$index", expandedKeys, linkClicks, viewerImages, onOpenImages, onLinkClick)
            }
        }
    }
}

/** 引用块配色:亮/暗分别对齐鸿蒙 post_content_border(左条)与 post_content_surface_bg(背景)。 */
@androidx.compose.runtime.ReadOnlyComposable
@androidx.compose.runtime.Composable
private fun QuoteAccent(bar: Boolean): Color {
    val dark = isArkdoInDarkTheme()
    return if (bar) {
        if (dark) Color(0xFF454B54) else Color(0xFFC7CDD5)
    } else {
        if (dark) Color(0xFF23262C) else Color(0xFFE6E8EB)
    }
}

/** 话题/帖子引用卡:头像 + 标题 + 分类徽章头部,正文预览递归渲染,整卡可点跳转。 */
@Composable
private fun TopicQuoteBlock(
    block: RichBlock,
    key: String,
    onLinkClick: (String) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(QuoteAccent(bar = true))
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .background(QuoteAccent(bar = false))
                .clickable { block.href?.let(onLinkClick) }
                .padding(10.dp)
        ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 引用卡头像与列表/楼层共用全局 UserAvatar（动图候选 + 首字母回退），尺寸外部控制。
            UserAvatar(
                user = User(
                    id = 0,
                    username = block.quoteAuthor ?: "",
                    animatedAvatar = block.quoteAvatarUrl
                ),
                avatarSize = 24.dp,
                showFlair = false
            )
            Column(modifier = Modifier.padding(start = 8.dp)) {
                block.quoteTitle?.let {
                    Text(plainNodeText(it), fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
                        BlockView(inner, "$key-p$index", mutableStateListOf(), null, emptyList(), { _, _ -> }, onLinkClick)
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
    linkClicks: Map<String, Int>? = null,
    viewerImages: List<ViewerImage> = emptyList(),
    onOpenImages: (List<ViewerImage>, Int) -> Unit = { _, _ -> },
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
                        BlockView(inner, "$key-c$index", expandedKeys, linkClicks, viewerImages, onOpenImages, onLinkClick)
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
            text = plainNodeText(block.text.orEmpty()),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun ImageBlock(
    block: RichBlock,
    viewerImages: List<ViewerImage>,
    onOpenImages: (List<ViewerImage>, Int) -> Unit
) {
    val src = block.src.orEmpty()
    val url = block.href?.takeIf { it.isNotEmpty() } ?: src
    val index = viewerImages.indexOfFirst { it.url == url && it.fallbackUrl == src }
    // 缩略图挂共享元素（与浏览器当前页大图同 key）：开/关时框架做飞入飞出。
    val sts = LocalSharedTransitionScope.current
    val avs = LocalAnimatedVisibilityScope.current
    val imageModifier = if (sts != null && avs != null) {
        with(sts) {
            Modifier.sharedElement(
                rememberSharedContentState(key = viewerImageKey(src)),
                animatedVisibilityScope = avs
            )
        }
    } else {
        Modifier
    }
    AsyncImage(
        model = src,
        contentDescription = block.alt,
        contentScale = ContentScale.FillWidth,
        modifier = imageModifier
            .fillMaxWidth()
            .clickable { onOpenImages(viewerImages, index.coerceAtLeast(0)) }
    )
}

@Composable
private fun ListBlock(
    block: RichBlock,
    key: String,
    expandedKeys: MutableList<String>,
    linkClicks: Map<String, Int>? = null,
    viewerImages: List<ViewerImage> = emptyList(),
    onOpenImages: (List<ViewerImage>, Int) -> Unit = { _, _ -> },
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
                            BlockView(inner, "$key-l$index-$bi", expandedKeys, linkClicks, viewerImages, onOpenImages, onLinkClick)
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
    linkClicks: Map<String, Int>? = null,
    viewerImages: List<ViewerImage> = emptyList(),
    onOpenImages: (List<ViewerImage>, Int) -> Unit = { _, _ -> },
    onLinkClick: (String) -> Unit
) {
    val revealed = key in expandedKeys
    if (revealed) {
        Column {
            block.blocks.orEmpty().forEachIndexed { index, inner ->
                BlockView(inner, "$key-s$index", expandedKeys, linkClicks, viewerImages, onOpenImages, onLinkClick)
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
    linkClicks: Map<String, Int>? = null,
    viewerImages: List<ViewerImage> = emptyList(),
    onOpenImages: (List<ViewerImage>, Int) -> Unit = { _, _ -> },
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
                text = plainNodeText(block.title ?: "详情"),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Text(if (expanded) "收起" else "展开", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        }
        if (expanded) {
            Column(modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) {
                block.blocks.orEmpty().forEachIndexed { index, inner ->
                    BlockView(inner, "$key-d$index", expandedKeys, linkClicks, viewerImages, onOpenImages, onLinkClick)
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
                plainNodeText(it),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        block.description?.let {
            Text(
                plainNodeText(it),
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
// 行内渲染:TEXT/LINK/CODE/MATH 进 AnnotatedString;行内图片(emoji 等)
// 走 inlineContent 占位以 ImageSpan 等价物内联。
// 非 private:boost 气泡等短文混排(文字 + emoji 图片)复用同一渲染。
// ==========================================================================

@Composable
internal fun InlineText(
    inlines: List<RichInlineNode>,
    fontSize: androidx.compose.ui.unit.TextUnit = BODY_FONT_SIZE,
    lineHeight: androidx.compose.ui.unit.TextUnit = BODY_LINE_HEIGHT,
    fontWeight: FontWeight? = null,
    forcePrimary: Boolean = false,
    fallbackText: String? = null,
    linkClicks: Map<String, Int>? = null,
    onLinkClick: (String) -> Unit = {},
    maxLines: Int = Int.MAX_VALUE,
    overflow: androidx.compose.ui.text.style.TextOverflow = androidx.compose.ui.text.style.TextOverflow.Clip,
    textModifier: Modifier = Modifier.fillMaxWidth()
) {
    val colors = arkdoColors
    val uriHandler = LocalUriHandler.current
    val isDark = isArkdoInDarkTheme()
    // 行内代码：咖色字 #B45309 + 14sp 等宽，无背景色（以用户实机鸿蒙观感为准）。
    val codeColor = Color(0xFFB45309)
    val iconMutedColor = if (isDark) Color(0xFF5C626B) else Color(0xFFBDBDBD)

    val imageContents = mutableMapOf<String, InlineTextContent>()
    val text = buildAnnotatedString {
        if (inlines.isEmpty() && !fallbackText.isNullOrEmpty()) {
            append(plainNodeText(fallbackText))
            return@buildAnnotatedString
        }
        inlines.forEachIndexed { index, token ->
            val isCode = token.type == RichInlineType.CODE || token.code
            val base = SpanStyle(
                color = if (isCode) codeColor else colors.textBody,
                fontWeight = when {
                    token.bold -> FontWeight.Bold
                    fontWeight != null -> fontWeight
                    else -> null
                },
                fontStyle = if (token.italic) FontStyle.Italic else null,
                background = Color.Unspecified,
                fontFamily = if (isCode) FontFamily.Monospace else null,
                fontSize = if (isCode) 14.sp else TextUnit.Unspecified
            )
            when (token.type) {
                RichInlineType.LINK -> {
                    // 附件下载链接：前置下载图标（鸿蒙用烘蓝 download_blue.svg，这里等价染色）。
                    if (token.attachment) {
                        val downloadId = "dl-$index"
                        imageContents[downloadId] = InlineTextContent(
                            androidx.compose.ui.text.Placeholder(
                                16.sp, 16.sp, androidx.compose.ui.text.PlaceholderVerticalAlign.Center
                            )
                        ) {
                            FaIcon("download", colors.brand, 14.dp)
                        }
                        appendInlineContent(downloadId, " ")
                    }
                    withLink(
                        LinkAnnotation.Clickable(
                            tag = token.href ?: "",
                            styles = TextLinkStyles(
                                base.copy(color = colors.brand, background = Color.Unspecified, fontFamily = null, fontSize = TextUnit.Unspecified)
                            ),
                            linkInteractionListener = { token.href?.let(onLinkClick) }
                        )
                    ) { append(plainNodeText(token.text)) }

                    // 链接点击数角标（对齐官方 span.badge-notification.clicks）：0 次不显示。
                    val clicks = clicksForHref(linkClicks, token.href)
                    if (clicks > 0) {
                        append(" ")
                        val badgeId = "clicks-$index"
                        val digits = clicks.toString().length
                        val badgeFg = if (isDark) Color(0xFF9A9A9A) else Color(0xFF767676)
                        val badgeBg = if (isDark) Color(0xFF2B2B2B) else Color(0xFFE6E8EB)
                        imageContents[badgeId] = InlineTextContent(
                            androidx.compose.ui.text.Placeholder(
                                (16 + digits * 9).sp, BODY_LINE_HEIGHT,
                                androidx.compose.ui.text.PlaceholderVerticalAlign.Center
                            )
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth()
                                    .background(badgeBg, RoundedCornerShape(11.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = clicks.toString(),
                                    fontSize = 14.sp,
                                    lineHeight = 22.sp,
                                    color = badgeFg,
                                    maxLines = 1
                                )
                            }
                        }
                        appendInlineContent(badgeId, " ${clicks} ")
                    }
                }

                RichInlineType.CODE, RichInlineType.TEXT -> withStyle(base) {
                    append(plainNodeText(token.text))
                }

                // discourse-math 行内公式:出图后用 InlineTextContent 内嵌,基线按 depth 下沉;
                // 未就绪/失败退回等宽裸文本(与块级同一取舍:宁可显示源码也不留白)。
                RichInlineType.MATH -> {
                    // 出图后用 InlineTextContent 内嵌,未就绪/失败退回等宽裸文本
                    // (与块级同一取舍:宁可显示源码也不留白)。
                    val mathState = inlineMathPlaceholder(token.text)
                    if (mathState is MathState.Ready) {
                        val image = mathState.image
                        val placeholderId = "math-$index"
                        imageContents[placeholderId] = InlineTextContent(
                            androidx.compose.ui.text.Placeholder(
                                width = image.cssWidth.sp,
                                height = image.cssHeight.sp,
                                // 行内公式按基线对齐:depth 是基线以下的下沉量,
                                // TextBottom 让下缘贴住文本基线(近似官方 inline math 观感)。
                                placeholderVerticalAlign = androidx.compose.ui.text.PlaceholderVerticalAlign.TextBottom
                            )
                        ) {
                            Image(
                                bitmap = image.bitmap.asImageBitmap(),
                                contentDescription = token.text,
                                modifier = Modifier
                                    .width(image.cssWidth.dp)
                                    .height(image.cssHeight.dp),
                                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(colors.textPrimary)
                            )
                        }
                        appendInlineContent(placeholderId, token.text)
                    } else {
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                color = colors.textSecondary,
                                fontSize = 14.sp
                            )
                        ) { append(token.text) }
                    }
                }

                RichInlineType.LINE_BREAK -> append(if (token.paragraphBreak) "\n\n" else token.text)

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
        maxLines = maxLines,
        overflow = overflow,
        modifier = textModifier
    )
}

/** 递归收集本楼全部块级图片（IMAGE 块），供图片浏览器整组翻页。 */
private fun collectViewerImages(blocks: List<RichBlock>): List<ViewerImage> {
    val out = mutableListOf<ViewerImage>()
    fun walk(list: List<RichBlock>) {
        for (block in list) {
            val src = block.src
            if (block.type == RichBlockType.IMAGE && !src.isNullOrEmpty()) {
                out.add(
                    ViewerImage(
                        url = block.href?.takeIf { it.isNotEmpty() } ?: src,
                        fallbackUrl = src
                    )
                )
            }
            block.blocks?.let(::walk)
            block.items?.forEach { item -> item.blocks?.let(::walk) }
        }
    }
    walk(blocks)
    return out
}

/**
 * 递归检测块树里是否含公式(块级 [RichBlockType.MATH] 或任意层级的行内 MATH token)。
 *
 * 遍历口径必须与渲染侧一致 —— 漏检会导致「有公式却不挂引擎」,公式永远停在裸 LaTeX。
 */
private fun containsMath(block: RichBlock): Boolean {
    if (block.type == RichBlockType.MATH) return true
    // inlines/titleInlines/items/tableRows/blocks 都是可空字段(不同类型块只填各自那几项)。
    if (block.inlines.orEmpty().any { it.type == RichInlineType.MATH }) return true
    if (block.titleInlines.orEmpty().any { it.type == RichInlineType.MATH }) return true
    if (block.items.orEmpty().any { item ->
            item.inlines.orEmpty().any { it.type == RichInlineType.MATH } ||
                item.blocks.orEmpty().any(::containsMath)
        }
    ) return true
    if (block.tableRows.orEmpty().any { row ->
            row.cells.any { cell -> cell.inlines.orEmpty().any { it.type == RichInlineType.MATH } }
        }
    ) return true
    return block.blocks.orEmpty().any(::containsMath)
}
