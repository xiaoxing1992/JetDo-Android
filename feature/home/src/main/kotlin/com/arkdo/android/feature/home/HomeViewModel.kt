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
    val showBackToTopFab: Boolean = false
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

    fun loadInitial() {
        val state = _uiState.value
        if (requestInFlight || state.topics.isNotEmpty()) return
        request(reset = true, page = 0)
    }

    fun refresh() {
        if (requestInFlight) return
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
        if (_uiState.value.query.feed == feed) return
        generation += 1
        _uiState.update {
            it.copy(
                query = it.query.copy(feed = feed),
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
