package com.arkdo.android.services.topic

import android.content.Context
import android.content.SharedPreferences
import com.arkdo.android.common.constants.StorageKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「抽奖帖提醒」开关(对齐鸿蒙 LotteryTipPrefs)。默认开:提醒挡下的是「永久禁言」这种
 * 不可逆后果,默认关等于没做;读盘失败也回落到开——宁可多提示一次,也不要因存储异常静默。
 *
 * 卡片不记「看过了」——每进一个抽奖帖都展开显示,想彻底不看的人走这个开关,
 * 它是唯一的持久化逃生口(卡片上的收起只在本次浏览有效)。
 */
object LotteryTipPrefs {
    private const val PREF_STORE_NAME = "app_settings"

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREF_STORE_NAME, Context.MODE_PRIVATE)
        _enabled.value = prefs.getBoolean(StorageKeys.PREFS_LOTTERY_TIP, true)
    }

    fun isEnabled(): Boolean = _enabled.value

    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        prefs.edit().putBoolean(StorageKeys.PREFS_LOTTERY_TIP, enabled).apply()
    }
}
