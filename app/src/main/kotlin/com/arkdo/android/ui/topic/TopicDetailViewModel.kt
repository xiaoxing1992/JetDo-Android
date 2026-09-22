package com.arkdo.android.ui.topic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.arkdo.android.data.models.TopicDetail
import com.arkdo.android.common.constants.AppConstants
import com.arkdo.android.data.models.PostReaction
import com.arkdo.android.data.models.TopicCategory
import com.arkdo.android.data.models.PostBoost
import com.arkdo.android.data.models.SharedIssueResult
import com.arkdo.android.data.models.TopicPost
import com.arkdo.android.services.session.LoginGate
import com.arkdo.android.services.reading.ReadingTracker
import com.arkdo.android.services.topic.PostDataSource
import com.arkdo.android.services.topic.PostStreamPager
import com.arkdo.android.services.network.ArkWebNetworkBridge
import com.arkdo.android.services.challenge.ChallengeRecoveryEventBus
import com.arkdo.android.services.challenge.ChallengeRecoveryState
import com.arkdo.android.services.site.CategoryIndex
import com.arkdo.android.services.topic.PollMutation
import com.arkdo.android.services.topic.TopicService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/** 每页拉取楼层数，对齐鸿蒙 POSTS_PAGE_SIZE。 */
private const val POSTS_PAGE_SIZE = 20

/** 跳楼落地高亮时长，对齐鸿蒙 armTargetFlash 的 3s。 */
private const val TARGET_FLASH_MS = 3000L

