package com.arkdo.android.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkdo.android.core.data.home.HomeRepository
import com.arkdo.android.core.data.site.CategoryLoadResult
import com.arkdo.android.core.data.site.CategoryRepository
import com.arkdo.android.core.model.HomeCategory
import com.arkdo.android.core.model.HomeFeed
import com.arkdo.android.core.model.HomePageResult
import com.arkdo.android.core.model.HomeQuery
import com.arkdo.android.core.model.HomeTopic
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class HomeUiState(
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
    val incomingPromptVisible: Boolean = false,
    /** 正在插入的新行。动画由界面播完后调用 [HomeViewModel.finishIncomingInsert]。 */
    val incomingInsertIds: List<Long> = emptyList(),
    val incomingInsertEpoch: Int = 0,
    /** 插入开始就铺上的底色。界面按截止时间淡回页面底色后调用 [HomeViewModel.finishIncomingHighlight]。 */
    val incomingHighlightIds: List<Long> = emptyList(),
    val incomingHighlightUntil: Long = 0L,
    val categories: List<HomeCategory> = emptyList(),
    val isCategoriesLoading: Boolean = false
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: HomeRepository,
    private val categoryRepository: CategoryRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var requestInFlight = false
    private var pendingLoad = false
    private var generation = 0
    private var incomingRequestEpoch = 0
    private var categoriesRequested = false

    /** Loads the site category index once; failures are silently swallowed (the list keeps working). */
    fun ensureCategoriesLoaded() {
        if (categoriesRequested) return
        categoriesRequested = true
        if (_uiState.value.categories.isNotEmpty()) return
        _uiState.update { it.copy(isCategoriesLoading = true) }
        viewModelScope.launch {
            val result = runCatching { categoryRepository.load() }
                .getOrElse { CategoryLoadResult.Failure(it.message ?: "版块加载失败") }
            when (result) {
                is CategoryLoadResult.Success -> _uiState.update {
                    it.copy(categories = result.categories, isCategoriesLoading = false)
                }
                is CategoryLoadResult.Failure -> _uiState.update { it.copy(isCategoriesLoading = false) }
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
        _uiState.update { it.copy(pendingIncomingRefresh = false, isLoadingIncoming = true) }
        val requestedIds = state.incomingTopicIds
        val requestEpoch = ++incomingRequestEpoch
        viewModelScope.launch {
            val result = runCatching { repository.loadByIds(requestedIds) }
                .getOrElse { HomePageResult.Failure(it.message ?: "加载失败，请重试") }
            if (requestEpoch != incomingRequestEpoch) return@launch
            when (result) {
                is HomePageResult.Success -> {
                    val incoming = result.page.items.distinctBy(HomeTopic::id)
                    if (incoming.isEmpty()) {
                        // 空结果保留提示条，用户可以再点一次。对齐鸿蒙：toast 后不摘掉待处理 id。
                        _uiState.update { it.copy(isLoadingIncoming = false, error = "暂未获取到新话题，请稍后重试") }
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
                .getOrElse { HomePageResult.Failure(it.message ?: "加载失败，请重试") }
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
}
