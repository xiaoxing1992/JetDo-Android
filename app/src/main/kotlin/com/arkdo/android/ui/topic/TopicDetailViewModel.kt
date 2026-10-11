package com.arkdo.android.ui.topic

import com.arkdo.android.R
import com.arkdo.android.core.data.AppContextHolder
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import android.net.Uri
import com.arkdo.android.data.models.TopicDetail
import com.arkdo.android.common.constants.AppConstants
import com.arkdo.android.data.models.PendingPost
import com.arkdo.android.data.models.PostReaction
import com.arkdo.android.data.models.ReactionUser
import com.arkdo.android.data.models.ReactionUsersPage
import com.arkdo.android.data.models.TopicCategory
import com.arkdo.android.data.models.PostBoost
import com.arkdo.android.data.models.SharedIssueResult
import com.arkdo.android.data.models.TopicPost
import com.arkdo.android.data.models.User
import com.arkdo.android.services.session.LoginGate
import com.arkdo.android.services.session.CurrentUserHolder
import com.arkdo.android.services.message.MessageBusEvent
import com.arkdo.android.services.message.MessageBusEventBus
import com.arkdo.android.services.message.extractCreatedPostId
import com.arkdo.android.services.presence.TopicPresenceService
import com.arkdo.android.services.realtime.RealtimePostsPrefs
import com.arkdo.android.services.reading.ReadingTracker
import com.arkdo.android.services.topic.PostDataSource
import com.arkdo.android.services.topic.ReactionUsersPager
import com.arkdo.android.services.topic.PostStreamPager
import com.arkdo.android.services.network.ArkWebNetworkBridge
import com.arkdo.android.services.network.JsonRequestOptions
import com.arkdo.android.services.challenge.ChallengeRecoveryEventBus
import com.arkdo.android.services.challenge.ChallengeRecoveryState
import com.arkdo.android.services.challenge.ChallengeShortcutEventBus
import com.arkdo.android.services.challenge.ChallengeShortcutPrefs
import com.arkdo.android.services.site.CategoryIndex
import com.arkdo.android.services.site.SiteSettingsService
import com.arkdo.android.services.site.SiteUploadSettings
import com.arkdo.android.services.site.defaultSiteUploadSettings
import com.arkdo.android.services.upload.DISGUISE_MIME
import com.arkdo.android.services.upload.UploadService
import com.arkdo.android.services.upload.VideoTranscoder
import com.arkdo.android.services.topic.LotteryTipPrefs
import com.arkdo.android.services.topic.LOTTERY_REPLY_HINT
import com.arkdo.android.services.topic.LOTTERY_REPLY_PLACEHOLDER
import com.arkdo.android.services.topic.LotteryNoticeContext
import com.arkdo.android.services.topic.shouldShowLotteryNotice
import com.arkdo.android.services.topic.PollMutation
import com.arkdo.android.services.topic.TopicService
import com.arkdo.android.services.content.EmojiPreviewHtml
import com.arkdo.android.services.content.getEmojiUrl
import com.arkdo.android.services.draft.DraftCountStore
import com.arkdo.android.services.draft.DraftService
import com.arkdo.android.services.draft.SaveEditDraftInput
import com.arkdo.android.services.draft.SaveReplyDraftInput
import com.arkdo.android.services.draft.replyDraftKey
import com.arkdo.android.ui.composer.ComposerUploadKind
import com.arkdo.android.ui.composer.MarkdownRenderEngine
import com.arkdo.android.ui.composer.PreparedRawFile
import com.arkdo.android.ui.composer.UploadOutcome
import com.arkdo.android.ui.composer.parseAttachmentOutcome
import com.arkdo.android.ui.composer.parseMediaOutcome
import com.arkdo.android.ui.composer.parseUploadOutcome
import com.arkdo.android.ui.composer.runUploadBatch
import com.arkdo.android.ui.drafts.DraftResume
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import javax.inject.Inject
import kotlin.coroutines.resume

/** 每页拉取楼层数，对齐鸿蒙 POSTS_PAGE_SIZE。 */
private const val POSTS_PAGE_SIZE = 20

/** 跳楼落地高亮时长，对齐鸿蒙 armTargetFlash 的 3s。 */
private const val TARGET_FLASH_MS = 3000L

/** 作者软删除后站点回的中文占位（响应没带 cooked 时本地兜底）。 */
private val DELETED_BY_AUTHOR_HTML: String
    get() = AppContextHolder.getString(R.string.detail_post_deleted_html)

/** 回帖/编辑草稿的自动保存防抖，对齐鸿蒙 TopicDetailPage 的 REPLY_DRAFT_SAVE_DEBOUNCE_MS（2 秒）。 */
private const val REPLY_DRAFT_SAVE_DEBOUNCE_MS = 2000L

/**
 * 实时新楼去抖窗口：合并 ~400ms 内连着来的多条 created，一次批量拉
 * （对齐鸿蒙 TopicDetailPage 的 INCOMING_POST_DEBOUNCE_MS）。
 */
private const val INCOMING_POST_DEBOUNCE_MS = 400L

/**
 * 话题详情页 ViewModel —— 窗口式加载，对齐鸿蒙 TopicDetailPage 的数据流。
 *
 * 首屏从一楼开窗；跳楼时：目标楼已加载 → 发一次性滚动请求；未加载 →
 * [rewindowToPost] 以目标楼为锚重拉一窗（详情接口本身支持楼号锚点）。
 * 双向分页沿 stream 由 [PostStreamPager] 取 id、[PostDataSource] 按楼号有序落位。
 *
 * 鸿蒙侧 loadEarlier 的 prefetch/pending 两段式（滚动停稳才插、避免列表跳动）
 * 在 Compose 由 LazyColumn 的 key 锚定行为承担，这里直接 prepend；
 * 滚动位置补偿若实测有跳动再在屏幕层补 —— 平台差异，行为语义不变。
 */
