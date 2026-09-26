package com.arkdo.android.ui.components

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import coil3.compose.AsyncImage
import com.arkdo.android.data.models.AcceptedAnswer
import com.arkdo.android.data.models.PostBoost
import com.arkdo.android.services.content.parseInlines
import com.arkdo.android.services.content.getEmojiUrl
import com.arkdo.android.data.models.SharedIssueResult
import com.arkdo.android.data.models.TopicPost
import com.arkdo.android.data.models.User
import com.arkdo.android.ui.theme.arkdoColors
import com.arkdo.android.ui.theme.isArkdoInDarkTheme
import com.arkdo.android.core.common.RelativeTime
import com.arkdo.android.core.designsystem.FaIcon

// ==========================================================================
// 楼层卡片，1:1 对齐鸿蒙 views/components/TopicPostItem.ets 的结构与几何。
//
// 已覆盖：整体骨架（top 12 / 正文区左右 10 / 底部分隔 / 落地高亮）、作者头部
// （头像 40 + 昵称 16 Medium + 金色徽章 + 状态表情；用户名/头衔 13 tertiary；
// 回复指示器；时间 13 + 未读蓝点 8dp #66CCFF 渐隐）、正文（top 12）、
// TOPIC OWNER 标记、页脚（回应汇总 emoji 图标最多 3 个 + 人数 16 / 被回复数
// 胶囊+箭头 / 点赞 38dp 三态（heart 实心红 / 其它表情 PNG / far-heart 空心灰）
// + 长按 10 表情面板 + 更多→拷贝链接+书签（bookmark 实心蓝 / far-bookmark 灰）
// / 回复）、被回复展开区（连接竖线 + 收起 + 跳到帖子）、「上次访问」红线。
//
// 待后续切片：boost 行与火箭、shared issue「俺也一样」、采纳答案面板、
// 拷贝画勾动画（暂 Toast）、全息称号（暂普通文字）、回应人数弹层。
// ==========================================================================

/** 昵称后徽章图标统一染金色（源 BADGE_GOLD）。 */
private const val BADGE_GOLD = "#E5A50A"

/** 未读蓝点：语义强调色，明暗底都成立（源注释：勿映射主题色，白天会变深蓝）。 */
private const val UNREAD_DOT_BLUE = "#66CCFF"

/** 点赞红（Palette.LIKE_RED）。 */
private const val LIKE_RED = "#EF4444"

/** 「上次访问」红线色值（对齐官网 last visit #e45735 系）。 */
private const val LAST_VISIT_RED = "#E45735"

