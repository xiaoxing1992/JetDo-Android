package com.arkdo.android.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkdo.android.data.models.TopicPost
import com.arkdo.android.data.models.User
import com.arkdo.android.ui.utils.RelativeTime
import com.arkdo.android.ui.theme.arkdoColors

// ==========================================================================
// 楼层卡片，1:1 对齐鸿蒙 views/components/TopicPostItem.ets 的结构与几何。
//
// 本切片覆盖：整体骨架（外层 padding top 12 / 正文区左右 10 / 底部 1dp 分隔 /
// 落地高亮背景）、作者头部（头像 40 + 昵称 16 Medium + 金色徽章 + 状态表情；
// 第二行 用户名 13 tertiary + 头衔 13；「回复别人」指示器；右侧时间 13 +
// 未读蓝点 8dp #66CCFF 随已读渐隐）、正文（margin top 12）、TOPIC OWNER 标记、
// 页脚（回应汇总 + 点赞/回复/更多→拷贝链接+书签 38dp 透明按钮）、「上次访问」红线。
//
// 待后续切片（源文件对应部分）：boost 行与火箭、被回复列表展开区、表情长按
// 选择面板（短按 heart 已接）、shared issue「俺也一样」、采纳答案面板、
// 拷贝画勾动画（先以 Snackbar 代替）。回调参数位已按源预留，接一个亮一个。
// ==========================================================================

/** 昵称后徽章图标（FontAwesome 单色）统一染金色，明暗底皆成立。 */
private const val BADGE_GOLD = "#E5A50A"

/** 未读蓝点：语义强调色，明暗底都成立（源注释：勿映射主题色，白天会变深蓝）。 */
private const val UNREAD_DOT_BLUE = "#66CCFF"

/** 点赞红 / 成功绿（Palette.LIKE_RED / SUCCESS_GREEN）。 */
private const val LIKE_RED = "#EF4444"

/** 「上次访问」红线色值（对齐官网 last visit #e45735 系）。 */
private const val LAST_VISIT_RED = "#E45735"

/** 楼主标记色（对齐官网 .cooked::after 的 #00AEFF）。 */
private const val TOPIC_OWNER_BLUE = "#00AEFF"

/**
 * 楼层卡片。
 *
 * [topicCreatedById]：楼主用户 id，本楼作者等于它时渲染「TOPIC OWNER」标记；
 * [showLastVisitedLine]：本楼是进入时快照的最后一条已读楼，底边渲染红线；
 * [flashTargetPostId]：跳楼目标楼 id，等于本楼 id 时背景短暂高亮再渐隐；
 * [onToggleReaction]/[onToggleBookmark]：请求先行由页面发网络，成功后再改本地显示。
 */
