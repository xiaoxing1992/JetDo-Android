package com.arkdo.android.services.session

import android.content.Context
import timber.log.Timber

// ==========================================================================
// 与 HarmonyOS 版 services/session/SessionEventBus.ets 逐字段对齐。
// 会话变化总线：登录/登出/换号/DoH 变更后广播，RootTabPage 订阅后重查会话、重载宿主。
// 带单调版本号去重（handledSessionVersion）。
// ==========================================================================

const val SESSION_LOG_TAG = "[ArkDO][Session]"

typealias SessionChangedListener = (version: Long) -> Unit

object SessionEventBus {
    private val listeners = mutableMapOf<Long, SessionChangedListener>()
    private var nextListenerId = 0L
    private var currentVersion = 0L

    fun subscribe(listener: SessionChangedListener): () -> Unit {
        val id = nextListenerId++
        listeners[id] = listener
        return { listeners.remove(id) }
    }

    fun notifySessionChanged(): Long {
        currentVersion++
        val version = currentVersion
        val snapshot = listeners.values.toList()
        Timber.i("%s session changed version=%d listenerCount=%d", SESSION_LOG_TAG, version, snapshot.size)
        snapshot.forEach { runCatching { it(version) } }
        return version
    }

    fun getCurrentVersion(): Long = currentVersion
}