@Composable
fun TopicPostItem(
    post: TopicPost,
    modifier: Modifier = Modifier,
    topicCreatedById: Long = -1,
    showLastVisitedLine: Boolean = false,
    flashTargetPostId: Long? = null,
    onOpenLink: (String) -> Unit = {},
    onOpenImages: (List<ViewerImage>, Int) -> Unit = { _, _ -> },
    onOpenUser: (User) -> Unit = {},
    // 采纳答案面板:仅一楼下发非空数组(对齐鸿蒙:postNumber===1 才传 detail.acceptedAnswers)。
    acceptedAnswers: List<AcceptedAnswer> = emptyList(),
    onReply: (TopicPost) -> Unit = {},
    onJumpToPost: (Int) -> Unit = {},
    onToggleReaction: (TopicPost, String, onDone: (Boolean) -> Unit) -> Unit = { _, _, done -> done(false) },
    onToggleBookmark: (TopicPost, onDone: (Boolean) -> Unit) -> Unit = { _, done -> done(false) },
    onCopyPostLink: (TopicPost) -> String? = { null },
    onRepliesLoad: (TopicPost, onDone: (List<TopicPost>?) -> Unit) -> Unit = { _, done -> done(null) },
    // 「俺也一样」（shared issue，话题级，仅一楼渲染）：可见性/已点/计数/能否点 由页面下发；
    // toggle 上抛页面发网络，回传服务端权威新态（null=失败回滚）。对齐鸿蒙同名 @Prop。
    sharedIssueVisible: Boolean = false,
    sharedIssueCreated: Boolean = false,
    sharedIssueCount: Int = 0,
    sharedIssueCanCreate: Boolean = false,
    onToggleSharedIssue: (onDone: (SharedIssueResult?) -> Unit) -> Unit = {},
    // discourse-poll:投票与撤销投票。这里收「用户意图」,乐观更新与失败回滚由 ViewModel 负责。
    onVotePoll: (TopicPost, String, List<String>) -> Unit = { _, _, _ -> },
    onRemovePollVote: (TopicPost, String) -> Unit = { _, _ -> },
    // discourse-boosts：火箭入口 + 气泡行。按楼传入：本楼 deleteTarget + 删除/新增回调。
    boostDeleteTargetId: Long = 0,
    boostDeleteLoadingId: Long = 0L,
    onToggleBoostDelete: (boostId: Long) -> Unit = {},
    onDeleteBoost: (TopicPost, PostBoost, onDone: (Boolean) -> Unit) -> Unit = { _, _, done -> done(false) },
    onAddBoost: (TopicPost) -> Unit = {},
    // 编辑自己的楼层：点铅笔上抛（取原文由页面/VM 负责）。editLoadingPostId 供转圈。
    onEdit: (TopicPost) -> Unit = {},
    editLoadingPostId: Long = 0L,
    // 软删除 / 恢复自己的楼（更多菜单：垃圾桶 ↔ 恢复箭头）。postActionLoadingId 供转圈。
    postActionLoadingId: Long = 0L,
    onDeletePost: (TopicPost) -> Unit = {},
    onRecoverPost: (TopicPost) -> Unit = {}
) {
    val colors = arkdoColors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    // 跳楼落地高亮：命中本楼时背景淡色 600ms 渐隐（同色系零透明度过渡，避免过路灰）。
    var flashActive by remember(post.id) { mutableStateOf(false) }
    LaunchedEffect(flashTargetPostId) {
        if (flashTargetPostId != null && flashTargetPostId == post.id) {
            flashActive = true
            kotlinx.coroutines.delay(600)
            flashActive = false
        }
    }
    val flashAlpha by animateFloatAsState(
        targetValue = if (flashActive) 1f else 0f,
        animationSpec = tween(durationMillis = 600),
        label = "flash"
    )
    val flashColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f * flashAlpha)

    var reactionLoading by remember(post.id) { mutableStateOf(false) }
    var bookmarkLoading by remember(post.id) { mutableStateOf(false) }
    var moreExpanded by remember(post.id) { mutableStateOf(false) }
    var bookmarkedLocal by remember(post.id) { mutableStateOf(post.bookmarked == true) }
    var showReactionPicker by remember(post.id) { mutableStateOf(false) }
    // 被回复展开区状态（源：repliesExpanded/repliesLoading/replies，组件自身持有）。
    var repliesExpanded by remember(post.id) { mutableStateOf(false) }
    var repliesLoading by remember(post.id) { mutableStateOf(false) }
    var replies by remember(post.id) { mutableStateOf<List<TopicPost>>(emptyList()) }

    // 「俺也一样」（shared issue）本地显示态由 PostFooter 自持（显示状态，随楼层 key 重建）。

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(flashColor)
            .padding(top = 12.dp)
    ) {
        // 正文区：左右留白只加在这层，红线才能通栏铺满。
        Column(modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp)) {
            PostAuthorHeader(post = post, onOpenUser = onOpenUser, onJumpToPost = onJumpToPost)

            PostRichContent(
                html = post.cookedHtml,
                linkClicks = post.linkClicks,
                onOpenImages = onOpenImages,
                onLinkClick = onOpenLink,
                modifier = Modifier.padding(top = 12.dp)
            )

            // discourse-poll:该楼的投票卡片(正文之后)。
            // 对齐鸿蒙 TopicPostItem 里 PollView 的位置;一个楼层可能挂多个投票(polls 是数组)。
            post.polls.orEmpty().forEach { poll ->
                PollView(
                    poll = poll,
                    onVote = { optionIds -> onVotePoll(post, poll.name, optionIds) },
                    onRemoveVote = { onRemovePollVote(post, poll.name) },
                    modifier = Modifier.padding(top = 12.dp)
                )
            }

            // 采纳答案面板:仅一楼渲染(对齐鸿蒙 AcceptedAnswerPanel 位置:正文后、楼主标记前)。
            if (acceptedAnswers.isNotEmpty()) {
                AcceptedAnswerPanel(
                    answers = acceptedAnswers,
                    onJumpToPost = onJumpToPost,
                    onOpenImages = onOpenImages,
                    onOpenLink = onOpenLink
                )
            }

            // 楼主标记：本楼作者 = 主题作者时，正文末尾右下角「TOPIC OWNER」
            // （对齐官网 .cooked::after：小号、斜体、加粗、#00AEFF、右浮动）。
            if (topicCreatedById > 0 && post.userId == topicCreatedById) {
                Text(
                    text = "TOPIC OWNER",
                    fontSize = 13.sp,
                    fontStyle = FontStyle.Italic,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00AEFF),
                    textAlign = TextAlign.End,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                )
            }

            PostFooter(
                post = post,
                reactionLoading = reactionLoading,
                bookmarkLoading = bookmarkLoading,
                bookmarkedLocal = bookmarkedLocal,
                moreExpanded = moreExpanded,
                repliesExpanded = repliesExpanded,
                repliesLoading = repliesLoading,
                showReactionPicker = showReactionPicker,
                onLikeToggle = { reaction ->
                    if (reactionLoading) return@PostFooter
                    reactionLoading = true
                    onToggleReaction(post, reaction) {
                        reactionLoading = false
                    }
                },
                onShowReactionPicker = { showReactionPicker = true },
                onDismissReactionPicker = { showReactionPicker = false },
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
                    bookmarkedLocal = !bookmarkedLocal
                    onToggleBookmark(post) { finalBookmarked ->
                        bookmarkLoading = false
                        bookmarkedLocal = finalBookmarked
                    }
                },
                onRepliesToggle = {
                    when {
                        repliesExpanded -> repliesExpanded = false
                        replies.isNotEmpty() -> repliesExpanded = true
                        else -> {
                            repliesLoading = true
                            onRepliesLoad(post) { list ->
                                repliesLoading = false
                                if (list != null) {
                                    replies = list
                                    repliesExpanded = true
                                }
                            }
                        }
                    }
                },
                onEdit = { onEdit(post) },
                editLoading = editLoadingPostId == post.id,
                onDeletePost = { onDeletePost(post) },
                onRecoverPost = { onRecoverPost(post) },
                postActionLoading = postActionLoadingId == post.id,
                // 火箭入口本楼可见性：鸿蒙 canAddBoost() 即 post.canBoost === true
                // （TopicPostItem.ets:1167）。注意可见性（canAddBoost）与点击回调（onAddBoost）
                // 是**两个**参数：只传前者会渲染出一颗点了没反应的火箭（PostFooter 的
                // onAddBoost 默认空 lambda），必须成对传。
                canAddBoost = post.canBoost == true,
                onAddBoost = { onAddBoost(post) },
                sharedIssueVisible = sharedIssueVisible,
                sharedIssueCreated = sharedIssueCreated,
                sharedIssueCount = sharedIssueCount,
                sharedIssueCanCreate = sharedIssueCanCreate,
                onToggleSharedIssue = onToggleSharedIssue
            )

            // 本楼已有 boost：头像+短文本气泡排（每楼下方，对齐鸿蒙 BoostRow）。
            // 末尾接火箭入口；气泡内点自己发的 boost 展开删除图标。
            val boosts = post.boosts.orEmpty()
            if (boosts.isNotEmpty()) {
                BoostRow(
                    boosts = boosts,
                    canAddBoost = post.canBoost == true,
                    currentUserId = com.arkdo.android.services.session.LoginGate.currentUserId(context),
                    deleteTargetBoostId = boostDeleteTargetId,
                    boostDeleteLoadingId = boostDeleteLoadingId,
                    showLastVisitedLine = showLastVisitedLine,
                    onToggleBoostDelete = { onToggleBoostDelete(it) },
                    onDeleteBoost = { b, done -> onDeleteBoost(post, b, done) },
                    onAddBoost = { onAddBoost(post) },
                    onOpenUser = onOpenUser
                )
            }

            if (repliesExpanded) {
                RepliesSection(
                    replies = replies,
                    repliesLoading = repliesLoading,
                    onOpenUser = onOpenUser,
                    onOpenLink = onOpenLink,
                    onOpenImages = onOpenImages,
                    onCollapse = { repliesExpanded = false },
                    onJumpToPost = onJumpToPost
                )
            }
        }

        if (showLastVisitedLine) {
            LastVisitedDivider()
        } else {
            HorizontalDivider(color = colors.border, thickness = 1.dp)
        }
    }
}

