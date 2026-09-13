package com.arkdo.android.feature.home

import com.arkdo.android.core.model.HomeCategory
import com.arkdo.android.core.model.HomeQuery
import com.arkdo.android.core.model.HomeTopic
import com.arkdo.android.core.model.HomeUser

private const val HOME_AVATAR_SIZE = "96"

internal fun homeUnreadTarget(topic: HomeTopic): Int? =
    topic.unreadPosts.takeIf { it > 0 }?.let { topic.firstUnreadPostNumber() }

internal fun homeHasMetadata(
    topic: HomeTopic,
    category: HomeCategory?,
    relativeTime: String
): Boolean = category != null || topic.tags.isNotEmpty() || relativeTime.isNotEmpty()

/**
 * Resolve the category the same way as the legacy list: prefer the category
 * embedded in the topic, then use the site category index supplied by the host.
 * The selected query is a final fallback while the index is still loading.
 */
internal fun homeCategoryForTopic(
    topic: HomeTopic,
    categories: List<HomeCategory>,
    query: HomeQuery
): HomeCategory? {
    topic.category?.let { return it }
    categories.firstOrNull { it.id == topic.categoryId }?.let { return it }
    val selectedCategoryId = query.categoryId
    if (selectedCategoryId != null && selectedCategoryId == topic.categoryId && !query.categoryName.isNullOrBlank()) {
        return HomeCategory(
            id = selectedCategoryId,
            name = query.categoryName!!,
            slug = query.categorySlug
        )
    }
    return null
}

internal fun homeAvatarCandidates(user: HomeUser?, baseUrl: String): List<String> = buildList {
    if (user == null) return@buildList
    user.animatedAvatar?.takeIf(String::isNotBlank)?.let { add(resolveHomeAsset(it, baseUrl)) }
    user.avatarTemplate?.takeIf(String::isNotBlank)?.let {
        add(resolveHomeAsset(it.replace("{size}", HOME_AVATAR_SIZE), baseUrl))
    }
}

internal fun resolveHomeAsset(value: String, baseUrl: String): String = when {
    value.startsWith("http://") || value.startsWith("https://") -> value
    value.startsWith("//") -> "https:$value"
    value.startsWith("/") -> baseUrl.trimEnd('/') + value
    else -> baseUrl.trimEnd('/') + "/" + value
}
