package com.arkdo.android.ui.drafts

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.arkdo.android.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkdo.android.data.models.Draft
import com.arkdo.android.services.draft.DraftCountStore
import com.arkdo.android.services.draft.DraftService
import com.arkdo.android.services.session.CurrentUserHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DraftsViewModel @Inject constructor(
    private val draftService: DraftService,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(DraftsUiState())
    val uiState: StateFlow<DraftsUiState> = _uiState.asStateFlow()

    private var offset = 0
    private var shownOnce = false
    private var refreshJob: Job? = null

    fun loadInitial(silent: Boolean = false) {
        offset = 0
        if (!silent) {
            _uiState.update { it.copy(initialLoading = it.drafts.isEmpty(), refreshing = it.drafts.isNotEmpty(), error = null) }
        }
        viewModelScope.launch {
            val result = draftService.getPage(0)
            if (result.success && result.data != null) {
                val page = result.data
                offset = page.drafts.size
                _uiState.update {
                    it.copy(
                        drafts = page.drafts,
                        hasMore = page.hasMore,
                        initialLoading = false,
                        refreshing = false,
                        error = null
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        initialLoading = false,
                        refreshing = false,
                        error = if (silent && it.drafts.isNotEmpty()) it.error else result.error?.message ?: appContext.getString(R.string.drafts_load_failed)
                    )
                }
            }
        }
    }

    /** 从续写或帖子详情回来时静默重拉。首次进入跳过，重复触发只留最后一次。 */
    fun onShown() {
        if (!shownOnce) {
            shownOnce = true
            return
        }
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            delay(DRAFT_REFRESH_DELAY_MS)
            loadInitial(silent = true)
        }
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.loadingMore || !state.hasMore || state.initialLoading) return
        _uiState.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            val result = draftService.getPage(offset)
            if (result.success && result.data != null) {
                val page = result.data
                offset += page.drafts.size
                _uiState.update {
                    it.copy(
                        drafts = it.drafts + page.drafts,
                        hasMore = page.hasMore,
                        loadingMore = false
                    )
                }
            } else {
                _uiState.update { it.copy(loadingMore = false) }
            }
        }
    }

    fun delete(draft: Draft) {
        if (_uiState.value.deletingKey.isNotEmpty()) return
        _uiState.update { it.copy(deletingKey = draft.draftKey) }
        viewModelScope.launch {
            val result = draftService.deleteDraft(draft.draftKey, draft.sequence)
            if (result.success) {
                // 删除已被服务端确认，直接更新共享角标数。刻意不重查会话：/session/current.json
                // 可能短暂返回删除前的缓存值，重查会把刚减掉的数写回去（对齐鸿蒙 handleDraftDeleted）。
                DraftCountStore.decrement(CurrentUserHolder.currentUser.value?.draftCount)
            }
            _uiState.update { state ->
                if (result.success) {
                    val next = state.drafts.filterNot { it.draftKey == draft.draftKey && it.sequence == draft.sequence }
                    if (next.size != state.drafts.size) {
                        offset = (offset - 1).coerceAtLeast(0)
                    }
                    state.copy(drafts = next, deletingKey = "", notice = appContext.getString(R.string.drafts_deleted))
                } else {
                    state.copy(deletingKey = "", notice = result.error?.message ?: appContext.getString(R.string.common_delete_failed))
                }
            }
        }
    }
    fun consumeNotice() {
        _uiState.update { it.copy(notice = null) }
    }
}

data class DraftsUiState(
    val drafts: List<Draft> = emptyList(),
    val initialLoading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val deletingKey: String = "",
    val error: String? = null,
    val notice: String? = null
)

private const val DRAFT_REFRESH_DELAY_MS = 320L
