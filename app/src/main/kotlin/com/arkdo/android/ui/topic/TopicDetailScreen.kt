package com.arkdo.android.ui.topic

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.arkdo.android.R
import com.arkdo.android.data.models.PendingPost
import com.arkdo.android.data.models.TopicCategory
import com.arkdo.android.data.models.AcceptedAnswer
import com.arkdo.android.data.models.PostBoost
import com.arkdo.android.data.models.SharedIssueResult
import com.arkdo.android.data.models.TopicDetail
import com.arkdo.android.data.models.TopicPost
import com.arkdo.android.data.models.TopicTag
import com.arkdo.android.data.models.User
import com.arkdo.android.services.content.DownloadEventBus
import com.arkdo.android.services.session.CurrentUserHolder
import com.arkdo.android.services.topic.LOTTERY_RULES_TOPIC_URL
import com.arkdo.android.services.topic.LotteryNoticeContext
import com.arkdo.android.services.topic.shouldShowLotteryNotice
import com.arkdo.android.services.topic.LotteryTipPrefs
import com.arkdo.android.services.content.LinkRoute
import com.arkdo.android.services.content.resolveLinkRoute
import com.arkdo.android.services.network.ArkWebNetworkBridge
import com.arkdo.android.services.site.CategoryIndex
import com.arkdo.android.ui.composer.MarkdownRenderEngine
import com.arkdo.android.ui.composer.ReplyComposerOverlay
import com.arkdo.android.ui.components.CommentInputOverlay
import com.arkdo.android.core.designsystem.FaIcon
import com.arkdo.android.ui.components.ImageViewer
import com.arkdo.android.ui.components.IncomingPostsBanner
import com.arkdo.android.ui.components.IncomingPostsBannerHeight
import com.arkdo.android.ui.components.LocalAnimatedVisibilityScope
import com.arkdo.android.ui.components.LocalSharedTransitionScope
import com.arkdo.android.ui.components.PendingPostCard
import com.arkdo.android.ui.components.ReplyingPresenceBar
import com.arkdo.android.ui.components.ReplyingPresenceBarHeight
import com.arkdo.android.ui.components.LotteryNoticeCard
import com.arkdo.android.ui.components.PostRichContent
import com.arkdo.android.ui.components.ReactionUsersSheetContent
import com.arkdo.android.ui.components.TopicMetadataBadges
import com.arkdo.android.ui.components.TopicPostItem
import com.arkdo.android.ui.components.ViewerImage
import com.arkdo.android.ui.drafts.DraftResume
import com.arkdo.android.ui.theme.ArkdoFixedColors
import com.arkdo.android.ui.theme.arkdoColors
import timber.log.Timber

