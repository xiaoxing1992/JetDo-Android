package com.arkdo.android.ui.bookmarks

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkdo.android.core.data.bookmark.BookmarkItem
import com.arkdo.android.core.data.bookmark.BookmarkPageResult
import com.arkdo.android.core.data.bookmark.DefaultBookmarkRepository
import com.arkdo.android.core.data.bookmark.sortPinnedFirst
import com.arkdo.android.data.models.TopicCategory
import com.arkdo.android.services.network.ArkWebNetworkBridge
import com.arkdo.android.services.session.LoginGate
import com.arkdo.android.services.site.CategoryIndex
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BookmarksUiState(
    val items: List<BookmarkItem> = emptyList(),
    val categories: List<TopicCategory> = emptyList(),
    val initialLoading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val error: String? = null,
    val toast: String? = null
)

@HiltViewModel
class BookmarksViewModel @Inject constructor(
    private val repository: DefaultBookmarkRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(BookmarksUiState())
    val uiState: StateFlow<BookmarksUiState> = _uiState.asStateFlow()

    private var username: String = ""
    private var moreUrl: String? = null
    private var loading = false

    fun loadInitial(username: String) {
        if (username.isBlank() || loading) return
        this.username = username
        loading = true
        val first = _uiState.value.items.isEmpty()
        _uiState.update { it.copy(initialLoading = first, refreshing = !first, error = null) }
        viewModelScope.launch {
            when (val result = repository.firstPage(username)) {
                is BookmarkPageResult.Success -> {
                    moreUrl = result.page.moreUrl
                    _uiState.update {
                        it.copy(
                            items = sortPinnedFirst(result.page.items),
                            hasMore = !result.page.moreUrl.isNullOrEmpty(),
                            initialLoading = false,
                            refreshing = false,
                            error = null
                        )
                    }
                }
                is BookmarkPageResult.Failure -> _uiState.update {
                    it.copy(initialLoading = false, refreshing = false, error = result.message)
                }
            }
            loading = false
        }
        loadCategories()
    }

    fun loadMore() {
        val url = moreUrl
        if (loading || url.isNullOrEmpty() || !_uiState.value.hasMore) return
        loading = true
        _uiState.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            when (val result = repository.nextPage(url)) {
                is BookmarkPageResult.Success -> {
                    if (result.page.items.isEmpty()) {
                        moreUrl = null
                        _uiState.update { it.copy(loadingMore = false, hasMore = false) }
                    } else {
                        moreUrl = result.page.moreUrl
                        _uiState.update {
                            it.copy(
                                items = sortPinnedFirst(it.items + result.page.items),
                                hasMore = !result.page.moreUrl.isNullOrEmpty(),
                                loadingMore = false
                            )
                        }
                    }
                }
                is BookmarkPageResult.Failure -> _uiState.update { it.copy(loadingMore = false) }
            }
            loading = false
        }
    }

    fun togglePin(item: BookmarkItem, context: Context) {
        if (!LoginGate.ensureLoggedIn(context)) return
        viewModelScope.launch {
            if (!repository.togglePin(item.id)) {
                _uiState.update { it.copy(toast = "操作失败，请稍后再试") }
                return@launch
            }
            val pinned = !item.pinned
            _uiState.update { state ->
                state.copy(
                    items = sortPinnedFirst(state.items.map { current ->
                        if (current.id == item.id) current.copy(pinned = pinned) else current
                    }),
                    toast = if (pinned) "已置顶" else "已取消置顶"
                )
            }
        }
    }

    fun delete(item: BookmarkItem, context: Context) {
        if (!LoginGate.ensureLoggedIn(context)) return
        viewModelScope.launch {
            if (!repository.delete(item.id)) {
                _uiState.update { it.copy(toast = "删除失败，请稍后再试") }
                return@launch
            }
            _uiState.update { state ->
                state.copy(items = state.items.filterNot { it.id == item.id }, toast = "已删除")
            }
        }
    }

    fun consumeToast() {
        _uiState.update { it.copy(toast = null) }
    }

    private fun loadCategories() {
        if (_uiState.value.categories.isNotEmpty()) return
        viewModelScope.launch {
            CategoryIndex.loadIfNeeded(
                bridge = ArkWebNetworkBridge,
                ready = true,
                alreadyLoaded = _uiState.value.categories.isNotEmpty(),
                logTag = "[ArkDO][Bookmarks]"
            )?.let { loaded -> _uiState.update { it.copy(categories = loaded) } }
        }
    }
}
