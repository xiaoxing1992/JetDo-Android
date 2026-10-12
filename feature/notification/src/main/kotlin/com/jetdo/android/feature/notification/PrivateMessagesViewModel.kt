package com.jetdo.android.feature.notification

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jetdo.android.core.data.pm.PrivateMessageRepository
import com.jetdo.android.core.model.HomePageResult
import com.jetdo.android.core.model.HomeTopic
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class PrivateMessagesUiState(
    val items: List<HomeTopic> = emptyList(),
    val initialLoading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val error: String? = null
)

/**
 * 私信列表（收件箱）的装载。
 *
 * 只做收件箱：已发送/归档属低频入口，官方 iOS 也是「收件箱 ▾」下拉而非平铺，
 * 首版先不做，[PrivateMessageRepository] 留了那两个方法备用。
 *
 * 与 NotificationViewModel 的差别：私信路径**带用户名**，所以这里要持有 username。
 * username 由调用方（MessagesScreen）在登录态变化时通过 [setUsername] 传入 ——
 * 不在这里直接读 CurrentUserHolder，是为了让本 ViewModel 保持可单测（无静态依赖）。
 */
@HiltViewModel
class PrivateMessagesViewModel @Inject constructor(
    private val repository: PrivateMessageRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(PrivateMessagesUiState())
    internal val uiState: StateFlow<PrivateMessagesUiState> = _uiState.asStateFlow()

    private var loading = false
    private var username: String? = null

    /**
     * 登录态变化时调用。用户名变了（换号/首次登录）就丢弃旧数据重拉，
     * 否则会出现「上一个账号的私信列表」——这比空列表更糟。
     */
    fun setUsername(value: String?) {
        val next = value?.takeIf { it.isNotEmpty() }
        if (next == username) return
        username = next
        _uiState.value = PrivateMessagesUiState()
        if (next != null) load(reset = true)
    }

    /** 进段时兜底拉一次；已有数据则不重复拉（与通知页 loadInitial 同规则）。 */
    fun loadInitial() {
        if (loading || _uiState.value.items.isNotEmpty()) return
        if (username == null) return
        load(reset = true)
    }

    fun refresh() {
        if (!loading && username != null) load(reset = true)
    }

    fun loadMore() {
        if (!loading && username != null && _uiState.value.hasMore && _uiState.value.items.isNotEmpty()) {
            load(reset = false)
        }
    }

    private fun load(reset: Boolean) {
        val user = username ?: return
        loading = true
        val isFirst = reset && _uiState.value.items.isEmpty()
        val nextPage = if (reset) 0 else _uiState.value.items.size / PAGE_SIZE
        _uiState.update {
            it.copy(
                initialLoading = isFirst,
                refreshing = reset && !isFirst,
                loadingMore = !reset,
                error = null
            )
        }
        viewModelScope.launch {
            val result = repository.inbox(user, nextPage)
            loading = false
            when (result) {
                is HomePageResult.Success -> {
                    val page = result.page.items
                    _uiState.update {
                        it.copy(
                            items = if (reset) page else it.items + page,
                            hasMore = result.page.hasMore,
                            initialLoading = false,
                            refreshing = false,
                            loadingMore = false,
                            error = null
                        )
                    }
                }
                is HomePageResult.Failure -> _uiState.update {
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