/**
 * 话题详情页
 *
 * 结构（对齐鸿蒙 TopicDetailPage）：
 * - 内容层：LazyColumn
 * - 顶栏层：实色底 + 双层标题（最后绘制，盖在内容之上）
 * - 图片浏览器：viewerState 非空时全屏覆盖（共享元素转场）
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun TopicDetailScreen(
    topicId: Long,
    slug: String? = null,
    title: String? = null,
    targetPostNumber: Int? = null,
    highlightTarget: String? = null,
    draftResume: DraftResume? = null,
    onNavigateBack: () -> Unit,
    onNavigateToTopic: (topicId: Long, slug: String?, targetPostNumber: Int?) -> Unit = { _, _, _ -> },
    onNavigateToUser: (User) -> Unit = {},
    // 正文里的站内非帖子页 / 外站链接 → 内置 WebView 页（对齐鸿蒙 RootTabPage.openWebPage）。
    // 不能交系统浏览器:站内页面要靠 App 的会话 Cookie 才有登录态,外部浏览器打开就是未登录。
    onOpenWeb: (url: String, title: String?) -> Unit = { _, _ -> },
    // 正文 @提及 / 用户主页链接。LinkRouter 的 User 路由只给出 username，没有 User 对象，
    // 所以不能复用 onNavigateToUser，单开一个按 username 跳原生主页的入口。
    onOpenUserProfile: (username: String) -> Unit = {},
    viewModel: TopicDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    // 单次快照：整个组合只用这一份 TopicDetailUiState（不可变 data class）。
    // 不这样做的话，VM 在桥回调线程写状态时，两次 uiState 读可能看到不同对象——
    // 「when 判完 topic 非空、下一行 topic!! 就 NPE」的崩溃就是这个撕裂读。
    val ui = uiState
    // 本次阅读的 timings 上报是否正被 Cloudflare 拦着（真源：ChallengeShortcutEventBus，
    // 由 ReadingTracker 的撞盾/恢复结果驱动）。为真时顶栏右侧亮「手动验证」入口。
    val challengeBlocked by viewModel.challengeBlocked.collectAsState()
    val listState = rememberLazyListState()
    // 图片浏览器覆盖层状态（本楼图片组 + 起始下标）。
    var viewerState by remember { mutableStateOf<Pair<List<ViewerImage>, Int>?>(null) }
    // 当前登录用户:待审卡的头像昵称用(待审内容只有本人可见,作者恒为当前用户)。
    val currentUser by CurrentUserHolder.currentUser.collectAsState()
    // 「抽奖帖提醒」开关(实验性设置;卡片是否显示还要过 LotteryTopicRule 的帖子判定)。
    val lotteryTipEnabled by LotteryTipPrefs.enabled.collectAsState()
    // 待审卡删除的二次确认(不可逆:审核队列里删掉就没了,原文只在这张卡上)。
    var pendingDeleteTarget by remember { mutableStateOf<PendingPost?>(null) }
    // 链接路由（LinkRouter，对齐鸿蒙 RootTabPage.handleOpenLink 的四类分流）：
    //   TOPIC    → 原生详情页（本帖内则直接滚动定位，不压栈）
    //   USER     → 原生他人主页
    //   DOWNLOAD → host WebView 的原生下载（带会话 Cookie，能过 Cloudflare）
    //   WEB      → 内置 WebViewPage（站内页面共享 App 的 Cookie，登录态在）
    // 此前后三类一律交 uriHandler.openUri，结果是站内页面在系统浏览器里没有登录态，
    // 点一条站内引用就掉到「请登录」。鸿蒙同一入口（RootTabPage.handleOpenLink）
    // 没有任何一类交给系统浏览器，这里按同样语义对齐。
    // 点作者/评论楼层/boost 头像 → 他人主页(对齐鸿蒙 openUserProfileFor)。
    val onOpenUser: (User) -> Unit = { user ->
        if (user.username.isNotEmpty()) {
            onNavigateToUser(user)
        }
    }
    val onOpenUri: (String) -> Unit = { url ->
        when (val route = resolveLinkRoute(url)) {
            is LinkRoute.Topic -> {
                if (route.topicId == topicId && route.targetPostNumber != null) {
                    // 本帖内链接：不压栈，直接滚动/换窗定位。
                    viewModel.jumpToPostNumber(route.targetPostNumber)
                } else {
                    onNavigateToTopic(route.topicId, null, route.targetPostNumber)
                }
            }
            is LinkRoute.Download -> {
                Timber.i("[ArkDO][LinkRouter] download url=%s", route.url)
                // 附件下载交 host WebView 的下载委托：带 _t / cf_clearance 且跟随重定向。
                // 宿主未就绪时退回内置 WebView，总之不进系统浏览器丢登录态。
                if (ArkWebNetworkBridge.startDownload(route.url)) {
                    Toast.makeText(context, "开始下载…", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "宿主未就绪，改用页面打开", Toast.LENGTH_SHORT).show()
                    onOpenWeb(route.url, null)
                }
            }
            is LinkRoute.User -> {
                Timber.i("[ArkDO][LinkRouter] user=%s url=%s", route.username, route.url)
                onOpenUserProfile(route.username)
            }
            is LinkRoute.Web -> {
                Timber.i("[ArkDO][LinkRouter] web url=%s", route.url)
                // 站内外一律内置：站内页靠共享 Cookie 保住登录态；外站也与鸿蒙一致留在站内。
                onOpenWeb(route.url, null)
            }
        }
    }

    // 附件下载结果反馈。host 下载委托完成/失败都经 DownloadEventBus 上抛，
    // 在此之前没有任何订阅方，下载完是静默的。
    DisposableEffect(Unit) {
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val unsubscribe = DownloadEventBus.subscribe { result ->
            mainHandler.post {
                val text = if (result.success) {
                    "已下载 ${result.fileName}"
                } else {
                    result.message.ifEmpty { "下载失败" }
                }
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            }
        }
        onDispose { unsubscribe() }
    }

    // 双层标题切换：摘要项（列表第 0 项）仍在视口首屏时显示「帖子详情」，滑出后切
    // 帖子标题与分类/标签行（对齐鸿蒙 summaryVisible = start === 0 的判定语义）。
    val summaryVisible by remember {
        derivedStateOf { listState.firstVisibleItemIndex == 0 }
    }
    // 顶栏总高 = 状态栏高度 + 56dp 栏体，内容顶距再 +12dp。注意不能用
    // WindowInsets.statusBars.add(WindowInsets(bottom=56)) 再取 top——add 是按边
    // 分量相加，56dp 落在 bottom 分量上会被 calculateTopPadding 丢掉，导致内容
    // 顶距只剩状态栏高度、首屏文字整段被顶栏盖住。
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val headerHeight = statusBarHeight + 56.dp
    // 栏底间隙：初版 12dp 用户实测偏大，收敛到 4dp。
    val contentTopGap = 4.dp
    // 几何自检：如果设备上仍看到"文字被顶栏盖住"，进日志页核对这三个值——
    // contentTop 必须大于 barBottom，默认位置的内容才可能落在栏下方。
    remember(headerHeight) {
        Timber.i(
            "[ArkDO][DetailHeader] statusBar=%s barBottom=%s contentTop=%s",
            statusBarHeight, headerHeight, headerHeight + contentTopGap
        )
    }

    // ── 楼层 scrubber（对齐鸿蒙 TopicScrubber + TopicDetailPage 的 scrubber 状态） ──
    // 手柄位置 = 视口首楼楼号。项序与阅读计时共用同一套偏移：0 = 摘要卡，
    // hasEarlier 时 1 = 更早加载器，其后才是楼层，底部是加载态行。
    val scrubberBounds by remember(ui.posts, ui.hasEarlier, ui.hasMore, ui.topic?.highestPostNumber) {
        derivedStateOf {
            // 上限优先取话题最高楼号；缺失时回退到已加载末楼（对齐鸿蒙 getHighestPostNumber）。
            val highest = (ui.topic?.highestPostNumber ?: 0).takeIf { it > 0 }
                ?: ui.posts.lastOrNull()?.postNumber
                ?: 0
            val firstIndex = listState.firstVisibleItemIndex
            val postsOffset = if (ui.hasEarlier) 2 else 1
            val current = when {
                highest <= 1 -> 1
                // 钉底：滚到列表物理底部**且末楼已加载完**时手柄钉在底，否则每屏最下面
                // 几楼在轨道上永远到不了。两个条件缺一不可——少了 !hasMore，在"还有几百楼
                // 没拉"时人一触底手柄就贴到最高楼，等于骗用户说已经读完了
                // （对齐鸿蒙 onReachEnd && !hasMorePosts 的双条件）。
                !listState.canScrollForward && !ui.hasMore -> highest
                firstIndex <= 0 -> 1
                else -> ui.posts.getOrNull(firstIndex - postsOffset)?.postNumber ?: 1
            }
            ScrubberBounds(current = current, highest = highest)
        }
    }
    // 整屏放得下（不可滚动）时不挂载：只有几楼的帖子它是纯噪声。
    // 与鸿蒙「摘要项与末尾项同时可见 → 隐藏」的判定同义。
    val scrubberScrollable by remember {
        derivedStateOf { listState.canScrollForward || listState.canScrollBackward }
    }
    // 松手后的跳转窗口：气泡转 loading 直到滚动请求被消费（已加载楼几乎瞬间，
    // 未加载楼要等 rewindow 拉完）。超时兜底，避免定位失败时永久卡在高亮态
    // （对齐鸿蒙 jumpTimerId 的 5s 兜底）。
    var pendingJump by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(pendingJump) {
        val target = pendingJump ?: return@LaunchedEffect
        delay(SCRUBBER_JUMP_TIMEOUT_MS)
        if (pendingJump == target) pendingJump = null
    }

    // 列表是否停在底部：实时新楼据此分流（在底部直接追加，否则累计「N 条新回复」提示条）。
    // 语义对齐鸿蒙 TopicDetailPage.atListEnd —— 那边由 onScrollIndex 维护，这边用
    // canScrollForward 反判（还能往下滚就是不在底部）。底部有 72dp 的 contentPadding，
    // 滚到它的尽头才算到底，与「最后一楼的操作区完全露出」是同一时刻。
    // 收到「到底」还会让 ViewModel 收起提示条（对齐鸿蒙 onReachEnd 的 clearIncomingBanner）。
    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollForward }
            .collect { canForward -> viewModel.updateListAtEnd(!canForward) }
    }

    LaunchedEffect(topicId, targetPostNumber) {
        viewModel.loadTopic(topicId, slug, title, targetPostNumber, draftResume)
    }

    // 阅读计时：页面前后台切换暂停/恢复；销毁时停止并 flushPending（对齐鸿蒙 TopicReadController.detach）。
    val detailLifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(topicId, detailLifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    viewModel.setTrackerFocus(true)
                    // 幂等拉起：正常首屏时 detail 还没到，这里是 no-op，由 applyDetail 启动；
                    // 但页面重建（见 onDispose 注释）后要靠它把停掉的表重新起回来。
                    viewModel.ensureReadingTracker()
                }
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> viewModel.setTrackerFocus(false)
                androidx.lifecycle.Lifecycle.Event.ON_DESTROY -> viewModel.onDetailDetach()
                else -> Unit
            }
        }
        detailLifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            // ⚠️ 这里**只能摘观察者，不能调 onDetailDetach()**。
            // 本 effect 的 key 含 LocalLifecycleOwner，而配置变更（切深色模式 / 旋转 / 改字号）
            // 会让 Activity 重建、NavHost 从保存状态恢复出一个**新的** NavBackStackEntry，
            // 于是 key 变化 → dispose → 误判成"离开详情页"。误调的代价是不可逆的：
            //   tracker.stopTiming() → flushPending() → reset()
            //   → ①清空 readPosts（已读记忆丢失）
            //   → ②cancel 掉还没到点的 pendingVisualJobs（那批楼上报其实已成功，
            //      只是 onRead 永不执行，UI 蓝点永远等不到淡出）
            // 更致命的是 LaunchedEffect(topicId) 的 key 没变、loadTopic 不会重跑，
            // applyDetail 不再执行 → startReadingTrackerIfNeeded 再也不会被调用
            //   → tracker 永久停表，蓝点永久卡在未读（真机上就是这个现象）。
            // 真正的销毁由上面的 ON_DESTROY 覆盖；即便个别机型漏发，ViewModel.onCleared()
            // 里还有 readingTracker.stop() 兜底，不会漏掉最后一次结算。
            detailLifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(arkdoColors.topicDetailPageBg)) {
        // 离屏公式引擎的唯一挂载点:有含公式的楼层时才会真正挂上 1×1 WebView,
        // 引用归零后空闲 20s 自行卸载(见 MathRenderService)。
        com.arkdo.android.ui.components.MathRenderHostSlot()

        // 共享元素转场层：楼层列表与图片浏览器共用（转场坐标必须在同一层里算）。
        SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = viewerState,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(220)) },
                label = "imageViewer"
            ) { viewer ->
                CompositionLocalProvider(
                    LocalSharedTransitionScope provides this@SharedTransitionLayout,
                    LocalAnimatedVisibilityScope provides this
                ) {
                    if (viewer == null) {
                        when {
                            ui.error != null && ui.topic == null -> {
                                ErrorView(
                                    message = ui.error,
                                    onRetry = { viewModel.retry() }
                                )
                            }
                            // topic 为空且无错误 = 加载中（含首帧 LaunchedEffect 尚未
                            // 触发的空档，避免整屏空白只剩顶栏）。
                            ui.topic == null -> {
                                LoadingView()
                            }
                            // 上面两支已排除「有错」与「topic 为空」，此处 topic 必非空。
                            else -> ui.topic.let { topic ->
                                TopicDetailContent(
                                        // 默认滚动位置：顶栏（状态栏+56dp）之下再让出 contentTopGap。
                                        headerTopPadding = headerHeight + contentTopGap,
                                        listState = listState,
                                        topic = topic,
                                        onOpenImages = { images, start -> viewerState = images to start },
                                        categories = ui.categories,
                                        posts = ui.posts,
                                        isLoadingMore = ui.isLoadingMore,
                                        hasMore = ui.hasMore,
                                        loadMoreMessage = ui.loadMoreMessage,
                                        onLoadMore = { viewModel.loadMorePosts() },
                                        targetPostNumber = targetPostNumber,
                                        flashTargetPostId = ui.flashTargetPostId,
                                        // 跳楼/换窗的一次性滚动请求来自 VM（jumpToPostNumber / rewindow）。
                                        scrollRequestPostNumber = ui.scrollRequestPostNumber,
                                        onScrollRequestConsumed = {
                                            viewModel.consumeScrollRequest()
                                            // 跳楼滚动落地 → 收回气泡 loading
                                            // （对齐鸿蒙 onJump 的 Promise resolve 收尾）。
                                            pendingJump = null
                                        },
                                        hasEarlier = ui.hasEarlier,
                                        earlierCount = ui.earlierCount,
                                        isLoadingEarlier = ui.isLoadingEarlier,
                                        onLoadEarlier = { viewModel.loadEarlierPosts() },
                                        onOpenLink = onOpenUri,
                                        onOpenUser = onOpenUser,
                                        onToggleReaction = { p, reaction, done -> viewModel.toggleReaction(p, reaction, done) },
                                        onToggleBookmark = { p, done -> viewModel.toggleBookmark(p, done) },
                                        onCopyPostLink = { p -> viewModel.postLink(p) },
                                        onJumpToPost = { postNumber -> viewModel.jumpToPostNumber(postNumber) },
                                        // 「上次访问」红线锚楼（VM 按进入时的已读水位算一次，锚定后不再变）。
                                        lastVisitedAnchorPostId = ui.lastVisitedAnchorPostId,
                                        onRepliesLoad = { p, done -> viewModel.loadReplies(p, done) },
                                        onReply = viewModel::openComposerForPost,
                                        topicCreatedById = topic.createdById ?: -1L,
                                        sharedIssueVisible = ui.sharedIssueVisible,
                                        sharedIssueCreated = ui.sharedIssueCreated,
                                        sharedIssueCount = ui.sharedIssueCount,
                                        sharedIssueCanCreate = ui.sharedIssueCanCreate,
                                        acceptedAnswers = topic.acceptedAnswers.orEmpty(),
                                        onToggleSharedIssue = { done -> viewModel.toggleSharedIssue(done) },
                                        onVotePoll = { p, pollName, ids -> viewModel.votePoll(p, pollName, ids) },
                                        onRemovePollVote = { p, pollName -> viewModel.removePollVote(p, pollName) },
                                        boostDeleteTargetId = ui.boostDeleteTargetId,
                                        boostDeleteLoadingId = ui.boostDeleteLoadingId,
                                        onToggleBoostDelete = { id -> viewModel.toggleBoostDelete(id) },
                                        onDeleteBoost = { p, b, done -> viewModel.deleteBoost(p, b, done) },
                                        onAddBoost = viewModel::openComposerForBoost,
                                        onEdit = { p -> viewModel.openEditorForEdit(p) },
                                        editLoadingPostId = ui.editLoadingPostId,
                                        postActionLoadingId = ui.postActionLoadingId,
                                        onDeletePost = { p -> viewModel.deleteOwnPost(p) {} },
                                        onRecoverPost = { p -> viewModel.recoverOwnPost(p) {} },
                                        onVisiblePostNumbers = { viewModel.updateVisiblePostNumbers(it) },
                                        // 回应胶囊 → 弹层(状态在 VM,对齐鸿蒙 TopicDetailPage:483)。
                                        showLotteryNotice = lotteryNoticeShowing(
                                            topic = ui.topic,
                                            currentUserId = currentUser?.id,
                                            tipEnabled = lotteryTipEnabled
                                        ),
                                        onOpenReactions = { viewModel.openReactionSheet(it) },
                                        pendingPosts = ui.pendingPosts,
                                        currentUser = currentUser,
                                        onDeletePendingPost = { pendingDeleteTarget = it }
                                )
                            }
                        }
                    } else {
                        ImageViewer(
                            images = viewer.first,
                            startIndex = viewer.second,
                            onDismiss = { viewerState = null }
                        )
                    }
                }
            }
        }

        // 楼层 scrubber：内容层之上、顶栏（zIndex 2）之下，绘制顺序在回复 FAB 之前
        // （对齐鸿蒙：TopicScrubber 声明在悬浮回复按钮之前）。
        // 挂载条件逐条对齐鸿蒙 TopicDetailPage：`detail &&` → topic 非空、highest > 1、
        // listScrollable、`!showComposer`；额外多一个 viewerState 判断——Android 的图片
        // 浏览器是同层全屏覆盖，不像鸿蒙那样另开图层，不挡的话 scrubber 会浮在图片上。
        if (ui.topic != null && viewerState == null && !ui.composerVisible &&
            scrubberBounds.highest > 1 && scrubberScrollable
        ) {
            TopicScrubber(
                currentPostNumber = scrubberBounds.current,
                highestPostNumber = scrubberBounds.highest,
                jumpTarget = pendingJump,
                onJump = { postNumber ->
                    pendingJump = postNumber
                    viewModel.jumpToPostNumber(postNumber)
                },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    // 不再额外内收：轨道贴屏右缘（内距由组件按鸿蒙几何算 4/8dp），
                    // 正好落在卡片外的页边距上而不是压着正文；触摸条仍有 24dp 宽，
                    // 手指在离边缘十几 dp 处按下同样能抓到，不必去挤系统返回手势最外一条。
                    .zIndex(1.5f)
            )
        }

        // 顶栏最后绘制：盖在共享元素覆盖层之上（zIndex 置顶）；图片浏览器打开时
        // 隐藏（浏览器自带 ✕/分享顶栏）。
        if (viewerState == null) {
            TopicDetailHeaderBar(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .zIndex(2f),
                summaryVisible = summaryVisible,
                title = ui.topic?.title ?: title ?: "话题详情",
                category = CategoryIndex.find(ui.categories, ui.topic?.categoryId),
                parentCategory = CategoryIndex.findParent(ui.categories, ui.topic?.categoryId),
                tags = ui.topic?.tags.orEmpty(),
                solved = !ui.topic?.acceptedAnswers.isNullOrEmpty(),
                bookmarked = ui.topic?.bookmarked == true,
                onBack = onNavigateBack,
                onTitleClick = { viewModel.jumpToPostNumber(1) },
                showChallengeShortcut = challengeBlocked,
                onOpenChallenge = { viewModel.requestManualChallenge() }
            )
        }

        // 回复输入浮层（切片 7）：盖在内容与顶栏之上；FAB 打开时隐藏由 VM 状态保证。
        // 三态：编辑（editingPostId>0）→ submitEdit；boost → submitBoost；否则普通回复。
        CommentInputOverlay(
            // 展开成全屏编辑器时把紧凑条整个撤掉：两者共用同一份文本，同时在场只会互相压住。
            visible = ui.composerVisible && !ui.composerExpanded,
            placeholder = ui.composerPlaceholder,
            initialText = ui.pendingReplyText,
            isSubmitting = ui.isSubmittingReply,
            onTextChange = viewModel::updateReplyDraft,
            minChars = if (ui.boostTargetPostId > 0 || ui.editingPostId > 0) 1 else 16,
            maxChars = if (ui.boostTargetPostId > 0) 16 else 0,
            editMode = ui.editingPostId > 0,
            onExpand = viewModel::expandComposer,
            onSubmit = { text, typingMs, composerMs, done ->
                when {
                    ui.editingPostId > 0 -> viewModel.submitEdit(text, done)
                    ui.boostTargetPostId > 0 -> viewModel.submitBoost(text, done)
                    else -> viewModel.createReply(text, typingMs, composerMs, done)
                }
            },
            onClose = viewModel::closeComposer,
            modifier = Modifier.zIndex(3f)
        )

        // 回帖/编辑/boost 的**全屏编辑器**（紧凑条点「展开」进入）。对齐鸿蒙
        // 「内联回复条 → 展开进 ComposerPage」，Markdown 工具栏 / 预览 / 图片与发帖页同一套。
        var replyEngine by remember { mutableStateOf<MarkdownRenderEngine?>(null) }
        LaunchedEffect(ui.composerExpanded) {
            if (ui.composerExpanded && replyEngine == null) {
                replyEngine = viewModel.ensureRenderEngine(context.applicationContext)
            }
        }
        // 离屏 markdown 编译 WebView 必须挂在视图树里才会加载完成（1×1、近透明，不可见）。
        replyEngine?.let { engine ->
            Box(modifier = Modifier.size(1.dp).alpha(0.01f)) {
                AndroidView(factory = { engine.webView }, update = {})
            }
        }
        // 待审帖删除确认(对齐鸿蒙 confirmDeletePending 的 AlertDialog)。
        pendingDeleteTarget?.let { target ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { pendingDeleteTarget = null },
                title = { Text("删除待审核内容") },
                text = { Text("删除后这条内容不会再进入审核，也无法恢复。确定删除吗？") },
                confirmButton = {
                    TextButton(onClick = {
                        pendingDeleteTarget = null
                        viewModel.deletePendingPost(target)
                    }) { Text("删除", color = arkdoColors.ldDanger) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDeleteTarget = null }) { Text("取消") }
                }
            )
        }

        // 「谁回应了」底部弹层(对齐鸿蒙 TopicDetailPage 的 bindSheet + ReactionUsersSheet)。
        // 状态与分页在 ViewModel,这里只负责挂载/收起与用户跳转。
        ui.reactionSheet?.let { sheet ->
            androidx.compose.material3.ModalBottomSheet(
                onDismissRequest = { viewModel.dismissReactionSheet() },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                ReactionUsersSheetContent(
                    reactions = sheet.reactions,
                    totalRows = sheet.totalRows,
                    users = sheet.users,
                    selectedReaction = sheet.selectedReaction,
                    initialLoading = sheet.initialLoading,
                    loadMoreLoading = sheet.loadMoreLoading,
                    hasMore = sheet.hasMore,
                    initialError = sheet.initialError,
                    loadMoreError = sheet.loadMoreError,
                    onSelectReaction = viewModel::selectReactionFilter,
                    onLoadMore = viewModel::loadMoreReactionUsers,
                    onRetry = viewModel::retryReactionUsers,
                    onOpenUser = { user ->
                        viewModel.dismissReactionSheet()
                        onOpenUser(user)
                    }
                )
            }
        }

        ReplyComposerOverlay(
            visible = ui.composerVisible && ui.composerExpanded,
            title = ui.composerPlaceholder,
            hintText = ui.composerHint,
            placeholder = ui.composerPlaceholder,
            initialText = ui.pendingReplyText,
            isSubmitting = ui.isSubmittingReply,
            isUploading = ui.isUploadingMedia,
            uploadProgressText = ui.uploadProgressText,
            minChars = if (ui.boostTargetPostId > 0 || ui.editingPostId > 0) 1 else 16,
            maxChars = if (ui.boostTargetPostId > 0) 16 else 0,
            editMode = ui.editingPostId > 0,
            onTextChange = viewModel::updateReplyDraft,
            onSubmit = { text, typingMs, composerMs, done ->
                when {
                    ui.editingPostId > 0 -> viewModel.submitEdit(text, done)
                    ui.boostTargetPostId > 0 -> viewModel.submitBoost(text, done)
                    else -> viewModel.createReply(text, typingMs, composerMs, done)
                }
            },
            onUploadImage = { image, done ->
                viewModel.uploadImage(image.base64, image.fileName, image.mimeType) { snippet, _ ->
                    done(snippet)
                }
            },
            showAttachment = ui.showAttachment,
            showMedia = ui.showMedia,
            attachmentMimeTypes = ui.attachmentMimeTypes,
            onUploadPickedFiles = { kind, uris, onSnippet ->
                viewModel.uploadPickedFiles(kind, uris, onSnippet)
            },
            renderPreview = { raw -> viewModel.renderPreview(raw) },
            onClose = viewModel::collapseComposer,
            modifier = Modifier.zIndex(5f)
        )

        // 一次性提示（发表成功/失败等）：Toast 展示后立即消费。
        LaunchedEffect(ui.actionMessage) {
            ui.actionMessage?.let { message ->
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                viewModel.consumeActionMessage()
            }
        }

        // 「N 条新回复」提示条（对齐鸿蒙 TopicDetailPage:854-890）：用户不在底部时实时新楼累计在这里，
        // 点按跳到首条新楼；在底部时新楼直接追加、不计数（那时本条不显示，见 VM.flushCreatedPosts）。
        //
        // 底距算式与鸿蒙一致：FAB 底距 + FAB 半高 − 胶囊半高 = 与悬浮回复按钮垂直居中，
        // 再抬高 IncomingBannerLift 让位给下方那条「正在回复」。
        if (ui.incomingNewPostCount > 0 && !ui.composerVisible && viewerState == null) {
            IncomingPostsBanner(
                count = ui.incomingNewPostCount,
                onClick = { viewModel.openIncomingPosts() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(
                        bottom = FloatingActionMargin + FloatingActionRadius -
                            IncomingPostsBannerHeight / 2 + IncomingBannerLift
                    )
                    .zIndex(3.8f)
            )
        }

        // 底部「正在回复」悬浮条：真实 presence 数据驱动（已滤自己、截 5 人）。
        // 定位与上面一致（满屏容器 + 底距），与右下角悬浮回复按钮垂直居中。
        if (ui.topic != null && ui.presenceUsers.isNotEmpty() && !ui.composerVisible &&
            viewerState == null
        ) {
            ReplyingPresenceBar(
                users = ui.presenceUsers,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(
                        bottom = FloatingActionMargin + FloatingActionRadius -
                            ReplyingPresenceBarHeight / 2
                    )
                    .zIndex(3.8f)
            )
        }

        // 右下角普通回复按钮：圆内 50% 白，描边 1.5dp、70% 白。不走玻璃。
        // 出现淡入 150ms；消失立即移除（打开浮层时被遮罩盖住，无退场动画）。
        AnimatedVisibility(
            visible = ui.topic != null && !ui.composerVisible && viewerState == null,
            enter = fadeIn(tween(150, easing = EaseOut)),
            exit = ExitTransition.None,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(end = 16.dp, bottom = 16.dp)
                .zIndex(4f)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(52.dp)
                    .shadow(6.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.5f))
                    .border(1.5.dp, Color.White.copy(alpha = 0.7f), CircleShape)
                    .clickable(onClick = viewModel::openComposerForTopic)
            ) {
                FaIcon("comment", ArkdoFixedColors.BrandBlue, 22.dp)
            }
        }
    }
}

/** 跳楼气泡的 loading 兜底时长：超过这个时间还没落地就收回高亮（对齐鸿蒙 5s jumpTimer）。 */
private const val SCRUBBER_JUMP_TIMEOUT_MS = 5000L

