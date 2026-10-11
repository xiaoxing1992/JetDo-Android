package com.jetdo.android.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Test

class TopicListStyleTest {
    @Test
    fun `默认值还原改造前的硬编码尺寸`() {
        val style = TopicListStyle.Default

        assertEquals(17, style.titleFontSize)
        assertEquals(500, style.titleFontWeight)
        assertEquals(1.35f, style.lineHeightRatio)
        assertEquals(23, style.titleLineHeight)
        assertEquals(13, style.secondaryFontSize)
        assertEquals(12, style.unreadBadgeFontSize)
        assertEquals(9, style.unreadBadgeRadius)
        assertEquals(8, style.unseenDotFontSize)
        assertEquals(15, style.inlineIconSize)
        assertEquals(5, style.inlineIconGap)
        assertEquals(8, style.rowPadding)
        assertEquals(48, style.avatarSize)
        assertEquals(8, style.avatarGap)
        assertEquals(3, style.metaRowGap)
        assertEquals(4, style.blurbGap)
    }

    @Test
    fun `越界和脏值都钳回合法范围`() {
        assertEquals(13, clampTitleFontSize(1f))
        assertEquals(21, clampTitleFontSize(99f))
        assertEquals(17, clampTitleFontSize(Float.NaN))

        assertEquals(4, clampRowPadding(-1f))
        assertEquals(20, clampRowPadding(40f))
        assertEquals(32, clampAvatarSize(0f))
        assertEquals(56, clampAvatarSize(80f))

        assertEquals(1.15f, clampLineHeightRatio(0.2f))
        assertEquals(1.90f, clampLineHeightRatio(3f))
        assertEquals(1.35f, clampLineHeightRatio(1.3500001f))
        assertEquals(1.35f, clampLineHeightRatio(Float.NaN))

        assertEquals(400, normalizeTitleFontWeight(400))
        assertEquals(600, normalizeTitleFontWeight(600))
        assertEquals(500, normalizeTitleFontWeight(700))
        assertEquals(500, normalizeTitleFontWeight(0))
    }

    @Test
    fun `派生尺寸跟着五个值走并封住上下限`() {
        val small = TopicListStyle(13, 400, 1.15f, 4, 32)
        assertEquals(15, small.titleLineHeight)
        assertEquals(10, small.secondaryFontSize)
        assertEquals(9, small.unreadBadgeFontSize)
        assertEquals(7, small.unreadBadgeRadius)
        assertEquals(6, small.unseenDotFontSize)
        assertEquals(11, small.inlineIconSize)
        assertEquals(6, small.avatarGap)
        assertEquals(2, small.metaRowGap)
        assertEquals(2, small.blurbGap)

        val large = TopicListStyle(21, 600, 1.9f, 20, 56)
        assertEquals(40, large.titleLineHeight)
        assertEquals(17, large.secondaryFontSize)
        assertEquals(16, large.unreadBadgeFontSize)
        assertEquals(12, large.unreadBadgeRadius)
        assertEquals(12, large.unseenDotFontSize)
        assertEquals(19, large.inlineIconSize)
        assertEquals(9, large.avatarGap)
        assertEquals(8, large.metaRowGap)
        assertEquals(10, large.blurbGap)
    }
}
