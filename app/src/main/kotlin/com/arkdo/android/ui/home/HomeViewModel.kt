package com.arkdo.android.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkdo.android.data.models.Topic
import com.arkdo.android.data.models.TopicCategory
import com.arkdo.android.data.models.TopicTag
import com.arkdo.android.services.network.ArkWebNetworkBridge
import com.arkdo.android.services.site.CategoryIndex
import com.arkdo.android.services.topic.TagSuggestion
import com.arkdo.android.services.topic.TopicListQuery
import com.arkdo.android.services.topic.TopicService
import com.arkdo.android.services.topic.createDefaultTopicListQuery
import com.arkdo.android.services.topic.sameTopicListQuery
import com.arkdo.android.ui.home.HomeConstants.FAB_TOP_HIDE_RATIO
import com.arkdo.android.ui.home.HomeConstants.FAB_TOP_SHOW_RATIO
import com.arkdo.android.ui.home.HomeConstants.PAGE_SIZE
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * 首页 ViewModel - 1:1 对齐鸿蒙版 HomePage.ets
 *
 * 核心功能：
 * - 话题列表加载（初始/刷新/加载更多/增量插入）
 * - 多维度筛选（版块/标签/过滤器）
 * - 实时新话题提醒（MessageBus 订阅）
 * - 右下角智能 FAB（发帖 ↔ 回到顶部）
 * - 版块筛选抽屉状态管理
 * - 会话变化自动刷新
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val topicService: TopicService
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    // Session 变化版本追踪
    private var handledSessionVersion: Long = 0

    // 增量加载 epoch（用于取消过期请求）
    private var incomingRequestEpoch: Int = 0

    // 标签联想 epoch + 防抖 job
    private var tagRequestEpoch: Int = 0
    private var tagSearchJob: kotlinx.coroutines.Job? = null

    init {
        Timber.i("[ArkDO][Home] init: waiting for WebView bridge to be ready")
        // 不再在 init 中立即加载，而是等待外部通知 bridge 就绪

        // 订阅 Session 事件：验证通过后自动刷新
        com.arkdo.android.services.session.SessionEventBus.subscribe { version ->
            handleSessionChanged(version)
        }
    }

    /**
     * 会话变化处理：撞上在途请求时先记账，请求结束后补拉
     */
    private fun handleSessionChanged(version: Long) {
        if (version <= handledSessionVersion) {
            return
        }
        handledSessionVersion = version
        Timber.i("[ArkDO][Home] session changed version=$version, refresh topics")

        val state = _uiState.value
        if (state.isRefreshing || state.isLoadingMore || state.isInitialLoading || state.isLoadingIncoming) {
            _uiState.update { it.copy(pendingSessionRefresh = true) }
            Timber.i("[ArkDO][Home] session refresh queued version=$version")
            return
        }
        refresh()
    }

    /**
     * 执行待定的会话刷新
     */
    private fun runPendingSessionRefresh() {
        val state = _uiState.value
        if (!state.pendingSessionRefresh) {
            return
        }
        if (state.isRefreshing || state.isLoadingMore || state.isInitialLoading || state.isLoadingIncoming) {
            return
        }
        _uiState.update { it.copy(pendingSessionRefresh = false) }
        Timber.i("[ArkDO][Home] run queued session refresh")
        refresh()
    }

    /**
     * 初始加载（冷启动）
     * 应该在 WebView bridge 就绪后由外部调用
     */
    fun loadInitial() {
        val state = _uiState.value
        Timber.i("[ArkDO][Home] loadInitial: isInitialLoading=${state.isInitialLoading} topics.size=${state.topics.size}")

        if (state.isInitialLoading || state.topics.isNotEmpty()) {
            Timber.w("[ArkDO][Home] loadInitial: skipped (already loading or has topics)")
            return
        }

        Timber.i("[ArkDO][Home] stage=firstLatestFetchStart ts=${System.currentTimeMillis()}")
        _uiState.update { it.copy(isInitialLoading = true, error = null) }

        viewModelScope.launch {
            val result = fetchCurrentQueryPage(0)
            _uiState.update { current ->
                if (result.success && result.data != null) {
                    current.copy(
                        isInitialLoading = false,
                        topics = result.data.topics,
                        currentPage = 0,
                        hasMore = result.data.hasMore,
                        error = null
                    )
                } else {
                    current.copy(
                        isInitialLoading = false,
                        error = result.error?.message ?: "加载失败，请重试"
                    )
                }
            }.also {
                runPendingSessionRefresh()
                runPendingIncomingRefresh()
            }
        }
    }

    /**
     * 下拉刷新
     */
    fun refresh() {
        val state = _uiState.value
        if (state.isRefreshing || state.isLoadingMore) {
            return
        }

        // 顺带补拉版块：下拉刷新是用户能主动触发的最自然的重试入口。
        if (state.categories.isEmpty()) loadSiteMetadataIfNeeded(ready = true)

        // 下拉刷新代表用户主动查看最新列表，先清空增量提醒
        clearIncomingTopicsState()

        _uiState.update { it.copy(isRefreshing = true, error = null) }

        viewModelScope.launch {
            val result = fetchCurrentQueryPage(0)
            _uiState.update { current ->
                if (result.success && result.data != null) {
                    current.copy(
                        isRefreshing = false,
                        topics = result.data.topics,
                        currentPage = 0,
                        hasMore = result.data.hasMore,
                        error = null
                    )
                } else {
                    current.copy(
                        isRefreshing = false,
                        error = result.error?.message ?: "刷新失败"
                    )
                }
            }.also {
                runPendingSessionRefresh()
                runPendingIncomingRefresh()
            }
        }
    }

    /**
     * 加载更多（上拉翻页）
     */
    fun loadMore() {
        val state = _uiState.value
        if (state.isLoadingMore || !state.hasMore || state.isRefreshing || state.isLoadingIncoming) {
            return
        }

        _uiState.update { it.copy(isLoadingMore = true) }

        viewModelScope.launch {
            val nextPage = state.currentPage + 1
            val result = fetchCurrentQueryPage(nextPage)
            _uiState.update { current ->
                if (result.success && result.data != null) {
                    val merged = appendTopicsUnique(current.topics, result.data.topics)
                    if (merged.size == current.topics.size) {
                        Timber.i("[ArkDO][Home] loadMore: 第 %d 页与现有列表完全重叠，无新增", nextPage)
                    }
                    current.copy(
                        isLoadingMore = false,
                        topics = merged,
                        currentPage = nextPage,
                        hasMore = result.data.hasMore
                    )
                } else {
                    current.copy(
                        isLoadingMore = false
                    )
                }
            }.also {
                runPendingSessionRefresh()
                runPendingIncomingRefresh()
            }
        }
    }

    /**
     * 按当前筛选条件获取一页话题
     * 支持版块/标签/过滤器三维度正交筛选
     */
    private suspend fun fetchCurrentQueryPage(page: Int) =
        topicService.getTopicsPage(_uiState.value.listQuery, page)

    /**
     * 重试（错误态点击重试）
     */
    fun retry() {
        _uiState.update { it.copy(error = null) }
        loadInitial()
    }

    // ========== 版块筛选 ==========

    /**
     * 点击话题徽章上的分类：把它加进当前条件，等同于在抽屉里选中它再点「查看结果」
     */
    fun selectCategory(category: TopicCategory) {
        if (_uiState.value.listQuery.category?.id == category.id) {
            return // 已经在按它筛选，静默忽略
        }
        _uiState.update { it.copy(listQuery = it.listQuery.copy(category = category)) }
        resetAndReload()
    }

    /**
     * 点击话题徽章上的标签：把它加进当前条件
     */
    fun selectTag(tag: TopicTag) {
        if (_uiState.value.listQuery.tag?.name == tag.name) {
            return // 已经在按它筛选，静默忽略
        }
        _uiState.update { it.copy(listQuery = it.listQuery.copy(tag = tag)) }
        resetAndReload()
    }

    /**
     * 清空筛选条件，回到默认流
     */
    fun clearFilters() {
        _uiState.update { it.copy(listQuery = createDefaultTopicListQuery()) }
        resetAndReload()
    }

    // ========== 侧边筛选抽屉 ==========

    /**
     * 打开抽屉，对齐鸿蒙 HomePage.prepareDrawerContent()。
     *
     * 每次打开都从生效条件重拷一份草稿 —— 上次没点「查看结果」的改动就此丢弃，
     * 等同于「取消」。同时把选中子版块的父版块自动展开，否则它在树里是看不见的。
     */
    fun openDrawer() {
        val state = _uiState.value
        val parentId = state.listQuery.category?.parentCategoryId
        val expanded = if (parentId != null && parentId !in state.drawerExpandedIds) {
            state.drawerExpandedIds + parentId
        } else {
            state.drawerExpandedIds
        }
        // 版块可能上次没拉到（抽屉里就会是空的），开抽屉时再试一次。
        if (state.categories.isEmpty()) loadSiteMetadataIfNeeded(ready = true)
        _uiState.update {
            it.copy(
                drawerOpen = true,
                draftQuery = it.listQuery,
                drawerExpandedIds = expanded,
                // 标签候选每次开抽屉重取，与鸿蒙"内容随开合挂载/卸载"的语义一致。
                tagQuery = "",
                tagResults = emptyList()
            )
        }
    }

    fun closeDrawer() {
        _uiState.update { it.copy(drawerOpen = false) }
    }

    /** 抽屉内的任何改动都只写草稿，不触发请求。 */
    fun updateDraft(next: TopicListQuery) {
        _uiState.update { it.copy(draftQuery = next) }
        // 版块变了 → 标签候选的上下文失效，重取。
        if (next.category?.id != _uiState.value.listQuery.category?.id) {
            loadTagSuggestions(_uiState.value.tagQuery)
        }
    }

    /** 重置：只改草稿，不提交、不关抽屉。 */
    fun resetDraft() {
        _uiState.update { it.copy(draftQuery = createDefaultTopicListQuery()) }
    }

    /**
     * 提交草稿。对齐鸿蒙 handleApplyDraftQuery()：先关抽屉，条件没变就直接返回。
     */
    fun applyDraft() {
        val state = _uiState.value
        _uiState.update { it.copy(drawerOpen = false) }
        if (sameTopicListQuery(state.draftQuery, state.listQuery)) {
            return
        }
        _uiState.update { it.copy(listQuery = state.draftQuery) }
        resetAndReload()
    }

    /** 版块树的展开/折叠。 */
    fun toggleDrawerExpand(categoryId: Long) {
        _uiState.update {
            val ids = it.drawerExpandedIds
            it.copy(
                drawerExpandedIds = if (categoryId in ids) ids - categoryId else ids + categoryId
            )
        }
    }

    /** 标签搜索框输入。防抖 300ms，与鸿蒙 TAG_DEBOUNCE_MS 一致。 */
    fun onTagQueryChanged(query: String) {
        _uiState.update { it.copy(tagQuery = query) }
        tagSearchJob?.cancel()
        tagSearchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(TAG_DEBOUNCE_MS)
            loadTagSuggestions(query.trim())
        }
    }

    /** 首次切到标签页时拉一次热门标签。 */
    fun loadHotTagsIfNeeded() {
        if (_uiState.value.tagResults.isNotEmpty() || _uiState.value.tagLoading) return
        loadTagSuggestions(_uiState.value.tagQuery.trim())
    }

    private fun loadTagSuggestions(query: String) {
        val epoch = ++tagRequestEpoch
        _uiState.update { it.copy(tagLoading = true) }
        viewModelScope.launch {
            val result = topicService.searchTagsSiteWide(query)
            // 过期响应丢弃：用户可能已经又敲了几个字。
            if (epoch != tagRequestEpoch) return@launch
            _uiState.update {
                it.copy(
                    tagLoading = false,
                    tagResults = if (result.success && result.data != null) result.data else emptyList()
                )
            }
        }
    }

    /**
     * 重置列表并重新加载（切换筛选条件后）
     */
    private fun resetAndReload() {
        clearIncomingTopicsState()
        _uiState.update {
            it.copy(
                topics = emptyList(),
                currentPage = 0,
                hasMore = true,
                error = null,
                showBackToTopFab = false
            )
        }
        loadInitial()
    }

    // ========== 实时新话题提醒 ==========

    /**
     * 清空增量状态（下拉刷新、关闭实时开关、切源时调用）
     */
    private fun clearIncomingTopicsState() {
        incomingRequestEpoch++
        _uiState.update {
            it.copy(
                isLoadingIncoming = false,
                pendingIncomingRefresh = false,
                incomingTopicIds = emptyList(),
                incomingPromptVisible = false
            )
        }
    }

    /**
     * MessageBus 推送新话题 ID（由外部调用）
     */
    fun addIncomingTopic(topicId: Long) {
        val state = _uiState.value
        if (state.incomingTopicIds.contains(topicId)) {
            return
        }
        _uiState.update {
            it.copy(
                incomingTopicIds = it.incomingTopicIds + topicId,
                incomingPromptVisible = true
            )
        }
    }

    /**
     * 点击顶部提示条：加载增量话题
     */
    fun loadIncomingTopics() {
        val state = _uiState.value
        if (state.incomingTopicIds.isEmpty()) {
            return
        }

        // 若列表请求在途，等当前请求结束再做增量加载
        if (state.isRefreshing || state.isLoadingMore || state.isInitialLoading || state.isLoadingIncoming) {
            _uiState.update { it.copy(pendingIncomingRefresh = true) }
            return
        }

        _uiState.update { it.copy(pendingIncomingRefresh = false) }
        doLoadIncomingTopics(state.incomingTopicIds)
    }

    /**
     * 执行待定的增量刷新
     */
    private fun runPendingIncomingRefresh() {
        val state = _uiState.value
        if (!state.pendingIncomingRefresh) {
            return
        }
        if (state.isRefreshing || state.isLoadingMore || state.isInitialLoading || state.isLoadingIncoming) {
            return
        }
        if (state.incomingTopicIds.isEmpty()) {
            _uiState.update { it.copy(pendingIncomingRefresh = false) }
            return
        }
        _uiState.update { it.copy(pendingIncomingRefresh = false) }
        doLoadIncomingTopics(state.incomingTopicIds)
    }

    /**
     * 按 ID 列表获取话题（内部辅助方法）
     */
    private suspend fun getLatestTopicsByIds(topicIds: List<Long>) =
        topicService.getLatestTopicsByIds(topicIds)

    /**
     * 实际执行增量话题加载
     */
    private fun doLoadIncomingTopics(topicIds: List<Long>) {
        _uiState.update { it.copy(isLoadingIncoming = true) }
        val requestEpoch = ++incomingRequestEpoch

        viewModelScope.launch {
            Timber.i("[ArkDO][Home] incoming topics fetch started count=${topicIds.size}")
            val result = getLatestTopicsByIds(topicIds)

            if (requestEpoch != incomingRequestEpoch) {
                return@launch // 过期请求，丢弃
            }

            if (result.success && result.data != null && result.data.isNotEmpty()) {
                val incomingTopics = result.data
                Timber.i("[ArkDO][Home] incoming topics fetch completed requested=${topicIds.size} received=${incomingTopics.size}")

                _uiState.update { current ->
                    // 去重：已存在的话题不重复插入
                    val existingIds = current.topics.map { it.id }.toSet()
                    val newTopics = incomingTopics.filter { it.id !in existingIds }

                    current.copy(
                        topics = newTopics + current.topics, // 插入表头
                        isLoadingIncoming = false,
                        incomingTopicIds = emptyList(),
                        incomingPromptVisible = false
                    )
                }.also {
                    runPendingSessionRefresh()
                }
            } else {
                Timber.w("[ArkDO][Home] incoming topics fetch failed or empty")
                _uiState.update {
                    it.copy(
                        isLoadingIncoming = false,
                        incomingTopicIds = emptyList(),
                        incomingPromptVisible = false
                    )
                }
                runPendingSessionRefresh()
            }
        }
    }

    // ========== 站点版块 ==========

    /**
     * 按需加载全站版块，对齐鸿蒙版 HomePage.loadSiteMetadataIfNeeded()。
     *
     * 卡片上的版块徽章和抽屉的版块树都靠这份数据，取不到就整块是空的。
     * 鸿蒙侧从 8 个地方调它（进页面/会话变化/刷新/切站点…），Android 之前只在
     * WebView 就绪那一次调用 —— 那一次失败就再也没有第二次机会，所以这里补上重试。
     */
    fun loadSiteMetadataIfNeeded(ready: Boolean, force: Boolean = false) {
        if (!ready) return
        val state = _uiState.value
        if (state.categoriesLoading) return
        if (state.categories.isNotEmpty() && !force) return

        _uiState.update { it.copy(categoriesLoading = true, categoriesError = null) }
        viewModelScope.launch {
            val loaded = CategoryIndex.loadIfNeeded(
                bridge = ArkWebNetworkBridge,
                ready = true,
                alreadyLoaded = false,
                logTag = "[ArkDO][Home]"
            )
            if (loaded == null) {
                // ready=true 且 alreadyLoaded=false 时返回 null 只有一种含义：取数失败。
                Timber.w("[ArkDO][Home] 版块加载失败")
                _uiState.update {
                    it.copy(categoriesLoading = false, categoriesError = "版块加载失败")
                }
                return@launch
            }
            Timber.i("[ArkDO][Home] categories loaded size=${loaded.size}")
            _uiState.update {
                it.copy(
                    categories = loaded,
                    categoriesLoading = false,
                    categoriesError = if (loaded.isEmpty()) "站点未返回任何版块" else null
                )
            }
        }
    }

    /** 抽屉里点「重试」。强制绕过缓存判断重来一次。 */
    fun retryLoadCategories() {
        CategoryIndex.clearCache()
        loadSiteMetadataIfNeeded(ready = true, force = true)
    }

    // ========== 右下角智能 FAB ==========

    /**
     * 更新 FAB 模式（由 UI 层滚动监听调用）
     * 滞回：滑过 1.5 屏才换成回顶，退回 1.2 屏内才变回发帖
     */
    fun updateFabMode(scrollOffset: Float, viewportHeight: Float) {
        if (viewportHeight <= 0f) {
            return
        }
        val ratio = scrollOffset / viewportHeight
        val state = _uiState.value

        when {
            !state.showBackToTopFab && ratio > FAB_TOP_SHOW_RATIO -> {
                _uiState.update { it.copy(showBackToTopFab = true) }
            }
            state.showBackToTopFab && ratio < FAB_TOP_HIDE_RATIO -> {
                _uiState.update { it.copy(showBackToTopFab = false) }
            }
        }
    }

    companion object {
        /** 标签联想防抖，对齐鸿蒙 TAG_DEBOUNCE_MS。 */
        private const val TAG_DEBOUNCE_MS = 300L
    }
}