/** 悬浮回复按钮的底距/侧距：与 FAB 的 padding 必须同值（下面两条浮层要对齐它的圆心）。 */
private val FloatingActionMargin = 16.dp
/** 悬浮回复按钮半径（52dp 的一半）。 */
private val FloatingActionRadius = 26.dp
/** 「N 条新回复」在「与 FAB 垂直居中」基准位之上再抬多少——底部那行让给「正在回复」头像区。 */
private val IncomingBannerLift = 24.dp

/**
 * 楼层 scrubber 的派生状态。
 *
 * 打包成一个不可变值，让 Screen 的一次重组里读到的 current/highest 必然同源——
 * 分别 derivedStateOf 会在两次读之间看到不同的 posts 快照。
 */
private data class ScrubberBounds(val current: Int, val highest: Int)

@Composable
private fun TopicDetailContent(
    headerTopPadding: Dp,
    listState: androidx.compose.foundation.lazy.LazyListState,
    topic: com.arkdo.android.data.models.TopicDetail,
    categories: List<TopicCategory>,
    posts: List<TopicPost>,
    isLoadingMore: Boolean,
    hasMore: Boolean,
    /** 列表尽头文案（「正在加载更多」/「没有更多了」/失败重试），对齐鸿蒙 loadMoreMessage。 */
    loadMoreMessage: String = "",
    onLoadMore: () -> Unit,
    targetPostNumber: Int? = null,
    flashTargetPostId: Long? = null,
    scrollRequestPostNumber: Int? = null,
    onScrollRequestConsumed: () -> Unit = {},
    hasEarlier: Boolean = false,
    earlierCount: Int = 0,
    isLoadingEarlier: Boolean = false,
    onLoadEarlier: () -> Unit = {},
    onOpenLink: (String) -> Unit = {},
    onOpenUser: (User) -> Unit = {},
    onOpenImages: (List<ViewerImage>, Int) -> Unit = { _, _ -> },
    onToggleReaction: (TopicPost, String, (Boolean) -> Unit) -> Unit = { _, _, done -> done(false) },
    onToggleBookmark: (TopicPost, (Boolean) -> Unit) -> Unit = { _, done -> done(false) },
    onCopyPostLink: (TopicPost) -> String? = { null },
    onJumpToPost: (Int) -> Unit = {},
    onRepliesLoad: (TopicPost, (List<TopicPost>?) -> Unit) -> Unit = { _, done -> done(null) },
    onReply: (TopicPost) -> Unit = {},
    topicCreatedById: Long = -1,
    sharedIssueVisible: Boolean = false,
    sharedIssueCreated: Boolean = false,
    sharedIssueCount: Int = 0,
    sharedIssueCanCreate: Boolean = false,
    acceptedAnswers: List<AcceptedAnswer> = emptyList(),
    onToggleSharedIssue: ((SharedIssueResult?) -> Unit) -> Unit = {},
    onVotePoll: (TopicPost, String, List<String>) -> Unit = { _, _, _ -> },
    onRemovePollVote: (TopicPost, String) -> Unit = { _, _ -> },
    // discourse-boosts：按楼删除目标 + 删除/新增回调。
    boostDeleteTargetId: Long = 0L,
    boostDeleteLoadingId: Long = 0L,
    onToggleBoostDelete: (Long) -> Unit = {},
    onDeleteBoost: (TopicPost, PostBoost, onDone: (Boolean) -> Unit) -> Unit = { _, _, done -> done(false) },
    onAddBoost: (TopicPost) -> Unit = {},
    // 编辑自己的楼层：点铅笔上抛（VM 取原文开浮层）。editLoadingPostId 供铅笔转圈。
    onEdit: (TopicPost) -> Unit = {},
    editLoadingPostId: Long = 0L,
    // 软删除 / 恢复自己的楼（更多菜单里的垃圾桶 ↔ 恢复）。postActionLoadingId 供转圈。
    postActionLoadingId: Long = 0L,
    onDeletePost: (TopicPost) -> Unit = {},
    onRecoverPost: (TopicPost) -> Unit = {},
    // 「上次访问」红线锚楼 id：>0 时该楼底边画红线（对齐鸿蒙 TopicDetailPage.ets:456）。
    lastVisitedAnchorPostId: Long = 0L,
    // 阅读计时上报：可见楼层号变化（滚动/分页）时回调，由上层喂给 ReadingTracker。
    onVisiblePostNumbers: (List<Int>) -> Unit = {},
    // 回应人数胶囊点击 → 上层开「谁回应了」弹层。
    onOpenReactions: (TopicPost) -> Unit = {},
    // 抽奖帖提醒卡(判定已在页面层算好,这里只管渲染与跳转)。
    showLotteryNotice: Boolean = false,
    // 待审核区:当前用户在本话题下被扣审的内容 + 当前用户(画头像昵称) + 撤回回调。
    pendingPosts: List<PendingPost> = emptyList(),
    currentUser: com.arkdo.android.data.models.User? = null,
    onDeletePendingPost: (PendingPost) -> Unit = {}
) {
    // 未读行跳楼：目标楼层加载出来后滚过去（头部标题占第 0 位，楼层索引 +1）。
    // 只跳一次：posts 会因已读上报(markPostsRead 重建列表)等原因变化，若 key 含 posts
    // 会导致跳楼逻辑反复触发，把用户从滚动位置拉回目标楼。
    // 目标楼未加载时（index<0）继续等 posts 变化，加载出来后滚一次即结束。
    var handledTarget by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(targetPostNumber) {
        val target = targetPostNumber ?: return@LaunchedEffect
        if (handledTarget == target) return@LaunchedEffect
        snapshotFlow { posts }
            .first { currentPosts ->
                val index = currentPosts.indexOfFirst { it.postNumber == target }
                if (index >= 0) {
                    handledTarget = target
                    listState.animateScrollToItem(index + 1)
                    true
                } else {
                    false
                }
            }
    }

    // VM 发起的跳楼（帖内链接定位 / 引用跳楼 / 换窗定位 / 楼层 scrubber）：
    // 一次性请求，处理完立刻消费清除。
    LaunchedEffect(scrollRequestPostNumber) {
        val target = scrollRequestPostNumber ?: return@LaunchedEffect
        val index = posts.indexOfFirst { it.postNumber == target }
        if (index >= 0) {
            val listIndex = index + 1
            // 目标楼已**完整**在视口内 → 原地不动，只靠落地高亮提示。
            // 对齐鸿蒙 ScrollAlign.AUTO 的语义：不把用户已经看得见的楼层无谓拽到屏顶。
            // scrubber 拖到相邻楼时这个差别很明显——不判的话每跳一次内容都要往上顶一下。
            val layout = listState.layoutInfo
            val info = layout.visibleItemsInfo.firstOrNull { it.index == listIndex }
            val fullyVisible = info != null &&
                info.offset >= layout.viewportStartOffset &&
                info.offset + info.size <= layout.viewportEndOffset
            if (!fullyVisible) {
                listState.animateScrollToItem(listIndex)
            }
        }
        onScrollRequestConsumed()
    }

    // 阅读计时：把当前在屏的楼层号喂给 tracker（对齐鸿蒙 onVisibleRange）。
    // 列表布局：index 0 = 标题卡；hasEarlier 时 index 1 = 更早加载器；其后才是 posts 楼层；
    // 底部 load_more 占位在 items 之后。用 layoutInfo 的可见项反查楼层号，防抖只上报变化。
    LaunchedEffect(listState, posts, hasEarlier) {
        val postsOffset = if (hasEarlier) 2 else 1
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
            .collect { indexes ->
                val visible = indexes.mapNotNull { itemIndex ->
                    val postIndex = itemIndex - postsOffset
                    posts.getOrNull(postIndex)?.postNumber
                }
                if (visible.isNotEmpty()) {
                    onVisiblePostNumbers(visible)
                }
            }
    }

    LazyColumn(
        state = listState,
        // 底部 72dp：让最后一楼的操作区完全越过 52dp 悬浮回复按钮（对齐鸿蒙底部余量）。
        contentPadding = PaddingValues(top = headerTopPadding, bottom = 72.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // 话题标题
        item {
            TopicHeader(topic = topic, categories = categories)
            // 抽奖帖提醒:挂在摘要与一楼之间,跟着列表滚走。刻意塞进这个已存在的 item 而不新开
            // 一项——已读上报/顶栏双层标题/scrubber 都硬依赖「index 0 是摘要、楼层从 index 1 起」
            // 的下标算术,多一项就得让这些偏移量随卡片在不在而变(对齐鸿蒙 TopicDetailPage:437)。
            if (showLotteryNotice) {
                LotteryNoticeCard(
                    // 官方规则帖走站内链接路由,直开原生详情页(不落 WebView)。
                    onOpenAnnouncement = { onOpenLink(LOTTERY_RULES_TOPIC_URL) }
                )
            }
        }

        // 窗口式进入时往上补更早楼层（自动触发：出现在列表顶部即拉）。
        if (hasEarlier) {
            item(key = "earlier_loader") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isLoadingEarlier) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    } else {
                        TextButton(onClick = onLoadEarlier) {
                            Text(if (earlierCount > 0) "加载更早（还有 $earlierCount 楼）" else "加载更早")
                        }
                    }
                }
            }
        }

        // 回复列表
        items(
            items = posts,
            key = { it.id }
        ) { post ->
            TopicPostItem(
                post = post,
                topicCreatedById = topicCreatedById,
                onOpenUser = onOpenUser,
                acceptedAnswers = if (post.postNumber == 1) acceptedAnswers else emptyList(),
                flashTargetPostId = flashTargetPostId,
                onOpenLink = onOpenLink,
                onOpenImages = onOpenImages,
                // 帖内音频:论坛同源上传件经 host 桥的下载委托过 CF 下到沙箱再播(对齐鸿蒙
                // TopicDetailPage.ets:476 的 onResolveMedia → bridge.downloadMedia)。
                onResolveMedia = { url -> ArkWebNetworkBridge.downloadMedia(url) },
                // 回应人数胶囊 → 「谁回应了」弹层(对齐鸿蒙 TopicDetailPage:483 openReactionUsers)。
                onOpenReactions = { onOpenReactions(post) },
                onReply = onReply,
                onJumpToPost = onJumpToPost,
                // 「上次访问」红线：只画在锚楼底边（对齐鸿蒙 :456 的 post.id === anchor && anchor > 0）。
                showLastVisitedLine = lastVisitedAnchorPostId > 0L && post.id == lastVisitedAnchorPostId,
                // 「俺也一样」：话题级功能，仅一楼渲染（页面只对一楼下发，对齐鸿蒙）。
                sharedIssueVisible = post.postNumber == 1 && sharedIssueVisible,
                sharedIssueCreated = sharedIssueCreated,
                sharedIssueCount = sharedIssueCount,
                sharedIssueCanCreate = sharedIssueCanCreate,
                onToggleSharedIssue = onToggleSharedIssue,
                onVotePoll = onVotePoll,
                onRemovePollVote = onRemovePollVote,
                boostDeleteTargetId = boostDeleteTargetId,
                boostDeleteLoadingId = boostDeleteLoadingId,
                onToggleBoostDelete = onToggleBoostDelete,
                onDeleteBoost = onDeleteBoost,
                onAddBoost = onAddBoost,
                onToggleReaction = onToggleReaction,
                onToggleBookmark = onToggleBookmark,
                onCopyPostLink = onCopyPostLink,
                onRepliesLoad = onRepliesLoad,
                onEdit = { onEdit(it) },
                editLoadingPostId = editLoadingPostId,
                postActionLoadingId = postActionLoadingId,
                onDeletePost = { onDeletePost(it) },
                onRecoverPost = { onRecoverPost(it) }
            )
        }

        // 待审核区(对齐鸿蒙 TopicDetailPage:547):铺在楼层流最底部,且只有楼层全部加载完
        // (!hasMore)才渲染——停在第 3 楼时不该看见它。待审帖不是楼层:不占楼号、不进已读上报。
        if (!hasMore && pendingPosts.isNotEmpty()) {
            item(key = "pending_posts") {
                Column {
                    pendingPosts.forEach { pending ->
                        PendingPostCard(
                            pending = pending,
                            currentUser = currentUser,
                            onDelete = { onDeletePendingPost(pending) }
                        )
                    }
                }
            }
        }

        // 加载更多：尽头文案行（对齐鸿蒙 TopicDetailPage.ets:563-589——转圈与 13sp 次级色文案
        // 同排居中，行高 54）。文案由 VM 的 loadMoreMessage 驱动：「正在加载更多」/「没有更多了」/
        // 「加载更多失败，上拉重试」。兜底 ifBlank 覆盖「刚进帖、hasMore 已被置 false 但还没跑过
        // loadMorePosts」的空档，否则会渲染一条 54 高的空行。
        if (posts.isNotEmpty() && (isLoadingMore || !hasMore)) {
            item(key = "load_more_footer") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isLoadingMore) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = arkdoColors.brand,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = loadMoreMessage.ifBlank { "没有更多了" },
                        fontSize = 13.sp,
                        color = arkdoColors.textTertiary
                    )
                }
            }
        } else if (hasMore) {
            item(key = "load_more_trigger") {
                // key 用 posts.size:每成功并入一批新楼就换 key,LaunchedEffect 重启,
                // 底部加载器仍在视口内时会继续拉下一页(长列表连续上拉不断流)。
                LaunchedEffect(posts.size) {
                    onLoadMore()
                }
            }
        }
    }
}

