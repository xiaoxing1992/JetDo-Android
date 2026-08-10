package com.arkdo.android.services.challenge

import timber.log.Timber

// ==========================================================================
// 与 HarmonyOS 版 services/challenge/ChallengeShortcutEventBus.ets 逐字段对齐。
// 「阅读时是否正被盾拦着」的信号通道：timings 上报（notifyChallenge:false 的后台请求）
// 撞盾时置真，供详情页右上角亮快捷过盾入口。不广播重复值。
// ==========================================================================

typealias ChallengeShortcutListener = (blocked: Boolean) -> Unit

object ChallengeShortcutEventBus {
    private var blocked = false
    private val listeners = mutableMapOf<Long, ChallengeShortcutListener>()
    private var nextListenerId = 0L

    fun isBlocked(): Boolean = blocked

    /** 进入新帖子详情时清运行态，不广播。 */
    fun reset() {
        blocked = false
    }

    fun subscribe(listener: ChallengeShortcutListener): () -> Unit {
        val id = nextListenerId++
        listeners[id] = listener
        return { listeners.remove(id) }
    }

    /** timings 撞盾（ReadingTracker）。 */
    fun notifyBlocked() {
        if (blocked) return
        blocked = true
        Timber.i("[ArkDO][Challenge] shortcut blocked=true")
        listeners.values.toList().forEach { l ->
            runCatching { l(true) }.onFailure {
                Timber.e(it, "[ArkDO][Challenge] shortcut listener threw")
            }
        }
    }

    /** 过盾成功/后续上报成功。 */
    fun notifyCleared() {
        if (!blocked) return
        blocked = false
        Timber.i("[ArkDO][Challenge] shortcut blocked=false")
        listeners.values.toList().forEach { l ->
            runCatching { l(false) }.onFailure {
                Timber.e(it, "[ArkDO][Challenge] shortcut listener threw")
            }
        }
    }
}