// ==========================================================================
// 作者头部
// ==========================================================================

@Composable
private fun PostAuthorHeader(
    post: TopicPost,
    onOpenUser: (User) -> Unit,
    // 「回复别人」指示器：点击定位到被回复楼层（对齐鸿蒙 TopicPostItem.ets:391-396）。
    onJumpToPost: (Int) -> Unit = {}
) {
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
                // 状态表情（user_status.emoji，如 "lollipop"、"tieba_003"）：接口只给短名，
                // 要经站点表情目录换成图片。目录里查不到就不画——把短名当文字渲染出来是
                // 一串英文单词，比不显示更糟（对齐鸿蒙 TopicPostItem.ets:336-343）。
                getEmojiUrl(post.statusEmoji)?.let { url ->
                    AsyncImage(
                        model = url,
                        contentDescription = post.statusEmoji,
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .size(16.dp)
                    )
                }
                post.badgeIcons.orEmpty().forEach { iconId ->
                    FaIcon(iconId, parseHexColor(BADGE_GOLD), 15.dp, Modifier.padding(start = 5.dp))
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
        // 此前 clickable 里是空注释 → 有涟漪无跳转，用户会当 bug 报。对齐鸿蒙：图标与头像间距 4。
        if (post.replyToUser != null && post.replyToPostNumber != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clickable { onJumpToPost(post.replyToPostNumber) }
            ) {
                FaIcon("share", MaterialTheme.colorScheme.onSurfaceVariant, 16.dp)
                UserAvatar(user = post.replyToUser, avatarSize = 20.dp, showFlair = false)
            }
        }

        // 右上角：时间 + 未读小蓝点（蓝点常驻占位，读后渐隐，时间不位移）。
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
            // 上报进行中（reporting && !read）：蓝点循环呼吸（opacity 1↔0.35，700ms 周期，
            // 对齐鸿蒙 breatheDim 定时器语义）。非呼吸期恒 1，不参与淡出。
            val breathing = post.reporting && !read
            val breatheAlpha = if (breathing) {
                val transition = rememberInfiniteTransition(label = "breatheDot")
                val value by transition.animateFloat(
                    initialValue = 0.35f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 700, easing = EaseInOut),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "breatheDotValue"
                )
                value
            } else {
                1f
            }
            Box(
                modifier = Modifier
                    .padding(start = 2.dp)
                    .size(8.dp)
                    .alpha(dotAlpha * breatheAlpha)
                    .clip(CircleShape)
                    .background(parseHexColor(UNREAD_DOT_BLUE))
            )
        }
    }
}

