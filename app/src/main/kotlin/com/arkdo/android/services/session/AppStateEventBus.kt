package com.arkdo.android.services.session

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import timber.log.Timber

// ==========================================================================
// 与 HarmonyOS 版 services/session/AppStateEventBus.ets 逐字段对齐。
// 应用前后台状态广播：MainActivity 在 onStart/onStop 通知，
// 详情页订阅用于暂停/恢复阅读计时，NotificationService 订阅用于停/起轮询表。
// ==========================================================================

const val APP_STATE_LOG_TAG = "[ArkDO][AppState]"

typealias AppForegroundListener = (isForeground: Boolean) -> Unit

object AppStateEventBus {
    private val listeners = mutableMapOf<Long, AppForegroundListener>()
    private var nextListenerId = 0L
    @Volatile
    private var foreground = true

    fun subscribe(listener: AppForegroundListener): () -> Unit {
        val id = nextListenerId++
        listeners[id] = listener
        return { listeners.remove(id) }
    }

    fun notifyForegroundChanged(isForeground: Boolean) {
        foreground = isForeground
        val snapshot = listeners.values.toList()
        Timber.i("%s foreground=%b listenerCount=%d", APP_STATE_LOG_TAG, isForeground, snapshot.size)
        snapshot.forEach { runCatching { it(isForeground) } }
    }

    fun isForeground(): Boolean = foreground
}

/** 挂到 LifecycleOwner（Activity）上，把 onStart/onStop 转成前后台广播。 */
fun AppStateEventBus.observeLifecycle(owner: LifecycleOwner) {
    owner.lifecycle.addObserver(LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> notifyForegroundChanged(true)
            Lifecycle.Event.ON_STOP -> notifyForegroundChanged(false)
            else -> Unit
        }
    })
}