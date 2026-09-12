package com.arkdo.android.ui.notification

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkdo.android.data.models.NotificationItem
import com.arkdo.android.services.notification.NotificationApi
import com.arkdo.android.services.notification.NotificationEventBus
import com.arkdo.android.services.notification.NotificationSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * 通知中心 ViewModel(对齐鸿蒙 NotificationPage 的数据流)。
 *
 * 初始/刷新都走 `recent=true`(顺带把 seen 值推进去,清掉铃铛红点);
 * 触底加载更多用 offset = 已加载条数(recent=false,不动 seen)。
 * 未读行点开会乐观标记已读并同步 NotificationEventBus,让铃铛角标即时减一。
 */
@HiltViewModel
class NotificationViewModel @Inject constructor(
    private val notificationApi: NotificationApi
) : ViewModel() {

    data class UiState(
        val items: List<NotificationItem> = emptyList(),
        val initialLoading: Boolean = false,
        val refreshing: Boolean = false,
        val loadingMore: Boolean = false,
        val hasMore: Boolean = true,
        val error: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** 与列表 loading 互斥的全局闸(同一时刻只允许一个请求在途)。 */
    private var loading = false

    /** 冷启动/回到页面时的首次加载(仅当列表为空)。 */
    fun loadInitial() {
        if (loading || _uiState.value.items.isNotEmpty()) return
        doLoad(reset = true)
    }

    /** 下拉刷新:重拉第 0 页(带 recent,推进 seen 清红点)。 */
    fun refresh() {
        if (loading) return
        doLoad(reset = true)
    }

    /** 触底加载更多:offset = 当前已加载条数。 */
    fun loadMore() {
        if (loading || !_uiState.value.hasMore || _uiState.value.items.isEmpty()) return
        doLoad(reset = false)
    }

    /**
     * 未读行点开:乐观标记已读(本地立即翻灰 + 角标减一),再异步落服务端。
     * 失败时静默回滚,待下次刷新时由服务端数据纠正。
     */
    fun markItemRead(item: NotificationItem) {
        if (item.read) return
        _uiState.update { state ->
            state.copy(
                items = state.items.map { if (it.id == item.id) it.copy(read = true) else it }
            )
        }
        NotificationEventBus.decrementUnread(item.type == 6 || item.type == 7)
        viewModelScope.launch {
            val ok = notificationApi.markRead(item.id)
            if (!ok) {
                // 回滚:恢复未读态,角标原值靠后续轮询纠正。
                _uiState.update { state ->
                    state.copy(
                        items = state.items.map { if (it.id == item.id) it.copy(read = false) else it }
                    )
                }
                Timber.i("[ArkDO][NotificationVM] markRead failed id=%d", item.id)
            }
        }
    }

    private fun doLoad(reset: Boolean) {
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
            val result = notificationApi.load(limit = NOTIFICATION_PAGE_SIZE, offset = offset, recent = reset)
            loading = false
            _uiState.update {
                if (result.success && result.data != null) {
                    val page = result.data
                    val items = if (reset) page else it.items + page
                    // 服务端数据是最可信的未读数来源:以结果里的未读条数为准,刷新本地计数。
                    if (reset) {
                        val unread = page.count { item -> !item.read }
                        NotificationEventBus.publish(
                            NotificationSnapshot(
                                unread = unread,
                                highPriority = NotificationEventBus.current().highPriority
                            )
                        )
                    }
                    it.copy(
                        items = items,
                        hasMore = page.size >= NOTIFICATION_PAGE_SIZE,
                        initialLoading = false,
                        refreshing = false,
                        loadingMore = false,
                        error = null
                    )
                } else {
                    it.copy(
                        initialLoading = false,
                        refreshing = false,
                        loadingMore = false,
                        error = if (reset) (result.error?.message ?: "加载失败,请重试") else null
                    )
                }
            }
        }
    }

    /** 全部标记已读:本地全翻灰 + bus 清零(私信计数一并清,对齐鸿蒙)。 */
    fun markAllRead() {
        val hasUnread = _uiState.value.items.any { !it.read }
        if (!hasUnread) return
        _uiState.update { state ->
            state.copy(items = state.items.map { if (it.read) it else it.copy(read = true) })
        }
        NotificationEventBus.publish(NotificationSnapshot(0, 0))
        viewModelScope.launch {
            val ok = notificationApi.markAllRead()
            if (!ok) {
                // 失败回滚:角标原值靠下次轮询纠正。
                _uiState.update { state ->
                    state.copy(items = state.items.map { if (it.read) it.copy(read = false) else it })
                }
                Timber.i("[ArkDO][NotificationVM] markAllRead failed")
            }
        }
    }

    companion object {
        /** 每页条数,对齐鸿蒙 NOTIFICATION_PAGE_SIZE。 */
        private const val NOTIFICATION_PAGE_SIZE = 30
    }
}