@Composable
fun TopicPostItem(
    post: TopicPost,
    modifier: Modifier = Modifier,
    topicCreatedById: Long = -1,
    showLastVisitedLine: Boolean = false,
    flashTargetPostId: Long? = null,
    onOpenLink: (String) -> Unit = {},
    onOpenUser: (User) -> Unit = {},
    onReply: (TopicPost) -> Unit = {},
    onToggleReaction: (TopicPost, String, onDone: (Boolean) -> Unit) -> Unit = { _, _, done -> done(false) },
    onToggleBookmark: (TopicPost, onDone: (Boolean) -> Unit) -> Unit = { _, done -> done(false) },
    onCopyPostLink: (TopicPost) -> String? = { null }
) {
    val colors = arkdoColors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    // 落地高亮：目标楼背景淡蓝 600ms 渐隐回透明（深色底用深蓝防刺眼，同源逻辑）。
    var flashActive by remember(post.id) { mutableStateOf(false) }
    LaunchedEffect(flashTargetPostId) {
        if (flashTargetPostId != null && flashTargetPostId == post.id) {
            flashActive = true
            kotlinx.coroutines.delay(600)
            flashActive = false
        }
    }
    val flashBg by animateFloatAsState(
        targetValue = if (flashActive) 1f else 0f,
        animationSpec = tween(durationMillis = 600),
        label = "flash"
    )
    val flashColor = MaterialFlashColor(flashBg)

    // 点赞请求进行中：按钮转圈防重复点击（请求先行，不做乐观更新）。
    var reactionLoading by remember(post.id) { mutableStateOf(false) }
    var bookmarkLoading by remember(post.id) { mutableStateOf(false) }
    var moreExpanded by remember(post.id) { mutableStateOf(false) }
    // 书签本地显示态：点击乐观翻转，再用页面回传态校正。
    var bookmarkedLocal by remember(post.id) { mutableStateOf(post.bookmarked == true) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(flashColor)
            .padding(top = 12.dp)
            .let { if (!showLastVisitedLine) it else it }
    ) {
        // 正文区：左右留白只加在这层，红线才能通栏铺满。
        Column(modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp)) {
            PostAuthorHeader(post = post, onOpenUser = onOpenUser)

            PostRichContent(
                html = post.cookedHtml,
                linkClicks = post.linkClicks,
                onLinkClick = onOpenLink,
                modifier = Modifier.padding(top = 12.dp)
            )

            // 楼主标记：本楼作者 = 主题作者时，正文末尾右下角「TOPIC OWNER」
            // （对齐官网 .cooked::after：小号、斜体、加粗、#00AEFF、右浮动）。
            if (topicCreatedById > 0 && post.userId == topicCreatedById) {
                Text(
                    text = "TOPIC OWNER",
                    fontSize = 13.sp,
                    fontStyle = FontStyle.Italic,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00AEFF),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                )
            }

            PostFooter(
                post = post,
                reactionLoading = reactionLoading,
                bookmarkLoading = bookmarkLoading,
                bookmarkedLocal = bookmarkedLocal,
                moreExpanded = moreExpanded,
                onLikeToggle = {
                    if (reactionLoading) return@PostFooter
                    reactionLoading = true
                    onToggleReaction(post, "heart") { success ->
                        reactionLoading = false
                    }
                },
                onReply = { onReply(post) },
                onMoreExpand = { moreExpanded = true },
                onCopyLink = {
                    val url = onCopyPostLink(post)
                    if (url != null) {
                        clipboard.setText(AnnotatedString(url))
                        android.widget.Toast
                            .makeText(context, "已拷贝链接", android.widget.Toast.LENGTH_SHORT)
                            .show()
                    }
                },
                onBookmarkToggle = {
                    if (bookmarkLoading) return@PostFooter
                    bookmarkLoading = true
                    // 乐观翻转，页面回传最终态校正。
                    bookmarkedLocal = !bookmarkedLocal
                    onToggleBookmark(post) { finalBookmarked ->
                        bookmarkLoading = false
                        bookmarkedLocal = finalBookmarked
                    }
                }
            )
        }

        // 上次访问红线：通栏，画在本楼底边（最后一条已读楼）。
        if (showLastVisitedLine) {
            LastVisitedDivider()
        } else {
            HorizontalDivider(color = colors.border, thickness = 1.dp)
        }
    }
}

/** 落地高亮背景色：alpha 由 1→0 渐隐（同色系零透明度，避免过路半透明灰）。 */
@Composable
private fun MaterialFlashColor(alpha: Float): Color =
    androidx.compose.material3.MaterialTheme.colorScheme.primary.copy(alpha = 0.10f * alpha)

/** 作者头部：头像(40) + 昵称行(昵称 16 Medium + 状态表情 + 金色徽章) / 用户名行(13 tertiary)。 */
@Composable
private fun PostAuthorHeader(post: TopicPost, onOpenUser: (User) -> Unit) {
    val colors = arkdoColors
    val author = User(
        id = post.userId ?: 0,
        username = post.username ?: "",
        name = post.name,
        avatarTemplate = post.avatarTemplate,
        animatedAvatar = post.animatedAvatar,
        flairUrl = post.flairUrl,
        flairBgColor = post.flairBgColor,
        flairColor = post.flairColor,
        flairName = post.flairName
    )

    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        UserAvatar(
            user = author,
            avatarSize = 40.dp,
            showFlair = true,
            modifier = Modifier
                .padding(end = 12.dp)
                .clickable { onOpenUser(author) }
        )

        Column(modifier = Modifier.weight(1f)) {
            // 第一行：昵称（过长省略）+ 状态表情 + 金色徽章图标。
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = author.displayName,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clickable { onOpenUser(author) }
                )
                post.statusEmoji?.let { emoji ->
                    Text(
                        text = emoji,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
                post.badgeIcons.orEmpty().forEach { iconId ->
                    FaIcon(
                        iconId = iconId,
                        color = parseHexColor(BADGE_GOLD),
                        size = 15.dp,
                        modifier = Modifier.padding(start = 5.dp)
                    )
                }
            }

            // 第二行：用户名 + 头衔（13 tertiary，top 3）。
            Row(modifier = Modifier.padding(top = 3.dp)) {
                Text(
                    text = post.username ?: "",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                post.userTitle?.let { title ->
                    Text(
                        text = title,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = if (post.username.isNullOrEmpty()) 0.dp else 6.dp)
                    )
                }
            }
        }

        // 「回复别人」指示器：share 图标 + 被回复者头像(20)，点击定位被回复楼层。
        if (post.replyToUser != null && post.replyToPostNumber != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clickable { /* onJumpToPost：页面接线后接通 */ }
            ) {
                FaIcon("share", MaterialTheme.colorScheme.onSurfaceVariant, 16.dp)
                UserAvatar(user = post.replyToUser, avatarSize = 20.dp, showFlair = false)
            }
        }

        // 右上角：时间 + 未读小蓝点（读后渐隐；蓝点常驻占位，时间不位移）。
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp)) {
            Text(
                text = RelativeTime.fromNow(post.createdAt),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val read = post.read == true
            val dotAlpha by animateFloatAsState(
                targetValue = if (read) 0f else 1f,
                animationSpec = tween(durationMillis = if (read) 1200 else 0),
                label = "unreadDot"
            )
            Box(
                modifier = Modifier
                    .padding(start = 2.dp)
                    .size(8.dp)
                    .alpha(dotAlpha)
                    .clip(CircleShape)
                    .background(parseHexColor(UNREAD_DOT_BLUE))
            )
        }
    }
}

