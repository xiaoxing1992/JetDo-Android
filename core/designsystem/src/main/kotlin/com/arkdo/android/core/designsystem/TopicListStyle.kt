package com.arkdo.android.core.designsystem

import androidx.compose.ui.text.font.FontWeight
import kotlin.math.roundToInt

/**
 * 帖子列表行的可调样式。首页和搜索共用 [TopicRow]，所以同一套值两边一起生效。
 * 默认值就是改造前写死的数字：不动设置的人看到的列表不变。
 *
 * 次要文字、未读药丸、内联图标和间距不单独开项。它们和标题在同一行里，
 * 不跟着缩放就会和标题对不齐。
 */
data class TopicListStyle(
    val titleFontSize: Int,
    val titleFontWeight: Int,
    val lineHeightRatio: Float,
    val rowPadding: Int,
    val avatarSize: Int
) {
    val titleLineHeight: Int get() = (titleFontSize * lineHeightRatio).roundToInt()
    val titleWeight: FontWeight get() = when (titleFontWeight) {
        TITLE_FONT_WEIGHT_REGULAR -> FontWeight.Normal
        TITLE_FONT_WEIGHT_SEMI_BOLD -> FontWeight.SemiBold
        else -> FontWeight.Medium
    }
    val secondaryFontSize: Int get() = (titleFontSize - 4).coerceIn(10, 17)
    val unreadBadgeFontSize: Int get() = (titleFontSize - 5).coerceIn(9, 16)
    val unreadBadgeRadius: Int get() = maxOf(6, (unreadBadgeFontSize * 0.75f).roundToInt())
    val unseenDotFontSize: Int get() = (titleFontSize - 9).coerceIn(6, 12)
    val inlineIconSize: Int get() = (titleFontSize * 15f / 17f).roundToInt().coerceIn(11, 19)
    val inlineIconGap: Int get() = maxOf(3, inlineIconSize / 3)
    val avatarGap: Int get() = maxOf(6, avatarSize / 6)
    val metaRowGap: Int get() = maxOf(2, (rowPadding * 3f / 8f).roundToInt())
    val blurbGap: Int get() = maxOf(2, rowPadding / 2)

    companion object {
        val Default = TopicListStyle(
            titleFontSize = TITLE_FONT_SIZE_DEFAULT,
            titleFontWeight = TITLE_FONT_WEIGHT_DEFAULT,
            lineHeightRatio = LINE_HEIGHT_RATIO_DEFAULT,
            rowPadding = ROW_PADDING_DEFAULT,
            avatarSize = AVATAR_SIZE_DEFAULT
        )
    }
}

/** 标题字号。下限再小就和次要文字一个级别，上限再大两行标题会吃掉大半屏。 */
const val TITLE_FONT_SIZE_MIN = 13
const val TITLE_FONT_SIZE_MAX = 21
const val TITLE_FONT_SIZE_DEFAULT = 17

/** 字体里真实存在的三档，数值即 CSS 字重。脏值不就近取整，直接回中等。 */
const val TITLE_FONT_WEIGHT_REGULAR = 400
const val TITLE_FONT_WEIGHT_MEDIUM = 500
const val TITLE_FONT_WEIGHT_SEMI_BOLD = 600
const val TITLE_FONT_WEIGHT_DEFAULT = TITLE_FONT_WEIGHT_MEDIUM

/** 行高存倍率而不是绝对像素，改字号时行高跟着走。1.35 × 17 四舍五入正好是原来的 23。 */
const val LINE_HEIGHT_RATIO_MIN = 1.15f
const val LINE_HEIGHT_RATIO_MAX = 1.9f
const val LINE_HEIGHT_RATIO_STEP = 0.05f
const val LINE_HEIGHT_RATIO_DEFAULT = 1.35f

/** 每行内容区的上下留白。下限还有分割线兜底，上限之后一屏只剩五条。 */
const val ROW_PADDING_MIN = 4
const val ROW_PADDING_MAX = 20
const val ROW_PADDING_DEFAULT = 8

/** 头像直径。下限还能认出人脸，上限已经和两行标题一样高。 */
const val AVATAR_SIZE_MIN = 32
const val AVATAR_SIZE_MAX = 56
const val AVATAR_SIZE_DEFAULT = 48

fun clampTitleFontSize(value: Float): Int =
    clampInt(value, TITLE_FONT_SIZE_MIN, TITLE_FONT_SIZE_MAX, TITLE_FONT_SIZE_DEFAULT)

fun clampRowPadding(value: Float): Int =
    clampInt(value, ROW_PADDING_MIN, ROW_PADDING_MAX, ROW_PADDING_DEFAULT)

fun clampAvatarSize(value: Float): Int =
    clampInt(value, AVATAR_SIZE_MIN, AVATAR_SIZE_MAX, AVATAR_SIZE_DEFAULT)

/** 步进是 0.05，浮点累加会攒出 1.3500001。钳制后再收到两位小数。 */
fun clampLineHeightRatio(value: Float): Float {
    if (!value.isFinite()) return LINE_HEIGHT_RATIO_DEFAULT
    val bounded = value.coerceIn(LINE_HEIGHT_RATIO_MIN, LINE_HEIGHT_RATIO_MAX)
    return (bounded * 100f).roundToInt() / 100f
}

fun normalizeTitleFontWeight(value: Int): Int = when (value) {
    TITLE_FONT_WEIGHT_REGULAR, TITLE_FONT_WEIGHT_SEMI_BOLD -> value
    else -> TITLE_FONT_WEIGHT_MEDIUM
}

private fun clampInt(value: Float, min: Int, max: Int, fallback: Int): Int {
    if (!value.isFinite()) return fallback
    return value.roundToInt().coerceIn(min, max)
}
