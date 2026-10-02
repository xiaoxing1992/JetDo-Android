package com.arkdo.android.common.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 折叠姿态判定单测。
 *
 * 覆盖官方列出的三类形态 —— 铺平、图书姿态（竖直折痕半开）、桌面姿态（水平折痕半开）——
 * 外加非折叠设备与三折叠（多铰链、全 FLAT）。真机上凑不齐这些形态，只能靠纯函数覆盖。
 */
class FoldPostureTest {

    private fun fold(
        state: FoldState,
        orientation: FoldOrientation,
        isSeparating: Boolean = false,
        boundsDp: FoldBounds? = null
    ) = FoldFeature(
        state = state,
        orientation = orientation,
        isSeparating = isSeparating,
        boundsDp = boundsDp
    )

    /** 一条竖直、分隔、带位置的折痕 —— 分栏对齐要用的那种。 */
    private fun verticalHinge(leftDp: Float, rightDp: Float) = fold(
        state = FoldState.HALF_OPENED,
        orientation = FoldOrientation.VERTICAL,
        isSeparating = true,
        boundsDp = FoldBounds(leftDp = leftDp, topDp = 0f, rightDp = rightDp, bottomDp = 2480f)
    )

    // ── isUnfolded ────────────────────────────────────────────────────────

    @Test
    fun `非折叠设备恒判否`() {
        // 直板手机拿到的就是空列表，判定必须退化回「不是折叠屏」。
        assertFalse(FoldPosture.isUnfolded(emptyList()))
    }

    @Test
    fun `完全铺平算展开`() {
        // 铺平时内屏是一整块连续显示区，折痕方向不影响判定。
        assertTrue(FoldPosture.isUnfolded(listOf(fold(FoldState.FLAT, FoldOrientation.VERTICAL))))
        assertTrue(FoldPosture.isUnfolded(listOf(fold(FoldState.FLAT, FoldOrientation.HORIZONTAL))))
    }

    @Test
    fun `竖直折痕半开按图书姿态算展开`() {
        // book posture：屏幕被竖直折痕分成左右两块，每块仍是一个完整视口，
        // 分栏线正好压在折痕上 —— 这是天然的双栏，该算展开。
        assertTrue(FoldPosture.isUnfolded(listOf(fold(FoldState.HALF_OPENED, FoldOrientation.VERTICAL))))
    }

    @Test
    fun `水平折痕半开按桌面姿态不算展开`() {
        // tabletop：屏幕分成上下两块，任何一栏都只有半高；此时再左右各分一半，
        // 等于把正文塞进约四分之一高的区域里，不该分栏。
        assertFalse(FoldPosture.isUnfolded(listOf(fold(FoldState.HALF_OPENED, FoldOrientation.HORIZONTAL))))
    }

    @Test
    fun `三折叠展开态按铺平识别`() {
        // 官方：三折叠不支持桌面姿态、也用不到 HALF_OPENED，展开后 hingeList 含两个铰链且都是 FLAT。
        // 这里不需要为三折叠单开分支，`any { FLAT }` 天然命中。
        assertTrue(
            FoldPosture.isUnfolded(
                listOf(
                    fold(FoldState.FLAT, FoldOrientation.VERTICAL),
                    fold(FoldState.FLAT, FoldOrientation.VERTICAL)
                )
            )
        )
    }

    @Test
    fun `列表里任一命中即算展开`() {
        // 三折半开一类形态可能同时报出多条折叠特征，只要有一条成立就该分栏。
        assertTrue(
            FoldPosture.isUnfolded(
                listOf(
                    fold(FoldState.HALF_OPENED, FoldOrientation.HORIZONTAL), // 桌面姿态，单独看不成立
                    fold(FoldState.FLAT, FoldOrientation.VERTICAL)
                )
            )
        )
    }

    // ── isBookPosture ─────────────────────────────────────────────────────
    // 这几条不传 isSeparating：官方保证 `HALF_OPENED` 必然分隔，判定里就不再重复检查它。

    @Test
    fun `竖直折痕半开即图书姿态`() {
        assertTrue(
            FoldPosture.isBookPosture(listOf(fold(FoldState.HALF_OPENED, FoldOrientation.VERTICAL)))
        )
    }

    @Test
    fun `铺平不算图书姿态`() {
        // 这是它与 isUnfolded 的唯一差别：铺平后的内屏是一整块连续显示区，
        // 左右之间没有折痕作依据，人为锯成两页并不成立。
        assertFalse(FoldPosture.isBookPosture(listOf(fold(FoldState.FLAT, FoldOrientation.VERTICAL))))
        assertFalse(FoldPosture.isBookPosture(listOf(fold(FoldState.FLAT, FoldOrientation.HORIZONTAL))))
    }

    @Test
    fun `桌面姿态不算图书姿态`() {
        // 水平折痕切的是上下，摊开的书脊是竖直的。
        assertFalse(
            FoldPosture.isBookPosture(listOf(fold(FoldState.HALF_OPENED, FoldOrientation.HORIZONTAL)))
        )
    }

    @Test
    fun `非折叠设备与三折叠都进不了图书姿态`() {
        // 前者没有折叠特征；后者官方明确不产生 HALF_OPENED，只走尺寸阶梯 —— 两条路都到不了。
        assertFalse(FoldPosture.isBookPosture(emptyList()))
        assertFalse(
            FoldPosture.isBookPosture(
                listOf(
                    fold(FoldState.FLAT, FoldOrientation.VERTICAL),
                    fold(FoldState.FLAT, FoldOrientation.VERTICAL)
                )
            )
        )
    }

    // ── verticalSplitHinge ────────────────────────────────────────────────

    @Test
    fun `非折叠设备没有可对齐的铰链`() {
        assertNull(FoldPosture.verticalSplitHinge(emptyList()))
    }

