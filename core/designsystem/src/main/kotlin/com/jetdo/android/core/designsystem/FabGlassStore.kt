package com.jetdo.android.core.designsystem

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 磨砂半径(dp)。默认 28 是改造前的硬编码值，不动设置的人升级后观感不变。
 * 下限取 4 而不是 0：0 等于没有磨砂，那是关掉开关该做的事。
 * 上限 60 之后再加已经看不出差别。
 */
const val FAB_GLASS_RADIUS_MIN = 4
const val FAB_GLASS_RADIUS_MAX = 60
const val FAB_GLASS_RADIUS_DEFAULT = 28

/** 非数字回默认，其余收到区间内再取整。读盘、滑块、外部调用都走这里。 */
fun clampFabGlassRadius(value: Float): Int {
    if (!value.isFinite()) return FAB_GLASS_RADIUS_DEFAULT
    return value.roundToInt().coerceIn(FAB_GLASS_RADIUS_MIN, FAB_GLASS_RADIUS_MAX)
}

/**
 * 悬浮按钮玻璃效果。内存镜像给设置页、首页和详情页同时看，拖动时只改内存，松手再落盘。
 * 和主题、列表样式共用 app_settings，键名与鸿蒙一致。默认关。
 */
object FabGlassStore {
    private const val PREF_STORE_NAME = "app_settings"
    private const val KEY_ENABLED = "fab_glass_enabled"
    private const val KEY_RADIUS = "fab_glass_radius"

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _radius = MutableStateFlow(FAB_GLASS_RADIUS_DEFAULT)
    val radius: StateFlow<Int> = _radius.asStateFlow()

    private lateinit var prefs: SharedPreferences

    fun isEnabled(): Boolean = _enabled.value

    fun radius(): Int = _radius.value

    /** 首帧之前读盘。读失败回默认，外观设置不该挡住启动。 */
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREF_STORE_NAME, Context.MODE_PRIVATE)
        _enabled.value = prefs.getBoolean(KEY_ENABLED, false)
        _radius.value = clampFabGlassRadius(prefs.getFloat(KEY_RADIUS, FAB_GLASS_RADIUS_DEFAULT.toFloat()))
    }

    /** 开关翻转立即落盘：它不是连续拖动，没有必要攒着。 */
    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        if (::prefs.isInitialized) {
            prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        }
    }

    /** 拖动滑块：只改内存，两处按钮即时跟手。 */
    fun setRadius(value: Float): Int {
        val clamped = clampFabGlassRadius(value)
        _radius.value = clamped
        return clamped
    }

    /** 滑块松手时调一次。 */
    fun flushRadius() {
        if (!::prefs.isInitialized) return
        prefs.edit().putFloat(KEY_RADIUS, radius().toFloat()).apply()
    }
}