/**
 * 按 id 去重地追加一页话题（同页内重复也只留第一个）。
 *
 * 必须去重：LazyColumn 用 topic.id 做 key，而 Discourse 分页在话题被顶帖/移动时
 * 页与页会重叠（置顶帖每页都出现），裸拼接产生重复 id 会让 Compose 在测量阶段
 * 抛 "Key was already used" 直接闪退。鸿蒙列表不强制唯一 key，同样数据不炸。
 */
internal fun appendTopicsUnique(existing: List<Topic>, incoming: List<Topic>): List<Topic> {
    val seen = existing.mapTo(HashSet()) { it.id }
    return existing + incoming.filter { seen.add(it.id) }
}

/**
 * 首页 UI 状态 - 1:1 对齐鸿蒙版
 */
data class HomeUiState(
    // ========== 列表核心状态 ==========
    val topics: List<Topic> = emptyList(),
    val isInitialLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val currentPage: Int = 0,
    val error: String? = null,

    // ========== 筛选维度 ==========
    /** 生效中的查询条件（filter × 版块 × 标签，三者正交）。 */
    val listQuery: TopicListQuery = createDefaultTopicListQuery(),

    // ========== 实时新话题提醒 ==========
    val incomingTopicIds: List<Long> = emptyList(),
    val isLoadingIncoming: Boolean = false,
    val pendingIncomingRefresh: Boolean = false,
    val incomingPromptVisible: Boolean = false,

    // ========== 会话变化处理 ==========
    val pendingSessionRefresh: Boolean = false,

    // ========== 右下角智能 FAB ==========
    val showBackToTopFab: Boolean = false,

    // ========== 版块筛选抽屉 ==========
    val drawerOpen: Boolean = false,
    /** 抽屉内的暂存条件，点「查看结果」才提交给 [listQuery]。 */
    val draftQuery: TopicListQuery = createDefaultTopicListQuery(),
    val drawerExpandedIds: List<Long> = emptyList(),
    val categories: List<TopicCategory> = emptyList(),
    val categoriesLoading: Boolean = false,
    val categoriesError: String? = null,
    /** current_user.sidebar_category_ids，「我的版块」用。 */
    val myCategoryIds: List<Long> = emptyList(),
    /** current_user.sidebar_tags，「我的标签」用。 */
    val myTags: List<TopicTag> = emptyList(),
    val isLoggedIn: Boolean = false,
    val tagQuery: String = "",
    val tagResults: List<TagSuggestion> = emptyList(),
    val tagLoading: Boolean = false
) {
    /** 顶栏与卡片筛选用的便捷读取。 */
    val selectedCategory: TopicCategory? get() = listQuery.category
    val selectedTag: TopicTag? get() = listQuery.tag
}
