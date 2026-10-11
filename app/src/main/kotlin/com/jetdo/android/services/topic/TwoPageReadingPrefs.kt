package com.jetdo.android.services.topic

import android.content.Context
import android.content.SharedPreferences
import com.jetdo.android.common.constants.StorageKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「双页阅读」开关（实验性，**默认关**）。
 *
 * 打开后，折叠屏处于**书本姿态**（竖直折痕 + 半开）时，帖子详情页排成左右两页。
 *
 * 为什么默认关：这个形态只在双折设备上存在 —— 三折叠不产生 `HALF_OPENED`
 * （官方走尺寸阶梯而非姿态阶梯），手机与平板也进不来。对绝大多数用户它是一段
 * 永远不触发的分支，不该默认压在详情页的日常路径上。想做的人自己去实验性功能里打开。
 */
object TwoPageReadingPrefs {
    private const val PREF_STORE_NAME = "app_settings"

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREF_STORE_NAME, Context.MODE_PRIVATE)
        _enabled.value = prefs.getBoolean(StorageKeys.PREFS_TWO_PAGE_READING, false)
    }

    fun isEnabled(): Boolean = _enabled.value

    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        if (::prefs.isInitialized) {
            prefs.edit().putBoolean(StorageKeys.PREFS_TWO_PAGE_READING, enabled).apply()
        }
    }
}
