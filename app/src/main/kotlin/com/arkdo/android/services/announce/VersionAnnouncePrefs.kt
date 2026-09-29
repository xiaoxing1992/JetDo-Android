package com.arkdo.android.services.announce

import android.content.Context
import android.content.SharedPreferences
import com.arkdo.android.common.constants.StorageKeys

/**
 * 「本版公告已弹过」标记(对齐鸿蒙 HomePage 的 announce prefs):
 * 记住最近一次弹过「本次更新」的 versionCode;同一版本只弹一次,
 * 「发现新版本」提示不做去重——只要还没升级,每次冷启动都提醒。
 */
object VersionAnnouncePrefs {
    private const val PREF_STORE_NAME = "app_settings"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREF_STORE_NAME, Context.MODE_PRIVATE)
    }

    fun isShown(versionCode: Int): Boolean =
        !this::prefs.isInitialized || prefs.getInt(StorageKeys.PREFS_ANNOUNCE_SHOWN_CODE, 0) == versionCode

    fun markShown(versionCode: Int) {
        if (!this::prefs.isInitialized) return
        prefs.edit().putInt(StorageKeys.PREFS_ANNOUNCE_SHOWN_CODE, versionCode).apply()
    }
}
