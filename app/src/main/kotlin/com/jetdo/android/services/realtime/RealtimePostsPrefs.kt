package com.jetdo.android.services.realtime

import android.content.Context
import android.content.SharedPreferences
import com.jetdo.android.common.constants.StorageKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「帖子内新回复提醒」开关（对齐鸿蒙 RealtimePostsPrefs）。
 *
 * 默认**关**——属实验性功能，需用户在实验性功能里主动开启；与「首页新话题提醒」
 * （[RealtimeTopicsPrefs]，同为默认关）相互独立。
 *
 * 开启后进入帖子详情会多订一个临时频道 `/topic/<id>`（MessageBus 长轮询本就为通知常驻运行，
 * 多一个频道的开销极低），并顺带启用「正在回复」presence 下行——两者在鸿蒙侧同受这一个开关门控。
 */
object RealtimePostsPrefs {
    private const val PREF_STORE_NAME = "app_settings"

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREF_STORE_NAME, Context.MODE_PRIVATE)
        _enabled.value = prefs.getBoolean(StorageKeys.PREFS_REALTIME_POSTS, false)
    }

    fun isEnabled(): Boolean = _enabled.value

    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        if (::prefs.isInitialized) {
            prefs.edit().putBoolean(StorageKeys.PREFS_REALTIME_POSTS, enabled).apply()
        }
    }
}
