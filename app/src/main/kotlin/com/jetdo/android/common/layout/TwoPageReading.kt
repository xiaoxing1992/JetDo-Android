package com.jetdo.android.common.layout

/**
 * 「双页阅读」该不该生效。
 *
 * 判定收在这一处，别散到调用点去 —— 它同时要满足「默认关时不跑任何逻辑」和
 * 「分栏后不能再按窗口宽度二次分栏」两个约束。
 */
object TwoPageReading {

    /**
     * 能摊开两页所需的**最小 pane 宽度**（dp）。
     *
     * 为什么量的是 pane 而不是窗口：平板横屏分栏时，详情页只占右栏 —— 1280dp 的窗口里它可能
     * 只有 800dp，而折叠屏展开后若外部已经分了栏，它更是只剩一半（实测 838×945dp 的折叠 AVD，
     * 详情页在右栏只有 419dp）。这时再按窗口宽度判「够宽」，就会在一栏里硬塞两页，每页不到
     * 210dp，正文读不成句。**必须在详情页自己那一层量。**
     *
     * 取 600（官方 medium 宽度下界）：对半后每页 300dp，是正文还能成句的底线。
     */
    const val MIN_PANE_WIDTH_DP: Float = WindowBreakpoints.WIDTH_DP_MEDIUM_LOWER_BOUND

    /**
     * @param enabled 用户在「设置 → 实验性功能」里的开关。**关着时恒 false** —— 这是这个特性
     *   敢默认关的前提，也是它不打扰绝大多数用户（手机、平板、三折叠都进不了这个形态）的保证。
     * @param bookPosture 窗口处于图书姿态（竖直折痕 + 半开）。语义上的正主：左右两页正对两块
     *   物理半屏，折痕就是书脊。为真时不再看宽度 —— 图书姿态下外层会强制单栏（见
     *   [WideLayoutHost] 的 `forceSinglePane`），整屏交给详情页去分两页，宽度天然够。
     * @param paneWidthDp 详情页**自身**的可用宽度（dp）。非图书姿态时靠它决定要不要摊开两页。
     */
    fun isActive(enabled: Boolean, bookPosture: Boolean, paneWidthDp: Float): Boolean =
        enabled && (bookPosture || paneWidthDp >= MIN_PANE_WIDTH_DP)
}
