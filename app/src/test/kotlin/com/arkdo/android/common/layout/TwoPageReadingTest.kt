package com.arkdo.android.common.layout

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TwoPageReadingTest {
    @Test
    fun `开关关着时，任何形态都不生效`() {
        // 这是这个特性敢默认关的前提：总闸一关，后续判定一个都不该放行。
        assertFalse(TwoPageReading.isActive(enabled = false, bookPosture = true, paneWidthDp = 2000f))
        assertFalse(TwoPageReading.isActive(enabled = false, bookPosture = false, paneWidthDp = 2000f))
        assertFalse(TwoPageReading.isActive(enabled = false, bookPosture = false, paneWidthDp = 0f))
    }

    @Test
    fun `图书姿态下不看宽度，一律生效`() {
        // 图书姿态时外层会强制单栏，整块屏都交给详情页，宽度天然够 ——
        // 这里若还拿宽度去卡，会出现「明明折起来了却不摊开」的矛盾。
        assertTrue(TwoPageReading.isActive(enabled = true, bookPosture = true, paneWidthDp = 0f))
        assertTrue(TwoPageReading.isActive(enabled = true, bookPosture = true, paneWidthDp = 419f))
    }

    @Test
    fun `非图书姿态时按 pane 宽度判，600 是分界`() {
        assertTrue(TwoPageReading.isActive(enabled = true, bookPosture = false, paneWidthDp = 600f))
        assertTrue(TwoPageReading.isActive(enabled = true, bookPosture = false, paneWidthDp = 1280f))
        assertFalse(TwoPageReading.isActive(enabled = true, bookPosture = false, paneWidthDp = 599f))
    }

    @Test
    fun `手机竖屏与分栏后的右栏都不摊开`() {
        // 手机竖屏 pane ≈ 400dp；838×945dp 折叠屏在未折起时外层仍分栏，详情页只占右栏 419dp。
        // 后者正是「按窗口宽度判」会踩的坑：窗口 838 ≥ 600 看着够宽，实际这一栏只有一半。
        assertFalse(TwoPageReading.isActive(enabled = true, bookPosture = false, paneWidthDp = 400f))
        assertFalse(TwoPageReading.isActive(enabled = true, bookPosture = false, paneWidthDp = 419f))
    }
}