    @Test
    fun `水平折痕不参与左右分栏`() {
        // 桌面姿态的折痕切的是上下，拿它当左右分栏线是概念错位 —— 必须返回 null。
        assertNull(
            FoldPosture.verticalSplitHinge(
                listOf(
                    fold(
                        state = FoldState.HALF_OPENED,
                        orientation = FoldOrientation.HORIZONTAL,
                        isSeparating = true,
                        boundsDp = FoldBounds(0f, 1240f, 2200f, 1241f)
                    )
                )
            )
        )
    }

    @Test
    fun `不切分屏的折痕不参与分栏`() {
        // isSeparating == false 意味着折痕没有把显示区切成两块，压上去没有意义。
        assertNull(
            FoldPosture.verticalSplitHinge(
                listOf(
                    fold(
                        state = FoldState.HALF_OPENED,
                        orientation = FoldOrientation.VERTICAL,
                        isSeparating = false,
                        boundsDp = FoldBounds(640f, 0f, 641f, 2480f)
                    )
                )
            )
        )
    }

    @Test
    fun `没上报位置的折痕无法对齐`() {
        // 位置是设备给的，缺失时不能瞎猜「折痕一定在中线」—— 退回按窗口对半更安全。
        assertNull(
            FoldPosture.verticalSplitHinge(
                listOf(
                    fold(
                        state = FoldState.FLAT,
                        orientation = FoldOrientation.VERTICAL,
                        isSeparating = true,
                        boundsDp = null
                    )
                )
            )
        )
    }

    @Test
    fun `竖直分隔折痕给出它的横向区间`() {
        val hinge = FoldPosture.verticalSplitHinge(listOf(verticalHinge(437.3f, 437.7f)))
        assertEquals(437.3f, hinge!!.startDp, 0.001f)
        assertEquals(437.7f, hinge.endDp, 0.001f)
        assertEquals(0.4f, hinge.widthDp, 0.001f)
    }

    @Test
    fun `多条铰链取最靠左的一条`() {
        // 三折叠展开后左右各一条折痕，竖起来就有两条竖直铰链。当前布局只有左右两栏，
        // 第二条铰链没有第三栏去承接 —— 取最靠左那条，至少保证左侧不压在折痕上。
        // 顺序也故意打乱：设备上报顺序没有约定，实现里排过序。
        val hinge = FoldPosture.verticalSplitHinge(
            listOf(
                verticalHinge(1466f, 1467f),
                verticalHinge(733f, 734f)
            )
        )
        assertEquals(733f, hinge!!.startDp, 0.001f)
    }

    // ── isTabletopPosture / horizontalSplitHinge ─────────────────────────

    /** 一条水平、分隔、带位置的折痕 —— 桌面姿态上下分区要用的那种。 */
    private fun horizontalHinge(topDp: Float, bottomDp: Float) = fold(
        state = FoldState.HALF_OPENED,
        orientation = FoldOrientation.HORIZONTAL,
        isSeparating = true,
        boundsDp = FoldBounds(leftDp = 0f, topDp = topDp, rightDp = 2200f, bottomDp = bottomDp)
    )

    @Test
    fun `水平折痕半开即桌面姿态`() {
        assertTrue(
            FoldPosture.isTabletopPosture(listOf(fold(FoldState.HALF_OPENED, FoldOrientation.HORIZONTAL)))
        )
    }

    @Test
    fun `铺平与竖直折痕都不算桌面姿态`() {
        // 铺平时没有折痕分隔；竖直折痕是图书姿态。与 isBookPosture 的三条判定完全对称。
        assertFalse(FoldPosture.isTabletopPosture(listOf(fold(FoldState.FLAT, FoldOrientation.HORIZONTAL))))
        assertFalse(FoldPosture.isTabletopPosture(listOf(fold(FoldState.HALF_OPENED, FoldOrientation.VERTICAL))))
        assertFalse(FoldPosture.isTabletopPosture(emptyList()))
    }

    @Test
    fun `竖直折痕不参与上下分区`() {
        // 图书姿态的折痕切的是左右，拿它当上下分区线是概念错位 —— 必须返回 null。
        assertNull(FoldPosture.horizontalSplitHinge(listOf(verticalHinge(437.3f, 437.7f))))
    }

    @Test
    fun `不切分或没报位置的水平折痕不参与上下分区`() {
        assertNull(
            FoldPosture.horizontalSplitHinge(
                listOf(
                    fold(
                        state = FoldState.HALF_OPENED,
                        orientation = FoldOrientation.HORIZONTAL,
                        isSeparating = false,
                        boundsDp = FoldBounds(0f, 1240f, 2200f, 1241f)
                    )
                )
            )
        )
        assertNull(
            FoldPosture.horizontalSplitHinge(
                listOf(fold(FoldState.HALF_OPENED, FoldOrientation.HORIZONTAL, isSeparating = true, boundsDp = null))
            )
        )
    }

    @Test
    fun `水平分隔折痕给出它的纵向区间`() {
        val hinge = FoldPosture.horizontalSplitHinge(listOf(horizontalHinge(471.8f, 472.2f)))
        assertEquals(471.8f, hinge!!.startDp, 0.001f)
        assertEquals(472.2f, hinge.endDp, 0.001f)
        assertEquals(0.4f, hinge.widthDp, 0.001f)
    }

    @Test
    fun `多条水平铰链取最靠顶的一条`() {
        // 顺序故意打乱：设备上报顺序没有约定，实现里排过序。
        val hinge = FoldPosture.horizontalSplitHinge(
            listOf(horizontalHinge(700f, 701f), horizontalHinge(472f, 473f))
        )
        assertEquals(472f, hinge!!.startDp, 0.001f)
    }
}