/** 作者软删除后站点回的中文占位（响应没带 cooked 时本地兜底）。 */
private const val DELETED_BY_AUTHOR_HTML = "<p>(帖子已被作者删除)</p>"

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
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val dataSource = PostDataSource()

    private val _uiState = MutableStateFlow(TopicDetailUiState())
    val uiState: StateFlow<TopicDetailUiState> = _uiState.asStateFlow()

    /** 阅读计时上报（对齐鸿蒙 TopicReadController 的 ReadingTracker 托管）。 */
    private val readingTracker = ReadingTracker(viewModelScope)

    /** 撞盾挂起批次补发的前提：过盾成功（COOLDOWN）信号（对齐鸿蒙 TopicReadController.attach 的订阅）。 */
    private val challengeUnsubscribe = ChallengeRecoveryEventBus.subscribeState { state ->
        if (state == ChallengeRecoveryState.COOLDOWN) {
            readingTracker.notifyChallengeResolved()
        }
    }

    override fun onCleared() {
        challengeUnsubscribe()
        readingTracker.stop()
        super.onCleared()
    }

    private var currentTopicId: Long? = null
    private var currentSlug: String? = null

    fun loadTopic(topicId: Long, slug: String?, title: String?, targetPostNumber: Int? = null) {
        if (currentTopicId == topicId) {
            // 同一话题再次进入（如引用跳楼）：走跳楼分流，不重复首屏加载。
            if (targetPostNumber != null) {
                jumpToPostNumber(targetPostNumber)
            }
            return
        }
        currentTopicId = topicId
        currentSlug = slug

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
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = result.error?.message ?: "加载失败"
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
                _uiState.update { it.copy(actionMessage = "楼层定位失败，请稍后重试") }
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
            _uiState.update { it.copy(hasMore = false, loadMoreMessage = "没有更多了") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true, loadMoreMessage = "正在加载更多") }
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
                        ) "上拉加载更多" else "没有更多了"
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isLoadingMore = false,
                        loadMoreMessage = "加载更多失败，上拉重试"
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
                logTag = "[ArkDO][TopicDetail]"
            )?.let { loaded ->
                _uiState.update { it.copy(categories = loaded) }
            }
        }
    }

    /**
     * 表情回应切换（点赞 = heart）：请求先行，结果经 done 回调（成功 true）。
     * 成功后的本地计数/已点态刷新依赖详情重拉，暂以回调通知屏幕。
     */
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
                _uiState.update { it.copy(actionMessage = "投票失败，请稍后重试") }
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
                _uiState.update { it.copy(actionMessage = "撤销投票失败，请稍后重试") }
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

    /** 书签 toggle：加书签直接加；移除需要 bookmarkId（详情接口暂未下发，暂不支持移除）。 */
    fun toggleBookmark(post: TopicPost, done: (Boolean) -> Unit) {
        val topicId = currentTopicId ?: return done(false)
        viewModelScope.launch {
            if (post.bookmarked == true) {
                val bookmarkId = post.bookmarkId
                if (bookmarkId == null) {
                    done(true)
                    return@launch
                }
                val result = topicService.removeBookmark(bookmarkId, topicId)
                if (result.success && post.postNumber == 1) syncTopicBookmarked(false)
                done(result.success)
            } else {
                val result = topicService.addBookmark(post.id, topicId)
                if (result.success && post.postNumber == 1) syncTopicBookmarked(true)
                done(result.success)
            }
        }
    }

    /** 一楼收藏即主题收藏：标题栏图层 B 书签图标以此为真源，toggle 后即时联动（对齐鸿蒙）。 */
    private fun syncTopicBookmarked(value: Boolean) {
        _uiState.update { it.copy(topic = it.topic?.copy(bookmarked = value)) }
    }

    // ========== 回复输入栏（切片 7，逐行对齐鸿蒙 openComposerForTopic / openComposerForPost） ==========

    /**
     * 点击右下角悬浮回复按钮。与鸿蒙同序：未登录先拦（ensureLoggedIn 会广播请求登录，
     * 登录页弹出属登录切片）→ 复位 composer 各模式 → 定占位文字 → 再展开浮层。
     */
    fun openComposerForTopic() {
        if (!LoginGate.ensureLoggedIn(appContext)) {
            return
        }
        resetComposerModes()
        // 抽奖帖占位分支依赖 LotteryTopicRule（未迁移，见切片4），非抽奖恒「回复当前话题」。
        _uiState.update {
            it.copy(
                composerPlaceholder = "回复当前话题",
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
            ?: "该用户"
        _uiState.update {
            it.copy(
                composerPlaceholder = "回复$authorName",
                composerReplyToPostNumber = post.postNumber,
                composerVisible = true
            )
        }
    }

    /** 对齐鸿蒙 resetComposerModes：回复/boost/编辑三态复位。 */
    private fun resetComposerModes() {
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
                composerPlaceholder = "发一个 boost...",
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
                _uiState.update { it.copy(actionMessage = "读取原文失败，请稍后重试") }
                return@launch
            }
            resetComposerModes()
            _uiState.update {
                it.copy(
                    editingPostId = post.id,
                    editingOriginalRaw = result.data,
                    composerPlaceholder = "编辑这条回复",
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
                _uiState.update {
                    it.copy(
                        composerVisible = false,
                        pendingReplyText = "",
                        editingPostId = 0L,
                        editingOriginalRaw = "",
                        actionMessage = "已保存"
                    )
                }
                done(true)
            } else if (result.statusCode == 409) {
                _uiState.update { it.copy(actionMessage = "这条回复已被改动，请重新打开编辑") }
                done(false)
            } else {
                _uiState.update { it.copy(actionMessage = "保存失败，请稍后重试") }
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
                carryLocalFields(from = post, onto = placeholder)
                dataSource.replacePost(placeholder)
                _uiState.update { it.copy(posts = dataSource.snapshotPosts) }
                done(true)
            } else {
                _uiState.update { it.copy(actionMessage = "删除失败，请稍后重试") }
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
                carryLocalFields(from = post, onto = restored)
                dataSource.replacePost(restored)
                _uiState.update { it.copy(posts = dataSource.snapshotPosts) }
                done(true)
            } else {
                _uiState.update { it.copy(actionMessage = "恢复失败，请稍后重试") }
                done(false)
            }
        }
    }

    /** copy / mapPosts 都会丢掉类体可变字段，写回列表前从原楼接上。 */
    private fun carryLocalFields(from: TopicPost, onto: TopicPost) {
        if (onto.author == null) onto.author = from.author
        if (onto.replyToUser == null) onto.replyToUser = from.replyToUser
        if (onto.plainText.isEmpty()) onto.plainText = from.plainText
        onto.reporting = from.reporting
        if (onto.badgeIcons == null) onto.badgeIcons = from.badgeIcons
        if (onto.linkClicks == null) onto.linkClicks = from.linkClicks
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
                        actionMessage = "Boost 已发送"
                    )
                }
                done(true)
            } else {
                _uiState.update { it.copy(isSubmittingReply = false, actionMessage = "Boost 发送失败，请稍后重试") }
                done(false)
            }
        }
    }

    fun closeComposer() {
        _uiState.update {
            it.copy(
                composerVisible = false,
                editingPostId = 0L,
                editingOriginalRaw = ""
            )
        }
    }

    /** 输入内容暂存：关掉浮层再点开内容还在（鸿蒙 pendingReplyText 的内存等价物，磁盘草稿后续切片接）。 */
    fun updateReplyDraft(text: String) {
        _uiState.update { it.copy(pendingReplyText = text) }
    }

    /**
     * 提交回复：POST /posts。直接发布 → 新楼追加到列表底部 + 清草稿 + 关浮层；进审核队列 →
     * 只提示不打楼层；失败 → 保留浮层与内容供重试（done(false) 输入条不关）。
     */
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
                    _uiState.update {
                        it.copy(
                            isSubmittingReply = false,
                            composerVisible = false,
                            pendingReplyText = "",
                            composerReplyToPostNumber = null,
                            actionMessage = "评论已提交，等待审核"
                        )
                    }
                } else if (outcome.post != null) {
                    dataSource.appendPosts(listOf(outcome.post))
                    _uiState.update {
                        it.copy(
                            isSubmittingReply = false,
                            composerVisible = false,
                            pendingReplyText = "",
                            composerReplyToPostNumber = null,
                            posts = dataSource.snapshotPosts,
                            actionMessage = "评论已发表"
                        )
                    }
                } else {
                    _uiState.update { it.copy(isSubmittingReply = false, actionMessage = "发表失败，请稍后重试。") }
                    done(false)
                    return@launch
                }
                done(true)
            } else {
                val reason = result.error?.message ?: "发表失败，请稍后重试。"
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
     * 发 boost：成功把新气泡追加到该楼 boosts、并把 canBoost 置 false（一人一楼的本地约束，
     * 对齐鸿蒙 submitBoost 的 ObjectLink 数组替换）。
     */
    fun createBoost(post: TopicPost, raw: String, done: (Boolean) -> Unit) {
        val topicId = currentTopicId ?: return done(false)
        viewModelScope.launch {
            val result = topicService.createBoost(post.id, topicId, raw)
            if (result.success && result.data != null) {
                dataSource.findById(post.id)?.let { target ->
                    val next = (target.boosts ?: emptyList()) + result.data!!
                    dataSource.replacePost(target.copy(boosts = next, canBoost = false))
                    _uiState.update {
                        it.copy(
                            posts = dataSource.snapshotPosts,
                            boostDeleteTargetId = 0L,
                            actionMessage = "Boost 已发送"
                        )
                    }
                }
                done(true)
            } else {
                _uiState.update { it.copy(actionMessage = "Boost 发送失败，请稍后重试") }
                done(false)
            }
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
                dataSource.findById(post.id)?.let { target ->
                    val next = (target.boosts ?: emptyList()).filterNot { it.id == boost.id }
                    dataSource.replacePost(target.copy(boosts = next, canBoost = true))
                    _uiState.update {
                        it.copy(
                            posts = dataSource.snapshotPosts,
                            boostDeleteTargetId = 0L,
                            boostDeleteLoadingId = 0L,
                            actionMessage = "Boost 已删除"
                        )
                    }
                }
                done(true)
            } else {
                _uiState.update { it.copy(boostDeleteLoadingId = 0L, actionMessage = "Boost 删除失败，请稍后重试") }
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

    /** 整体替换数据源并重算双向余量（首屏/换窗共用）。 */
    private fun applyDetail(detail: TopicDetail) {
        dataSource.reset(detail.postStream?.posts ?: emptyList())
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
                loadMoreMessage = if (laterCount > 0) "上拉加载更多" else "没有更多了"
            )
        }
        startReadingTrackerIfNeeded(detail)
    }

    // ==================== 阅读计时上报（对齐鸿蒙 TopicReadController） ====================

    /**
     * 详情加载成功后启动计时上报。未登录一律不启动：匿名 POST /topics/timings 会被后端拒。
     * 已读楼预置进 tracker（seedRead），避免对已读楼重复计时上报。
     */
    private fun startReadingTrackerIfNeeded(detail: TopicDetail) {
        if (!LoginGate.isLoggedIn(appContext)) return
        val posts = dataSource.posts
        if (posts.isEmpty()) return
        readingTracker.start(
            topicId = detail.id,
            reportFn = ReadingTracker.ReportTimings { topicId, timings, topicTime ->
                topicService.reportTimings(topicId, timings, topicTime)
            },
            onRead = { postNumbers -> markPostsRead(postNumbers) },
            onReporting = { postNumbers, reporting -> markPostsReporting(postNumbers, reporting) }
        )
        readingTracker.seedRead(posts.filter { it.read == true }.map { it.postNumber })
        readingTracker.seedRead(posts.map { it.postNumber }.filter { it <= (detail.lastReadPostNumber ?: 0) })
        Timber.i("[ArkDO][ReadingTracker] started topicId=%d posts=%d", detail.id, posts.size)
    }

    /** 可见楼层变化（详情页滚动时喂入）：更新在屏楼层列表 + 刷新防挂机滚动时间戳。 */
    fun updateVisiblePostNumbers(postNumbers: List<Int>) {
        readingTracker.setOnscreen(postNumbers)
        readingTracker.scrolled()
    }

    /** 页面前后台切换：暂停/恢复阅读计时（对齐鸿蒙 setFocus）。 */
    fun setTrackerFocus(hasFocus: Boolean) {
        readingTracker.setFocus(hasFocus)
    }

    /** 离开详情页：停表结算 + 把剩余批次发出（对齐鸿蒙 TopicReadController.detach 的两段收尾）。 */
    fun onDetailDetach() {
        if (readingTracker.stopTiming()) {
            readingTracker.flushPending()
        }
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
                    posts[i] = post.copy().also { it.reporting = reporting }
                    changed = true
                }
            }
            if (!changed) state else state.copy(posts = posts)
        }
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
    val composerPlaceholder: String = "回复当前话题",
    val composerReplyToPostNumber: Int? = null,
    val pendingReplyText: String = "",
    val isSubmittingReply: Boolean = false,
    // 「俺也一样」（shared issue，话题级）：可见性/已点/计数/当前用户能否点。
    val sharedIssueVisible: Boolean = false,
    val sharedIssueCreated: Boolean = false,
    val sharedIssueCount: Int = 0,
    val sharedIssueCanCreate: Boolean = false,
    // boost：当前展开删除图标的 boost id（0=没有，同一时刻只展开一条，对齐鸿蒙 deleteTargetBoostId）。
    val boostDeleteTargetId: Long = 0L,
    // boost 目标楼 id：>0 时回复输入浮层处于 boost 模式（对齐鸿蒙 boostTargetPostId）。
    val boostTargetPostId: Long = 0L,
    // 正在删除的 boost id（转圈防重复，对齐鸿蒙 deletingBoostId）。
    val boostDeleteLoadingId: Long = 0L,
    // 编辑模式：目标楼 id（>0 时回复浮层处于编辑态）+ 乐观并发基线原文 + 取原文 loading。
    val editingPostId: Long = 0L,
    val editingOriginalRaw: String = "",
    val editLoadingPostId: Long = 0L,
    // 正在软删除或恢复的楼 id（>0 时该楼操作按钮转圈，避免连点）。
    val postActionLoadingId: Long = 0L
)
