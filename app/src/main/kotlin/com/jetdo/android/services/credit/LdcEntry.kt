package com.jetdo.android.services.credit

import com.jetdo.android.data.models.LdcUserInfo

/**
 * 个人中心 Credit 入口行的状态机，对齐鸿蒙 ProfilePage.refreshLdcEntry / loadLdcBalance。
 * 纯函数，不碰 WebView：有会话且缓存过期才挂宿主去取数；失败时有旧余额就继续显示，没有才变成「重试」。
 */
enum class LdcEntryPhase { LOADING, READY, ANON, ERROR }

data class LdcEntryState(
    val phase: LdcEntryPhase = LdcEntryPhase.LOADING,
    val info: LdcUserInfo? = null,
    val hostActive: Boolean = false
)

sealed interface LdcEntryEvent {
    data class Refresh(val hasSession: Boolean, val cached: LdcUserInfo?, val cacheFresh: Boolean) : LdcEntryEvent
    data object Reset : LdcEntryEvent
    data class Loaded(val info: LdcUserInfo) : LdcEntryEvent
    data object Unauthorized : LdcEntryEvent
    data object LoadFailed : LdcEntryEvent
}

fun reduceLdcEntry(state: LdcEntryState, event: LdcEntryEvent): LdcEntryState = when (event) {
    is LdcEntryEvent.Refresh -> when {
        !event.hasSession -> LdcEntryState(phase = LdcEntryPhase.ANON)
        event.cacheFresh && event.cached != null -> LdcEntryState(phase = LdcEntryPhase.READY, info = event.cached)
        else -> LdcEntryState(phase = LdcEntryPhase.LOADING, info = event.cached, hostActive = true)
    }
    LdcEntryEvent.Reset -> LdcEntryState()
    is LdcEntryEvent.Loaded -> LdcEntryState(phase = LdcEntryPhase.READY, info = event.info)
    LdcEntryEvent.Unauthorized -> LdcEntryState(phase = LdcEntryPhase.ANON)
    LdcEntryEvent.LoadFailed -> if (state.info != null) {
        state.copy(phase = LdcEntryPhase.READY, hostActive = false)
    } else {
        state.copy(phase = LdcEntryPhase.ERROR, hostActive = false)
    }
}
