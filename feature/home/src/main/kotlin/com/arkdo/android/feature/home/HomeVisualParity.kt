package com.arkdo.android.feature.home

import com.arkdo.android.core.model.HomeCategory
import com.arkdo.android.core.model.HomeQuery
import com.arkdo.android.core.model.HomeTopic
import com.arkdo.android.core.model.HomeUser

internal fun homeUnreadTarget(topic: HomeTopic): Int? =
    com.arkdo.android.core.designsystem.topicUnreadTarget(topic)

internal fun homeHasMetadata(
    topic: HomeTopic,
    category: HomeCategory?,
    relativeTime: String
): Boolean = com.arkdo.android.core.designsystem.topicHasMetadata(topic, category, relativeTime)

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

internal fun homeAvatarCandidates(user: HomeUser?, baseUrl: String): List<String> =
    com.arkdo.android.core.designsystem.topicAvatarCandidates(user, baseUrl)

internal fun resolveHomeAsset(value: String, baseUrl: String): String =
    com.arkdo.android.core.designsystem.resolveAsset(value, baseUrl)

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

internal const val INCOMING_DIGIT_MS: Int = 260

internal enum class IncomingDigitKind { STABLE, ROLL, ENTER, EXIT }

internal data class IncomingDigitColumn(
    val previous: String,
    val current: String,
    val kind: IncomingDigitKind
)

/**
 * 提示条数字按位对齐。个位在右，短的一侧前导位是空串：
 * 空的旧位是进位，空的新位是退位，两边都有且不同才滚动。
 */
internal fun incomingDigitColumns(previousCount: Int, currentCount: Int): List<IncomingDigitColumn> {
    val previous = previousCount.coerceAtLeast(0).toString()
    val current = currentCount.coerceAtLeast(0).toString()
    val width = maxOf(previous.length, current.length)
    return List(width) { index ->
        val previousDigit = digitAt(previous, index, width)
        val currentDigit = digitAt(current, index, width)
        val kind = when {
            previousDigit.isEmpty() -> IncomingDigitKind.ENTER
            currentDigit.isEmpty() -> IncomingDigitKind.EXIT
            previousDigit != currentDigit -> IncomingDigitKind.ROLL
            else -> IncomingDigitKind.STABLE
        }
        IncomingDigitColumn(previousDigit, currentDigit, kind)
    }
}

private fun digitAt(value: String, index: Int, width: Int): String {
    val offset = index - (width - value.length)
    return if (offset < 0) "" else value[offset].toString()
}

/** enter 从 0 展开到满宽，exit 从满宽合到 0，其余保持满宽。 */
internal fun incomingDigitColumnWidth(kind: IncomingDigitKind, progress: Float, fullWidth: Float): Float {
    val clamped = progress.coerceIn(0f, 1f)
    return when (kind) {
        IncomingDigitKind.ENTER -> fullWidth * clamped
        IncomingDigitKind.EXIT -> fullWidth * (1f - clamped)
        else -> fullWidth
    }
}

internal data class HomeTextSegment(val text: String, val hit: Boolean)

/**
 * 按鸿蒙 TopicListItem.segmentsOf 切分标题/摘要。
 * 大小写不敏感，从光标处取最早出现的词；同一位置多个词时保留列表里更靠前的那个。
 * 切分本身在搜索模块，首页测试仍走这个名字。
 */
internal fun homeSearchSegments(text: String, highlights: List<String>): List<HomeTextSegment> =
    com.arkdo.android.core.designsystem.searchTextSegments(text, highlights)
        .map { HomeTextSegment(it.text, it.hit) }
