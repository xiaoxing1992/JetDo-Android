package com.jetdo.android.core.designsystem

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 帖子列表样式。内存镜像给设置页、首页和搜索同时看，拖动时只改内存，松手再落盘。
 * 和主题、新话题提醒共用 app_settings，键名与鸿蒙一致。
 */
object TopicListStyleStore {
    private const val PREF_STORE_NAME = "app_settings"
    private const val KEY_TITLE_FONT_SIZE = "topic_list_title_font_size"
    private const val KEY_TITLE_FONT_WEIGHT = "topic_list_title_font_weight"
    private const val KEY_LINE_HEIGHT_RATIO = "topic_list_line_height_ratio"
    private const val KEY_ROW_PADDING = "topic_list_row_padding"
    private const val KEY_AVATAR_SIZE = "topic_list_avatar_size"

    private val _style = MutableStateFlow(TopicListStyle.Default)
    val style: StateFlow<TopicListStyle> = _style.asStateFlow()

    private lateinit var prefs: SharedPreferences

    fun current(): TopicListStyle = _style.value

    /** 首帧之前读盘。读失败或数值越界都回默认，外观设置不该挡住启动。 */
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREF_STORE_NAME, Context.MODE_PRIVATE)
        _style.value = read(prefs)
    }

    fun setTitleFontSize(value: Float): TopicListStyle =
        update(current().copy(titleFontSize = clampTitleFontSize(value)))

    fun setTitleFontWeight(value: Int): TopicListStyle =
        update(current().copy(titleFontWeight = normalizeTitleFontWeight(value)))

    fun setLineHeightRatio(value: Float): TopicListStyle =
        update(current().copy(lineHeightRatio = clampLineHeightRatio(value)))

    fun setRowPadding(value: Float): TopicListStyle =
        update(current().copy(rowPadding = clampRowPadding(value)))

    fun setAvatarSize(value: Float): TopicListStyle =
        update(current().copy(avatarSize = clampAvatarSize(value)))

    fun resetDefaults(): TopicListStyle {
        val style = TopicListStyle.Default
        _style.value = style
        flush()
        return style
    }

    /** 滑块松手、切换字重、恢复默认时各写一次。拖动途中不写。 */
    fun flush() {
        if (!::prefs.isInitialized) return
        val style = current()
        prefs.edit()
            .putInt(KEY_TITLE_FONT_SIZE, style.titleFontSize)
            .putInt(KEY_TITLE_FONT_WEIGHT, style.titleFontWeight)
            .putFloat(KEY_LINE_HEIGHT_RATIO, style.lineHeightRatio)
            .putInt(KEY_ROW_PADDING, style.rowPadding)
            .putInt(KEY_AVATAR_SIZE, style.avatarSize)
            .apply()
    }

    private fun update(style: TopicListStyle): TopicListStyle {
        _style.value = style
        return style
    }

    private fun read(prefs: SharedPreferences): TopicListStyle = TopicListStyle(
        titleFontSize = clampTitleFontSize(
            prefs.getInt(KEY_TITLE_FONT_SIZE, TITLE_FONT_SIZE_DEFAULT).toFloat()
        ),
        titleFontWeight = normalizeTitleFontWeight(
            prefs.getInt(KEY_TITLE_FONT_WEIGHT, TITLE_FONT_WEIGHT_DEFAULT)
        ),
        lineHeightRatio = clampLineHeightRatio(
            prefs.getFloat(KEY_LINE_HEIGHT_RATIO, LINE_HEIGHT_RATIO_DEFAULT)
        ),
        rowPadding = clampRowPadding(prefs.getInt(KEY_ROW_PADDING, ROW_PADDING_DEFAULT).toFloat()),
        avatarSize = clampAvatarSize(prefs.getInt(KEY_AVATAR_SIZE, AVATAR_SIZE_DEFAULT).toFloat())
    )
}
