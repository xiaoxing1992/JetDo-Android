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

internal const val INCOMING_INSERT_MS: Int = 360
internal const val INCOMING_HIGHLIGHT_MS: Long = 1500
internal const val INCOMING_HIGHLIGHT_FADE_MS: Long = 600
internal const val INCOMING_INSERT_ROW_ESTIMATE_DP: Int = 96

/** 旧行在新布局里先上移整块高度，再滑回 0。新行距离传 0。 */
internal fun incomingInsertOffset(progress: Float, distance: Float): Float {
    val clamped = progress.coerceIn(0f, 1f)
    val safeDistance = distance.coerceAtLeast(0f)
    if (clamped >= 1f || safeDistance == 0f) return 0f
    return -safeDistance * (1f - clamped)
}

/** 只有新插入的行跟着进度淡入，旧行保持不透明。 */
internal fun incomingInsertAlpha(progress: Float, fadeIn: Boolean): Float =
    if (fadeIn) progress.coerceIn(0f, 1f) else 1f

/** 高亮先稳住，最后 600ms 再淡出。剩余时间不够时立刻开始淡出。 */
internal fun incomingHighlightHoldMs(remainingMs: Long): Long =
    (remainingMs - INCOMING_HIGHLIGHT_FADE_MS).coerceAtLeast(0L)

internal data class HomeTextSegment(val text: String, val hit: Boolean)

/**
 * 按鸿蒙 TopicListItem.segmentsOf 切分标题/摘要。
 * 大小写不敏感，从光标处取最早出现的词；同一位置多个词时保留列表里更靠前的那个。
 */
internal fun homeSearchSegments(text: String, highlights: List<String>): List<HomeTextSegment> {
    if (text.isEmpty()) return emptyList()
    val terms = highlights.mapNotNull { term -> term.takeIf(String::isNotEmpty)?.lowercase() }
    if (terms.isEmpty()) return listOf(HomeTextSegment(text, hit = false))

    val lower = text.lowercase()
    val segments = mutableListOf<HomeTextSegment>()
    var cursor = 0
    while (cursor < text.length) {
        var bestPos = -1
        var bestLen = 0
        for (term in terms) {
            val pos = lower.indexOf(term, cursor)
            if (pos >= 0 && (bestPos < 0 || pos < bestPos)) {
                bestPos = pos
                bestLen = term.length
            }
        }
        if (bestPos < 0) {
            segments += HomeTextSegment(text.substring(cursor), hit = false)
            break
        }
        if (bestPos > cursor) {
            segments += HomeTextSegment(text.substring(cursor, bestPos), hit = false)
        }
        segments += HomeTextSegment(text.substring(bestPos, bestPos + bestLen), hit = true)
        cursor = bestPos + bestLen
    }
    return segments
}
