package com.arkdo.android.common.theme

import android.content.Context
import android.content.SharedPreferences
import com.arkdo.android.common.constants.StorageKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 主题三态(对齐鸿蒙 ThemeMode):跟随系统 / 浅色 / 深色。
 * 持久化在 app_settings,内存镜像走 StateFlow。JetDoTheme 订阅后整棵界面跟着变,
 * 设置页写入即生效,无需重启。
 */
enum class AppThemeMode(val storage: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun from(storage: String?): AppThemeMode =
            entries.firstOrNull { it.storage == storage } ?: SYSTEM
    }
}

object ThemeModeStore {
    private const val PREF_STORE_NAME = "app_settings"

    private val _mode = MutableStateFlow(AppThemeMode.SYSTEM)
    val mode: StateFlow<AppThemeMode> = _mode.asStateFlow()

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREF_STORE_NAME, Context.MODE_PRIVATE)
        _mode.value = AppThemeMode.from(prefs.getString(StorageKeys.PREFS_THEME_MODE, null))
    }

    fun setMode(mode: AppThemeMode) {
        _mode.value = mode
        if (::prefs.isInitialized) {
            prefs.edit().putString(StorageKeys.PREFS_THEME_MODE, mode.storage).apply()
        }
    }
}