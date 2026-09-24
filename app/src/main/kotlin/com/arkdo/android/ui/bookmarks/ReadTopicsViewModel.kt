package com.arkdo.android.ui.bookmarks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkdo.android.core.data.bookmark.DefaultBookmarkRepository
import com.arkdo.android.core.data.bookmark.mergeReadTopics
import com.arkdo.android.core.model.HomePageResult
import com.arkdo.android.core.model.HomeTopic
import com.arkdo.android.data.models.TopicCategory
import com.arkdo.android.services.network.ArkWebNetworkBridge
import com.arkdo.android.services.site.CategoryIndex
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReadTopicsUiState(
    val items: List<HomeTopic> = emptyList(),
    val categories: List<TopicCategory> = emptyList(),
    val initialLoading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val error: String? = null
)

@HiltViewModel
class ReadTopicsViewModel @Inject constructor(
    private val repository: DefaultBookmarkRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(ReadTopicsUiState())
    val uiState: StateFlow<ReadTopicsUiState> = _uiState.asStateFlow()

    private var page = 0
    private var loading = false

    fun loadInitial() {
        if (loading) return
        loading = true
        val first = _uiState.value.items.isEmpty()
        _uiState.update { it.copy(initialLoading = first, refreshing = !first, error = null) }
        viewModelScope.launch {
            when (val result = repository.readTopics(0)) {
                is HomePageResult.Success -> {
                    page = 0
                    _uiState.update {
                        it.copy(
                            items = result.page.items,
                            hasMore = result.page.hasMore,
                            initialLoading = false,
                            refreshing = false,
                            error = null
                        )
                    }
                }
                is HomePageResult.Failure -> _uiState.update {
                    it.copy(initialLoading = false, refreshing = false, error = result.message)
                }
            }
            loading = false
        }
        loadCategories()
    }

    fun loadMore() {
        val state = _uiState.value
        if (loading || !state.hasMore || state.items.isEmpty()) return
        loading = true
        _uiState.update { it.copy(loadingMore = true) }
        val nextPage = page + 1
        viewModelScope.launch {
            when (val result = repository.readTopics(nextPage)) {
                is HomePageResult.Success -> {
                    if (result.page.items.isEmpty()) {
                        _uiState.update { it.copy(loadingMore = false, hasMore = false) }
                    } else {
                        page = nextPage
                        _uiState.update {
                            it.copy(
                                items = mergeReadTopics(it.items, result.page.items),
                                hasMore = result.page.hasMore,
                                loadingMore = false
                            )
                        }
                    }
                }
                is HomePageResult.Failure -> _uiState.update { it.copy(loadingMore = false) }
            }
            loading = false
        }
    }

    private fun loadCategories() {
        if (_uiState.value.categories.isNotEmpty()) return
        viewModelScope.launch {
            CategoryIndex.loadIfNeeded(
                bridge = ArkWebNetworkBridge,
                ready = true,
                alreadyLoaded = _uiState.value.categories.isNotEmpty(),
                logTag = "[ArkDO][ReadTopics]"
            )?.let { loaded -> _uiState.update { it.copy(categories = loaded) } }
        }
    }
}
