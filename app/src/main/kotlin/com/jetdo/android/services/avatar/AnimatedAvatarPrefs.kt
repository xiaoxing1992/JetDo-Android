package com.jetdo.android.services.avatar

import android.content.Context
import android.content.SharedPreferences
import com.jetdo.android.common.constants.StorageKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「动态头像」开关（**默认开**）。控制头像与 flair 角标是否播放动图。
 *
 * 对齐鸿蒙 services/avatar/AnimatedAvatarPrefs.ets：
 * - 开（默认）：头像候选优先动图（animated_avatar），CDN 上的动图内容交给 Coil 自动播放；
 * - 关：头像一律取首帧渲染成静态图 —— 跳过动图候选 + 请求强制 BitmapFactory 解码
 *   （BitmapFactory 对 GIF/WebP 只解首帧），flair 角标同理。见
 *   core/designsystem 的 LocalAnimatedAvatarEnabled / rememberAvatarImageModel。
 *
 * 代价是列表里多个头像同时播动图会让渲染树每帧变脏；若遇掉帧，可在实验性功能里
 * 关掉作对照（这个开关也是排查掉帧的对照工具）。
 *
 * 持久化复用既有死键 StorageKeys.PREFS_ANIMATED_AVATAR（鸿蒙同名字段），不另起名。
 */
object AnimatedAvatarPrefs {
    private const val PREF_STORE_NAME = "app_settings"

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREF_STORE_NAME, Context.MODE_PRIVATE)
        // 默认开：动图头像是更贴近网页端的观感。只有用户显式关过（存 false）才为关。
        _enabled.value = prefs.getBoolean(StorageKeys.PREFS_ANIMATED_AVATAR, true)
    }

    fun isEnabled(): Boolean = if (::prefs.isInitialized) _enabled.value else true

    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        if (::prefs.isInitialized) {
            prefs.edit().putBoolean(StorageKeys.PREFS_ANIMATED_AVATAR, enabled).apply()
        }
    }
}
