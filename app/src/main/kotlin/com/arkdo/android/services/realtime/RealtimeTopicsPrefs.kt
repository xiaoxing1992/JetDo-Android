package com.arkdo.android.services.realtime

import android.content.Context
import android.content.SharedPreferences
import com.arkdo.android.common.constants.StorageKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「首页新话题提醒」。默认开，存在 app_settings。
 * 内存镜像给设置页和首页同时看，关掉时首页丢掉已经攒下的提示。
 */
object RealtimeTopicsPrefs {
    private const val PREF_STORE_NAME = "app_settings"

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREF_STORE_NAME, Context.MODE_PRIVATE)
        _enabled.value = prefs.getBoolean(StorageKeys.PREFS_REALTIME_TOPICS, true)
    }

    fun isEnabled(): Boolean = _enabled.value

    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        if (::prefs.isInitialized) {
            prefs.edit().putBoolean(StorageKeys.PREFS_REALTIME_TOPICS, enabled).apply()
        }
    }
}
