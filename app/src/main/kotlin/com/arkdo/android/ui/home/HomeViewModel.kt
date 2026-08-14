package com.arkdo.android.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkdo.android.data.models.Topic
import com.arkdo.android.data.models.TopicCategory
import com.arkdo.android.data.models.TopicTag
import com.arkdo.android.services.topic.TopicService
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
                    current.copy(
                        isLoadingMore = false,
                        topics = current.topics + result.data.topics,
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
    private suspend fun fetchCurrentQueryPage(page: Int) = when {
        _uiState.value.selectedCategory != null -> {
            topicService.getCategoryTopicsPage(_uiState.value.selectedCategory!!, page)
        }
        else -> {
            // 默认：最新话题流
            topicService.getLatestTopicsPage(page)
        }
    }

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
        val state = _uiState.value
        if (state.selectedCategory?.id == category.id) {
            return // 已经在按它筛选，静默忽略
        }
        _uiState.update { it.copy(selectedCategory = category) }
        resetAndReload()
    }

    /**
     * 点击话题徽章上的标签：把它加进当前条件
     */
    fun selectTag(tag: TopicTag) {
        val state = _uiState.value
        if (state.selectedTag?.name == tag.name) {
            return // 已经在按它筛选，静默忽略
        }
        _uiState.update { it.copy(selectedTag = tag) }
        resetAndReload()
    }

    /**
     * 清空筛选条件，回到默认流
     */
    fun clearFilters() {
        _uiState.update { it.copy(selectedCategory = null, selectedTag = null) }
        resetAndReload()
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
        // FAB 切换阈值（单位：视口高度的倍数）
        private const val FAB_TOP_SHOW_RATIO = 1.5f
        private const val FAB_TOP_HIDE_RATIO = 1.2f
    }
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
    val selectedCategory: TopicCategory? = null,
    val selectedTag: TopicTag? = null,
    // TODO: 添加 filter 维度（latest/hot/unread/new）

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
    val categories: List<TopicCategory> = emptyList()
)