@HiltViewModel
class TopicDetailViewModel @Inject constructor(
    private val topicService: TopicService,
    private val draftService: DraftService,
    private val readStateSource: com.arkdo.android.core.data.session.TopicReadStateSource,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val dataSource = PostDataSource()

    private val _uiState = MutableStateFlow(TopicDetailUiState())
    val uiState: StateFlow<TopicDetailUiState> = _uiState.asStateFlow()

    /** 阅读计时上报（对齐鸿蒙 TopicReadController 的 ReadingTracker 托管）。 */
    private val readingTracker = ReadingTracker(viewModelScope)

    /**
     * tracker 是否已为本页启动过（对齐鸿蒙 TopicReadController.started）。
     * 只在 detach 或换话题时复位；applyDetail 因 rewindow 会被反复调用，不能据此重启。
     */
    private var trackerStarted = false

    /** 上一次喂给 tracker 的在屏楼号（仅用于日志去重，避免滚动时刷屏）。 */
    private var lastOnscreenLogged: List<Int> = emptyList()

    /** 本次浏览到的最高楼号（对齐鸿蒙 TopicReadController.maxSeenPostNumber，结算已读水位用）。 */
    private var maxSeenPostNumber = 0

    /**
     * 最近一次成功应用的详情快照，供 [ensureReadingTracker] 幂等拉起上报用。
     *
     * 为什么需要它：页面重建（配置变更）后 Compose 会重新走 ON_RESUME，而此时 `loadTopic`
     * 的 LaunchedEffect key 没变、`applyDetail` 不会再执行 —— 如果 tracker 在重建过程中被停掉，
     * 就没有任何路径能把它起回来。这里留一份详情，让前台信号能重新驱动启动（内部有
     * [trackerStarted] 闸门，重复调用无害）。
     */
    private var lastDetail: TopicDetail? = null

    /** 撞盾挂起批次补发的前提：过盾成功（COOLDOWN）信号（对齐鸿蒙 TopicReadController.attach 的订阅）。 */
    private val challengeUnsubscribe = ChallengeRecoveryEventBus.subscribeState { state ->
        if (state == ChallengeRecoveryState.COOLDOWN) {
            readingTracker.notifyChallengeResolved()
        }
    }

    /**
     * 本次阅读的 timings 上报是否正被 Cloudflare 挡着 —— 顶栏右侧「手动验证」入口的显示条件。
     *
     * 真源是全局 [ChallengeShortcutEventBus]：ReadingTracker 的后台上报（notifyChallenge:false，
     * 不弹验证页）撞盾时置真，之后上报通了置假。这里只做镜像，让页面能用 Compose 状态订阅它。
     *
     * 语义对齐鸿蒙 TopicDetailPage.challengeBlocked：入口可见 = 用户开了 ChallengeShortcutPrefs
     * 开关（默认关）∧ 真被拦下（bus 置真）。两条件在 combine 里 AND，开关翻转时入口即时显隐。
     */
    private val _busChallengeBlocked = MutableStateFlow(false)
    val challengeBlocked: StateFlow<Boolean> = combine(
        _busChallengeBlocked,
        ChallengeShortcutPrefs.enabled
    ) { blocked, enabled -> blocked && enabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val challengeShortcutUnsubscribe = ChallengeShortcutEventBus.subscribe { blocked ->
        _busChallengeBlocked.value = blocked
    }

    /** 用户点顶栏「手动验证」：请求恢复协调器停下来静默重试、把验证页交给用户（对齐鸿蒙 onOpenChallenge）。 */
    fun requestManualChallenge() {
        Timber.i("[JetDo][TopicDetail] manual challenge requested topicId=%s", currentTopicId)
        ChallengeRecoveryEventBus.requestManual()
    }

    override fun onCleared() {
        challengeUnsubscribe()
        challengeShortcutUnsubscribe()
        // 实时链路兜底退订：正常路径由 onDetailDetach 收尾，但 VM 被直接回收（进程内导航栈清理）
        // 时那个回调未必到，留着订阅会让已销毁的页面继续收事件。
        releaseRealtime(clearBanner = true)
        renderEngine?.release()
        renderEngine = null
        readingTracker.stop()
        super.onCleared()
    }

    private var currentTopicId: Long? = null
    private var currentSlug: String? = null
    /** 从草稿列表带进来的续写。发表成功后删掉，只删一次。 */
    private var resumedDraft: DraftResume? = null
    private var draftComposerOpened = false

    // ===== 回帖 / 编辑草稿自动落盘（对齐鸿蒙 TopicDetailPage 的 replyDraft* 一族）=====
    // 回帖与编辑**共用同一个 key（topic_<id>）与同一个序号**：各存各的序号时，交替保存
    // 必有一方拿着过期序号被服务端拒（鸿蒙源码里对这点有明确注释，照搬其语义）。
    private var replyDraftSequence = 0L
    private var replyDraftSaving = false
    private var replyDraftDirty = false
    private var replyDraftPersisted = false
    private var replyDraftPublished = false
    private var replyDraftJob: Job? = null

    /** 离屏 markdown 引擎：用户真去切预览时才懒建，页面销毁释放。 */
    private var renderEngine: MarkdownRenderEngine? = null
    // 「上次访问」红线：进入时快照的已读水位（只取一次，页内重开窗口 / 本次阅读推高水位都不
    // 重取，否则线会消失）+ 锚楼 id（快照后第一条未读楼的上一楼，锚一旦确定不再变化）。
    // 对齐鸿蒙 TopicDetailPage.ets:177-180 / :2114-2132。
    private var lastVisitedSnapshot: Int = 0
    private var lastVisitedAnchorPostId: Long = 0L

    // ===== 帖内实时：新回复（/topic/<id> 的 created）+「正在回复」presence =====
    // 两者同受「帖子内新回复提醒」开关门控（对齐鸿蒙 TopicDetailPage.realtimePostsEnabled，
    // 那边 presence 也挂在这一个开关下）。开关默认关 —— 见 RealtimePostsPrefs。

    /** 已挂上实时订阅的 topicId（0 = 未挂）。换帖/退订时复位。 */
    private var realtimeTopicId = 0L
    private var messageBusUnsubscribe: (() -> Unit)? = null
    /** created 只带 post id、不带 cooked：收集待拉 id，去抖后一次 loadMorePosts 批量拉。 */
    private val pendingCreatedPostIds = mutableListOf<Long>()
    private var createdFlushJob: Job? = null
    /** 列表是否停在底部。在底部时新楼直接追加、不计数；否则累计提示条。由屏幕层上报。 */
    private var listAtEnd = true
    /** 提示条里首条新楼的楼号（点按跳过去）。0 = 当前没有可跳目标。 */
    private var incomingFirstNewPostNumber = 0

    /** 最近一次拉到的站点上传设置（算体积上限与选择器后缀过滤）。加载完成前是出厂默认值。 */
    private var uploadSettings: SiteUploadSettings = defaultSiteUploadSettings()

    /**
     * 「正在回复」下行服务。bridge 走闭包惰性读取（单例在 Application 里才装配好），
     * 当前用户 id 每次现取（换号后不必重建服务）。
     */
    private val presenceService = TopicPresenceService(
        scopeProvider = { viewModelScope },
        bridgeProvider = { ArkWebNetworkBridge },
        currentUserId = { CurrentUserHolder.currentUser.value?.id ?: 0L },
        onChange = { users -> _uiState.update { it.copy(presenceUsers = users) } }
    )

    init {
        // 开关热切换（对齐鸿蒙 TopicDetailPage 的 @Watch onRealtimePostsToggled）：
        // 开 ⇒ 补订本帖频道 + 启动 presence；关 ⇒ 退订、停 presence、清提示条。
        // 首次收值即当前开关态，detail 未就位时 attachRealtime 是 no-op，等 applyDetail 再挂。
        viewModelScope.launch {
            RealtimePostsPrefs.enabled.collect { enabled ->
                if (enabled) attachRealtime() else releaseRealtime(clearBanner = true)
            }
        }
    }

    fun loadTopic(
        topicId: Long,
        slug: String?,
        title: String?,
        targetPostNumber: Int? = null,
        draft: DraftResume? = null
    ) {
        if (draft != null && resumedDraft == null) {
            resumedDraft = draft
        }
        if (currentTopicId == topicId) {
            // 同一话题再次进入（如引用跳楼）：走跳楼分流，不重复首屏加载。
            if (targetPostNumber != null) {
                jumpToPostNumber(targetPostNumber)
            }
            // 页面重建（配置变更）或离页后再回来会重新走这里：实时订阅可能已被 onDetailDetach
            // 退掉，这里补挂（幂等——同帖已在挂时 attachRealtime 直接返回）。
            attachRealtime()
            maybeOpenDraftComposer()
            return
        }
        currentTopicId = topicId
        currentSlug = slug
        // 换了话题：浏览水位归位，别把上一帖的最高楼号带进这一帖的已读结算。
        maxSeenPostNumber = 0
        // 编辑器上传入口的显隐取决于站点设置（白名单/新用户附件限额）：进帖即预热，
        // 等用户点开编辑器时已就绪，不会出现「先只有图片钮、过一秒多出两个」的跳动。
        loadUploadCapabilities()
        // 换了话题：上一帖的 tracker 收尾并复位启动标志（对齐鸿蒙换页 = 新 controller 实例）。
        if (trackerStarted) {
            readingTracker.stop()
            trackerStarted = false
        }
        // 换了话题：清掉上一帖的撞盾运行态（对齐鸿蒙 aboutToAppear 里的 ChallengeShortcutEventBus.reset）。
        // reset 不广播，所以镜像状态要自己同步回落，否则新帖首帧会误亮「手动验证」入口。
        ChallengeShortcutEventBus.reset()
        _busChallengeBlocked.value = false
        // 换了话题：红线水位与锚楼一并复位（否则新帖会沿用上一帖的锚楼 id）。
        lastVisitedSnapshot = 0
        lastVisitedAnchorPostId = 0L

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val result = topicService.getTopicDetail(topicId, slug)

            if (result.success && result.data != null) {
                applyDetail(result.data)
                _uiState.update { it.copy(isLoading = false) }
                loadCategoriesIfNeeded()
                // 对齐鸿蒙：带目标楼进入时首屏照常从一楼加载，随后走跳楼分流。
                if (targetPostNumber != null) {
                    jumpToPostNumber(targetPostNumber)
                }
                maybeOpenDraftComposer()
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = result.error?.message ?: appContext.getString(R.string.common_load_failed)
                    )
                }
            }
        }
    }

    /**
     * 跳到指定楼层：已加载 → 发一次性滚动请求并武装落地高亮；
     * 未加载 → 重开窗口。对齐鸿蒙 jumpToPostNumber。
     */
    fun jumpToPostNumber(postNumber: Int) {
        val index = dataSource.findIndexByPostNumber(postNumber)
        if (index >= 0) {
            _uiState.update {
                it.copy(
                    scrollRequestPostNumber = postNumber,
                    flashTargetPostId = dataSource.posts[index].id
                )
            }
            armTargetFlash(dataSource.posts[index].id)
        } else {
            rewindowToPost(postNumber)
        }
    }

    /**
     * 引用卡「展开查看被引用楼层全文」：先翻已加载楼层（仅本帖流里有），没有再
     * `GET /posts/by_number/{topicId}/{postNumber}` 拉一次。
     * 对齐鸿蒙 TopicDetailPage.resolveQuotedPost（:1658）。
     *
     * 拿不到返回空串 —— 渲染层据此隐藏展开箭头、只保留折叠预览，不弹错误（引用卡是次要内容，
     * 一次拉取失败不该打断阅读）。这是挂起函数：由引用卡自己的 coroutineScope 调用，
     * 楼层被 LazyColumn 回收时随作用域一并取消，不会在已卸载的 UI 上落地。
     */
    suspend fun resolveQuotedPost(topicId: Long, postNumber: Int): String {
        if (topicId <= 0L || postNumber <= 0) return ""
        if (topicId == _uiState.value.topic?.id) {
            val index = dataSource.findIndexByPostNumber(postNumber)
            if (index >= 0) {
                val html = dataSource.posts[index].cookedHtml
                if (html.isNotEmpty()) return html
            }
        }
        val result = topicService.getPostByNumber(topicId, postNumber)
        return if (result.success) result.data.orEmpty() else ""
    }

    /**
     * 以目标楼为锚重拉一窗并整体替换（鸿蒙 rewindowToPost）。
     * 失败时旧列表保持原位，只发一条提示消息。
     */
    private fun rewindowToPost(postNumber: Int, asTop: Boolean = false) {
        val topic = _uiState.value.topic ?: return
        if (_uiState.value.isRewindowing) {
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isRewindowing = true) }
            val result = topicService.getTopicDetail(topic.id, currentSlug, postNumber)
            _uiState.update { it.copy(isRewindowing = false) }

            val detail = result.data
            if (result.success && detail != null && detail.postStream?.posts?.isNotEmpty() == true) {
                applyDetail(detail)
                _uiState.update {
                    it.copy(
                        scrollRequestPostNumber = if (asTop) null else postNumber,
                        flashTargetPostId = if (asTop) null else detail.postStream?.posts
                            ?.firstOrNull { it.postNumber >= postNumber }?.id
                    )
                }
                if (!asTop) {
                    _uiState.value.flashTargetPostId?.let(::armTargetFlash)
                }
            } else {
                _uiState.update { it.copy(actionMessage = appContext.getString(R.string.detail_jump_failed)) }
            }
        }
    }

    /** 上拉加载更多（向下分页），对齐鸿蒙 loadMorePosts。 */
    fun loadMorePosts() {
        val state = _uiState.value
        val topic = state.topic ?: return
        if (state.isLoadingMore || !state.hasMore) {
            return
        }
        val stream = topic.postStream?.stream ?: emptyList()
        val nextIds = PostStreamPager.collectNextIds(stream, dataSource.posts, POSTS_PAGE_SIZE)
        if (nextIds.isEmpty()) {
            _uiState.update { it.copy(hasMore = false, loadMoreMessage = appContext.getString(R.string.common_no_more)) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true, loadMoreMessage = appContext.getString(R.string.common_loading_more)) }
            val result = topicService.loadMorePosts(topic.id, nextIds)

            if (result.success && result.data != null) {
                dataSource.appendPosts(result.data)
                _uiState.update {
                    it.copy(
                        isLoadingMore = false,
                        posts = dataSource.snapshotPosts,
                        hasMore = PostStreamPager.countLater(stream, dataSource.posts) > 0,
                        loadMoreMessage = if (
                            PostStreamPager.countLater(stream, dataSource.posts) > 0
                        ) appContext.getString(R.string.common_pull_load_more) else appContext.getString(R.string.common_no_more)
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isLoadingMore = false,
                        loadMoreMessage = appContext.getString(R.string.common_load_more_failed)
                    )
                }
            }
        }
    }

    /** 下拉补更早楼层（向上分页），对齐鸿蒙 prefetchEarlierPosts 的数据流部分。 */
    fun loadEarlierPosts() {
        val state = _uiState.value
        val topic = state.topic ?: return
        if (state.isLoadingEarlier || !state.hasEarlier) {
            return
        }
        val stream = topic.postStream?.stream ?: emptyList()
        val prevIds = PostStreamPager.collectPrevIds(stream, dataSource.posts, POSTS_PAGE_SIZE)
        if (prevIds.isEmpty()) {
            _uiState.update { it.copy(hasEarlier = false) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingEarlier = true) }
            val result = topicService.loadMorePosts(topic.id, prevIds)

            if (result.success && result.data != null) {
                dataSource.prependPosts(result.data)
                _uiState.update {
                    it.copy(
                        isLoadingEarlier = false,
                        posts = dataSource.snapshotPosts,
                        hasEarlier = PostStreamPager.countEarlier(stream, dataSource.posts) > 0,
                        earlierCount = PostStreamPager.countEarlier(stream, dataSource.posts)
                    )
                }
            } else {
                _uiState.update { it.copy(isLoadingEarlier = false) }
            }
        }
    }

    /** 按需加载站点分类（应用级缓存），供摘要卡分类徽章显示。对齐鸿蒙 loadSiteMetadataIfNeeded。 */
    private fun loadCategoriesIfNeeded() {
        if (_uiState.value.categories.isNotEmpty()) {
            return
        }
        viewModelScope.launch {
            CategoryIndex.loadIfNeeded(
                bridge = ArkWebNetworkBridge,
                ready = true,
                alreadyLoaded = _uiState.value.categories.isNotEmpty(),
                logTag = "[JetDo][TopicDetail]"
            )?.let { loaded ->
                _uiState.update { it.copy(categories = loaded) }
            }
        }
    }

    /**
     * 表情回应切换（点赞 = heart）：请求先行，结果经 done 回调（成功 true）。
     * 成功后的本地计数/已点态刷新依赖详情重拉，暂以回调通知屏幕。
     */
    // ── 「谁回应了」弹层(对齐鸿蒙 TopicDetailPage.openReactionUsers / loadReactionUsersPage) ──

    /** 翻页游标:下一次要取的页(服务端 page 从 0 起)。 */
    private var reactionSheetNextPage: Int = 0

    /** 打开弹层即自增;在途响应回来时 epoch 不匹配则丢弃,防止旧弹层的响应写进新弹层。 */
    private var reactionSheetEpoch: Int = 0

    fun openReactionSheet(post: TopicPost) {
        val total = post.reactionUsersCount ?: 0
        if (total <= 0) return
        reactionSheetEpoch++
        reactionSheetNextPage = 0
        _uiState.update {
            it.copy(
                reactionSheet = ReactionUsersSheetState(
                    post = post,
                    reactions = post.reactions.orEmpty(),
                    totalRows = total,
                    hasMore = true
                )
            )
        }
        loadReactionUsersPage(reset = true, page = 0)
    }

    fun dismissReactionSheet() {
        reactionSheetEpoch++
        _uiState.update { it.copy(reactionSheet = null) }
    }

    /** 切过滤表情:纯客户端过滤已加载名单,不重发请求(对齐鸿蒙 selectedReactionUserFilter)。 */
    fun selectReactionFilter(reaction: String) {
        _uiState.update {
            it.reactionSheet?.let { sheet -> it.copy(reactionSheet = sheet.copy(selectedReaction = reaction)) } ?: it
        }
    }

    fun loadMoreReactionUsers() {
        val sheet = _uiState.value.reactionSheet ?: return
        if (!sheet.hasMore || sheet.loadMoreLoading || sheet.initialLoading) return
        loadReactionUsersPage(reset = false, page = reactionSheetNextPage)
    }

    fun retryReactionUsers() {
        val sheet = _uiState.value.reactionSheet ?: return
        if (sheet.initialError != null) {
            loadReactionUsersPage(reset = true, page = 0)
        } else {
            loadMoreReactionUsers()
        }
    }

    private fun loadReactionUsersPage(reset: Boolean, page: Int) {
        val sheet = _uiState.value.reactionSheet ?: return
        if (sheet.initialLoading || sheet.loadMoreLoading) return
        if (!reset && !sheet.hasMore) return
        val epoch = reactionSheetEpoch
        _uiState.update {
            it.reactionSheet?.let { s ->
                it.copy(
                    reactionSheet = if (reset) {
                        s.copy(initialLoading = true, initialError = null)
                    } else {
                        s.copy(loadMoreLoading = true, loadMoreError = null)
                    }
                )
            } ?: it
        }
        viewModelScope.launch {
            val result = topicService.getReactionUsers(sheet.post.id, page)
            if (epoch != reactionSheetEpoch) return@launch
            _uiState.update {
                it.reactionSheet?.let { s ->
                    if (!result.success || result.data == null) {
                        val message = result.error?.message ?: appContext.getString(R.string.detail_reactions_load_failed)
                        it.copy(
                            reactionSheet = if (reset) {
                                s.copy(initialLoading = false, initialError = message)
                            } else {
                                s.copy(loadMoreLoading = false, loadMoreError = message)
                            }
                        )
                    } else {
                        val pageData = result.data
                        // 去重累积与 hasMore 判定走纯逻辑(对齐鸿蒙 ReactionUsersPager,含单测)。
                        val merged = ReactionUsersPager.merge(s.users, pageData, s.totalRows)
                        it.copy(
                            reactionSheet = s.copy(
                                users = merged.users,
                                totalRows = merged.totalRows,
                                initialLoading = false,
                                loadMoreLoading = false,
                                hasMore = merged.hasMore
                            )
                        )
                    }
                } ?: it
            }
            if (result.success) reactionSheetNextPage = page + 1
        }
    }

    fun toggleReaction(post: TopicPost, reaction: String, done: (Boolean) -> Unit) {
        val topicId = currentTopicId ?: return done(false)
        viewModelScope.launch {
            val result = topicService.toggleReaction(post.id, topicId, reaction)
            if (result.success) {
                // 对齐鸿蒙 ReactionMutation.applyToggle：请求成功后本地精准更新该楼回应态。
                applyReactionToggle(post.id, reaction)
            }
            done(result.success)
        }
    }

    /**
     * discourse-poll:投票(对齐鸿蒙 PollView.doVote 的乐观更新流程)。
     *
     * 顺序:先本地乐观改(立即重渲染)→ 发请求 → 成功用服务端真值校正(吸收他人
     * 并发投票的偏差)、失败回滚。本地改动走 dataSource.replacePost,它会重建快照,
     * 保证 UiState.posts 引用变化被 Compose 感知。
     */
    fun votePoll(post: TopicPost, pollName: String, optionIds: List<String>) {
        val topicId = currentTopicId ?: return
        val poll = post.polls?.firstOrNull { it.name == pollName } ?: return
        val snapshot = PollMutation.snapshot(poll)

        // 乐观:先改本地。
        replacePollLocally(post.id, pollName) { PollMutation.applyVote(it, optionIds) }

        viewModelScope.launch {
            val result = topicService.votePoll(post.id, topicId, pollName, optionIds)
            val server = result.data
            if (result.success && server != null) {
                replacePollLocally(post.id, pollName) { PollMutation.applyServerResult(it, server) }
            } else {
                // 回滚。
                replacePollLocally(post.id, pollName) { PollMutation.restore(it, snapshot) }
                _uiState.update { it.copy(actionMessage = appContext.getString(R.string.poll_vote_failed)) }
            }
        }
    }

    /** discourse-poll:撤销投票(同样乐观更新 + 失败回滚)。 */
    fun removePollVote(post: TopicPost, pollName: String) {
        val topicId = currentTopicId ?: return
        val poll = post.polls?.firstOrNull { it.name == pollName } ?: return
        val snapshot = PollMutation.snapshot(poll)

        replacePollLocally(post.id, pollName) { PollMutation.applyRemove(it) }

        viewModelScope.launch {
            val result = topicService.removePollVote(post.id, topicId, pollName)
            val server = result.data
            if (result.success && server != null) {
                replacePollLocally(post.id, pollName) { PollMutation.applyServerResult(it, server) }
            } else {
                replacePollLocally(post.id, pollName) { PollMutation.restore(it, snapshot) }
                _uiState.update { it.copy(actionMessage = appContext.getString(R.string.poll_undo_failed)) }
            }
        }
    }

    /** 就地替换某楼的某个投票,并重发快照(Compose 按引用比对,必须换新 List)。 */
    private fun replacePollLocally(
        postId: Long,
        pollName: String,
        transform: (com.arkdo.android.data.models.Poll) -> com.arkdo.android.data.models.Poll
    ) {
        val target = dataSource.findById(postId) ?: return
        val polls = target.polls ?: return
        val index = polls.indexOfFirst { it.name == pollName }
        if (index < 0) return
        val next = polls.toMutableList()
        next[index] = transform(next[index])
        dataSource.replacePost(target.copy(polls = next.toList()))
        _uiState.update { it.copy(posts = dataSource.snapshotPosts) }
    }

    private fun loadPostReplies(post: TopicPost, done: (List<TopicPost>?) -> Unit) {
        viewModelScope.launch {
            val result = topicService.getPostReplies(post.id)
            done(if (result.success) result.data else null)
        }
    }

    /** 本地回应 toggle：同表情再点=取消；切换=旧减新增；人数只在新增回应者时 +1；按数量降序。 */
    private fun applyReactionToggle(postId: Long, reaction: String) {
        _uiState.update { state ->
            val posts = state.posts.toMutableList()
            val index = posts.indexOfFirst { it.id == postId }
            if (index < 0) return@update state
            val post = posts[index]
            val reactions = (post.reactions ?: emptyList()).toMutableList()
            val prev = post.currentUserReaction
            var usersCount = post.reactionUsersCount ?: 0

            if (prev == reaction) {
                decrementReaction(reactions, reaction)
                usersCount = maxOf(0, usersCount - 1)
            } else {
                if (prev != null) {
                    decrementReaction(reactions, prev)
                } else {
                    usersCount += 1
                }
                incrementReaction(reactions, reaction)
            }
            reactions.sortByDescending { it.count }

            val updated = post.copy(
                reactions = reactions,
                reactionUsersCount = usersCount,
                currentUserReaction = if (prev == reaction) null else reaction
            )
            posts[index] = updated
            state.copy(posts = posts)
        }
    }

    private fun decrementReaction(reactions: MutableList<PostReaction>, id: String) {
        val idx = reactions.indexOfFirst { it.id == id }
        if (idx < 0) return
        val next = reactions[idx].count - 1
        if (next <= 0) reactions.removeAt(idx) else reactions[idx] = reactions[idx].copy(count = next)
    }

    private fun incrementReaction(reactions: MutableList<PostReaction>, id: String) {
        val idx = reactions.indexOfFirst { it.id == id }
        if (idx >= 0) {
            reactions[idx] = reactions[idx].copy(count = reactions[idx].count + 1)
        } else {
            reactions.add(PostReaction(id = id, count = 1))
        }
    }

    /** 被回复列表加载（页脚「回复数+箭头」胶囊展开用）。 */
    fun loadReplies(post: TopicPost, done: (List<TopicPost>?) -> Unit) {
        loadPostReplies(post, done)
    }

    /** 屏幕消费一次性滚动请求后调用，避免重组时重复触发。 */

    /**
     * 书签 toggle，对齐鸿蒙 `PostActions.toggleBookmark`（PostActions.ets:136-180）：
     * - 已收藏且带 bookmarkId → removeBookmark，成功后清掉本楼 bookmarked/bookmarkId；
     * - 其余（未收藏，或已收藏但接口没下发 id）→ addBookmark，成功后把 id 回填到本楼。
     *
     * 不再有「拿不到 id 就 done(true)」的静默成功：那样 UI 会收起书签态、Toast 也提示成功，
     * 但服务端根本没删，刷新后书签又回来（用户视角是「点了没反应 / 又活了」）。
     */
    fun toggleBookmark(post: TopicPost, done: (Boolean) -> Unit) {
        val topicId = currentTopicId ?: return done(false)
        viewModelScope.launch {
            val bookmarkId = post.bookmarkId
            if (post.bookmarked == true && bookmarkId != null) {
                val result = topicService.removeBookmark(bookmarkId, topicId)
                if (result.success) {
                    dataSource.replacePost(post.copy(bookmarked = false, bookmarkId = null))
                    // 主题级态优先用接口权威值；拿不到时退化为 false（鸿蒙 :155-156 同语义）。
                    if (post.postNumber == 1) syncTopicBookmarked(result.data ?: false)
                }
                done(result.success)
            } else {
                val result = topicService.addBookmark(post.id, topicId)
                val newId = result.data
                if (result.success && newId != null) {
                    dataSource.replacePost(post.copy(bookmarked = true, bookmarkId = newId))
                    // 新增任一楼层书签 → 主题必然有书签（鸿蒙 :172-173：topicBookmarked = true）。
                    syncTopicBookmarked(true)
                }
                done(result.success && newId != null)
            }
        }
    }

    /** 一楼收藏即主题收藏：标题栏图层 B 书签图标以此为真源，toggle 后即时联动（对齐鸿蒙）。 */
    private fun syncTopicBookmarked(value: Boolean) {
        _uiState.update { it.copy(topic = it.topic?.copy(bookmarked = value)) }
    }

    // ========== 回复输入栏（切片 7，逐行对齐鸿蒙 openComposerForTopic / openComposerForPost） ==========

    /**
     * 草稿列表点进来：详情加载完后自动打开已有回复/编辑浮层，不再请求原文。
     * 编辑必须带楼 id 和原文基线，缺一不可，否则会把旧正文当成新回复发出去。
     */
    private fun maybeOpenDraftComposer() {
        val draft = resumedDraft ?: return
        if (draftComposerOpened || draft.reply.isEmpty()) return
        draftComposerOpened = true
        // 续写沿用服务端带回来的序号：换个序号会另存成新的一条草稿。
        replyDraftSequence = draft.sequence
        replyDraftPersisted = true
        val editPostId = draft.editPostId
        val original = draft.originalText
        if (editPostId != null && editPostId > 0 && original != null) {
            resetComposerModes()
            _uiState.update {
                it.copy(
                    editingPostId = editPostId,
                    editingOriginalRaw = original,
                    composerPlaceholder = appContext.getString(R.string.detail_edit_reply),
                    composerVisible = true,
                    pendingReplyText = draft.reply
                )
            }
            return
        }
        resetComposerModes()
        val lotteryOnResume = showsLotteryNotice()
        _uiState.update {
            it.copy(
                composerPlaceholder = if (lotteryOnResume) LOTTERY_REPLY_PLACEHOLDER else appContext.getString(R.string.detail_reply_to_topic),
                composerHint = if (lotteryOnResume) LOTTERY_REPLY_HINT else "",
                composerReplyToPostNumber = null,
                composerVisible = true,
                pendingReplyText = draft.reply
            )
        }
    }

    /** 续写来的草稿发表成功后删掉。回帖自动草稿的 key 是 topic_<id>，那条由回复自动保存管，这里不重复删。 */
    private fun deleteResumedDraftIfAny() {
        val draft = resumedDraft ?: return
        resumedDraft = null
        val topicId = _uiState.value.topic?.id
        if (topicId != null && draft.draftKey == "topic_$topicId") return
        viewModelScope.launch {
            val result = draftService.deleteDraft(draft.draftKey, draft.sequence)
            // 草稿确已删除 → 角标乐观 -1（对齐鸿蒙 TopicDetailPage.deleteResumedDraftIfAny
            // 的 DraftEventBus.notifyDeleted）。不重查会话，理由同 handleDraftDeleted。
            if (result.success) {
                DraftCountStore.decrement(CurrentUserHolder.currentUser.value?.draftCount)
            }
        }
    }

    /**
     * 点击右下角悬浮回复按钮。与鸿蒙同序：未登录先拦（ensureLoggedIn 会广播请求登录，
     * 登录页弹出属登录切片）→ 复位 composer 各模式 → 定占位文字 → 再展开浮层。
     */
    fun openComposerForTopic() {
        if (!LoginGate.ensureLoggedIn(appContext)) {
            return
        }
        resetComposerModes()
        // 顶层回复 = 参与抽奖的那条:抽奖帖把占位换成社区规则提示语(对齐鸿蒙 TopicDetailPage:1033)。
        val lottery = showsLotteryNotice()
        _uiState.update {
            it.copy(
                composerPlaceholder = if (lottery) LOTTERY_REPLY_PLACEHOLDER else appContext.getString(R.string.detail_reply_to_topic),
                composerHint = if (lottery) LOTTERY_REPLY_HINT else "",
                composerReplyToPostNumber = null,
                composerVisible = true
            )
        }
    }

    /** 点某楼回复按钮：定向回复该楼，占位文字「回复<作者昵称>」（回退链对齐 resolveAuthorName）。 */
    fun openComposerForPost(post: TopicPost) {
        if (!LoginGate.ensureLoggedIn(appContext)) {
            return
        }
        resetComposerModes()
        val authorName = post.name?.takeIf { it.isNotBlank() }
            ?: post.username?.takeIf { it.isNotBlank() }
            ?: appContext.getString(R.string.user_this_user)
        _uiState.update {
            it.copy(
                composerPlaceholder = appContext.getString(R.string.detail_reply_to_user, authorName),
                composerReplyToPostNumber = post.postNumber,
                composerVisible = true
            )
        }
    }

    /** 对齐鸿蒙 resetComposerModes：回复/boost/编辑三态复位。 */
    private fun resetComposerModes() {
        // 新一轮编辑会话开始：解除「已发表」闸门。不复位的话，在同一页里发完一条回复后，
        // 再写第二条时草稿永远不会落盘（闸门一直是关的）——这是本页最容易漏的一处状态泄漏。
        replyDraftPublished = false
        replyDraftJob?.cancel()
        replyDraftJob = null
        _uiState.update {
            it.copy(
                composerReplyToPostNumber = null,
                boostTargetPostId = 0L,
                editingPostId = 0L,
                editingOriginalRaw = "",
                editLoadingPostId = 0L
            )
        }
    }

    /** 点火箭：打开 boost 输入浮层（占位「发一个 boost...」），对齐鸿蒙 openComposerForBoost。 */
    fun openComposerForBoost(post: TopicPost) {
        if (!LoginGate.ensureLoggedIn(appContext)) {
            return
        }
        resetComposerModes()
        _uiState.update {
            it.copy(
                composerPlaceholder = appContext.getString(R.string.detail_boost_hint),
                boostTargetPostId = post.id,
                composerVisible = true
            )
        }
    }

    /**
     * 点铅笔：取原文 → 打开编辑浮层（对齐鸿蒙 openComposerForEdit）。
     *
     * 列表接口只回 cooked HTML 不回 Markdown 原文，所以必须先 GET /posts/{id}.json 取 raw；
     * 这一趟顺带拿到最新原文，正好作为乐观并发基线存进 editingOriginalRaw。
     * 取原文期间先转圈（editLoadingPostId），失败 Toast，成功再开浮层预填。
     */
    fun openEditorForEdit(post: TopicPost) {
        if (!LoginGate.ensureLoggedIn(appContext)) {
            return
        }
        val state = _uiState.value
        if (state.editLoadingPostId > 0) return
        _uiState.update { it.copy(editLoadingPostId = post.id) }
        viewModelScope.launch {
            val result = topicService.getPostRaw(post.id)
            _uiState.update { it.copy(editLoadingPostId = 0L) }
            if (!result.success || result.data == null) {
                _uiState.update { it.copy(actionMessage = appContext.getString(R.string.detail_read_raw_failed)) }
                return@launch
            }
            resetComposerModes()
            _uiState.update {
                it.copy(
                    editingPostId = post.id,
                    editingOriginalRaw = result.data,
                    composerPlaceholder = appContext.getString(R.string.detail_edit_reply),
                    composerVisible = true,
                    pendingReplyText = result.data
                )
            }
        }
    }

    /**
     * 保存编辑（对齐鸿蒙 submitEdit）。成功就地把新 cooked 写回该楼（不整页重载，避免滚动位置被冲掉）；
     * 409 是编辑冲突（打开编辑框后这楼被改过），单独提示；其余失败按普通保存失败提示。
     */
    fun submitEdit(text: String, done: (Boolean) -> Unit) {
        val state = _uiState.value
        val postId = state.editingPostId
        val topic = state.topic ?: return done(false)
        if (postId <= 0) return done(false)
        val originalRaw = state.editingOriginalRaw
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmittingReply = true) }
            val result = topicService.updatePost(postId, topic.id, text, originalRaw)
            _uiState.update { it.copy(isSubmittingReply = false) }
            if (result.success && result.data != null) {
                applyEditedPost(result.data)
                deleteResumedDraftIfAny()
                deleteReplyDraftIfAny()
                _uiState.update {
                    it.copy(
                        composerVisible = false,
                        pendingReplyText = "",
                        editingPostId = 0L,
                        editingOriginalRaw = "",
                        actionMessage = appContext.getString(R.string.common_saved)
                    )
                }
                done(true)
            } else if (result.statusCode == 409) {
                _uiState.update { it.copy(actionMessage = appContext.getString(R.string.detail_reply_changed)) }
                done(false)
            } else {
                _uiState.update { it.copy(actionMessage = appContext.getString(R.string.common_save_failed2)) }
                done(false)
            }
        }
    }

    /** 把编辑后的楼层写回列表（对齐鸿蒙 applyEditedPost：就地替换，不整列重建）。 */
    private fun applyEditedPost(updated: TopicPost) {
        dataSource.replacePost(updated)
        _uiState.update { it.copy(posts = dataSource.snapshotPosts) }
    }

    /**
     * 软删除自己的楼（原站：更多菜单里的垃圾桶，不是编辑浮层）。
     *
     * DELETE 成功后楼还在：正文换成「(帖子已被作者删除)」，操作栏改成恢复。
     * 站点若回了更新后的 post 就用它的 cooked；没回就本地占位。
     */
    fun deleteOwnPost(post: TopicPost, done: (Boolean) -> Unit) {
        if (!LoginGate.ensureLoggedIn(appContext)) {
            return done(false)
        }
        val topic = _uiState.value.topic ?: return done(false)
        if (_uiState.value.postActionLoadingId > 0) return done(false)
        viewModelScope.launch {
            _uiState.update { it.copy(postActionLoadingId = post.id) }
            val result = topicService.deletePost(post.id, topic.id)
            _uiState.update { it.copy(postActionLoadingId = 0L) }
            if (result.success) {
                val fromServer = result.data
                val placeholder = fromServer?.takeIf { it.userDeleted == true }
                    ?: post.copy(
                        cookedHtml = DELETED_BY_AUTHOR_HTML,
                        userDeleted = true,
                        canRecover = true
                    )
                // mapPosts 不回填作者/被回复人/阅读态，就地替换时从原楼接回来。
                val merged = carryLocalFields(from = post, onto = placeholder)
                dataSource.replacePost(merged)
                _uiState.update { it.copy(posts = dataSource.snapshotPosts) }
                done(true)
            } else {
                _uiState.update { it.copy(actionMessage = appContext.getString(R.string.common_delete_failed2)) }
                done(false)
            }
        }
    }

    /**
     * 恢复自己软删除的楼。PUT /posts/{id}/recover，成功后用站点回的正文替换占位。
     */
    fun recoverOwnPost(post: TopicPost, done: (Boolean) -> Unit) {
        if (!LoginGate.ensureLoggedIn(appContext)) {
            return done(false)
        }
        val topic = _uiState.value.topic ?: return done(false)
        if (_uiState.value.postActionLoadingId > 0) return done(false)
        viewModelScope.launch {
            _uiState.update { it.copy(postActionLoadingId = post.id) }
            val result = topicService.recoverPost(post.id, topic.id)
            _uiState.update { it.copy(postActionLoadingId = 0L) }
            if (result.success && result.data != null) {
                val restored = result.data
                val merged = carryLocalFields(from = post, onto = restored)
                dataSource.replacePost(merged)
                _uiState.update { it.copy(posts = dataSource.snapshotPosts) }
                done(true)
            } else {
                _uiState.update { it.copy(actionMessage = appContext.getString(R.string.common_restore_failed)) }
                done(false)
            }
        }
    }

    /**
     * 服务层 mapPosts 新建的 TopicPost 只有构造参数，本地态字段是空的，写回列表前从原楼接上。
     *
     * 注意：类体可变字段已全部提升为构造参数（TopicModels.kt 里标 @Transient），所以
     * **post.copy(...) 现在会自动带上这些本地态**，本函数只服务于「从 DTO 重新映射出的新对象」。
     * 历史坑：未提升前，`post.copy(read = true)`（已读上报）会把 replyToUser 抹成 null，
     * 表现为「回复某人的头像在上报已读后消失」。
     */
    private fun carryLocalFields(from: TopicPost, onto: TopicPost): TopicPost {
        if (onto.author == null) onto.author = from.author
        if (onto.replyToUser == null) onto.replyToUser = from.replyToUser
        if (onto.plainText.isEmpty()) onto.plainText = from.plainText
        onto.reporting = from.reporting
        if (onto.badgeIcons == null) onto.badgeIcons = from.badgeIcons
        if (onto.linkClicks == null) onto.linkClicks = from.linkClicks
        // read 是构造参数，不能像上面几个一样就地赋值，只能 copy 出新对象。
        // 不接的话这一楼的已读态会被服务端响应里的旧值覆盖 —— 蓝点在删/恢复之后又冒出来。
        return if (onto.read == from.read) onto else onto.copy(read = from.read)
    }

    /** boost 提交：走 createBoost；成功回填楼层（气泡追加 + canBoost 置 false）。 */
    fun submitBoost(text: String, done: (Boolean) -> Unit) {
        val boostPostId = _uiState.value.boostTargetPostId
        val topic = _uiState.value.topic ?: return done(false)
        if (boostPostId <= 0) return done(false)
        val target = dataSource.findById(boostPostId) ?: return done(false)
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmittingReply = true) }
            val result = topicService.createBoost(boostPostId, topic.id, text)
            if (result.success && result.data != null) {
                val next = (target.boosts ?: emptyList()) + result.data!!
                dataSource.replacePost(target.copy(boosts = next, canBoost = false))
                _uiState.update {
                    it.copy(
                        isSubmittingReply = false,
                        composerVisible = false,
                        boostTargetPostId = 0L,
                        pendingReplyText = "",
                        posts = dataSource.snapshotPosts,
                        actionMessage = appContext.getString(R.string.detail_boost_sent)
                    )
                }
                done(true)
            } else {
                _uiState.update { it.copy(isSubmittingReply = false, actionMessage = appContext.getString(R.string.detail_boost_send_failed)) }
                done(false)
            }
        }
    }

    fun closeComposer() {
        flushReplyDraft()
        _uiState.update {
            it.copy(
                composerVisible = false,
                composerExpanded = false,
                editingPostId = 0L,
                editingOriginalRaw = ""
            )
        }
    }

    /**
     * 输入内容暂存：关掉浮层再点开内容还在（对齐鸿蒙 pendingReplyText），
     * 并且**停手 2 秒后自动落盘**（对齐鸿蒙 scheduleReplyDraftSave）。
     */
    fun updateReplyDraft(text: String) {
        _uiState.update { it.copy(pendingReplyText = text) }
        scheduleReplyDraftSave()
    }

    // ========== 全屏编辑器展开/收起 ==========

    /**
     * 紧凑输入条 → 全屏编辑器（对齐鸿蒙「内联回复条点展开进 ComposerPage」）。
     * 文本真源不变（仍是 pendingReplyText），只是换一种呈现，所以内容不会丢。
     */
    fun expandComposer() {
        _uiState.update { it.copy(composerExpanded = true) }
    }

    /** 全屏编辑器 → 退回紧凑条。收起时补存一次草稿，别让刚敲的字烂在防抖窗口里。 */
    fun collapseComposer() {
        flushReplyDraft()
        _uiState.update { it.copy(composerExpanded = false) }
    }

    // ========== 回帖 / 编辑草稿自动落盘 ==========

    /** 内容变化：标脏，停手 2 秒后再存。已发出的不再存（否则刚删的草稿又写回来）。 */
    private fun scheduleReplyDraftSave() {
        if (replyDraftPublished) return
        replyDraftDirty = true
        replyDraftJob?.cancel()
        replyDraftJob = viewModelScope.launch {
            delay(REPLY_DRAFT_SAVE_DEBOUNCE_MS)
            saveReplyDraftNow()
        }
    }

    /** 离屏/收起时补存一次还没到防抖点的改动。 */
    fun flushReplyDraft() {
        if (replyDraftPublished || !replyDraftDirty) return
        replyDraftJob?.cancel()
        replyDraftJob = null
        viewModelScope.launch { saveReplyDraftNow() }
    }

    /**
     * 存一次草稿。按当前是否编辑态选 schema（reply / edit），key 与序号两态共用。
     *
     * 空内容不存：一进编辑框就写一条空草稿，会平白把用户真正在写的回帖草稿顶掉
     * （鸿蒙 saveEditDraftNow 里有同样的护栏，另外还比对原文避免存"等于原文"的草稿）。
     */
    private suspend fun saveReplyDraftNow() {
        val state = _uiState.value
        val topicId = state.topic?.id ?: return
        if (replyDraftPublished || replyDraftSaving) return
        val text = state.pendingReplyText
        if (text.trim().isEmpty()) return
        val editingPostId = state.editingPostId
        // 编辑态下内容仍等于原文 → 没改过，不值得存（存了会把回帖草稿挤掉）。
        if (editingPostId > 0 && text == state.editingOriginalRaw) return
        replyDraftSaving = true
        replyDraftDirty = false
        val categoryId = state.topic?.categoryId ?: -1L
        val result = if (editingPostId > 0) {
            draftService.saveEditDraft(
                SaveEditDraftInput(
                    draftKey = replyDraftKey(topicId),
                    sequence = replyDraftSequence,
                    categoryId = categoryId,
                    reply = text,
                    postId = editingPostId,
                    originalText = state.editingOriginalRaw,
                    composerTimeMs = 0L,
                    typingTimeMs = 0L
                )
            )
        } else {
            draftService.saveReplyDraft(
                SaveReplyDraftInput(
                    draftKey = replyDraftKey(topicId),
                    sequence = replyDraftSequence,
                    categoryId = categoryId,
                    reply = text,
                    replyToPostNumber = state.composerReplyToPostNumber,
                    composerTimeMs = 0L,
                    typingTimeMs = 0L
                )
            )
        }
        replyDraftSaving = false
        if (result.success && result.data != null) {
            // 服务端回的是下次该带的序号，不存回去下次会因序号过期被拒。
            replyDraftSequence = result.data
            replyDraftPersisted = true
            Timber.i("[JetDo][TopicDetail] reply draft saved topicId=%s seq=%d", topicId, replyDraftSequence)
        } else {
            replyDraftDirty = true
        }
    }

    /** 发表/保存成功后删掉这条草稿。没真存过就不删，避免白发一次请求。 */
    private fun deleteReplyDraftIfAny() {
        replyDraftPublished = true
        replyDraftJob?.cancel()
        replyDraftJob = null
        val topicId = _uiState.value.topic?.id ?: return
        if (!replyDraftPersisted) return
        replyDraftPersisted = false
        val sequence = replyDraftSequence
        viewModelScope.launch {
            val result = draftService.deleteDraft(replyDraftKey(topicId), sequence)
            // 确已删除 → 角标乐观 -1（对齐鸿蒙的 DraftEventBus.notifyDeleted）。不重查会话，
            // 理由同 handleDraftDeleted：/session/current.json 会短暂回删除前的缓存值。
            if (result.success) {
                DraftCountStore.decrement(CurrentUserHolder.currentUser.value?.draftCount)
            }
        }
    }

    // ========== 上传与预览（全屏编辑器用） ==========

    /**
     * 执行一次上传（图片 / 附件 / 音视频共用），与发帖页 [NewTopicViewModel] 同构。
     * 差别只在两处：音视频要把文件名换成 `.xz`、MIME 换成伪装值（字节不动）；
     * 结果拼装不同（图片 `![]()`、附件 `[名|attachment]()`、音视频裸 `<video>`）。
     *
     * **不设重入闸、不动 isUploadingMedia** —— 归调用入口（[uploadImage] / [uploadPickedFiles]）管。
     * 执行层再守一道会与批量流程打架：批量入口开跑前已置 isUploadingMedia=true，执行层一进门就被
     * 自己挡回、从不调用 done，[uploadOne] 的 suspendCancellableCoroutine 便永远挂住，遮罩不落。
     */
    private fun uploadFile(
        kind: ComposerUploadKind,
        base64: String,
        fileName: String,
        mimeType: String,
        localWidth: Int = 0,
        localHeight: Int = 0,
        done: (snippet: String?, error: String?) -> Unit
    ) {
        viewModelScope.launch {
            val disguised = kind == ComposerUploadKind.Media
            val response = ArkWebNetworkBridge.uploadImage(
                base64 = base64,
                fileName = if (disguised) UploadService.disguisedName(fileName) else fileName,
                mimeType = if (disguised) DISGUISE_MIME else mimeType,
                options = JsonRequestOptions(notifyChallenge = true, referrer = "${AppConstants.BASE_URL}/")
            )
            val outcome = when (kind) {
                ComposerUploadKind.Image -> parseUploadOutcome(response)
                ComposerUploadKind.Attachment -> parseAttachmentOutcome(response)
                ComposerUploadKind.Media -> parseMediaOutcome(response, localWidth, localHeight, mimeType)
            }
            // 遮罩/重入闸的收尾归入口层，批量中途在这里复位会让遮罩闪烁、闸门敞开。
            outcome.error?.let { _uiState.update { s -> s.copy(actionMessage = it) } }
            done(outcome.snippet, outcome.error)
        }
    }

    /**
     * 选中一批文件后的完整处理：逐个「读字节 → 按类型上传 → 回传可插入的 markdown 片段」。
     * 串行顺序与失败汇总见 [runUploadBatch]（与发帖页共用同一份）。
     */
    fun uploadPickedFiles(
        kind: ComposerUploadKind,
        uris: List<Uri>,
        onSnippet: (String) -> Unit
    ) {
        if (_uiState.value.isUploadingMedia || uris.isEmpty()) return
        _uiState.update {
            it.copy(isUploadingMedia = true, uploadProgressText = kind.progressText, uploadCancelable = false)
        }
        viewModelScope.launch {
            val settings = uploadSettings
            val limitKb = if (kind == ComposerUploadKind.Image) {
                settings.maxImageSizeKb
            } else {
                settings.maxAttachmentSizeKb
            }
            val firstError = try {
                runUploadBatch(
                    context = appContext,
                    kind = kind,
                    uris = uris,
                    limitKb = limitKb,
                    onProgress = { progress ->
                        _uiState.update {
                            it.copy(uploadProgressText = progress.text, uploadCancelable = progress.cancelable)
                        }
                    },
                    onSnippet = onSnippet,
                    uploadOne = { file, width, height -> uploadOne(kind, file, width, height) }
                )
            } finally {
                // 收尾必须在 finally：isUploadingMedia 既是遮罩、也是上传入口的重入闸。
                _uiState.update {
                    it.copy(isUploadingMedia = false, uploadProgressText = null, uploadCancelable = false)
                }
            }
            firstError?.let { message ->
                _uiState.update { it.copy(actionMessage = message) }
            }
        }
    }

    /**
     * 中断正在进行的视频压缩。只有压缩阶段有出口（见 [UploadProgress.cancelable]）：
     * 上传阶段的请求已经发出去了，取消也收不回来。
     */
    fun cancelUpload() {
        VideoTranscoder.cancelActive()
    }

    /** 等一次上传结束（把 done 回调适配成挂起函数），供串行批处理使用。 */
    private suspend fun uploadOne(
        kind: ComposerUploadKind,
        file: PreparedRawFile,
        localWidth: Int,
        localHeight: Int
    ): UploadOutcome = suspendCancellableCoroutine { cont ->
        uploadFile(
            kind = kind,
            base64 = file.base64,
            fileName = file.fileName,
            mimeType = file.mimeType,
            localWidth = localWidth,
            localHeight = localHeight
        ) { snippet, error ->
            cont.resume(UploadOutcome(snippet, error))
        }
    }

    /**
     * 拉本站的上传设置，算出编辑器工具栏两个上传入口该不该露出（与发帖页同一判据）。
     * 附件入口 = 站点有非图片白名单 + 当前用户还能发附件；音视频入口是 linux.do 私有约定。
     */
    fun loadUploadCapabilities() {
        viewModelScope.launch {
            val settings = SiteSettingsService.load(ArkWebNetworkBridge)
            uploadSettings = settings
            val extensionMimes = UploadService.nonImageExtensions(settings)
                .map { UploadService.mimeFor(it) }
                .distinct()
            _uiState.update {
                it.copy(
                    showAttachment = extensionMimes.isNotEmpty() &&
                        LoginGate.canUploadAttachment(settings.newuserMaxAttachments),
                    showMedia = UploadService.mediaUploadEnabled(),
                    attachmentMimeTypes = extensionMimes
                )
            }
        }
    }

    /** 懒建渲染引擎（首次切预览时）。UI 串行调用，不额外加锁。 */
    fun ensureRenderEngine(context: Context): MarkdownRenderEngine {
        renderEngine?.let { return it }
        val engine = MarkdownRenderEngine(context)
        renderEngine = engine
        return engine
    }

    /**
     * 把 markdown 原文编译成可渲染的 HTML；引擎未就绪/超时返回 null，由 UI 退回原文。
     * 已知表情短码在预览里转成图片（与发帖预览同一条通路，见 EmojiPreviewHtml）。
     */
    suspend fun renderPreview(raw: String): String? =
        renderEngine?.render(raw)?.let { EmojiPreviewHtml.render(it, ::getEmojiUrl) }

    /**
     * 提交回复：POST /posts。直接发布 → 新楼追加到列表底部 + 清草稿 + 关浮层；进审核队列 →
     * 只提示不打楼层；失败 → 保留浮层与内容供重试（done(false) 输入条不关）。
     */
    /**
     * 当前话题是否该显示抽奖帖提醒(对齐鸿蒙 showsLotteryNotice):
     * 帖子判定(LotteryTopicRule 纯函数)+ 用户开关。判定失败一律不提醒。
     */
    private fun showsLotteryNotice(): Boolean {
        val topic = _uiState.value.topic ?: return false
        if (!LotteryTipPrefs.isEnabled()) return false
        return shouldShowLotteryNotice(
            LotteryNoticeContext(
                tags = topic.tags.orEmpty(),
                closed = topic.closed,
                archived = topic.archived,
                createdById = topic.createdById ?: 0L,
                currentUserId = CurrentUserHolder.currentUser.value?.id ?: 0L
            )
        )
    }

    /** 撤回待审帖:DELETE /review/{id},成功后本地移除(对齐鸿蒙 deletePending)。不可逆,确认框在页面层。 */
    fun deletePendingPost(pending: PendingPost) {
        val topicId = currentTopicId ?: return
        viewModelScope.launch {
            val result = topicService.deletePendingPost(pending.id, topicId)
            if (result.success) {
                _uiState.update {
                    it.copy(
                        pendingPosts = it.pendingPosts.filterNot { p -> p.id == pending.id },
                        actionMessage = appContext.getString(R.string.common_deleted)
                    )
                }
            } else {
                _uiState.update { it.copy(actionMessage = appContext.getString(R.string.common_delete_failed2)) }
            }
        }
    }

    fun createReply(text: String, typingMs: Long, composerMs: Long, done: (Boolean) -> Unit) {
        val state = _uiState.value
        val topic = state.topic ?: return done(false)
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmittingReply = true) }
            val result = topicService.createReply(
                topicId = topic.id,
                categoryId = topic.categoryId,
                raw = text,
                typingDurationMs = typingMs,
                composerOpenDurationMs = composerMs,
                replyToPostNumber = state.composerReplyToPostNumber
            )
            if (result.success && result.data != null) {
                val outcome = result.data
                if (outcome.pending) {
                    deleteResumedDraftIfAny()
                    deleteReplyDraftIfAny()
                    _uiState.update {
                        it.copy(
                            isSubmittingReply = false,
                            composerVisible = false,
                            pendingReplyText = "",
                            composerReplyToPostNumber = null,
                            actionMessage = appContext.getString(R.string.detail_comment_pending),
                            // 服务端把待审快照(id/raw/created_at)随响应回带,乐观追加到底部待审区,
                            // 不等下次刷新 topic.json(对齐鸿蒙 TopicDetailPage:1394)。
                            pendingPosts = outcome.pendingPost?.let { p -> it.pendingPosts + p }
                                ?: it.pendingPosts
                        )
                    }
                } else if (outcome.post != null) {
                    deleteResumedDraftIfAny()
                    deleteReplyDraftIfAny()
                    dataSource.appendPosts(listOf(outcome.post))
                    _uiState.update {
                        it.copy(
                            isSubmittingReply = false,
                            composerVisible = false,
                            pendingReplyText = "",
                            composerReplyToPostNumber = null,
                            posts = dataSource.snapshotPosts,
                            actionMessage = appContext.getString(R.string.detail_comment_posted)
                        )
                    }
                } else {
                    _uiState.update { it.copy(isSubmittingReply = false, actionMessage = appContext.getString(R.string.composer_publish_failed)) }
                    done(false)
                    return@launch
                }
                done(true)
            } else {
                val reason = result.error?.message ?: appContext.getString(R.string.composer_publish_failed)
                _uiState.update { it.copy(isSubmittingReply = false, actionMessage = reason) }
                done(false)
            }
        }
    }

    /** 楼层永久链接（拷贝链接用）。 */
    fun postLink(post: TopicPost): String? {
        val topic = _uiState.value.topic ?: return null
        val slug = topic.slug ?: return null
        return "${AppConstants.BASE_URL}/t/$slug/${topic.id}/${post.postNumber}"
    }

    /**
     * 「俺也一样」toggle（对齐鸿蒙 toggleMeToo）：不做乐观更新——点击即转圈（由 UI 层
     * meTooRequesting 承担），发网络等结果；成功用服务端权威新态覆盖，失败回 null 由 UI 保持原态。
     */
    fun toggleSharedIssue(done: (SharedIssueResult?) -> Unit) {
        val topicId = currentTopicId ?: return done(null)
        viewModelScope.launch {
            val result = topicService.toggleSharedIssue(topicId)
            done(if (result.success) result.data else null)
        }
    }

    /**
     * 点 boost 文字区：同一楼内点另一条即改指向它，再点自己则收起（对齐鸿蒙 toggleBoostDelete）。
     */
    fun toggleBoostDelete(boostId: Long) {
        _uiState.update {
            it.copy(boostDeleteTargetId = if (it.boostDeleteTargetId == boostId) 0L else boostId)
        }
    }

    /**
     * 删 boost：成功把该气泡从所在楼摘掉、canBoost 复原（对齐鸿蒙 deleteBoost）。
     */
    fun deleteBoost(post: TopicPost, boost: PostBoost, done: (Boolean) -> Unit) {
        val topicId = currentTopicId ?: return done(false)
        viewModelScope.launch {
            _uiState.update { it.copy(boostDeleteLoadingId = boost.id) }
            val result = topicService.deleteBoost(boost.id, topicId)
            if (result.success) {
                // 气泡摘除只在该楼仍在已加载窗口时做；楼不在窗口（findById null）时服务端已成功，
                // 等下次该楼进窗口会带服务端权威态回来。但 loading/确认态的清理与成功提示必须
                // 无条件执行——否则 boostDeleteLoadingId 卡在旧值（对齐鸿蒙：if (target) 只包
                // 数组替换，toast 在外面）。
                dataSource.findById(post.id)?.let { target ->
                    val next = (target.boosts ?: emptyList()).filterNot { it.id == boost.id }
                    dataSource.replacePost(target.copy(boosts = next, canBoost = true))
                    _uiState.update { it.copy(posts = dataSource.snapshotPosts) }
                }
                _uiState.update {
                    it.copy(
                        boostDeleteTargetId = 0L,
                        boostDeleteLoadingId = 0L,
                        actionMessage = appContext.getString(R.string.detail_boost_deleted)
                    )
                }
                done(true)
            } else {
                _uiState.update { it.copy(boostDeleteLoadingId = 0L, actionMessage = appContext.getString(R.string.detail_boost_delete_failed)) }
                done(false)
            }
        }
    }

    /** 屏幕消费一次性滚动请求后调用，避免重组时重复触发。 */
    fun consumeScrollRequest() {
        _uiState.update { it.copy(scrollRequestPostNumber = null) }
    }

    /** 屏幕展示一次性提示消息后调用。 */
    fun consumeActionMessage() {
        _uiState.update { it.copy(actionMessage = null) }
    }

    // ==================== 帖内实时：新回复 + 正在回复 ====================

    /**
     * 挂上本帖的实时链路：订阅 MessageBus 总线 + 订 `/topic/<id>` 频道 + 启动 presence 下行。
     *
     * 幂等——`applyDetail` 会被 rewindow 反复调用，同帖重复调用直接返回，不会重订阅/重发 GET。
     * 只在「开关开 + detail 已就位」时才真挂。
     */
    private fun attachRealtime() {
        if (!RealtimePostsPrefs.isEnabled()) return
        val topicId = currentTopicId ?: return
        if (realtimeTopicId == topicId) return
        releaseRealtime(clearBanner = true)
        realtimeTopicId = topicId
        messageBusUnsubscribe = MessageBusEventBus.subscribe(::handleMessageBusEvent)
        subscribeTopicChannel(topicId)
        presenceService.start(topicId)
    }

    /**
     * 卸载实时链路：退总线订阅、退频道、停 presence、清待拉队列与去抖计时器。
     *
     * [clearBanner] 控制是否连「N 条新回复」提示条一起清掉——换帖/关开关要清，
     * 而"同帖重挂"这条路径上提示条本来也该复位（新楼层还会重来）。
     */
    private fun releaseRealtime(clearBanner: Boolean) {
        messageBusUnsubscribe?.invoke()
        messageBusUnsubscribe = null
        val topicId = realtimeTopicId
        realtimeTopicId = 0L
        if (topicId > 0L) {
            ArkWebNetworkBridge.unsubscribeTopicChannel(topicId)
        }
        presenceService.stop()
        pendingCreatedPostIds.clear()
        createdFlushJob?.cancel()
        createdFlushJob = null
        if (clearBanner) {
            clearIncomingBanner()
        }
    }

    /** 收起「N 条新回复」提示条：计数与点按跳转目标一起复位（对齐鸿蒙 clearIncomingBanner）。 */
    private fun clearIncomingBanner() {
        incomingFirstNewPostNumber = 0
        if (_uiState.value.incomingNewPostCount != 0) {
            _uiState.update { it.copy(incomingNewPostCount = 0) }
        }
    }

    /** 订阅本帖频道，起点游标取详情的 message_bus_last_id（缺失回落 -1，只收新消息）。 */
    private fun subscribeTopicChannel(topicId: Long) {
        val position = lastDetail?.messageBusLastId ?: -1L
        ArkWebNetworkBridge.subscribeTopicChannel(topicId, position)
    }

    /**
     * 总线事件入口。Presence 增量与 `/topic` 新楼两条链路都从这里进。
     *
     * 新楼只在「已加载到话题结尾」时才接：否则新楼落在已加载窗口之外，原地追加会造成楼号跳断，
     * 用户翻到末页或重进即可见（与没有实时时行为一致，对齐鸿蒙 handleMessageBusEvent）。
     */
    private fun handleMessageBusEvent(event: MessageBusEvent) {
        // 长轮询循环重启（host 重载 / 前后台切换）后脚本内 state 重建，频道订阅会丢：
        // 补订本帖频道并重同步 presence（对齐鸿蒙的 config-ready 分支）。
        if (event.type == "status" && event.stage == "config-ready") {
            val topicId = realtimeTopicId
            if (topicId > 0L) subscribeTopicChannel(topicId)
            presenceService.resync()
            return
        }
        // Presence 增量：独立于下面的新楼门控（服务内部按 started / 频道自守卫）。
        presenceService.handleBusEvent(event)

        if (!RealtimePostsPrefs.isEnabled()) return
        val topicId = realtimeTopicId
        if (topicId <= 0L || event.channel != "/topic/$topicId") return
        if (_uiState.value.hasMore) return
        val postId = extractCreatedPostId(event) ?: return
        // 已加载 / 已在待拉队列的跳过（含用户自己刚发、已本地追加的楼）。
        if (dataSource.findById(postId) != null || postId in pendingCreatedPostIds) return
        pendingCreatedPostIds.add(postId)
        if (createdFlushJob?.isActive == true) return
        createdFlushJob = viewModelScope.launch {
            delay(INCOMING_POST_DEBOUNCE_MS)
            flushCreatedPosts()
        }
    }

    /**
     * 去抖到点：批量拉收集到的新楼并追加到列表尾。
     *
     * 在底部 → 只追加不计数（新楼正好补进末尾「加载器 + 底部留白」腾出的位置）；
     * 不在底部 → 累计「N 条新回复」提示条，记住首条新楼楼号供点按跳转。
     */
    private suspend fun flushCreatedPosts() {
        val topicId = currentTopicId ?: return
        val ids = pendingCreatedPostIds.filter { dataSource.findById(it) == null }
        pendingCreatedPostIds.clear()
        if (ids.isEmpty() || _uiState.value.hasMore) return

        val result = topicService.loadMorePosts(topicId, ids)
        // 拉取期间可能换窗（rewindowToPost 跳到中段）或已换帖：此时末尾追加会造成楼号跳断，
        // 或结果根本不属于当前帖 —— 一律放弃本次追加。
        if (!result.success || result.data.isNullOrEmpty()) return
        if (currentTopicId != topicId || _uiState.value.hasMore) return

        val before = dataSource.totalCount
        val added = dataSource.appendPosts(result.data)
        if (added <= 0) return
        val firstNewPostNumber = dataSource.posts[before].postNumber
        val stream = _uiState.value.topic?.postStream?.stream ?: emptyList()
        val laterCount = PostStreamPager.countLater(stream, dataSource.posts)
        val atEnd = listAtEnd
        if (!atEnd && _uiState.value.incomingNewPostCount == 0) {
            incomingFirstNewPostNumber = firstNewPostNumber
        }
        _uiState.update { state ->
            state.copy(
                posts = dataSource.snapshotPosts,
                hasMore = laterCount > 0,
                loadMoreMessage = if (laterCount > 0) appContext.getString(R.string.common_pull_load_more) else appContext.getString(R.string.common_no_more),
                // 在底部时新楼已在眼前，提示条不该再计数。
                incomingNewPostCount = if (atEnd) 0 else state.incomingNewPostCount + added
            )
        }
        if (atEnd) incomingFirstNewPostNumber = 0
        Timber.i(
            "[JetDo][TopicDetail] realtime created topicId=%s ids=%d added=%d atEnd=%s",
            topicId, ids.size, added, atEnd
        )
    }

    /**
     * 屏幕层上报「列表是否停在底部」。实时新楼据此分流：底部直接追加不计数，否则累计提示条。
     *
     * 对齐鸿蒙 TopicDetailPage.atListEnd：那边由 onScrollIndex 维护，这边由屏幕按
     * LazyListState 的 canScrollForward 上报（语义一致：还能往下滚 = 不在底部）。
     *
     * 滚到物理底部本身就是「那些新楼我已经看到了」——提示条必须同刻收起，否则挂着
     * 一个已经读过的新楼数字（对齐鸿蒙 onReachEnd 里的 clearIncomingBanner）。
     * 但「还有更多没加载」时不算真·到底：那时下方还有没拉进来的楼，人没看到全部，
     * 提示条留着才对（鸿蒙同样只在 `!hasMorePosts` 分支里清）。
     */
    fun updateListAtEnd(atEnd: Boolean) {
        listAtEnd = atEnd
        if (atEnd && !_uiState.value.hasMore) clearIncomingBanner()
    }

    /** 点「N 条新回复」提示条：跳到首条新楼（已在列表中，走 jumpToPostNumber 的已加载滚动分支）。 */
    fun openIncomingPosts() {
        val target = incomingFirstNewPostNumber
        clearIncomingBanner()
        if (target > 0) {
            jumpToPostNumber(target)
        }
    }

    /** 整体替换数据源并重算双向余量（首屏/换窗共用）。 */
    private fun applyDetail(detail: TopicDetail) {
        // 留存一份：页面重建后靠它把 tracker 幂等拉起（见 ensureReadingTracker）。
        lastDetail = detail
        // 换窗会把全部楼层换成服务端新对象。本页已确认已读的楼必须接回——服务端未必已经
        // 记下（上报还在攒批窗口里、或刚失败重试中），不接的话已读态当场被打回未读，
        // 而 tracker 的 readPosts 仍认为它已读、不再上报 → 蓝点永久卡住。
        val locallyRead = readingTracker.confirmedReadPostNumbers()
        val incoming = detail.postStream?.posts ?: emptyList()
        val restored = if (locallyRead.isEmpty()) incoming else {
            incoming.map { if (it.read != true && it.postNumber in locallyRead) it.copy(read = true) else it }
        }
        dataSource.reset(restored)
        val stream = detail.postStream?.stream ?: emptyList()
        val earlierCount = PostStreamPager.countEarlier(stream, dataSource.posts)
        val laterCount = PostStreamPager.countLater(stream, dataSource.posts)
        _uiState.update {
            it.copy(
                topic = detail,
                posts = dataSource.snapshotPosts,
                hasMore = laterCount > 0,
                hasEarlier = earlierCount > 0,
                earlierCount = earlierCount,
                // 「俺也一样」话题级态随详情下发（对齐鸿蒙 mapTopicDetail 的字段透传）。
                sharedIssueVisible = detail.sharedIssueVisible == true,
                sharedIssueCreated = detail.userCreatedSharedIssue == true,
                sharedIssueCount = detail.sharedIssueCount ?: 0,
                sharedIssueCanCreate = detail.canCreateSharedIssue == true,
                pendingPosts = detail.pendingPosts.orEmpty(),
                loadMoreMessage = if (laterCount > 0) appContext.getString(R.string.common_pull_load_more) else appContext.getString(R.string.common_no_more)
            )
        }
        // 诊断：确认服务端到底有没有下发「俺也一样」四要素与已读水位。
        // 全 false 说明该话题未开启共享问题（服务端不下发 → 按钮本就不该出现，属正常）；
        // true 而 UI 没有才是客户端接线问题。lastRead 同理解释红线是否该出现。
        Timber.i(
            "[JetDo][TopicDetail] topicId=%s sharedIssue(visible=%s canCreate=%s created=%s count=%s) lastRead=%s accepted=%s",
            detail.id,
            detail.sharedIssueVisible,
            detail.canCreateSharedIssue,
            detail.userCreatedSharedIssue,
            detail.sharedIssueCount,
            detail.lastReadPostNumber,
            detail.acceptedAnswers?.size
        )
        updateLastVisitedAnchor(detail)
        startReadingTrackerIfNeeded(detail)
        // 换窗后新窗口里新出现的已读楼也要预置进 tracker，否则会对它们重复计时上报。
        // （首屏那次由 startReadingTrackerIfNeeded 内部的 seedRead 负责，这里补的是已有 tracker 的情况。）
        if (trackerStarted) {
            readingTracker.seedRead(collectAlreadyReadPostNumbers(dataSource.posts, detail.lastReadPostNumber))
        }
        // detail 就位：订阅 /topic/<id> 实时新楼 + 启动「正在回复」presence（仅当开关开）。
        // 幂等：rewindow 会反复调到这里，同帖不重订阅（presence 服务内部也按 started/topicId 自守卫）。
        attachRealtime()
    }

    /**
     * 确定「上次访问」红线锚楼：快照水位后第一条未读楼的**上一楼**（最后一条已读楼）。
     * 红线画在锚楼底边、替代该处的灰色分割线 → 楼间只此一条，恰是「已读 | 未读」边界。
     * 上一楼须已加载（index > 0）才能锚；首条已加载楼即未读时，已读侧还在未加载区，
     * 留待 prepend 补楼后重试（本方法会被后续 applyDetail 再次调用）。锚一旦确定不再变化。
     * 对齐鸿蒙 TopicDetailPage.updateLastVisitedAnchor（:2114-2132）。
     */
    private fun updateLastVisitedAnchor(detail: TopicDetail) {
        if (lastVisitedSnapshot == 0) {
            lastVisitedSnapshot = detail.lastReadPostNumber ?: 0
        }
        if (lastVisitedSnapshot <= 0 || lastVisitedAnchorPostId > 0L) return
        val posts = dataSource.posts
        for (index in posts.indices) {
            if (posts[index].postNumber <= lastVisitedSnapshot) continue
            if (index > 0) {
                lastVisitedAnchorPostId = posts[index - 1].id
                _uiState.update { it.copy(lastVisitedAnchorPostId = lastVisitedAnchorPostId) }
            }
            return
        }
    }

    // ==================== 阅读计时上报（对齐鸿蒙 TopicReadController） ====================

    /**
     * 详情加载成功后启动计时上报。未登录一律不启动：匿名 POST /topics/timings 会被后端拒。
     * 已读楼预置进 tracker（seedRead），避免对已读楼重复计时上报。
     *
     * **每个页面实例只启动一次**（对齐鸿蒙 TopicReadController 的 `started` 标志）。
     * 这条不是优化而是正确性：applyDetail 除首屏外还被 rewindow 调用（楼层 scrubber 拖到
     * 未加载楼、帖内链接定位等都会触发），每次都 start 会 `stop()+reset()`，而 reset 会
     * ①清空 readPosts（已读记忆丢失，回头重复上报）②cancel 掉还没到点的视觉收尾 job
     * （那批楼的 onRead 永不执行 → 蓝点卡住不再淡出）。
     */
    private fun startReadingTrackerIfNeeded(detail: TopicDetail) {
        if (trackerStarted) {
            Timber.i("[JetDo][ReadingTracker] skip start: already started topicId=%d", detail.id)
            return
        }
        if (!LoginGate.isLoggedIn(appContext)) {
            Timber.i("[JetDo][ReadingTracker] skip start: not logged in topicId=%d", detail.id)
            return
        }
        val posts = dataSource.posts
        if (posts.isEmpty()) {
            Timber.i("[JetDo][ReadingTracker] skip start: no posts topicId=%d", detail.id)
            return
        }
        readingTracker.start(
            topicId = detail.id,
            reportFn = ReadingTracker.ReportTimings { topicId, timings, topicTime ->
                topicService.reportTimings(topicId, timings, topicTime)
            },
            onRead = { postNumbers -> markPostsRead(postNumbers) },
            onReporting = { postNumbers, reporting -> markPostsReporting(postNumbers, reporting) }
        )
        readingTracker.seedRead(collectAlreadyReadPostNumbers(posts, detail.lastReadPostNumber))
        trackerStarted = true
        Timber.i("[JetDo][ReadingTracker] started topicId=%d posts=%d", detail.id, posts.size)
    }

    /**
     * 首屏预置为已读的楼号（对齐鸿蒙 TopicReadController.collectAlreadyReadPostNumbers）。
     *
     * 服务端 `read` 是权威：显式 true 才算已读。`last_read_post_number`（水位）只在
     * `read` 缺失时兜底 —— 匿名响应或站点没下发该字段时，水位是唯一信号。
     *
     * 注意别把水位无条件当已读：那会让「水位以内、但服务端明确说 read=false」的楼被预置进
     * readPosts，从此既不计时也不上报 —— 用户视角就是蓝点永远不消（这里踩过）。
     */
    private fun collectAlreadyReadPostNumbers(posts: List<TopicPost>, watermark: Int?): List<Int> {
        val boundary = watermark ?: -1
        return posts.filter { post ->
            post.read == true || (post.read == null && boundary >= 0 && post.postNumber <= boundary)
        }.map { it.postNumber }
    }

    /** 可见楼层变化（详情页滚动时喂入）：更新在屏楼层列表 + 刷新防挂机滚动时间戳。 */
    fun updateVisiblePostNumbers(postNumbers: List<Int>) {
        if (postNumbers != lastOnscreenLogged) {
            lastOnscreenLogged = postNumbers
            Timber.i(
                "[JetDo][ReadingTracker] onscreen=%s..%s n=%d topicId=%s",
                postNumbers.firstOrNull(), postNumbers.lastOrNull(), postNumbers.size, currentTopicId
            )
        }
        readingTracker.setOnscreen(postNumbers)
        readingTracker.scrolled()
        postNumbers.maxOrNull()?.let { if (it > maxSeenPostNumber) maxSeenPostNumber = it }
    }

    /**
     * 幂等拉起阅读上报（页面回到前台时调用，见 TopicReadController 的焦点胶水）。
     *
     * 正常首屏路径由 [applyDetail] 启动，这里是**重建兜底**：配置变更（切深色模式 / 旋转 /
     * 改字号）会让 Compose 重建、重新走 ON_RESUME，而 `loadTopic` 的 LaunchedEffect key 未变、
     * 不会重跑，没有这条就没人能把可能被停掉的 tracker 起回来。
     */
    fun ensureReadingTracker() {
        lastDetail?.let { startReadingTrackerIfNeeded(it) }
    }

    /** 页面前后台切换：暂停/恢复阅读计时（对齐鸿蒙 setFocus）。 */
    fun setTrackerFocus(hasFocus: Boolean) {
        readingTracker.setFocus(hasFocus)
    }

    /** 离开详情页：停表结算 + 把剩余批次发出（对齐鸿蒙 TopicReadController.detach 的两段收尾）。 */
    fun onDetailDetach() {
        // 退订实时链路：总线退订同步做，频道退订经 WebView 注入（与首页一致，不留悬挂订阅）。
        releaseRealtime(clearBanner = true)
        // 复位启动标志：本页已收尾，若该 VM 再次被挂载（同一实例复用）需重新起表。
        trackerStarted = false
        if (readingTracker.stopTiming()) {
            readingTracker.flushPending()
        }
        publishReadStateToHomeLists()
    }

    /**
     * 离开详情页时结算一次已读状态，回传给列表页消蓝点、改未读徽标
     * （对齐鸿蒙 TopicReadController.detach → buildReadStatePayload → TopicReadStateEventBus）。
     *
     * 载荷计算对齐鸿蒙 buildReadStatePayload（TopicReadController.ets:197-214）：
     * - 匿名或响应无已读态时不上报，避免把服务端真实状态误清；
     * - 剩余未读 = max(0, 已知最高楼 − 已读水位)，水位取三个信号的最高者：
     *   服务端旧水位、本次浏览到的最高楼号、服务端明确 read=true 的最高楼号；
     * - unseen 恒 false（进过详情页就算看过）。
     */
    private fun publishReadStateToHomeLists() {
        val detail = lastDetail ?: return
        val posts = dataSource.posts
        // hasServerReadState：详情 last_read_post_number 或任一楼带 read 字段，二占其一即可。
        val hasServerReadState = detail.lastReadPostNumber != null || posts.any { it.read != null }
        if (!hasServerReadState) return

        var highest = detail.highestPostNumber ?: 0
        for (post in posts) if (post.postNumber > highest) highest = post.postNumber
        var watermark = detail.lastReadPostNumber ?: 0
        if (maxSeenPostNumber > watermark) watermark = maxSeenPostNumber
        for (post in posts) {
            if (post.read == true && post.postNumber > watermark) watermark = post.postNumber
        }

        readStateSource.publish(
            com.arkdo.android.core.data.session.TopicReadStateUpdate(
                topicId = detail.id,
                unseen = false,
                unreadPosts = maxOf(0, highest - watermark)
            )
        )
    }

    /** 上报成功：把这些楼标已读（蓝点淡出）。data class 需重建触发重组。 */
    private fun markPostsRead(postNumbers: List<Int>) {
        val numbers = postNumbers.toHashSet()
        _uiState.update { state ->
            val posts = state.posts.toMutableList()
            var changed = false
            for (i in posts.indices) {
                val post = posts[i]
                if (post.postNumber in numbers && post.read != true) {
                    posts[i] = post.copy(read = true)
                    changed = true
                }
            }
            if (!changed) state else state.copy(posts = posts)
        }
        // 关键：把新对象一并写回数据源。只改 UiState 的话，下一次 `posts = dataSource.snapshotPosts`
        // （分页 / 换窗 / 投票 / boost / 删恢复 / 编辑）就会把已读打回未读，而 tracker 的 readPosts
        // 已记住这些楼、不再上报 → 蓝点永久卡住（对齐鸿蒙：那边直接改 @Observed 实例，天然同源）。
        dataSource.acceptPosts(_uiState.value.posts.filter { it.postNumber in numbers })
        // 诊断：in = 本批要翻已读的楼数；nowRead = 翻完后 UiState 里这些楼确实 read=true 的数量。
        // 两手对不上（nowRead < in）说明 postNumber 没落到列表上；这条日志根本不出现则说明
        // onRead 没被执行（上报未成功，或视觉收尾 job 被 reset 掐掉）。
        Timber.i(
            "[JetDo][ReadingTracker] markRead topicId=%s in=%d nowRead=%d",
            currentTopicId,
            numbers.size,
            _uiState.value.posts.count { it.postNumber in numbers && it.read == true }
        )
    }

    /** 上报期间蓝点"呼吸"（reporting 状态）。 */
    private fun markPostsReporting(postNumbers: List<Int>, reporting: Boolean) {
        val numbers = postNumbers.toHashSet()
        _uiState.update { state ->
            val posts = state.posts.toMutableList()
            var changed = false
            for (i in posts.indices) {
                val post = posts[i]
                if (post.postNumber in numbers && post.reporting != reporting) {
                    posts[i] = post.copy(reporting = reporting)
                    changed = true
                }
            }
            if (!changed) state else state.copy(posts = posts)
        }
        // 同上：reporting 也是本地态，不写回数据源的话，任何一次快照回填都会让"呼吸"提前中断。
        dataSource.acceptPosts(_uiState.value.posts.filter { it.postNumber in numbers })
    }

    /** 武装目标楼落地高亮：3s 后自动清零，避免楼层重建时重播（对齐鸿蒙 armTargetFlash）。 */
    private fun armTargetFlash(postId: Long) {
        _uiState.update { it.copy(flashTargetPostId = postId) }
        viewModelScope.launch {
            delay(TARGET_FLASH_MS)
            _uiState.update { if (it.flashTargetPostId == postId) it.copy(flashTargetPostId = null) else it }
        }
    }

    fun retry() {
        val topicId = currentTopicId ?: return
        dataSource.reset(emptyList())
        _uiState.update { it.copy(topic = null) }
        currentTopicId = null
        loadTopic(topicId, currentSlug, null)
    }
}