/**
 * 详情页顶栏：实色底 + 双层标题（对齐鸿蒙 TopicDetailHeader.ets）。
 *
 * 图层 A：摘要在视口内时的静态标题「帖子详情」；图层 B：滑出后的帖子标题 + 分类/标签行。
 * 两层常驻，透明度 + 竖向位移（±8dp）随 summaryVisible 切换（250ms EaseInOut）。
 * 图层 B 整行可点：回一楼（跳到第一楼，对齐鸿蒙 jumpToFirstPost）。
 * 材质：surface 实色 + 栏底 0.5dp 细线，不做模糊（Haze 已从工程移除）。
 */
@Composable
private fun TopicDetailHeaderBar(
    modifier: Modifier = Modifier,
    summaryVisible: Boolean,
    title: String,
    category: TopicCategory?,
    parentCategory: TopicCategory?,
    tags: List<TopicTag>,
    solved: Boolean,
    bookmarked: Boolean,
    onBack: () -> Unit,
    onTitleClick: () -> Unit,
    /** 过盾快捷入口是否可见（对齐鸿蒙 HdsNavigation.menu 的 shouldShowChallengeShortcut）。 */
    showChallengeShortcut: Boolean = false,
    onOpenChallenge: () -> Unit = {}
) {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val headerHeight = statusBarPadding + 56.dp
    val layerAlphaA by animateFloatAsState(
        targetValue = if (summaryVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 250, easing = EaseInOut),
        label = "titleLayerA"
    )
    val layerAlphaB by animateFloatAsState(
        targetValue = if (summaryVisible) 0f else 1f,
        animationSpec = tween(durationMillis = 250, easing = EaseInOut),
        label = "titleLayerB"
    )
    val density = LocalDensity.current
    val translateA = with(density) { (if (summaryVisible) 0.dp else (-8).dp).toPx() }
    val translateB = with(density) { (if (summaryVisible) 8.dp else 0.dp).toPx() }

    // 实色底（用户反馈玻璃模糊下标题看不清）：浅色为纯白、深色取 surface（与楼层卡
    // 同档的微亮面）；栏底 0.5dp 细线交代内容滚入栏下的边界——设置页 / 他人主页同款。
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(headerHeight)
            .background(MaterialTheme.colorScheme.surface)
    ) {
        HorizontalDivider(
            modifier = Modifier.align(Alignment.BottomCenter),
            color = arkdoColors.border,
            thickness = 0.5.dp
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 12.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Box(modifier = Modifier.weight(1f).height(56.dp)) {
                // 图层 A：静态「帖子详情」。
                Text(
                    text = "帖子详情",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .graphicsLayer { alpha = layerAlphaA; translationY = translateA }
                )
                // 图层 B：帖子标题 + 分类/标签行，整行可点回一楼。标题行首按「书签 → 已解决勾」
                // 顺序内联 15dp 图标（间距 5，垂直居中），颜色烘死在矢量资源里（对齐鸿蒙 ImageSpan）。
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .graphicsLayer { alpha = layerAlphaB; translationY = translateB }
                        .clickable(enabled = layerAlphaB > 0.5f, onClick = onTitleClick)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (bookmarked) {
                            Icon(
                                painter = painterResource(R.drawable.ic_fa_bookmark_gray),
                                contentDescription = "已收藏",
                                tint = Color.Unspecified,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                        }
                        if (solved) {
                            Icon(
                                painter = painterResource(R.drawable.ic_fa_far_square_check_green),
                                contentDescription = "已解决",
                                tint = Color.Unspecified,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                        }
                        Text(
                            text = title,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (category != null || tags.isNotEmpty()) {
                        TopicMetadataBadges(
                            category = category,
                            parentCategory = parentCategory,
                            tags = tags,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
            // 过盾快捷入口（对齐鸿蒙 TopicDetailPage 的 titleBar menu 条目）。
            //
            // 阅读时的 timings 上报是 notifyChallenge:false 的后台请求 —— 撞盾时不会弹验证页，
            // 用户只看得到「蓝点一直不消失」，完全不知道发生了什么。这个按钮就是那个静默失败
            // 的唯一出口：点它由 ChallengeRecoveryCoordinator 停掉后台静默轮询、把验证页交给用户。
            //
            // 只在真的被拦下时才出现（不是常驻）：入口占的是标题栏空间，多数用户从不撞盾，
            // 常驻只会碍眼；「图标在 = 此刻确实有问题」也是鸿蒙那套语义。
            if (showChallengeShortcut) {
                IconButton(onClick = onOpenChallenge) {
                    Icon(
                        imageVector = Icons.Outlined.Shield,
                        contentDescription = "手动验证",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun TopicHeader(
    topic: com.arkdo.android.data.models.TopicDetail,
    categories: List<TopicCategory>
) {
    // 1:1 对齐鸿蒙 TopicSummaryCard：标题(22 加粗) → 分类/标签徽章行(8) →
    // 元信息行(12 次要色,8)。全部左对齐，底部 1dp 分隔。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, top = 20.dp, bottom = 16.dp)
    ) {
        Text(
            text = topic.title,
            fontSize = 22.sp,
            lineHeight = 30.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        val category = CategoryIndex.find(categories, topic.categoryId)
        val parentCategory = CategoryIndex.findParent(categories, topic.categoryId)
        if (category != null || topic.tags.isNotEmpty()) {
            TopicMetadataBadges(
                category = category,
                parentCategory = parentCategory,
                tags = topic.tags,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )
        }

        val postsCount = topic.postsCount ?: 0
        val replyCount = topic.replyCount ?: 0
        val likeCount = topic.likeCount ?: 0
        val viewCount = topic.viewCount ?: 0
        Text(
            text = "${postsCount} 帖 · ${replyCount} 回复 · ${likeCount} 赞 · ${viewCount} 浏览",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun LoadingView() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorView(
    message: String,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Text("重试")
        }
    }
}

/** 抽奖帖提醒判定:纯函数(LotteryTopicRule)+ 用户开关,页面层只组装数据。 */
private fun lotteryNoticeShowing(
    topic: TopicDetail?,
    currentUserId: Long?,
    tipEnabled: Boolean
): Boolean {
    topic ?: return false
    if (!tipEnabled) return false
    return shouldShowLotteryNotice(
        LotteryNoticeContext(
            tags = topic.tags,
            closed = topic.closed,
            archived = topic.archived,
            createdById = topic.createdById ?: 0L,
            currentUserId = currentUserId ?: 0L
        )
    )
}
