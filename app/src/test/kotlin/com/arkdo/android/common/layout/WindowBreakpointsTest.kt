package com.arkdo.android.common.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 断点判定单测（对齐鸿蒙 `entry/src/test/BreakpointManager.test.ets` 的九条用例）。
 *
 * 尺寸取自各机型实测 dp。改阈值时这一组就是回归护栏：每台机器每种形态该单栏还是双栏，
 * 都钉在这里 —— 真机上凑不齐这些形态，只能靠纯函数覆盖。
 */
class WindowBreakpointsTest {

    // ── isWideLayout ──────────────────────────────────────────────────────

    @Test
    fun `手机横竖屏都保持单栏`() {
        // 手机竖屏 / 横屏都够不着 840，非折叠屏也走不到第二条。
        assertFalse(WindowBreakpoints.isWideLayout(360f, 780f, unfolded = false))
        assertFalse(WindowBreakpoints.isWideLayout(780f, 360f, unfolded = false))
    }

    @Test
    fun `平板只有横屏才分栏`() {
        // 大屏平板竖屏宽度就有 965dp：光看阈值会误判，长宽比这一刀把它按回单栏。
        assertTrue(WindowBreakpoints.isWideLayout(1138f, 800f, unfolded = false))
        assertFalse(WindowBreakpoints.isWideLayout(965f, 1300f, unfolded = false))
    }

    @Test
    fun `竖折叠内屏横竖都分栏`() {
        // 竖折叠内屏近方形 741×832，比值 1.12，横竖都双栏。
        assertTrue(WindowBreakpoints.isWideLayout(741f, 832f, unfolded = true))
        assertTrue(WindowBreakpoints.isWideLayout(832f, 741f, unfolded = true))
        // 折叠态外屏 ≈360：低于 600 兜底阈值，不会被误判。
        assertFalse(WindowBreakpoints.isWideLayout(360f, 780f, unfolded = true))
    }

    @Test
    fun `阔折叠只有展开横持才分栏`() {
        // 展开横持 939×664 走第一条（939 ≥ 840 且横着）→ 双栏。
        assertTrue(WindowBreakpoints.isWideLayout(939f, 664f, unfolded = true))
        // 同一块内屏竖持 664×939，比值 1.41 已接近平板竖屏形状 → 单栏。
        // 若不看长宽比，这里会因 664 ≥ 600 被兜底判成双栏，与上面平板竖屏那条自相矛盾。
        assertFalse(WindowBreakpoints.isWideLayout(664f, 939f, unfolded = true))
        // 折叠态外屏 459×672，两条都不成立。
        assertFalse(WindowBreakpoints.isWideLayout(459f, 672f, unfolded = true))
        // 展开态 440×707：宽度不到 600，兜底那条本就够不着。
        assertFalse(WindowBreakpoints.isWideLayout(440f, 707f, unfolded = true))
        assertFalse(WindowBreakpoints.isWideLayout(326f, 326f, unfolded = true))
    }

    @Test
    fun `左右分屏回落单栏`() {
        // 折叠屏展开态再左右分屏，应用窗口只剩半宽：低于 600 直接落回单栏。
        assertFalse(WindowBreakpoints.isWideLayout(470f, 664f, unfolded = true))
        // 平板横屏分屏同理，半宽够不着 840。
        assertFalse(WindowBreakpoints.isWideLayout(569f, 800f, unfolded = false))
    }

    @Test
    fun `退化尺寸一律判否`() {
        // 首帧之前 / 异常回调可能喂进 0，不能除零也不能判成宽屏。
        assertFalse(WindowBreakpoints.isWideLayout(0f, 0f, unfolded = true))
        assertFalse(WindowBreakpoints.isWideLayout(700f, 0f, unfolded = true))
    }

    @Test
    fun `阈值与文档值一致`() {
        assertEquals(840f, WindowBreakpoints.WIDE_THRESHOLD_DP, 0f)
        assertEquals(600f, WindowBreakpoints.UNFOLDED_WIDE_THRESHOLD_DP, 0f)
        assertEquals(1.2f, WindowBreakpoints.UNFOLDED_SQUARE_RATIO_MAX, 0f)
        assertEquals(0.5f, WindowBreakpoints.SPLIT_NAV_BAR_RATIO, 0f)
        assertEquals(320f, WindowBreakpoints.SPLIT_NAV_BAR_MIN_WIDTH_DP, 0f)
        assertEquals(420f, WindowBreakpoints.SPLIT_NAV_BAR_MAX_WIDTH_DP, 0f)
    }

    // ── splitNavBarWidth ──────────────────────────────────────────────────

    @Test
    fun `左栏宽在非折叠设备上封顶`() {
        // 平板横屏对半是 569 / 720，都封顶 420。
        assertEquals(420f, WindowBreakpoints.splitNavBarWidth(1138f, unfolded = false), 0f)
        assertEquals(420f, WindowBreakpoints.splitNavBarWidth(1440f, unfolded = false), 0f)
        // 窄窗口对半不足 320 时抬到下限，保证分区抽屉（288）滑出不溢进右栏。
        assertEquals(320f, WindowBreakpoints.splitNavBarWidth(600f, unfolded = false), 0f)
    }

    @Test
    fun `展开态左栏宽对齐折痕`() {
        // 双折叠内屏 741 对半是 370.5 → 370。Kotlin 的 kotlin.math.round 是「就近取偶」
        // （ties to even，370.5 落到偶数 370），与鸿蒙参考实现的期望值一致；
        // 换成 Math.round 那种 half-up 会得 371，差 1dp。别改成 371。
        assertEquals(370f, WindowBreakpoints.splitNavBarWidth(741f, unfolded = true), 0f)
        // 阔折叠展开横持 939 对半是 469.5 → 470：被 420 夹住会让分割线偏离折痕约 50dp，
        // 展开态豁免上限后取满一半，压在折痕上，右栏 469。
        assertEquals(470f, WindowBreakpoints.splitNavBarWidth(939f, unfolded = true), 0f)
        // 上限只对非折叠设备生效——同一个宽度在直板/平板上仍然封顶。
        assertEquals(420f, WindowBreakpoints.splitNavBarWidth(939f, unfolded = false), 0f)
        // 下限对展开态同样生效。
        assertEquals(320f, WindowBreakpoints.splitNavBarWidth(600f, unfolded = true), 0f)
    }
}