/**
 * 话题详情页 UI 状态。
 *
 * [posts] 始终来自 [PostDataSource]（按楼号升序）；[scrollRequestPostNumber] 是
 * 一次性请求，屏幕滚动完成后必须调 [consumeScrollRequest] 清除。
 */
data class TopicDetailUiState(
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isLoadingEarlier: Boolean = false,
    val isRewindowing: Boolean = false,
    val error: String? = null,
    val actionMessage: String? = null,
    val topic: TopicDetail? = null,
    val posts: List<TopicPost> = emptyList(),
    val hasMore: Boolean = false,
    val hasEarlier: Boolean = false,
    val earlierCount: Int = 0,
    val loadMoreMessage: String = "",
    val scrollRequestPostNumber: Int? = null,
    val flashTargetPostId: Long? = null,
    val categories: List<TopicCategory> = emptyList(),
    // 回复输入浮层（切片 7）：可见性/占位文字/定向回复目标/未发出草稿/提交中。
    val composerVisible: Boolean = false,
    /** 是否展开成全屏编辑器（紧凑条与全屏编辑器共用同一份 pendingReplyText，切换不丢字）。 */
    val composerExpanded: Boolean = false,
    val composerPlaceholder: String = AppContextHolder.getString(R.string.detail_reply_to_topic),
    /** 回复浮层顶上的一行提醒(抽奖帖规则摘要,非抽奖为空;对齐鸿蒙 composerHint)。 */
    val composerHint: String = "",
    val composerReplyToPostNumber: Int? = null,
    val pendingReplyText: String = "",
    val isSubmittingReply: Boolean = false,
    /** 全屏编辑器里上传中（进度文案随行显示，不盖全屏遮罩）。 */
    val isUploadingMedia: Boolean = false,
    val uploadProgressText: String? = null,
    /** 上传提示行是否显示「取消」。仅视频压缩阶段为真。 */
    val uploadCancelable: Boolean = false,
    /** 编辑器工具栏「附件」入口是否露出(站点有非图片白名单 + 当前用户还能发附件)。 */
    val showAttachment: Boolean = false,
    /** 编辑器工具栏「音视频」入口是否露出(linux.do 私有约定)。 */
    val showMedia: Boolean = false,
    /** 附件选择器的 MIME 过滤（由站点白名单的非图片扩展名推出）。空 = 不限。 */
    val attachmentMimeTypes: List<String> = emptyList(),
    // 「俺也一样」（shared issue，话题级）：可见性/已点/计数/当前用户能否点。
    val sharedIssueVisible: Boolean = false,
    val sharedIssueCreated: Boolean = false,
    val sharedIssueCount: Int = 0,
    val sharedIssueCanCreate: Boolean = false,
    // boost：当前展开删除图标的 boost id（0=没有，同一时刻只展开一条，对齐鸿蒙 deleteTargetBoostId）。
    val boostDeleteTargetId: Long = 0L,
    // boost 目标楼 id：>0 时回复输入浮层处于 boost 模式（对齐鸿蒙 boostTargetPostId）。
    val boostTargetPostId: Long = 0L,
    // 「上次访问」红线锚楼 id：>0 时该楼底边画红线（对齐鸿蒙 TopicDetailPage.ets:456 的
    // lastVisitedAnchorPostId）。此前恒 0 → 页面从不传 showLastVisitedLine → 红线从不出现。
    val lastVisitedAnchorPostId: Long = 0L,
    // 正在删除的 boost id（转圈防重复，对齐鸿蒙 deletingBoostId）。
    val boostDeleteLoadingId: Long = 0L,
    // 编辑模式：目标楼 id（>0 时回复浮层处于编辑态）+ 乐观并发基线原文 + 取原文 loading。
    val editingPostId: Long = 0L,
    val editingOriginalRaw: String = "",
    val editLoadingPostId: Long = 0L,
    // 正在软删除或恢复的楼 id（>0 时该楼操作按钮转圈，避免连点）。
    val postActionLoadingId: Long = 0L,
    // 「谁回应了」弹层（对齐鸿蒙 ReactionUsersSheet）：非空即弹层可见，请求与分页状态都在这里。
    val reactionSheet: ReactionUsersSheetState? = null,
    // 当前用户在本话题下的待审内容（对齐鸿蒙 pendingPosts）：详情下发 + 发帖被扣审时乐观追加。
    val pendingPosts: List<PendingPost> = emptyList(),
    // ── 帖内实时（受「帖子内新回复提醒」开关门控）──
    /** 正在回复的用户（已滤掉自己、截到 5 个）。空 = 不显示「正在回复」悬浮条。 */
    val presenceUsers: List<User> = emptyList(),
    /** 「N 条新回复」提示条计数：>0 时显示，点按跳首条新楼。 */
    val incomingNewPostCount: Int = 0
)

/**
 * 「谁回应了」弹层状态。reactions(chips 来源)与 totalRows(总人数)取自楼层与首页响应；
 * users 为跨页累积去重后的名单，selectedReaction 为客户端过滤(不重发请求)。
 */
data class ReactionUsersSheetState(
    val post: TopicPost,
    val reactions: List<PostReaction>,
    val users: List<ReactionUser> = emptyList(),
    val totalRows: Int = 0,
    val selectedReaction: String = "",
    val initialLoading: Boolean = false,
    val loadMoreLoading: Boolean = false,
    val hasMore: Boolean = false,
    val initialError: String? = null,
    val loadMoreError: String? = null
)
