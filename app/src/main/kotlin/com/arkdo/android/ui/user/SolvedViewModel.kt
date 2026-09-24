package com.arkdo.android.ui.user

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkdo.android.data.models.SolvedPostItem
import com.arkdo.android.data.models.TopicCategory
import com.arkdo.android.services.network.ArkWebNetworkBridge
import com.arkdo.android.services.site.CategoryIndex
import com.arkdo.android.services.user.UserProfileService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 解决方案列表（对齐鸿蒙 SolvedPostsPage）：
 * 初始加载第 0 页、下拉刷新重拉、触底按已加载条数翻页。
 */
@HiltViewModel
class SolvedViewModel @Inject constructor(
    private val userProfileService: UserProfileService
) : ViewModel() {

    data class UiState(
        val items: List<SolvedPostItem> = emptyList(),
        val categories: List<TopicCategory> = emptyList(),
        val initialLoading: Boolean = false,
        val refreshing: Boolean = false,
        val loadingMore: Boolean = false,
        val hasMore: Boolean = true,
        val error: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var loadedUsername: String? = null
    private var loading = false

    fun loadInitial(username: String) {
        if (username.isBlank()) return
        loadedUsername = username
        if (loading) return
        loading = true
        val isFirst = _uiState.value.items.isEmpty()
        _uiState.update {
            it.copy(
                initialLoading = isFirst,
                refreshing = !isFirst,
                loadingMore = false,
                error = null
            )
        }
        viewModelScope.launch {
            val result = userProfileService.getSolvedPosts(username, 0)
            loading = false
            if (loadedUsername != username) return@launch
            _uiState.update {
                if (result.success && result.data != null) {
                    it.copy(
                        items = result.data.items,
                        hasMore = result.data.hasMore,
                        initialLoading = false,
                        refreshing = false,
                        error = null
                    )
                } else {
                    it.copy(
                        initialLoading = false,
                        refreshing = false,
                        error = result.error?.message ?: "加载失败，请重试"
                    )
                }
            }
        }
        loadCategoriesIfNeeded()
    }

    fun loadMore() {
        val state = _uiState.value
        if (loading || !state.hasMore || state.items.isEmpty()) return
        loading = true
        _uiState.update { it.copy(loadingMore = true) }
        val username = loadedUsername ?: return
        viewModelScope.launch {
            val result = userProfileService.getSolvedPosts(username, state.items.size)
            loading = false
            if (loadedUsername != username) return@launch
            _uiState.update {
                if (result.success && result.data != null) {
                    val more = result.data.items
                    if (more.isEmpty()) {
                        it.copy(loadingMore = false, hasMore = false)
                    } else {
                        it.copy(
                            items = it.items + more,
                            hasMore = result.data.hasMore,
                            loadingMore = false
                        )
                    }
                } else {
                    it.copy(loadingMore = false)
                }
            }
        }
    }

    private fun loadCategoriesIfNeeded() {
        if (_uiState.value.categories.isNotEmpty()) return
        viewModelScope.launch {
            CategoryIndex.loadIfNeeded(
                bridge = ArkWebNetworkBridge,
                ready = true,
                alreadyLoaded = _uiState.value.categories.isNotEmpty(),
                logTag = "[ArkDO][Solved]"
            )?.let { loaded ->
                _uiState.update { it.copy(categories = loaded) }
            }
        }
    }
}
