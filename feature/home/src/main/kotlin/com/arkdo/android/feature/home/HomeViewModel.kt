package com.arkdo.android.feature.home

import com.arkdo.android.feature.home.R
import com.arkdo.android.core.data.AppContextHolder
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkdo.android.core.data.home.HomeRepository
import com.arkdo.android.core.data.session.SessionVersionSource
import com.arkdo.android.core.data.session.SidebarPrefsSource
import com.arkdo.android.core.data.session.TopicReadStateSource
import com.arkdo.android.core.data.session.TopicReadStateUpdate
import com.arkdo.android.core.data.site.CategoryLoadResult
import com.arkdo.android.core.data.site.CategoryRepository
import com.arkdo.android.core.model.HomeCategory
import com.arkdo.android.core.model.HomeFeed
import com.arkdo.android.core.model.HomePageResult
import com.arkdo.android.core.model.HomeQuery
import com.arkdo.android.core.model.HomeTag
import com.arkdo.android.core.model.HomeTopic
import com.arkdo.android.core.model.SidebarPrefs
import com.arkdo.android.core.model.TagSuggestion
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class HomeUiState(
    val query: HomeQuery = HomeQuery(),
    val topics: List<HomeTopic> = emptyList(),
    val page: Int = 0,
    val hasMore: Boolean = true,
    val isInitialLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
    val showBackToTopFab: Boolean = false,
    val incomingTopicIds: List<Long> = emptyList(),
    val isLoadingIncoming: Boolean = false,
    val pendingIncomingRefresh: Boolean = false,
    val pendingSessionRefresh: Boolean = false,
    val incomingPromptVisible: Boolean = false,
    /** 正在插入的新行。动画由界面播完后调用 [HomeViewModel.finishIncomingInsert]。 */
    val incomingInsertIds: List<Long> = emptyList(),
    val incomingInsertEpoch: Int = 0,
    /** 插入开始就铺上的底色。界面按截止时间淡回页面底色后调用 [HomeViewModel.finishIncomingHighlight]。 */
    val incomingHighlightIds: List<Long> = emptyList(),
    val incomingHighlightUntil: Long = 0L,
    val categories: List<HomeCategory> = emptyList(),
    val isCategoriesLoading: Boolean = false,
    val categoriesError: String? = null,
    val drawerOpen: Boolean = false,
    val draftQuery: HomeQuery = HomeQuery(),
    val drawerExpandedIds: List<Long> = emptyList(),
    val tagQuery: String = "",
    val tagResults: List<TagSuggestion> = emptyList(),
    val tagLoading: Boolean = false,
    /** 抽屉「热门标签」组的兜底数据，来自 /site.json 的 top_tags。 */
    val topTags: List<TagSuggestion> = emptyList(),
    /** 抽屉「我的版块 / 我的标签」置顶组，来自 /session/current.json 的侧栏自选。 */
    val myCategoryIds: List<Long> = emptyList(),
    val myTags: List<HomeTag> = emptyList()
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: HomeRepository,
    private val categoryRepository: CategoryRepository,
    private val sidebarPrefsSource: SidebarPrefsSource,
    private val sessionVersion: SessionVersionSource,
    private val readState: TopicReadStateSource
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    internal val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /** 当前列表筛选。宿主发帖时带上选中的版块，不必订阅整份界面状态。 */
    val composeCategoryId: Long? get() = _uiState.value.query.categoryId

    /** 当前列表筛选。宿主判断实时新话题该不该提示时读这一份。 */
    val currentQuery: HomeQuery get() = _uiState.value.query

    private var requestInFlight = false
    private var pendingLoad = false
    private var generation = 0
    private var incomingRequestEpoch = 0
    private var categoriesRequested = false
    private var categoryLoadFailed = false
    private var sidebarRequested = false
    private var handledSessionVersion = sessionVersion.currentVersion()
    private val unsubscribeSession = sessionVersion.subscribe(::handleSessionChanged)
    // 详情页离开时结算的已读状态：回列表实时消蓝点、改未读徽标（对齐鸿蒙
    // HomePage.aboutToAppear 里对 TopicReadStateEventBus 的订阅，:362-364）。
    private val unsubscribeReadState = readState.subscribe(::handleTopicReadStateChanged)
    private var tagRequestEpoch = 0
    private var tagSearchJob: Job? = null

    init {
        // 订阅前已经涨过的版本（登录发生在首页挂载之前）补一次，对齐鸿蒙 syncSessionVersion。
        handleSessionChanged(sessionVersion.currentVersion())
    }

    override fun onCleared() {
        unsubscribeSession()
        unsubscribeReadState()
    }

    /**
     * 详情页回传的已读状态落到列表行上（对齐鸿蒙 HomePage.handleTopicReadStateChanged，:1976）。
     * 只动命中的那一行：unseen/unreadPosts 直接覆写；已读水位同步成「最高楼 − 剩余未读」，
     * 再点未读徽标时跳转目标才是最新的第一条未读楼。列表里没有这篇（被筛选/翻页外）就忽略。
     */
    private fun handleTopicReadStateChanged(update: TopicReadStateUpdate) {
        _uiState.update { state ->
            val index = state.topics.indexOfFirst { it.id == update.topicId }
            if (index < 0) return@update state
            val topic = state.topics[index]
            val syncedWatermark = topic.highestPostNumber
                ?.takeIf { it > 0 }
                ?.let { maxOf(0, it - update.unreadPosts) }
                ?: topic.lastReadPostNumber
            val patched = topic.copy(
                unseen = update.unseen,
                unreadPosts = update.unreadPosts,
                lastReadPostNumber = syncedWatermark
            )
            if (patched == topic) return@update state
            state.copy(topics = state.topics.toMutableList().also { it[index] = patched })
        }
    }

    /**
     * 会话版本变了就重拉当前列表。撞上正在进行的请求先记账，请求结束再补拉。
     * 版本不比已处理的新就丢掉，避免重复刷新。
     */
    private fun handleSessionChanged(version: Long) {
        if (version <= handledSessionVersion) return
        handledSessionVersion = version
        // 换号/登出后侧栏自选也换了人：置回未拉取，下次开抽屉重新取；
        // 同时就地清空已有的一组，免得拉开抽屉先闪一下上一个账号的版块。
        sidebarRequested = false
        _uiState.update { it.copy(myCategoryIds = emptyList(), myTags = emptyList()) }
        if (requestInFlight || _uiState.value.isLoadingIncoming) {
            _uiState.update { it.copy(pendingSessionRefresh = true) }
            return
        }
        refresh()
    }

    private fun runPendingSessionRefresh() {
        val state = _uiState.value
        if (!state.pendingSessionRefresh) return
        if (requestInFlight || state.isLoadingIncoming) return
        _uiState.update { it.copy(pendingSessionRefresh = false) }
        refresh()
    }

    /** Loads the site category index once; a failed load can be retried from the drawer. */
    fun ensureCategoriesLoaded() {
        if (categoriesRequested && !categoryLoadFailed) return
        categoriesRequested = true
        categoryLoadFailed = false
        if (_uiState.value.categories.isNotEmpty()) return
        _uiState.update { it.copy(isCategoriesLoading = true, categoriesError = null) }
        viewModelScope.launch {
            val result = runCatching { categoryRepository.load() }
                .getOrElse { CategoryLoadResult.Failure(it.message ?: AppContextHolder.getString(R.string.drawer_categories_failed)) }
            when (result) {
                is CategoryLoadResult.Success -> _uiState.update {
                    it.copy(
                        categories = result.categories,
                        topTags = result.topTags,
                        isCategoriesLoading = false,
                        categoriesError = if (result.categories.isEmpty()) AppContextHolder.getString(R.string.err_no_categories) else null
                    )
                }
                is CategoryLoadResult.Failure -> {
                    categoryLoadFailed = true
                    _uiState.update {
                        it.copy(isCategoriesLoading = false, categoriesError = result.message)
                    }
                }
            }
        }
    }

    fun loadInitial() {
        val state = _uiState.value
        if (requestInFlight || state.topics.isNotEmpty()) return
        request(reset = true, page = 0)
    }

    fun refresh() {
        if (requestInFlight) return
        clearIncomingTopicsState()
        request(reset = true, page = 0)
    }

    fun loadMore() {
        val state = _uiState.value
        if (requestInFlight || !state.hasMore || state.topics.isEmpty()) return
        request(reset = false, page = state.page + 1)
    }

    fun retry() {
        if (_uiState.value.topics.isEmpty()) loadInitial() else refresh()
    }

    /** Reloads the category index on demand (e.g. after a retry from the drawer). */
    fun reloadCategories() {
        categoriesRequested = false
        ensureCategoriesLoaded()
    }

    /**
     * 侧栏自选（抽屉的「我的版块 / 我的标签」）。
     *
     * 推迟到抽屉真要打开时才拉 —— 多数人开 App 只是刷首页，为它每次冷启动都打一次
     * /session/current.json 不划算。失败不重试（拿到空就是空，抽屉退回只有「所有版块 /
     * 热门标签」），会话变化时由 [handleSessionChanged] 置回未拉取。
     */
    fun ensureSidebarLoaded() {
        if (sidebarRequested) return
        sidebarRequested = true
        viewModelScope.launch {
            val prefs = runCatching { sidebarPrefsSource.load() }.getOrElse { SidebarPrefs.Empty }
            _uiState.update { it.copy(myCategoryIds = prefs.categoryIds, myTags = prefs.tags) }
        }
    }

    fun updateFabMode(scrollOffset: Float, viewportHeight: Float) {
        if (viewportHeight <= 0f) return
        val ratio = scrollOffset / viewportHeight
        val state = _uiState.value
        when {
            !state.showBackToTopFab && ratio > 1.5f ->
                _uiState.update { it.copy(showBackToTopFab = true) }
            state.showBackToTopFab && ratio < 1.2f ->
                _uiState.update { it.copy(showBackToTopFab = false) }
        }
    }

    fun selectFeed(feed: HomeFeed) {
        setQuery(_uiState.value.query.copy(feed = feed))
    }

    fun openDrawer(isLoggedIn: Boolean) {
        val state = _uiState.value
        val parentId = state.query.categoryId?.let { selectedId ->
            state.categories.firstOrNull { it.id == selectedId }?.parentCategoryId
        }
        val expanded = if (parentId != null && parentId !in state.drawerExpandedIds) {
            state.drawerExpandedIds + parentId
        } else {
            state.drawerExpandedIds
        }
        if (state.categories.isEmpty()) ensureCategoriesLoaded()
        ensureSidebarLoaded()
        val draft = state.query.withoutLoginOnlyFeeds(isLoggedIn)
        _uiState.update {
            it.copy(
                drawerOpen = true,
                draftQuery = draft,
                drawerExpandedIds = expanded,
                tagQuery = "",
                tagResults = emptyList()
            )
        }
    }

    fun closeDrawer() {
        _uiState.update { it.copy(drawerOpen = false) }
    }

    /** 抽屉里的改动只写草稿。未登录不能把未读流留在草稿里。 */
    fun updateDraft(next: HomeQuery, isLoggedIn: Boolean) {
        val draft = next.withoutLoginOnlyFeeds(isLoggedIn)
        val categoryChanged = draft.categoryId != _uiState.value.draftQuery.categoryId
        _uiState.update { it.copy(draftQuery = draft) }
        // 版块变了，标签候选的上下文跟着变（带 categoryId 后只回该版块下的标签）。
        // 正在搜索、或热门组此刻已经列着东西时才重取；两者都不是说明标签页压根没在看，
        // 记着等它真要显示时再说（这里用一个便宜的条件代替抽屉里的手风琴状态）。
        val showingTags = _uiState.value.tagQuery.isNotBlank() || _uiState.value.tagResults.isNotEmpty()
        if (categoryChanged && showingTags) {
            loadTagSuggestions(_uiState.value.tagQuery)
        }
    }

    fun resetDraft() {
        _uiState.update { it.copy(draftQuery = HomeQuery()) }
    }

    /** 提交草稿。条件没变就只关抽屉。 */
    fun applyDraft() {
        val draft = _uiState.value.draftQuery
        _uiState.update { it.copy(drawerOpen = false) }
        setQuery(draft)
    }

    fun toggleDrawerExpand(categoryId: Long) {
        _uiState.update {
            val ids = it.drawerExpandedIds
            it.copy(drawerExpandedIds = if (categoryId in ids) ids - categoryId else ids + categoryId)
        }
    }

    fun onTagQueryChanged(query: String) {
        _uiState.update { it.copy(tagQuery = query) }
        tagSearchJob?.cancel()
        tagSearchJob = viewModelScope.launch {
            delay(TAG_DEBOUNCE_MS)
            loadTagSuggestions(query.trim())
        }
    }

    fun loadHotTagsIfNeeded() {
        if (_uiState.value.tagResults.isNotEmpty() || _uiState.value.tagLoading) return
        loadTagSuggestions(_uiState.value.tagQuery.trim())
    }

    private fun loadTagSuggestions(query: String) {
        val state = _uiState.value
        val categoryId = state.draftQuery.categoryId

        // 「没输搜索词 + 没选版块」= 要的是站点热门标签，而不是一次联想查询。
        // 这份数据 /site.json 里就有(top_tags)，首页早已为版块树拉过那个接口 ——
        // 直接读缓存，既省一次请求，又绕开 /tags/filter/search 的 max_tag_search_results
        // 上限(默认只有 5，超了整条请求会被拒，表现为「暂无可选标签」)。
        if (query.isEmpty() && categoryId == null && state.topTags.isNotEmpty()) {
            tagSearchJob?.cancel()
            _uiState.update { it.copy(tagLoading = false, tagResults = it.topTags) }
            return
        }

        val epoch = ++tagRequestEpoch
        _uiState.update { it.copy(tagLoading = true) }
        viewModelScope.launch {
            val results = runCatching {
                // 选了版块就带上 categoryId：标签挂在版块上，几百个全站标签里翻没有意义。
                categoryRepository.suggestTags(query, categoryId)
            }.getOrElse { emptyList() }
            if (epoch != tagRequestEpoch) return@launch
            _uiState.update { it.copy(tagLoading = false, tagResults = results) }
        }
    }

    fun setQuery(query: HomeQuery) {
        if (_uiState.value.query == query) return
        generation += 1
        clearIncomingTopicsState()
        _uiState.update {
            it.copy(
                query = query,
                topics = emptyList(),
                page = 0,
                hasMore = true,
                error = null
            )
        }
        if (requestInFlight) {
            pendingLoad = true
        } else {
            loadInitial()
        }
    }

    /** 关掉「首页新话题提醒」时清掉提示条、待拉 id 和还没播完的插入。 */
    fun dismissIncomingTopics() {
        clearIncomingTopicsState()
    }

    /** Called by the host when MessageBus reports activity on a topic channel. */
    fun addIncomingTopic(topicId: Long) {
        if (topicId <= 0) return
        _uiState.update { state ->
            if (topicId in state.incomingTopicIds) {
                state
            } else {
                state.copy(
                    incomingTopicIds = state.incomingTopicIds + topicId,
                    incomingPromptVisible = true
                )
            }
        }
    }

    /**
     * 列表非空时的一次性错误提示已被消费（HomeRoute 弹完 toast 调用），清掉避免反复弹。
     * 空列表场景不调用——那里的 error 由 EmptyState 常驻显示，清了反而让用户看不到重试入口。
     */
    fun consumeError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * 拉取提示条记下的话题，整批插到表头。
     * 已在列表里的（更新）先从原位置摘掉再以新数据归位，不只追加全新的。
     */
    fun loadIncomingTopics() {
        val state = _uiState.value
        if (state.incomingTopicIds.isEmpty()) return
        if (requestInFlight || state.isLoadingIncoming) {
            _uiState.update { it.copy(pendingIncomingRefresh = true) }
            return
        }
        // 开拉先清掉上一条 error：否则同一句失败文案第二次不会再触发提示
        // （HomeRoute 的 toast 以 state.error 为 key，值不变就不重启）。
        _uiState.update {
            it.copy(pendingIncomingRefresh = false, isLoadingIncoming = true, error = null)
        }
        val requestedIds = state.incomingTopicIds
        val requestEpoch = ++incomingRequestEpoch
        viewModelScope.launch {
            val result = runCatching { repository.loadByIds(requestedIds) }
                .getOrElse { HomePageResult.Failure(it.message ?: AppContextHolder.getString(R.string.home_load_failed_retry)) }
            if (requestEpoch != incomingRequestEpoch) return@launch
            when (result) {
                is HomePageResult.Success -> {
                    val incoming = result.page.items.distinctBy(HomeTopic::id)
                    if (incoming.isEmpty()) {
                        // 空结果保留提示条，用户可以再点一次。对齐鸿蒙：toast 后不摘掉待处理 id。
                        _uiState.update { it.copy(isLoadingIncoming = false, error = AppContextHolder.getString(R.string.home_no_new_topics)) }
                    } else {
                        _uiState.update { current ->
                            val incomingIds = incoming.mapTo(HashSet(), HomeTopic::id)
                            val remaining = current.topics.filter { it.id !in incomingIds }
                            current.copy(
                                topics = incoming + remaining,
                                isLoadingIncoming = true,
                                incomingTopicIds = emptyList(),
                                incomingPromptVisible = false,
                                incomingInsertIds = incoming.map(HomeTopic::id),
                                incomingInsertEpoch = current.incomingInsertEpoch + 1,
                                incomingHighlightIds = incoming.map(HomeTopic::id),
                                incomingHighlightUntil = System.currentTimeMillis() +
                                    INCOMING_INSERT_MS + INCOMING_HIGHLIGHT_MS,
                                error = null
                            )
                        }
                    }
                }
                is HomePageResult.Failure -> _uiState.update {
                    it.copy(isLoadingIncoming = false, error = result.message)
                }
            }
            runPendingIncomingRefresh()
            runPendingSessionRefresh()
        }
    }

    /**
     * 插入动画结束。底色在插入开始时就已经铺上，这里只收起插入态。
     * epoch 对不上说明中途被刷新或下一次插入打断。
     */
    fun finishIncomingInsert(epoch: Int) {
        _uiState.update { state ->
            if (state.incomingInsertEpoch != epoch || state.incomingInsertIds.isEmpty()) return@update state
            state.copy(
                isLoadingIncoming = false,
                incomingInsertIds = emptyList()
            )
        }
        runPendingIncomingRefresh()
        runPendingSessionRefresh()
    }

    /** 浅蓝已经淡回页面底色，清掉高亮，避免行一直停在浅蓝色。 */
    fun finishIncomingHighlight() {
        _uiState.update { state ->
            if (state.incomingHighlightIds.isEmpty()) return@update state
            state.copy(incomingHighlightIds = emptyList(), incomingHighlightUntil = 0L)
        }
    }

    private fun clearIncomingTopicsState() {
        incomingRequestEpoch++
        _uiState.update {
            it.copy(
                isLoadingIncoming = false,
                pendingIncomingRefresh = false,
                incomingTopicIds = emptyList(),
                incomingPromptVisible = false,
                incomingInsertIds = emptyList(),
                incomingInsertEpoch = it.incomingInsertEpoch + 1,
                incomingHighlightIds = emptyList(),
                incomingHighlightUntil = 0L
            )
        }
    }

    private fun runPendingIncomingRefresh() {
        val state = _uiState.value
        if (!state.pendingIncomingRefresh || requestInFlight || state.incomingTopicIds.isEmpty()) return
        _uiState.update { it.copy(pendingIncomingRefresh = false) }
        loadIncomingTopics()
    }

    private fun request(reset: Boolean, page: Int) {
        if (requestInFlight) return
        requestInFlight = true
        val requestedQuery = _uiState.value.query
        val requestedGeneration = generation
        _uiState.update {
            it.copy(
                isInitialLoading = reset && it.topics.isEmpty(),
                isRefreshing = reset && it.topics.isNotEmpty(),
                isLoadingMore = !reset,
                error = null
            )
        }

        viewModelScope.launch {
            val result = runCatching { repository.load(requestedQuery, page) }
                .getOrElse { HomePageResult.Failure(it.message ?: AppContextHolder.getString(R.string.home_load_failed_retry)) }
            requestInFlight = false

            if (requestedGeneration == generation) {
                when (result) {
                    is HomePageResult.Success -> {
                        val topics = if (reset) {
                            result.page.items.distinctBy(HomeTopic::id)
                        } else {
                            appendTopicsUnique(_uiState.value.topics, result.page.items)
                        }
                        _uiState.update {
                            it.copy(
                                topics = topics,
                                page = result.page.page,
                                hasMore = result.page.hasMore,
                                isInitialLoading = false,
                                isRefreshing = false,
                                isLoadingMore = false,
                                error = null
                            )
                        }
                    }
                    is HomePageResult.Failure -> _uiState.update {
                        it.copy(
                            isInitialLoading = false,
                            isRefreshing = false,
                            isLoadingMore = false,
                            error = result.message
                        )
                    }
                }
            } else {
                _uiState.update {
                    it.copy(
                        isInitialLoading = false,
                        isRefreshing = false,
                        isLoadingMore = false
                    )
                }
            }

            if (pendingLoad && !requestInFlight) {
                pendingLoad = false
                loadInitial()
            }
            runPendingSessionRefresh()
            runPendingIncomingRefresh()
        }
    }

    private fun appendTopicsUnique(
        existing: List<HomeTopic>,
        incoming: List<HomeTopic>
    ): List<HomeTopic> {
        val seen = existing.mapTo(HashSet()) { it.id }
        return existing + incoming.filter { seen.add(it.id) }
    }

    private companion object {
        const val TAG_DEBOUNCE_MS = 300L
    }
}

/** 未读和未读新帖要登录。未登录时草稿退回「最新」，不发一定会失败的请求。 */
internal fun HomeQuery.withoutLoginOnlyFeeds(isLoggedIn: Boolean): HomeQuery =
    if (!isLoggedIn && (feed == HomeFeed.UNREAD || feed == HomeFeed.NEW)) {
        copy(feed = HomeFeed.LATEST)
    } else {
        this
    }
