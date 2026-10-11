package com.jetdo.android.services.challenge

import android.content.Context
import android.content.SharedPreferences
import com.jetdo.android.common.constants.StorageKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「帖子页显示过盾入口」开关（对齐鸿蒙 ChallengeShortcutPrefs，storageKey 同义）。
 * 默认**关**：多数用户从不撞盾，常驻入口只会碍眼；打开后也不是常驻——还要真的探测到
 * timings 上报被盾拦下才显示（见 [ChallengeShortcutEventBus]，两条件在
 * TopicDetailViewModel.challengeBlocked 里 AND 起来）。
 */
object ChallengeShortcutPrefs {
    private const val PREF_STORE_NAME = "app_settings"

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREF_STORE_NAME, Context.MODE_PRIVATE)
        _enabled.value = prefs.getBoolean(StorageKeys.PREFS_CHALLENGE_SHORTCUT, false)
    }

    fun isEnabled(): Boolean = _enabled.value

    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        if (::prefs.isInitialized) {
            prefs.edit().putBoolean(StorageKeys.PREFS_CHALLENGE_SHORTCUT, enabled).apply()
        }
    }
}
