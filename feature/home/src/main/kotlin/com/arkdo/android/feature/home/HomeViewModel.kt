package com.arkdo.android.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkdo.android.core.data.home.HomeRepository
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
    val incomingPromptVisible: Boolean = false
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: HomeRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var requestInFlight = false
    private var pendingLoad = false
    private var generation = 0
    private var incomingRequestEpoch = 0

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

    /** Loads pending realtime topics and prepends only topics not already visible. */
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
                    _uiState.update { current ->
                        val existing = current.topics.mapTo(HashSet(), HomeTopic::id)
                        val incoming = result.page.items.filter { it.id !in existing }
                        current.copy(
                            topics = incoming + current.topics,
                            isLoadingIncoming = false,
                            incomingTopicIds = emptyList(),
                            incomingPromptVisible = false
                        )
                    }
                }
                is HomePageResult.Failure -> _uiState.update {
                    it.copy(
                        isLoadingIncoming = false,
                        incomingTopicIds = emptyList(),
                        incomingPromptVisible = false
                    )
                }
            }
            runPendingIncomingRefresh()
        }
    }

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
