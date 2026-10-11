package com.jetdo.android.ui.components

import android.content.Context
import androidx.compose.ui.graphics.Color
import com.jetdo.android.common.constants.AppConstants
import com.jetdo.android.data.models.TopicCategory
import com.jetdo.android.core.designsystem.FaIconSet

/**
 * 徽章图标 / 颜色解析，逐函数对齐鸿蒙版 views/components/TopicBadgeUtils.ets。
 */
object TopicBadgeUtils {

    private val FALLBACK_COLOR_HEX = "6B7280"

    /**
     * 分类图标：自身取不到时回退父分类。
     * 返回可用的 FA 图标 id，没有则返回 null。
     */
    fun categoryIcon(
        context: Context,
        category: TopicCategory,
        parentCategory: TopicCategory?
    ): String? {
        categoryIconByName(context, normalizeIconName(category.icon))?.let { return it }
        return parentCategory?.let { categoryIconByName(context, normalizeIconName(it.icon)) }
    }

    /**
     * 分类 logo：自身没有时回退父分类。返回空串表示都没有。
     */
    fun categoryLogo(category: TopicCategory, parentCategory: TopicCategory?): String {
        category.uploadedLogo?.takeIf { it.isNotEmpty() }?.let { return it }
        parentCategory?.uploadedLogo?.takeIf { it.isNotEmpty() }?.let { return it }
        return ""
    }

    /** 分类色，已补 # 前缀。 */
    fun categoryColor(category: TopicCategory): Color =
        parseHexColor(normalizeHex(category.color, FALLBACK_COLOR_HEX))

    /** 标签色；查不到样式时用 #6B7280。 */
    fun tagColor(tagName: String): Color {
        val style = TagIconRegistry.getTagIconStyle(tagName)
        return style?.let { TagIconRegistry.parseColor(it.color) }
            ?: parseHexColor(FALLBACK_COLOR_HEX)
    }

    /** 标签图标 id；查不到返回 null。 */
    fun tagIconId(tagName: String): String? =
        TagIconRegistry.getTagIconStyle(tagName)?.iconId?.takeIf { it.isNotEmpty() }

    /** 相对路径的徽章资源补全成绝对 URL。 */
    fun resolveBadgeAssetUrl(rawUrl: String): String = when {
        rawUrl.startsWith("http://") || rawUrl.startsWith("https://") -> rawUrl
        rawUrl.startsWith("//") -> "https:$rawUrl"
        rawUrl.startsWith("/") -> "${AppConstants.BASE_URL}$rawUrl"
        else -> "${AppConstants.BASE_URL}/$rawUrl"
    }

    /**
     * 先直查，再走同义词映射。同义词表与鸿蒙 getCategoryIconByName 的 switch 逐条对应。
     */
    private fun categoryIconByName(context: Context, name: String): String? {
        if (name.isEmpty()) return null
        if (FaIconSet.has(context, name)) return name

        val mapped = when (name) {
            "file-code" -> "code"
            "award", "medal", "trophy" -> "trophy"
            "book-open-reader", "book-open", "book" -> "book-open-reader"
            "seedling", "leaf" -> "seedling"
            "file-lines", "doc", "newspaper" -> "file-lines"
            "clone", "database", "server" -> "hard-drive"
            "users", "user-group", "user" -> "users"
            "megaphone" -> "bullhorn"
            "comment" -> "comments"
            else -> ""
        }
        return mapped.takeIf { it.isNotEmpty() && FaIconSet.has(context, it) }
    }

    /**
     * 去掉 fa/fas/far/fab/fa-solid 之类的前缀词，取最后一个有效 token。
     * Discourse 的 icon 字段可能是 "fa-solid fa-book" 这种多段写法。
     */
    private fun normalizeIconName(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var name = ""
        for (token in raw.trim().lowercase().split(Regex("\\s+"))) {
            if (token in PREFIX_TOKENS) continue
            name = if (token.startsWith("fa-")) token.substring(3) else token
        }
        return name
    }

    private val PREFIX_TOKENS = setOf("fa-solid", "fa-regular", "fa-brands", "fas", "far", "fab", "fa")

    private fun normalizeHex(value: String?, fallback: String): String {
        val normalized = value?.replace("#", "")?.trim().orEmpty()
        return normalized.ifEmpty { fallback }
    }

    private fun parseHexColor(hex: String): Color = runCatching {
        Color(android.graphics.Color.parseColor("#$hex"))
    }.getOrElse { Color(0xFF6B7280) }
}