// ==========================================================================
// 页脚：左 = 回应汇总（emoji 最多 3 个 + 人数）+ 被回复胶囊；右 = 点赞 / 更多 / 回复
// ==========================================================================

@Composable
private fun PostFooter(
    post: TopicPost,
    reactionLoading: Boolean,
    bookmarkLoading: Boolean,
    bookmarkedLocal: Boolean,
    moreExpanded: Boolean,
    repliesExpanded: Boolean,
    repliesLoading: Boolean,
    showReactionPicker: Boolean,
    onLikeToggle: (String) -> Unit,
    onShowReactionPicker: () -> Unit,
    onDismissReactionPicker: () -> Unit,
    onReply: () -> Unit,
    onMoreExpand: () -> Unit,
    onCopyLink: () -> Unit,
    onBookmarkToggle: () -> Unit,
    onRepliesToggle: () -> Unit,
    // 火箭入口（本楼无 boost 时显示，位于回复左边）。
    canAddBoost: Boolean = false,
    // 「俺也一样」(shared issue)。
    sharedIssueVisible: Boolean = false,
    sharedIssueCreated: Boolean = false,
    sharedIssueCount: Int = 0,
    sharedIssueCanCreate: Boolean = false,
    onToggleSharedIssue: (onDone: (SharedIssueResult?) -> Unit) -> Unit = {},
    // discourse-poll:投票与撤销投票。这里收「用户意图」,乐观更新与失败回滚由 ViewModel 负责。
    onVotePoll: (TopicPost, String, List<String>) -> Unit = { _, _, _ -> },
    onRemovePollVote: (TopicPost, String) -> Unit = { _, _ -> },
    // discourse-boosts：火箭入口 + 气泡行。按楼传入：本楼 deleteTarget + 删除/新增回调。
    boostDeleteTargetId: Long = 0,
    boostDeleteLoadingId: Long = 0L,
    onToggleBoostDelete: (boostId: Long) -> Unit = {},
    onDeleteBoost: (TopicPost, PostBoost, onDone: (Boolean) -> Unit) -> Unit = { _, _, done -> done(false) },
    onAddBoost: (TopicPost) -> Unit = {},
    // 编辑自己的楼层：yours=true 时把点赞位换成铅笔。
    onEdit: () -> Unit = {},
    editLoading: Boolean = false,
    // 软删除 / 恢复：展开更多后，自己的楼多一个垃圾桶；删掉后换成恢复箭头。
    onDeletePost: () -> Unit = {},
    onRecoverPost: () -> Unit = {},
    postActionLoading: Boolean = false
) {
    val colors = arkdoColors
    val iconMuted = colors.iconMuted
    val context = LocalContext.current

    // 「俺也一样」本地显示态：初始取 props（话题级值），toggle 后用服务端权威新态覆盖。
    var sharedIssueCreatedLocal by remember { mutableStateOf(sharedIssueCreated) }
    var sharedIssueCountLocal by remember { mutableStateOf(sharedIssueCount) }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp)) {
        // 「俺也一样」（shared issue）：悬在操作栏上方（对齐鸿蒙 MeTooButton 位置）。
        // 已登录但无权（canCreate=false，如楼主）置灰禁用；未登录不禁用——点击须能触发登录拦截。
        if (sharedIssueVisible) {            var meTooRequesting by remember { mutableStateOf(false) }
            val meTooDisabled = com.arkdo.android.services.session.LoginGate.isLoggedIn(context) && !sharedIssueCanCreate
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .alpha(if (meTooDisabled) 0.45f else 1f)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp))
                    .border(1.dp, colors.meTooBorder, RoundedCornerShape(4.dp))
                    .clickable(enabled = !meTooDisabled && !meTooRequesting) {
                        meTooRequesting = true
                        onToggleSharedIssue { result ->
                            meTooRequesting = false
                            if (result != null) {
                                sharedIssueCreatedLocal = result.created
                                sharedIssueCountLocal = result.count
                            }
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                when {
                    meTooRequesting -> LoadingDot()
                    sharedIssueCreatedLocal -> FaIcon("hand", colors.brand, 16.dp)
                    else -> FaIcon("hand", colors.meTooFg, 16.dp)
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (sharedIssueCreatedLocal) "俺也一样($sharedIssueCountLocal)" else "俺也一样",
                    fontSize = 15.sp,
                    color = colors.meTooFg
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左组：回应汇总（无回应不渲染，避免占位空隙） + 被回复胶囊。
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val reactions = post.reactions.orEmpty().filter { it.count > 0 }
                if ((post.reactionUsersCount ?: 0) > 0 && reactions.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)
                    ) {
                        // 汇总胶囊最多展示前 3 种表情，总人数仍用 reactionUsersCount。
                        reactions.take(3).forEach { reaction ->
                            val asset = reactionEmojiAsset(reaction.id)
                            if (asset != null) {
                                AsyncImage(
                                    model = "file:///android_asset/$asset",
                                    contentDescription = reaction.id,
                                    modifier = Modifier.padding(end = 2.dp).size(16.dp)
                                )
                            }
                        }
                        Text(
                            text = "${post.reactionUsersCount ?: 0}",
                            fontSize = 16.sp,
                            color = colors.reactionCountFg,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }

                // 被回复数量 + 箭头胶囊（replyCount > 0 时显示）。
                if ((post.replyCount ?: 0) > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (repliesExpanded) colors.postReplyControlActiveBg else colors.postReplyControlBg)
                            .clickable { onRepliesToggle() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(text = "${post.replyCount}", fontSize = 16.sp, color = colors.postReplyControlFg)
                        when {
                            repliesLoading -> LoadingDot()
                            repliesExpanded -> FaIcon("chevron-up", colors.postReplyControlFg, 14.dp)
                            else -> FaIcon("chevron-down", colors.postReplyControlFg, 14.dp)
                        }
                    }
                }
            }

            // 右组：点赞 / 更多(拷贝+书签) / 回复。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    // 自己的楼不给点赞入口，改放编辑铅笔（对齐鸿蒙 canEditInline 语义：
                    // 仅「还在编辑时限内」且页面接了 onEdit 才显示，否则留空）。
                    if (post.yours == true && post.canEdit == true) {
                        ActionButton(
                            enabled = !editLoading,
                            onClick = onEdit
                        ) {
                            if (editLoading) {
                                LoadingDot()
                            } else {
                                FaIcon("pencil", iconMuted, 16.dp)
                            }
                        }
                    } else if (post.yours != true) {
                        ActionButton(
                            enabled = !reactionLoading,
                            onClick = { onLikeToggle("heart") },
                            onLongClick = onShowReactionPicker
                        ) {
                            when {
                                // 请求中转圈；heart → 实心红；其它已选表情 → PNG；未回应 → 空心灰。
                                reactionLoading -> LoadingDot()
                                post.currentUserReaction == "heart" ->
                                    FaIcon("heart", parseHexColor(LIKE_RED), 16.dp)
                                reactionEmojiAsset(post.currentUserReaction) != null ->
                                    AsyncImage(
                                        model = "file:///android_asset/${reactionEmojiAsset(post.currentUserReaction)}",
                                        contentDescription = post.currentUserReaction,
                                        modifier = Modifier.size(16.dp)
                                    )
                                else ->
                                    FaIcon("far-heart", iconMuted, 16.dp)
                            }
                        }
                        if (showReactionPicker) {
                            // 长按弹出的 10 表情面板（2 行 x 5），点某个即以该表情回应。
                            Popup(
                                alignment = Alignment.BottomEnd,
                                onDismissRequest = onDismissReactionPicker
                            ) {
                                Column(
                                    modifier = Modifier
                                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                                        .padding(6.dp)
                                ) {
                                    listOf(0, 1).forEach { row ->
                                        Row {
                                            REACTION_ORDER.drop(row * 5).take(5).forEach { name ->
                                                val asset = reactionEmojiAsset(name) ?: return@forEach
                                                AsyncImage(
                                                    model = "file:///android_asset/$asset",
                                                    contentDescription = name,
                                                    modifier = Modifier
                                                        .padding(6.dp)
                                                        .size(28.dp)
                                                        .clickable {
                                                            onDismissReactionPicker()
                                                            onLikeToggle(name)
                                                        }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                // 火箭入口（对齐鸿蒙）：无 boost 时跟在操作区、位于「更多」之前、回复按钮左边。
                // 一旦本楼已有 boost，火箭改到 boost 行末尾（见 BoostRow），此处不再显示。
                // 顺序对齐鸿蒙 TopicPostItem.ets:495-511 —— Like → Rocket → More → Reply。
                if (canAddBoost && post.boosts.isNullOrEmpty()) {
                    ActionButton(onClick = { onAddBoost(post) }) {
                        FaIcon("rocket", iconMuted, 16.dp)
                    }
                }
                if (moreExpanded) {
                    ActionButton(onClick = onCopyLink) {
                        FaIcon("link", iconMuted, 16.dp)
                    }
                    ActionButton(enabled = !bookmarkLoading, onClick = onBookmarkToggle) {
                        when {
                            bookmarkLoading -> LoadingDot()
                            // 已收藏实心（ld_accent 蓝）/ 未收藏空心灰（bookmark / far-bookmark）。
                            bookmarkedLocal -> FaIcon("bookmark", colors.brand, 16.dp)
                            else -> FaIcon("far-bookmark", iconMuted, 16.dp)
                        }
                    }
                    // 自己的楼：垃圾桶软删除；删掉后换成恢复（原站三个点展开后的那一颗）。
                    if (post.yours == true) {
                        val deleted = post.userDeleted == true
                        ActionButton(
                            enabled = !postActionLoading,
                            onClick = { if (deleted) onRecoverPost() else onDeletePost() }
                        ) {
                            if (postActionLoading) {
                                LoadingDot()
                            } else if (deleted) {
                                FaIcon("arrow-rotate-left", iconMuted, 16.dp)
                            } else {
                                FaIcon("trash-can", colors.ldDanger, 16.dp)
                            }
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

/** 38x38 透明底圆角(10)操作按钮；点赞按钮支持长按（弹表情面板）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActionButton(
    enabled: Boolean = true,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val base = Modifier
        .size(38.dp)
        .clip(RoundedCornerShape(10.dp))
    Box(
        modifier = if (onLongClick != null) {
            base.combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick)
        } else {
            base.clickable(enabled = enabled) { onClick() }
        },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

// ==========================================================================
// 被回复列表展开区：竖线 + 逐条精简项（头像 40 + 昵称/用户名 + 正文），
// 底部收起按钮（仅最后一条，白底描边方块盖住线尾）+「跳到帖子」。
// ==========================================================================

@Composable
private fun RepliesSection(
    replies: List<TopicPost>,
    repliesLoading: Boolean,
    onOpenUser: (User) -> Unit,
    onOpenLink: (String) -> Unit,
    onOpenImages: (List<ViewerImage>, Int) -> Unit = { _, _ -> },
    onCollapse: () -> Unit,
    onJumpToPost: (Int) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
    ) {
        // 楼层连接竖线：1dp #E9E9E9，x 落在头像水平中心（40 头像 → 中心 20，线左移 19.5）。
        // 线随内容高度通高；头像/按钮不透明盖在线上，只在竖直空隙露出线段。
        Box(
            modifier = Modifier
                .padding(start = 19.5.dp)
                .width(1.dp)
                .fillMaxHeight()
                .background(arkdoColors.surfaceMuted)
        )
        Column(modifier = Modifier.fillMaxWidth()) {
        if (repliesLoading) {
            Box(modifier = Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                LoadingDot()
            }
        }
        replies.forEachIndexed { index, reply ->
            val replyAuthor = User(
                id = reply.userId ?: 0,
                username = reply.username ?: "",
                name = reply.name,
                avatarTemplate = reply.avatarTemplate,
                animatedAvatar = reply.animatedAvatar
            )
            Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    UserAvatar(
                        user = replyAuthor,
                        avatarSize = 40.dp,
                        showFlair = true,
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .clickable { onOpenUser(replyAuthor) }
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            // 对齐 replyAuthorName/replyUsername：displayName 空时用户名顶到名字位，
                            // 此时用户名位不再重复显示。
                            val displayName = replyAuthor.displayName
                            val username = reply.username ?: ""
                            Text(
                                text = displayName.ifEmpty { username },
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (username.isNotEmpty() && displayName.isNotEmpty()) {
                                Text(
                                    text = username,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        PostRichContent(
                            html = reply.cookedHtml,
                            onOpenImages = onOpenImages,
                            onLinkClick = onOpenLink,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp)
                        .padding(start = if (index == replies.lastIndex) 0.dp else 52.dp)
                ) {
                    if (index == replies.lastIndex) {
                        // 收起按钮：32 宽（左移 4 使中心落在竖线上），白底描边圆角方块。
                        Box(
                            modifier = Modifier
                                .padding(start = 4.dp, end = 16.dp)
                                .size(32.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .clickable { onCollapse() },
                            contentAlignment = Alignment.Center
                        ) {
                            FaIcon("chevron-up", MaterialTheme.colorScheme.onSurfaceVariant, 14.dp)
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.clickable { onJumpToPost(reply.postNumber) }
                    ) {
                        FaIcon("arrow-down", MaterialTheme.colorScheme.onSurfaceVariant, 12.dp)
                        Text(
                            text = "跳到帖子",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

}
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

// ==========================================================================
// boost 行:头像 + 短文本气泡排,末尾接火箭入口(对齐鸿蒙 BoostRow)。
// 点气泡内文字展开/收起删除图标;点头像进该用户主页。
// ==========================================================================
@Composable
private fun BoostRow(
    boosts: List<PostBoost>,
    canAddBoost: Boolean,
    currentUserId: Long,
    deleteTargetBoostId: Long,
    boostDeleteLoadingId: Long = 0L,
    showLastVisitedLine: Boolean = false,
    onToggleBoostDelete: (Long) -> Unit,
    onDeleteBoost: (PostBoost, onDone: (Boolean) -> Unit) -> Unit = { _, done -> done(false) },
    onAddBoost: () -> Unit,
    onOpenUser: (User) -> Unit
) {
    val colors = arkdoColors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 对齐鸿蒙 BoostRow 外边距：上 2、下留出与底分隔线的间距。普通 1dp 灰线
            // 鸿蒙不加下间距（margin bottom 0）；「上次访问」红线文字上侵入时留 8。
            .padding(top = 2.dp, bottom = if (showLastVisitedLine) 8.dp else 0.dp)
    ) {
        // FlexWrap 等价:FlowRow 逐行排列,超出换行。
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            boosts.forEach { boost ->
                val expanded = deleteTargetBoostId == boost.id
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .background(colors.boostBubbleBg, RoundedCornerShape(16.dp))
                        .padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp)
                ) {
                    UserAvatar(
                        user = boost.user,
                        avatarSize = 20.dp,
                        showFlair = false,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { onOpenUser(boost.user) }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    // 点文字区展开/收起删除图标(头像各自独立)。
                    val inlines = remember(boost.cookedHtml) { parseInlines(boost.cookedHtml) }
                    InlineText(
                        inlines = inlines,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        textModifier = Modifier
                            .widthIn(max = 240.dp)
                            .clickable(enabled = currentUserId > 0 && boost.user.id == currentUserId) { onToggleBoostDelete(boost.id) }
                    )
                    if (expanded) {
                        Spacer(modifier = Modifier.width(4.dp))
                        val deleting = boostDeleteLoadingId == boost.id
                        Box(
                            modifier = Modifier
                                .size(21.dp)
                                .clip(CircleShape)
                                .background(Color(0x14000000))
                                .clickable(enabled = !deleting) {
                                    if (deleting) return@clickable
                                    onDeleteBoost(boost) { ok ->
                                        if (ok) {
                                            // 删除成功：VM 已把该条从楼层摘掉并把展开态复位，
                                            // 这里只确保图标不再残留展开态。
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (deleting) {
                                LoadingDot()
                            } else {
                                FaIcon("trash-can", Color(0xFFE45735), 14.dp)
                            }
                        }
                    }
                }
            }
            if (canAddBoost) {
                // 火箭跟在气泡后:与气泡等高(28)。
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onAddBoost),
                    contentAlignment = Alignment.Center
                ) {
                    FaIcon("rocket", arkdoColors.iconMuted, 16.dp)
                }
            }
        }
    }
}
