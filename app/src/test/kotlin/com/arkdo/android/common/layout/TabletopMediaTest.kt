package com.arkdo.android.common.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 桌面姿态媒体上下分区的几何单测。
 *
 * 核心矩阵就四格：铰链在卡片上方 / 范围内 / 下方，加上 clamp 两个边界 ——
 * 滚动列表里卡片与铰链的相对位置每帧都在变，返回 null（退回普通布局）与
 * clamp（保底可用面积）两条路径都必须稳定。
 */
class TabletopMediaTest {

    @Test
    fun `铰链在卡片正中切一半`() {
        val f = TabletopMedia.splitFraction(
            cardTopYDp = 0f, cardHeightDp = 400f, hingeCenterYDp = 200f
        )
        assertEquals(0.5f, f!!, 0.001f)
    }

    @Test
    fun `铰链在卡片上方不分区`() {
        // 卡片整体在铰链之下（top > hinge）：整块都在下半屏，没有上下分区可做。
        assertNull(
            TabletopMedia.splitFraction(
                cardTopYDp = 500f, cardHeightDp = 300f, hingeCenterYDp = 460f
            )
        )
    }

    @Test
    fun `铰链在卡片下方不分区`() {
        // 卡片整体在铰链之上：整块都在上半屏。
        assertNull(
            TabletopMedia.splitFraction(
                cardTopYDp = 60f, cardHeightDp = 300f, hingeCenterYDp = 472f
            )
        )
    }

    @Test
    fun `铰链贴着顶缘时压到下限`() {
        // 真实位置 0.1，clamp 到 0.35：视频区保住最小可用面积，
        // 宁可偏离铰链也不让画面只剩一条缝。
        val f = TabletopMedia.splitFraction(
            cardTopYDp = 0f, cardHeightDp = 400f, hingeCenterYDp = 40f
        )
        assertEquals(TabletopMedia.MIN_FRACTION, f!!, 0.001f)
    }

    @Test
    fun `铰链贴着底缘时压到上限`() {
        // 真实位置 0.95，clamp 到 0.75：控件区（下半屏手够的地方）保住可用面积。
        val f = TabletopMedia.splitFraction(
            cardTopYDp = 0f, cardHeightDp = 400f, hingeCenterYDp = 380f
        )
        assertEquals(TabletopMedia.MAX_FRACTION, f!!, 0.001f)
    }

    @Test
    fun `铰链恰好在边界值上不分区`() {
        // raw == 0 / raw == 1 属于「不相交」的边界约定：切在正顶/正底等于没切。
        assertNull(
            TabletopMedia.splitFraction(
                cardTopYDp = 100f, cardHeightDp = 300f, hingeCenterYDp = 100f
            )
        )
        assertNull(
            TabletopMedia.splitFraction(
                cardTopYDp = 100f, cardHeightDp = 300f, hingeCenterYDp = 400f
            )
        )
    }

    @Test
    fun `尚未量到位置或高度非法时不分区`() {
        // 首帧 onGloballyPositioned 还没回调（NaN）或高度为 0，都不能拿去算。
        assertNull(
            TabletopMedia.splitFraction(
                cardTopYDp = Float.NaN, cardHeightDp = 300f, hingeCenterYDp = 200f
            )
        )
        assertNull(
            TabletopMedia.splitFraction(
                cardTopYDp = 0f, cardHeightDp = 0f, hingeCenterYDp = 200f
            )
        )
    }
}
