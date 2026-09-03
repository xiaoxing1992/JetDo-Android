package com.arkdo.android.ui.components

import android.content.Context
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 标签图标注册表 - 1:1 对齐鸿蒙版 TagIconRegistry.ets
 *
 * 从 linuxdo_tag_icons.json 加载标签的图标和颜色配置
 */
object TagIconRegistry {

    @Serializable
    data class TagIconStyle(
        val tagName: String,
        val iconId: String,
        val color: String
    )

    private val tagIconMap = mutableMapOf<String, TagIconStyle>()
    private var tagIconsLoaded = false

    /**
     * 从应用上下文加载标签图标配置
     */
    fun ensureTagIconsLoaded(context: Context): Int {
        if (tagIconsLoaded && tagIconMap.isNotEmpty()) {
            return tagIconMap.size
        }

        return try {
            val inputStream = context.resources.openRawResource(
                context.resources.getIdentifier(
                    "linuxdo_tag_icons",
                    "raw",
                    context.packageName
                )
            )
            val json = BufferedReader(InputStreamReader(inputStream)).use { it.readText() }
            loadTagIconsFromJson(json)
        } catch (e: Exception) {
            tagIconMap.clear()
            tagIconsLoaded = true
            0
        }
    }

    /**
     * 从 JSON 字符串加载标签图标配置
     */
    fun loadTagIconsFromJson(json: String): Int {
        return try {
            val styles = Json.decodeFromString<List<TagIconStyle>>(json)
            tagIconMap.clear()

            styles.forEach { style ->
                val normalizedKey = normalizeTagKey(style.tagName)
                if (normalizedKey.isNotEmpty() && style.iconId.isNotEmpty()) {
                    tagIconMap[normalizedKey] = style
                }
            }

            tagIconsLoaded = true
            tagIconMap.size
        } catch (e: Exception) {
            tagIconMap.clear()
            tagIconsLoaded = true
            0
        }
    }

    /**
     * 获取标签的图标样式配置
     */
    fun getTagIconStyle(tagName: String): TagIconStyle? {
        return tagIconMap[normalizeTagKey(tagName)]
    }

    /**
     * 解析颜色字符串为 Compose Color
     */
    fun parseColor(colorHex: String): Color? {
        return try {
            val cleanHex = colorHex.removePrefix("#")
            val colorInt = android.graphics.Color.parseColor("#$cleanHex")
            Color(colorInt)
        } catch (e: Exception) {
            null
        }
    }

    private fun normalizeTagKey(tagName: String): String {
        return tagName.trim().lowercase()
    }
}
