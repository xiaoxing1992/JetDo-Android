package com.arkdo.android.feature.home

import android.content.Context
import androidx.compose.ui.graphics.Color
import com.arkdo.android.core.designsystem.FaIconSet
import com.arkdo.android.core.model.HomeCategory
import com.arkdo.android.feature.search.parseTopicHexColor

internal data class HomeTagStyle(val iconId: String, val color: Color)

internal fun homeCategoryIcon(
    context: Context,
    category: HomeCategory,
    parentCategory: HomeCategory?
): String? {
    fun find(value: String?): String? {
        val normalized = normalizeHomeIcon(value)
        if (normalized.isEmpty()) return null
        if (FaIconSet.has(context, normalized)) return normalized
        val mapped = when (normalized) {
            "file-code" -> "code"
            "award", "medal", "trophy" -> "trophy"
            "book-open-reader", "book-open", "book" -> "book-open-reader"
            "seedling", "leaf" -> "seedling"
            "file-lines", "doc", "newspaper" -> "file-lines"
            "clone", "database", "server" -> "hard-drive"
            "users", "user-group", "user" -> "users"
            "megaphone" -> "bullhorn"
            "comment" -> "comments"
            else -> null
        }
        return mapped?.takeIf { FaIconSet.has(context, it) }
    }
    return find(category.icon) ?: find(parentCategory?.icon)
}

internal fun normalizeHomeIcon(value: String?): String {
    if (value.isNullOrBlank()) return ""
    var result = ""
    value.trim().lowercase().split(Regex("\\s+")).forEach { token ->
        if (token !in setOf("fa-solid", "fa-regular", "fa-brands", "fas", "far", "fab", "fa")) {
            result = if (token.startsWith("fa-")) token.substring(3) else token
        }
    }
    return result
}

internal fun loadHomeTagStyles(context: Context): Map<String, HomeTagStyle> = runCatching {
    val resourceId = context.resources.getIdentifier("linuxdo_tag_icons", "raw", context.packageName)
    if (resourceId == 0) return@runCatching emptyMap()
    val json = context.resources.openRawResource(resourceId).bufferedReader().use { it.readText() }
    val array = org.json.JSONArray(json)
    buildMap {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val name = item.optString("tagName").trim().lowercase()
            val icon = item.optString("iconId")
            val color = parseTopicHexColor(item.optString("color")) ?: Color(0xFF6B7280)
            if (name.isNotEmpty() && icon.isNotEmpty()) put(name, HomeTagStyle(icon, color))
        }
    }
}.getOrDefault(emptyMap())
