package com.jetdo.android.common.layout

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
        // 手机竖屏 / 横屏都够不着 840，也够不着近方形那条的 600。
        assertFalse(WindowBreakpoints.isWideLayout(360f, 780f))
        assertFalse(WindowBreakpoints.isWideLayout(780f, 360f))
    }

    @Test
    fun `平板只有横屏才分栏`() {
        // 大屏平板竖屏宽度就有 965dp：光看阈值会误判，长宽比这一刀把它按回单栏。
        assertTrue(WindowBreakpoints.isWideLayout(1138f, 800f))
        assertFalse(WindowBreakpoints.isWideLayout(965f, 1300f))
        assertFalse(WindowBreakpoints.isWideLayout(800f, 1280f))
    }

    @Test
    fun `横屏手机被高度闸门挡下`() {
        // 这一类是加闸门之前唯一的真错：手机横屏宽度普遍 900~1050dp，早就越过 840，
        // 但高度只有 448 上下 —— 并排两栏后每栏只剩一条窄缝。官方把「宽度中等、高度紧凑」
        // 明确列为双窗格不适用的场景，高度紧凑的上界就是 480dp。
        assertFalse(WindowBreakpoints.isWideLayout(997f, 448f)) // Pixel 8 Pro 横屏
        assertFalse(WindowBreakpoints.isWideLayout(1029f, 479f)) // 闸门内侧一档

        // 正是闸门本身在起作用：同一个宽度，高度补到 480 就成立。
        // 若哪天有人把闸门去掉或改小，上面两条会连着这条一起变红。
        assertTrue(WindowBreakpoints.isWideLayout(1029f, 480f))

        // 平板横屏高度远在闸门之上，不受影响。
        assertTrue(WindowBreakpoints.isWideLayout(1024f, 640f))
    }

    @Test
    fun `竖折叠内屏横竖都分栏`() {
        // 竖折叠内屏近方形 741×832，比值 1.12，横竖都双栏。
        assertTrue(WindowBreakpoints.isWideLayout(741f, 832f))
        assertTrue(WindowBreakpoints.isWideLayout(832f, 741f))
        // 折叠态外屏 ≈360：低于 600 门槛，不会被误判。
        assertFalse(WindowBreakpoints.isWideLayout(360f, 780f))
    }

    @Test
    fun `展开态不再依赖折叠信号上报`() {
        // 缺口 H 的回归用例。模拟器（以及部分真机）在**完全展开**时上报的是空 FoldingFeature
        // 列表 —— 裸的 838×945dp 内屏连 840 都够不着。判定若依赖折叠信号，这块内屏会退化成单栏。
        // 现在「近方形大窗口」这条纯尺寸路径直接兜住它，判定函数里已经没有折叠参数了。
        assertTrue(WindowBreakpoints.isWideLayout(838f, 945f)) // 8in Foldable 展开态实测尺寸
        assertTrue(WindowBreakpoints.isWideLayout(945f, 838f))

        // 反例：同一条路径不能顺手把「竖长条」也算进来，否则平板竖屏会误判。
        assertFalse(WindowBreakpoints.isWideLayout(700f, 1200f))
    }

    @Test
    fun `阔折叠只有展开横持才分栏`() {
        // 展开横持 939×664 走第一条（939 ≥ 840 且横着）→ 双栏。
        assertTrue(WindowBreakpoints.isWideLayout(939f, 664f))
        // 同一块内屏竖持 664×939，比值 1.41 已接近平板竖屏形状 → 单栏。
        // 若不看长宽比，这里会因 664 ≥ 600 被判成双栏，与上面平板竖屏那条自相矛盾。
        assertFalse(WindowBreakpoints.isWideLayout(664f, 939f))
        // 折叠态外屏 459×672，两条都不成立。
        assertFalse(WindowBreakpoints.isWideLayout(459f, 672f))
        // 展开态 440×707：宽度不到 600，近方形那条够不着；又不满足「够宽且横着」。
        assertFalse(WindowBreakpoints.isWideLayout(440f, 707f))
        assertFalse(WindowBreakpoints.isWideLayout(326f, 326f))
    }

    @Test
    fun `左右分屏回落单栏`() {
        // 折叠屏展开态再左右分屏，应用窗口只剩半宽：低于 600 直接落回单栏。
        assertFalse(WindowBreakpoints.isWideLayout(470f, 664f))
        // 平板横屏分屏同理，半宽够不着 840。
        assertFalse(WindowBreakpoints.isWideLayout(569f, 800f))
    }

    @Test
    fun `退化尺寸一律判否`() {
        // 首帧之前 / 异常回调可能喂进 0，不能除零也不能判成宽屏。
        assertFalse(WindowBreakpoints.isWideLayout(0f, 0f))
        assertFalse(WindowBreakpoints.isWideLayout(700f, 0f))
    }

    @Test
    fun `阈值与官方值一致`() {
        assertEquals(480f, WindowBreakpoints.MIN_SPLIT_HEIGHT_DP, 0f)
        assertEquals(840f, WindowBreakpoints.WIDE_THRESHOLD_DP, 0f)
        assertEquals(600f, WindowBreakpoints.SQUARE_WIDE_THRESHOLD_DP, 0f)
        assertEquals(1.2f, WindowBreakpoints.SQUARE_WIDE_RATIO_MAX, 0f)
        assertEquals(0.5f, WindowBreakpoints.SPLIT_NAV_BAR_RATIO, 0f)
        assertEquals(320f, WindowBreakpoints.SPLIT_NAV_BAR_MIN_WIDTH_DP, 0f)
        assertEquals(420f, WindowBreakpoints.SPLIT_NAV_BAR_MAX_WIDTH_DP, 0f)
    }

    // ── 档位判定 ──────────────────────────────────────────────────────────

    @Test
    fun `宽度分五档且断点取官方值`() {
        // 每一档都取「下界 - 1 / 下界」两侧，避免写成 `>` 或 `>=` 时静默错位。
        assertEquals(WindowWidthSizeClass.COMPACT, WindowBreakpoints.widthSizeClassOf(599f))
        assertEquals(WindowWidthSizeClass.MEDIUM, WindowBreakpoints.widthSizeClassOf(600f))
        assertEquals(WindowWidthSizeClass.MEDIUM, WindowBreakpoints.widthSizeClassOf(839f))
        assertEquals(WindowWidthSizeClass.EXPANDED, WindowBreakpoints.widthSizeClassOf(840f))
        assertEquals(WindowWidthSizeClass.EXPANDED, WindowBreakpoints.widthSizeClassOf(1199f))
        assertEquals(WindowWidthSizeClass.LARGE, WindowBreakpoints.widthSizeClassOf(1200f))
        assertEquals(WindowWidthSizeClass.LARGE, WindowBreakpoints.widthSizeClassOf(1599f))
        assertEquals(WindowWidthSizeClass.EXTRA_LARGE, WindowBreakpoints.widthSizeClassOf(1600f))

        // 档位下界与双栏阈值必须是同一个数（840 同时是 EXPANDED 下界），否则会出现
        // 「判成双栏但档位还是 MEDIUM」这种自相矛盾。
        assertEquals(
            WindowWidthSizeClass.EXPANDED,
            WindowBreakpoints.widthSizeClassOf(WindowBreakpoints.WIDE_THRESHOLD_DP)
        )
    }

    @Test
    fun `高度分三档`() {
        assertEquals(WindowHeightSizeClass.COMPACT, WindowBreakpoints.heightSizeClassOf(479f))
        assertEquals(WindowHeightSizeClass.MEDIUM, WindowBreakpoints.heightSizeClassOf(480f))
        assertEquals(WindowHeightSizeClass.MEDIUM, WindowBreakpoints.heightSizeClassOf(899f))
        assertEquals(WindowHeightSizeClass.EXPANDED, WindowBreakpoints.heightSizeClassOf(900f))
        // 高度档的 COMPACT 上界就是高度闸门，两者共用一个常量。
        assertEquals(
            WindowHeightSizeClass.MEDIUM,
            WindowBreakpoints.heightSizeClassOf(WindowBreakpoints.MIN_SPLIT_HEIGHT_DP)
        )
    }

    // ── splitGeometry ─────────────────────────────────────────────────────

    /** 单测里只关心左栏宽时用这个，省得每行都写 `.navBarWidthDp`。 */
    private fun navBarWidth(
        widthDp: Float,
        unfolded: Boolean = false,
        hinge: FoldHingeSpan? = null
    ): Float = WindowBreakpoints.splitGeometry(widthDp, unfolded, hinge).navBarWidthDp

    @Test
    fun `左栏宽按宽度档封顶`() {
        // expanded 档（840 ~ 1200）：对半 569 封到 420。
        assertEquals(420f, navBarWidth(1138f), 0f)
        // large 档（1200 ~ 1600）：10.5" 平板对半 640 封到 480 —— 这一档是主力大屏形态，
        // 仍按 420 封顶会让列表卡片挤成一条。
        assertEquals(480f, navBarWidth(1280f), 0f)
        assertEquals(480f, navBarWidth(1440f), 0f)
        // extra-large 档（≥ 1600）：桌面显示器，对半 800 封到 560。
        assertEquals(560f, navBarWidth(1600f), 0f)
        assertEquals(560f, navBarWidth(2560f), 0f)
        // 窄窗口对半不足 320 时抬到下限，保证分区抽屉（288）滑出不溢进右栏。
        assertEquals(320f, navBarWidth(600f), 0f)
    }

    @Test
    fun `展开态左栏宽豁免上限以对齐折痕`() {
        // 双折叠内屏 741 对半是 370.5 → 370。Kotlin 的 kotlin.math.round 是「就近取偶」
        // （ties to even，370.5 落到偶数 370），与鸿蒙参考实现的期望值一致；
        // 换成 Math.round 那种 half-up 会得 371，差 1dp。别改成 371。
        assertEquals(370f, navBarWidth(741f, unfolded = true), 0f)
        // 阔折叠展开横持 939 对半是 469.5 → 470：被 420 夹住会让分割线偏离折痕约 50dp，
        // 展开态豁免上限后取满一半，压在折痕上，右栏 469。
        assertEquals(470f, navBarWidth(939f, unfolded = true), 0f)
        // 上限只对非折叠设备生效——同一个宽度在直板/平板上仍然按档封顶。
        assertEquals(420f, navBarWidth(939f, unfolded = false), 0f)
        // 下限对展开态同样生效。
        assertEquals(320f, navBarWidth(600f, unfolded = true), 0f)
    }

    @Test
    fun `有铰链时按铰链位置分栏并留出铰链宽度`() {
        // 1280dp 内屏、铰链落在 640：分栏线压到铰链左缘，而不是「对半算出来的 480」。
        // 这才是缺口 C 要的效果 —— 内容两侧都不压在铰链上。
        val hinged = WindowBreakpoints.splitGeometry(
            widthDp = 1280f,
            unfolded = true,
            hinge = FoldHingeSpan(startDp = 640f, endDp = 641f)
        )
        assertEquals(640f, hinged.navBarWidthDp, 0f)
        assertEquals(1f, hinged.hingeGapWidthDp, 0f)
    }

    @Test
    fun `铰链位置不合适时退回对半`() {
        // 铰链离左缘太近：左栏放不下（< 320），对齐它没意义。
        val tooFarLeft = WindowBreakpoints.splitGeometry(
            widthDp = 1280f,
            unfolded = false,
            hinge = FoldHingeSpan(startDp = 200f, endDp = 210f)
        )
        assertEquals(480f, tooFarLeft.navBarWidthDp, 0f)
        assertEquals(0f, tooFarLeft.hingeGapWidthDp, 0f)

        // 铰链离右缘太近：右栏会被挤成一条缝。
        val tooFarRight = WindowBreakpoints.splitGeometry(
            widthDp = 1280f,
            unfolded = false,
            hinge = FoldHingeSpan(startDp = 1000f, endDp = 1010f)
        )
        assertEquals(480f, tooFarRight.navBarWidthDp, 0f)
        assertEquals(0f, tooFarRight.hingeGapWidthDp, 0f)
    }

    @Test
    fun `无物理宽度的折痕不留白`() {
        // 可折叠屏的折痕是 1px 级的线，换算成 dp 后几乎没有宽度 —— 留白必须是 0，
        // 否则两栏之间会莫名多出一条缝。
        val crease = WindowBreakpoints.splitGeometry(
            widthDp = 741f,
            unfolded = true,
            hinge = FoldHingeSpan(startDp = 370f, endDp = 370f)
        )
        assertEquals(370f, crease.navBarWidthDp, 0f)
        assertEquals(0f, crease.hingeGapWidthDp, 0f)
    }

    @Test
    fun `没有铰链时不插留白`() {
        // 直板 / 平板 / 桌面窗口：整条副路径都不该产生缝隙。
        assertEquals(0f, WindowBreakpoints.splitGeometry(1138f, unfolded = false).hingeGapWidthDp, 0f)
        assertEquals(0f, WindowBreakpoints.splitGeometry(741f, unfolded = true).hingeGapWidthDp, 0f)
    }
}
