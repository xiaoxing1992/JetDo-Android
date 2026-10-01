package com.arkdo.android.common.layout

import kotlin.math.round

/**
 * 窗口断点判定：当前窗口适不适合双栏（对齐鸿蒙 `common/utils/BreakpointManager.ets`）。
 *
 * 全部是纯函数，不碰 Context / Composition / Configuration —— 真机上「平板竖屏、折叠屏内屏、
 * 左右分屏、阔折叠竖持」这几种形态极难凑齐，抽成纯函数才能用 JVM 单测把每种机型都钉住。
 *
 * 两条路任一成立即宽屏：
 *  ① 够宽（≥ [WIDE_THRESHOLD_DP]）**且横着**（宽 > 高）—— 平板横屏。
 *     只看宽度不够：大屏平板竖屏宽度就有 965dp，光凭阈值同样会被判成宽屏、竖屏照样分栏；
 *     单纯抬高阈值又会误伤窄的平板横屏。加上长宽比这一刀，「竖屏一律单栏」才与设备无关地成立，
 *     左右分屏（窗口只剩半宽）也自然落回单栏。
 *  ② 折叠屏展开、宽度 ≥ [UNFOLDED_WIDE_THRESHOLD_DP] **且**内屏近方形 —— 竖折叠内屏
 *     （741×832）竖着拿也该双栏，①的长宽比这一刀对它不适用。近方形这个限制是为了把阔折叠
 *     竖持挡在外面，见 [UNFOLDED_SQUARE_RATIO_MAX]。
 */
object WindowBreakpoints {

    /**
     * 双栏阈值（dp）。取 840：
     * - 手机（≈360，横屏也极少超 840）→ 非宽屏，单栏，与现状一致；
     * - 平板横屏（1138 ~ 1440）→ 宽屏，双栏。
     */
    const val WIDE_THRESHOLD_DP: Float = 840f

    /**
     * 折叠屏展开态的双栏阈值（dp）。内屏近似方形，竖着拿也要双栏，故不看长宽比，只用宽度兜底：
     * 直板折叠（Flip）展开后仍是 ≈360 的窄屏，低于此值，不会被误判成双栏。
     */
    const val UNFOLDED_WIDE_THRESHOLD_DP: Float = 600f

    /**
     * 折叠屏展开态「近方形」上限（长边 / 短边）。展开态那条兜底敢不看长宽比，前提是内屏接近
     * 正方形（如 741×832，比值 1.12）——竖着拿也有足够横向空间。阔折叠展开态（939×664，
     * 比值 1.41）不满足这个前提：竖持后形状已接近平板竖屏，再走兜底就会出现「同样 664dp 宽，
     * 平板判单栏、阔折叠判双栏」的自相矛盾，且左右各 332dp 反而挤了阅读。
     * 取 1.2 卡在 1.12 与 1.41 之间。
     */
    const val UNFOLDED_SQUARE_RATIO_MAX: Float = 1.2f

    /** 双栏左栏占窗口宽的比例。对半分，分割线正好落在竖折叠内屏的折痕上。 */
    const val SPLIT_NAV_BAR_RATIO: Float = 0.5f

    /** 左栏下限须 ≥ 分区抽屉宽度（288dp），否则抽屉滑出会溢进右栏，留点余量取 320。 */
    const val SPLIT_NAV_BAR_MIN_WIDTH_DP: Float = 320f

    /**
     * 左栏上限（dp），**只对非折叠设备生效**。平板横屏 1440dp 对半是 720，右栏读正文用不了
     * 这么宽的目录，封到 420。
     *
     * 折叠屏展开态豁免这条上限：内屏是对折出来的，折痕必在几何中线，左栏取满一半分割线才压在
     * 折痕上——内屏 741dp 时算出 370，本来就没触顶；阔折叠（展开横持 939dp）对半是 470，
     * 会被 420 夹住，分割线偏离折痕约 50dp，正是这条豁免要救的场景。
     */
    const val SPLIT_NAV_BAR_MAX_WIDTH_DP: Float = 420f

    /**
     * 纯函数版判定：给定窗口尺寸（dp）与折叠屏是否展开，算「当前窗口是否适合双栏」。
     *
     * @param widthDp 窗口可用宽度；首帧之前或异常回调可能喂进 0 或负数，由 [isNearSquare] 兜住。
     * @param unfolded 折叠屏是否处于展开态（半折的书本形态同样按展开算）。非折叠设备恒 false。
     */
    fun isWideLayout(widthDp: Float, heightDp: Float, unfolded: Boolean): Boolean {
        val landscapeWide = widthDp >= WIDE_THRESHOLD_DP && widthDp > heightDp
        val unfoldedWide = unfolded &&
            widthDp >= UNFOLDED_WIDE_THRESHOLD_DP &&
            isNearSquare(widthDp, heightDp)
        return landscapeWide || unfoldedWide
    }

    /**
     * 纯函数版左栏宽：窗口宽的一半，夹进 [[SPLIT_NAV_BAR_MIN_WIDTH_DP], [SPLIT_NAV_BAR_MAX_WIDTH_DP]]；
     * 折叠屏展开态不封上限，让分割线压在折痕上。理由与已知不足见 [SPLIT_NAV_BAR_MAX_WIDTH_DP]。
     */
    fun splitNavBarWidth(widthDp: Float, unfolded: Boolean): Float {
        // 先四舍五入再夹：不 round 的话 939 × 0.5 = 469.5 会原样带进布局，
        // 亚像素宽度在分割线上表现为时粗时细的一条缝（对齐鸿蒙的 Math.round）。
        val half = maxOf(round(widthDp * SPLIT_NAV_BAR_RATIO), SPLIT_NAV_BAR_MIN_WIDTH_DP)
        return if (unfolded) half else minOf(half, SPLIT_NAV_BAR_MAX_WIDTH_DP)
    }

    /** 长边 / 短边是否落在近方形范围内。尺寸非法（≤0）时判否，顺带避开除零。 */
    internal fun isNearSquare(widthDp: Float, heightDp: Float): Boolean {
        val shortSide = minOf(widthDp, heightDp)
        val longSide = maxOf(widthDp, heightDp)
        if (shortSide <= 0f) return false
        return longSide / shortSide <= UNFOLDED_SQUARE_RATIO_MAX
    }
}
