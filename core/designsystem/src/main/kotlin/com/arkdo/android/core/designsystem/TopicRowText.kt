package com.arkdo.android.core.designsystem

import com.arkdo.android.core.model.HomeCategory
import com.arkdo.android.core.model.HomeTopic
import com.arkdo.android.core.model.HomeUser

fun topicUnreadTarget(topic: HomeTopic): Int? =
    topic.unreadPosts.takeIf { it > 0 }?.let { topic.firstUnreadPostNumber() }

fun topicHasMetadata(
    topic: HomeTopic,
    category: HomeCategory?,
    relativeTime: String
): Boolean = category != null || topic.tags.isNotEmpty() || relativeTime.isNotEmpty()

private const val TOPIC_AVATAR_SIZE = "96"

fun topicAvatarCandidates(user: HomeUser?, baseUrl: String): List<String> = buildList {
    if (user == null) return@buildList
    user.animatedAvatar?.takeIf(String::isNotBlank)?.let { add(resolveAsset(it, baseUrl)) }
    user.avatarTemplate?.takeIf(String::isNotBlank)?.let {
        add(resolveAsset(it.replace("{size}", TOPIC_AVATAR_SIZE), baseUrl))
    }
}

fun resolveAsset(value: String, baseUrl: String): String = when {
    value.startsWith("http://") || value.startsWith("https://") -> value
    value.startsWith("//") -> "https:$value"
    value.startsWith("/") -> baseUrl.trimEnd('/') + value
    else -> baseUrl.trimEnd('/') + "/" + value
}

data class SearchTextSegment(val text: String, val hit: Boolean)

/**
 * 按鸿蒙 TopicListItem.segmentsOf 切分标题/摘要。
 * 大小写不敏感，从光标处取最早出现的词；同一位置多个词时保留列表里更靠前的那个。
 */
fun searchTextSegments(text: String, highlights: List<String>): List<SearchTextSegment> {
    if (text.isEmpty()) return emptyList()
    val terms = highlights.mapNotNull { term -> term.takeIf(String::isNotEmpty)?.lowercase() }
    if (terms.isEmpty()) return listOf(SearchTextSegment(text, hit = false))

    val lower = text.lowercase()
    val segments = mutableListOf<SearchTextSegment>()
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
            segments += SearchTextSegment(text.substring(cursor), hit = false)
            break
        }
        if (bestPos > cursor) {
            segments += SearchTextSegment(text.substring(cursor, bestPos), hit = false)
        }
        segments += SearchTextSegment(text.substring(bestPos, bestPos + bestLen), hit = true)
        cursor = bestPos + bestLen
    }
    return segments
}
