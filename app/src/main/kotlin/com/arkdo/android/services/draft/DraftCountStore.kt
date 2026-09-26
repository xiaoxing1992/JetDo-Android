package com.arkdo.android.services.draft

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

// ==========================================================================
// 「我的草稿」角标数(对齐鸿蒙 AppStorage[PROFILE_DRAFT_COUNT_KEY])。
//
// 鸿蒙用一个内存镜像 + 三处写入维护这个数:
//   1. 会话复查成功 → 用 currentUser.draftCount 覆盖(RootTabPage.ets:1836)
//   2. 登出本地清态 → 归零(RootTabPage.ets:1798)
//   3. 删除草稿确认成功 → 本地 -1(RootTabPage.ets:1779)，且**不重查会话**:
//      服务端 /session/current.json 会短暂返回删除前的缓存值，重查反而把刚减掉的数写回去。
//
// Android 侧此前直接读「登录那一刻」的 User.draftCount 快照，没有任何更新通路，
// 于是删了草稿角标不动、登出后还挂着上一个账号的数。这里把真源独立出来。
//
// null 表示「还没被权威结果写过」，读取方应回退到 User.draftCount
// (同鸿蒙 storedCount === undefined ? currentUser.draftCount : storedCount)。
// ==========================================================================

object DraftCountStore {

    private const val LOG_TAG = "[ArkDO][DraftCount]"

    private val _count = MutableStateFlow<Int?>(null)
    val count: StateFlow<Int?> = _count.asStateFlow()

    /** 会话复查拿到权威值时覆盖。传 null(未登录)等价归零。 */
    fun set(value: Int?) {
        _count.value = (value ?: 0).coerceAtLeast(0)
    }

    /**
     * 删除草稿已确认成功时本地 -1。刻意不重查会话(见文件头注释第 3 条)。
     *
     * [fallback] 是「store 还没被会话复查写过」时的回退值，对应鸿蒙 handleDraftDeleted 里的
     * `storedCount !== undefined ? storedCount : currentUser.draftCount`。正常路径上会话复查
     * 早就写过，这条分支只有在会话检查因过盾/断网失败、而草稿接口仍可用时才会走到。
     * 调用方传 `CurrentUserHolder.currentUser.value?.draftCount` 即可。
     */
    fun decrement(fallback: Int? = null) {
        val current = _count.value ?: fallback ?: 0
        val next = (current - 1).coerceAtLeast(0)
        _count.value = next
        Timber.i("%s draft count updated %d->%d", LOG_TAG, current, next)
    }

    /** 登出本地清态。 */
    fun reset() {
        _count.value = 0
    }

    /** 仅供测试：把「未被写过」的初始态还原。 */
    internal fun resetForTest() {
        _count.value = null
    }
}
