package com.jetdo.android.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 选中胶囊宽度几何。
 *
 * 钉住的是这条回归:胶囊宽度曾是**写死的 77dp** —— 那是按 4 tab 调的。
 * 底栏从 4 tab 收成 3 tab 后槽宽从 ~90dp 涨到 ~120dp,写死的绝对值没跟着变,
 * 占槽比例从 86% 掉到 64%,选中左右端 tab 时胶囊孤零零浮在槽中央(两端各空 21dp),
 * 就是"左右没那么好看"。改成按槽宽取比例后,tab 数变化时胶囊自动跟着缩放。
 *
 * 槽宽口径:容器宽 = 屏宽 - 2 × ContainerHorizontalMargin,槽宽 = 容器宽 / tabCount。
 * 下面用的是真机实测值(华为 DCO-AL00,1212px / density 500 → 宽 387.84dp,
 * 实测容器 1124px、槽 374.8px、胶囊 300px = 95.96dp,与断言一致)。
 */
class BottomBarCapsuleWidthTest {

    private val phoneWidth = 387.84.dp

    private fun slotFor(tabCount: Int): Dp =
        (phoneWidth - BottomBarDefaults.ContainerHorizontalMargin * 2) / tabCount

    /** Dp 是 Float 值类,统一走 Double 比较,避免 Float/Double 重载歧义。 */
    private fun assertDp(expected: Double, actual: Dp, delta: Double = 0.05) {
        assertEquals(expected, actual.value.toDouble(), delta)
    }

    @Test
    fun `3 tab 时胶囊占槽宽八成`() {
        val slot = slotFor(3)
        // 槽宽 ≈ 119.95dp → 胶囊 ≈ 95.96dp,不超过上限 100dp
        assertDp(119.95, slot)
        assertDp(95.96, capsuleWidthFor(slot))
    }

    @Test
    fun `3 tab 时比旧的写死 77dp 明显更宽`() {
        val width = capsuleWidthFor(slotFor(3)).value
        assertTrue("3 tab 胶囊 ${width}dp 应显著宽于旧值 77dp", width > 77f * 1.15f)
    }

    @Test
    fun `4 tab 时胶囊仍接近旧观感而不瘦成一点`() {
        // 槽宽 ≈ 89.96dp → 0.8 × 89.96 ≈ 71.97dp,与旧写死 77dp 同量级
        val width = capsuleWidthFor(slotFor(4)).value
        assertDp(71.97, capsuleWidthFor(slotFor(4)))
        assertTrue("不该比旧值缩水太多", width > 77f * 0.9f)
    }

    @Test
    fun `槽宽变宽时胶囊跟着变宽`() {
        // 这正是本次修的东西:4 tab → 3 tab,槽变宽,胶囊必须跟着变宽。
        assertTrue(capsuleWidthFor(slotFor(3)) > capsuleWidthFor(slotFor(4)))
    }

    @Test
    fun `槽宽超宽时被上限压住`() {
        // 平板/展开态:槽宽 240dp → 0.8 比例会算出 192dp 的横条,必须被 100dp 截住
        assertDp(100.0, capsuleWidthFor(240.dp), 0.01)
    }

    @Test
    fun `槽宽极窄时被下限抬起但不超出槽宽`() {
        // 40dp 槽:比例算出 32dp,下限 68dp 想抬,但两侧必须各留 ContentPadding(4dp)
        assertDp(32.0, capsuleWidthFor(40.dp), 0.01)
    }

    @Test
    fun `任何槽宽下都留出两侧内缩`() {
        for (raw in listOf(30, 40, 60, 80, 90, 120, 200, 400)) {
            val slot = raw.dp
            val width = capsuleWidthFor(slot)
            assertTrue(
                "槽宽 ${raw}dp 时胶囊 ${width.value}dp 超了槽宽 - 2×ContentPadding",
                width <= slot - BottomBarDefaults.ContentPadding * 2
            )
            assertTrue("胶囊宽度必须为正", width.value > 0f)
        }
    }

    @Test
    fun `两端间隙随槽宽一起放大而不是固定值`() {
        val gap3 = (slotFor(3) - capsuleWidthFor(slotFor(3))) / 2
        val gap4 = (slotFor(4) - capsuleWidthFor(slotFor(4))) / 2
        // 3 tab 槽更宽,但胶囊是固定宽的话间隙会撑到 ~21dp;比例化后两侧各 ≈12dp
        assertDp(12.0, gap3, 0.1)
        assertTrue("3 tab 的两端间隙应小于固定宽时代的 21dp", gap3.value < 21f)
        // 且比 4 tab 的间隙大一些(槽更宽,按比例留出的绝对间隙也更大)
        assertTrue(gap3 > gap4)
    }
}
