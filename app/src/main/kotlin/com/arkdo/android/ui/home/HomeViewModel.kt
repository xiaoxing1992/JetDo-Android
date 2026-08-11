package com.arkdo.android.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkdo.android.data.models.Topic
import com.arkdo.android.services.topic.TopicService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 首页 ViewModel
 *
 * 状态管理：
 * - 话题列表加载（初始/刷新/加载更多）
 * - 空态/错误态
 * - 分页状态
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val topicService: TopicService
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadInitial()
    }

    /**
     * 初始加载（冷启动）
     */
    private fun loadInitial() {
        if (_uiState.value.isInitialLoading || _uiState.value.topics.isNotEmpty()) {
            return
        }

        _uiState.update { it.copy(isInitialLoading = true, error = null) }

        viewModelScope.launch {
            val result = topicService.getLatestTopicsPage(page = 0)
            _uiState.update { state ->
                if (result.success && result.data != null) {
                    state.copy(
                        isInitialLoading = false,
                        topics = result.data.topics,
                        currentPage = 0,
                        hasMore = result.data.hasMore,
                        error = null
                    )
                } else {
                    state.copy(
                        isInitialLoading = false,
                        error = "加载失败，请重试"
                    )
                }
            }
        }
    }

    /**
     * 下拉刷新
     */
    fun refresh() {
        if (_uiState.value.isRefreshing || _uiState.value.isLoadingMore) {
            return
        }

        _uiState.update { it.copy(isRefreshing = true, error = null) }

        viewModelScope.launch {
            val result = topicService.getLatestTopicsPage(page = 0)
            _uiState.update { state ->
                if (result.success && result.data != null) {
                    state.copy(
                        isRefreshing = false,
                        topics = result.data.topics,
                        currentPage = 0,
                        hasMore = result.data.hasMore,
                        error = null
                    )
                } else {
                    state.copy(
                        isRefreshing = false,
                        error = "刷新失败"
                    )
                }
            }
        }
    }

    /**
     * 加载更多（上拉翻页）
     */
    fun loadMore() {
        val state = _uiState.value
        if (state.isLoadingMore || !state.hasMore || state.isRefreshing) {
            return
        }

        _uiState.update { it.copy(isLoadingMore = true) }

        viewModelScope.launch {
            val nextPage = state.currentPage + 1
            val result = topicService.getLatestTopicsPage(page = nextPage)
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
            }
        }
    }

    /**
     * 重试（错误态点击重试）
     */
    fun retry() {
        _uiState.update { it.copy(error = null) }
        loadInitial()
    }
}

/**
 * 首页 UI 状态
 */
data class HomeUiState(
    val topics: List<Topic> = emptyList(),
    val isInitialLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val currentPage: Int = 0,
    val error: String? = null
)
