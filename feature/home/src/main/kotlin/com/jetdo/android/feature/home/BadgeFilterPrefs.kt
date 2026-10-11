package com.jetdo.android.feature.home

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「点分类/标签快速筛选」开关（对齐鸿蒙 services/home/BadgeFilterPrefs.ets）。
 *
 * 默认**关**——开着时帖子行上的徽章会抢走点击，而整行点击是「打开帖子」这个最高频的动作：
 * 误触代价不对称，所以让用户在实验性功能里显式打开。
 *
 * 存储：与 app 侧其余开关同用 app_settings 这个 SharedPreferences 文件（key `badgeFilterEnabled`，
 * 与鸿蒙 AppStorage key 一致）。feature 模块不依赖 app，故 key 常量落在本类而非 app 的 StorageKeys。
 */
object BadgeFilterPrefs {
    private const val PREF_STORE_NAME = "app_settings"
    private const val PREF_KEY = "badgeFilterEnabled"

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREF_STORE_NAME, Context.MODE_PRIVATE)
        _enabled.value = prefs.getBoolean(PREF_KEY, false)
    }

    fun isEnabled(): Boolean = _enabled.value

    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        if (::prefs.isInitialized) {
            prefs.edit().putBoolean(PREF_KEY, enabled).apply()
        }
    }
}