/** 楼层页脚：左 = 回应汇总（人数 16）；右 = 点赞 / 更多(拷贝链接+书签) / 回复（38dp 透明按钮）。 */
@Composable
private fun PostFooter(
    post: TopicPost,
    reactionLoading: Boolean,
    bookmarkLoading: Boolean,
    bookmarkedLocal: Boolean,
    moreExpanded: Boolean,
    onLikeToggle: () -> Unit,
    onReply: () -> Unit,
    onMoreExpand: () -> Unit,
    onCopyLink: () -> Unit,
    onBookmarkToggle: () -> Unit
) {
    val colors = arkdoColors
    // icon_muted（浅色 #BDBDBD，源 Palette 注释值）：arkdoColors 尚未收录该 token。
    val iconMuted = parseHexColor("#BDBDBD")
    Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左组：表情回应汇总（无回应不渲染，避免占位空隙）。
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                val reactions = post.reactions.orEmpty().filter { it.count > 0 }
                if (reactions.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)
                    ) {
                        reactions.forEach { reaction ->
                            // EmojiRegistry 迁移前先以短名文字占位（heart/smile 等回应名）。
                            Text(
                                text = reaction.id,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 2.dp)
                            )
                        }
                        Text(
                            text = "${post.reactionUsersCount ?: 0}",
                            fontSize = 16.sp,
                            // 浅色下 icon_muted 对白底对比不达标，单列 reaction_count_fg #888888。
                            color = parseHexColor("#888888"),
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }

            // 右组：点赞(自己的楼换占位空) + 更多 + 回复。
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 待 TopicPost.yours/canEdit 字段迁移后补编辑分支；当前统一渲染点赞。
                ActionButton(enabled = !reactionLoading, onClick = onLikeToggle) {
                    if (reactionLoading) {
                        LoadingDot()
                    } else {
                        FaIcon("heart", parseHexColor(LIKE_RED), 16.dp)
                    }
                }
                if (moreExpanded) {
                    ActionButton(onClick = onCopyLink) {
                        FaIcon("link", iconMuted, 16.dp)
                    }
                    ActionButton(enabled = !bookmarkLoading, onClick = onBookmarkToggle) {
                        if (bookmarkLoading) {
                            LoadingDot()
                        } else if (bookmarkedLocal) {
                            FaIcon("bookmark", parseHexColor(BADGE_GOLD), 16.dp)
                        } else {
                            FaIcon("bookmark", iconMuted, 16.dp)
                        }
                    }
                } else {
                    ActionButton(onClick = onMoreExpand) {
                        FaIcon("ellipsis", iconMuted, 16.dp)
                    }
                }
                ActionButton(onClick = onReply) {
                    FaIcon("reply", MaterialTheme.colorScheme.onSurfaceVariant, 16.dp)
                }
            }
        }
    }
}

/** 38x38 透明底圆角(10)操作按钮，图标 16 居中（对齐鸿蒙按钮几何）。 */
@Composable
private fun ActionButton(
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/** 请求进行中的小转圈占位（LoadingProgress 的过渡实现）。 */
@Composable
private fun LoadingDot() {
    Box(
        modifier = Modifier
            .size(14.dp)
            .clip(CircleShape)
            .background(parseHexColor("#BDBDBD").copy(alpha = 0.4f))
    )
}

/** 「上次访问」红色分隔线：两侧细线夹居中文字，通栏。 */
@Composable
private fun LastVisitedDivider() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = parseHexColor(LAST_VISIT_RED),
            thickness = 1.dp
        )
        Text(
            text = "上次访问",
            fontSize = 12.sp,
            color = parseHexColor(LAST_VISIT_RED),
            modifier = Modifier.padding(start = 10.dp, end = 10.dp)
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = parseHexColor(LAST_VISIT_RED),
            thickness = 1.dp
        )
    }
}

private fun parseHexColor(hex: String): Color =
    Color(android.graphics.Color.parseColor(hex))
