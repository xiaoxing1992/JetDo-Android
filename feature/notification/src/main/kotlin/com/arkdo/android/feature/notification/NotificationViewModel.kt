package com.arkdo.android.feature.notification

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkdo.android.core.data.notification.NotificationPageResult
import com.arkdo.android.core.data.notification.NotificationRepository
import com.arkdo.android.core.data.notification.NotificationUnreadStore
import com.arkdo.android.core.model.NotificationItem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class NotificationUiState(
    val items: List<NotificationItem> = emptyList(),
    val initialLoading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val error: String? = null
)

@HiltViewModel
class NotificationViewModel @Inject constructor(
    private val repository: NotificationRepository,
    private val unreadStore: NotificationUnreadStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(NotificationUiState())
    internal val uiState: StateFlow<NotificationUiState> = _uiState.asStateFlow()

    private var loading = false

    fun loadInitial() {
        if (loading || _uiState.value.items.isNotEmpty()) return
        load(reset = true)
    }

    fun refresh() {
        if (!loading) load(reset = true)
    }

    fun loadMore() {
        if (!loading && _uiState.value.hasMore && _uiState.value.items.isNotEmpty()) {
            load(reset = false)
        }
    }

    fun markItemRead(item: NotificationItem) {
        if (item.read) return
        _uiState.update { state ->
            state.copy(items = state.items.map { if (it.id == item.id) it.copy(read = true) else it })
        }
        unreadStore.decrement(item.type == 6 || item.type == 7)
        viewModelScope.launch {
            if (!repository.markRead(item.id)) {
                _uiState.update { state ->
                    state.copy(items = state.items.map { if (it.id == item.id) it.copy(read = false) else it })
                }
                unreadStore.publish(
                    _uiState.value.items.count { !it.read },
                    unreadStore.currentHighPriority()
                )
            }
        }
    }

    fun markAllRead() {
        val unreadIds = _uiState.value.items.filterNot { it.read }.map { it.id }.toSet()
        if (unreadIds.isEmpty()) return
        _uiState.update { state ->
            state.copy(items = state.items.map { if (it.read) it else it.copy(read = true) })
        }
        unreadStore.publish(0, 0)
        viewModelScope.launch {
            if (!repository.markAllRead()) {
                _uiState.update { state ->
                    state.copy(items = state.items.map { if (it.id in unreadIds) it.copy(read = false) else it })
                }
                unreadStore.publish(unreadIds.size, unreadStore.currentHighPriority())
            }
        }
    }

    private fun load(reset: Boolean) {
        loading = true
        val isFirst = reset && _uiState.value.items.isEmpty()
        val offset = if (reset) 0 else _uiState.value.items.size
        _uiState.update {
            it.copy(
                initialLoading = isFirst,
                refreshing = reset && !isFirst,
                loadingMore = !reset,
                error = null
            )
        }
        viewModelScope.launch {
            val result = repository.load(limit = PAGE_SIZE, offset = offset, recent = reset)
            loading = false
            when (result) {
                is NotificationPageResult.Success -> {
                    val page = result.page.items
                    val items = if (reset) page else _uiState.value.items + page
                    if (reset) unreadStore.publish(page.count { !it.read }, unreadStore.currentHighPriority())
                    _uiState.update {
                        it.copy(
                            items = items,
                            hasMore = page.size >= PAGE_SIZE,
                            initialLoading = false,
                            refreshing = false,
                            loadingMore = false,
                            error = null
                        )
                    }
                }
                is NotificationPageResult.Failure -> _uiState.update {
                    it.copy(
                        initialLoading = false,
                        refreshing = false,
                        loadingMore = false,
                        error = result.message
                    )
                }
            }
        }
    }

    private companion object {
        const val PAGE_SIZE = 30
    }
}
