package com.jetdo.android.common.layout

import kotlin.math.round

/**
 * 窗口宽度尺寸类别（官方 5 档）。
 *
 * 老教程里常见的是 compact / medium / expanded **三档**，当前官方定义已经扩到 5 档，多出来的
 * 「大 / 超大」是给大型平板与桌面显示器准备的。我方判定仍然只用 840（expanded 下界）这一刀，
 * 但左栏宽度上限、以及将来「列表多列 / 三栏」的分组参数要按档给，所以先按官方口径把档位落全。
 *
 * 定义：描述的是**应用窗口**而不是设备。同一个物理设备在分屏、桌面窗口化、折叠/展开之后
 * 可以落在不同档位，所以不能用 `isTablet` 这类设备逻辑替代它。
 */
enum class WindowWidthSizeClass {
    /** 宽 < 600dp：手机竖屏。 */
    COMPACT,

    /** 600 ≤ 宽 < 840dp：纵向平板、大多数纵向大折叠内屏。 */
    MEDIUM,

    /** 840 ≤ 宽 < 1200dp：横向平板、大多数横向大折叠内屏。 */
    EXPANDED,

    /** 1200 ≤ 宽 < 1600dp：大型平板。 */
    LARGE,

    /** 宽 ≥ 1600dp：桌面显示器。 */
    EXTRA_LARGE
}

/**
 * 窗口高度尺寸类别（官方 3 档）。
 *
 * 当前只用来喂高度闸门。注意 [WindowHeightSizeClass.COMPACT] 的上界就是
 * [WindowBreakpoints.MIN_SPLIT_HEIGHT_DP]，两者是同一个数，别各改各的。
 */
enum class WindowHeightSizeClass {
    /** 高 < 480dp：99.78% 的横向手机。 */
    COMPACT,

    /** 480 ≤ 高 < 900dp：横向平板、纵向手机。 */
    MEDIUM,

    /** 高 ≥ 900dp：纵向平板。 */
    EXPANDED
}

/**
 * 窗口断点判定：当前窗口适不适合双栏（对齐鸿蒙 `common/utils/BreakpointManager.ets`）。
 *
 * 全部是纯函数，不碰 Context / Composition / Configuration —— 真机上「平板竖屏、折叠屏内屏、
 * 左右分屏、阔折叠竖持」这几种形态极难凑齐，抽成纯函数才能用 JVM 单测把每种机型都钉住。
 *
 * 判定顺序：先过 [MIN_SPLIT_HEIGHT_DP] 高度闸门，再看下面两条路任一成立即宽屏：
 *  ① 够宽（≥ [WIDE_THRESHOLD_DP]）**且横着**（宽 > 高）—— 平板横屏。
 *     只看宽度不够：大屏平板竖屏宽度就有 965dp，光凭阈值同样会被判成宽屏、竖屏照样分栏；
 *     单纯抬高阈值又会误伤窄的平板横屏。加上长宽比这一刀，「竖屏一律单栏」才与设备无关地成立，
 *     左右分屏（窗口只剩半宽）也自然落回单栏。
 *  ② **近方形的大窗口**（宽 ≥ [SQUARE_WIDE_THRESHOLD_DP]）—— 折叠屏内屏就是长这样的。
 *     它竖着拿也该双栏，①的长宽比这一刀对它不适用。近方形这个限制是为了把阔折叠竖持与
 *     平板竖屏挡在外面，见 [SQUARE_WIDE_RATIO_MAX]。
 *
 * ⚠️ **判定里刻意不出现折叠信号**（历史版本有过）：折叠屏展开后的内屏本身就是「一块近方形
 * 的大窗口」，尺寸已足够说明问题；反过来把分栏挂在 `FoldingFeature` 上会踩一个实测到的坑 ——
 * 部分设备/模拟器在**完全展开**时上报的是空列表（见 `docs/adaptive-devices.md` §5.2），
 * 一旦如此，838×945dp 的内屏就退化成单栏。折叠信号真正还有用的地方在两处：
 * 左栏宽度的上限豁免（[splitGeometry]）与将来的 Tier 1 姿态适配。
 */
object WindowBreakpoints {

    /**
     * 双栏所需的最小窗口高度（dp）。
     *
     * 数值取官方窗口尺寸类别里「紧凑高度」的上界（`WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND`）。
     * 官方文档在尺寸类别一节专门点出了这个场景：
     *
     * > 对于手机或横向折叠设备等场景，也请考虑高度窗口尺寸类别；在这些情况下，窗口宽度通常为
     * > 中等，但窗口高度为紧凑，此时双窗格布局并不实用。
     *
     * 横屏手机正落在这里：Pixel 8 Pro 横屏窗口约 997×448，宽度早就越过 [WIDE_THRESHOLD_DP]，
     * 可高度不足 480dp，并排两栏后每栏只剩一条窄缝，正文列读不成句。
     * 加这道闸门之前，它会被稳定判成双栏。
     */
    const val MIN_SPLIT_HEIGHT_DP: Float = 480f

    /**
     * 双栏阈值（dp）。取 840：
     * - 手机竖屏（≈360）→ 够不着，单栏；
     * - 手机横屏（780 ~ 1030）→ 宽度确实过线，但高度不足 [MIN_SPLIT_HEIGHT_DP]，被闸门挡下；
     * - 平板横屏（1024 ~ 1440）→ 宽屏，双栏。
     *
     * 这个数同时是 [WindowWidthSizeClass.EXPANDED] 的下界，别单独改。
     */
    const val WIDE_THRESHOLD_DP: Float = 840f

    /**
     * 「近方形的大窗口」宽度下界（dp），即第二路径的门槛。
     *
     * 取 600（官方 medium 宽度下界）：直板折叠（Flip）展开后内屏仍是 ≈360 的窄屏，够不着这个数，
     * 不会被误判成双栏。
     */
    const val SQUARE_WIDE_THRESHOLD_DP: Float = 600f

    /**
     * 「近方形」的上限（长边 / 短边）。第二路径敢不看长宽比，前提是这块屏接近正方形
     * （如竖折叠内屏 741×832，比值 1.12）—— 竖着拿也有足够横向空间。
     *
     * 不满足这个前提的两种形态都被它按回单栏：
     * - 阔折叠内屏竖持（939×664 转过来是 664×939，比值 1.41）：形状已接近平板竖屏，若因
     *   664 ≥ 600 判成双栏，就会出现「同样 664dp 宽，平板判单栏、阔折叠判双栏」的自相矛盾，
     *   且左右各 332dp 反而挤了阅读；
     * - 平板/桌面竖屏（965×1300、800×1280，比值 1.35 ~ 1.6）。
     *
     * 取 1.2 卡在 1.12 与 1.41 之间。
     */
    const val SQUARE_WIDE_RATIO_MAX: Float = 1.2f

    /** 官方 medium 宽度下界。 */
    const val WIDTH_DP_MEDIUM_LOWER_BOUND: Float = 600f

    /** 官方 large 宽度下界（大型平板）。 */
    const val WIDTH_DP_LARGE_LOWER_BOUND: Float = 1200f

    /** 官方 extra-large 宽度下界（桌面显示器）。 */
    const val WIDTH_DP_EXTRA_LARGE_LOWER_BOUND: Float = 1600f

    /** 官方 expanded 高度下界（纵向平板）。 */
    const val HEIGHT_DP_EXPANDED_LOWER_BOUND: Float = 900f

    /** 双栏左栏占窗口宽的比例。对半分，分割线正好落在竖折叠内屏的折痕上。 */
    const val SPLIT_NAV_BAR_RATIO: Float = 0.5f

    /** 左栏下限须 ≥ 分区抽屉宽度（288dp），否则抽屉滑出会溢进右栏，留点余量取 320。 */
    const val SPLIT_NAV_BAR_MIN_WIDTH_DP: Float = 320f

    /**
     * 左栏上限（dp），**expanded 档（840 ~ 1200）与更窄的档位共用**。
     *
     * 平板横屏 1138dp 对半是 569，右栏读正文用不了这么宽的目录，封到 420。
     *
     * 折叠屏展开态豁免这条上限：内屏是对折出来的，折痕必在几何中线，左栏取满一半分割线才压在
     * 折痕上——内屏 741dp 时算出 370，本来就没触顶；阔折叠（展开横持 939dp）对半是 470，
     * 会被 420 夹住，分割线偏离折痕约 50dp，正是这条豁免要救的场景。
     * 豁免的实现在 [maxNavBarWidthDp]。
     */
    const val SPLIT_NAV_BAR_MAX_WIDTH_DP: Float = 420f

    /**
     * 左栏上限（dp）在 [WindowWidthSizeClass.LARGE] 档（1200 ~ 1600）取的值。
     *
     * 与 expanded 档同用一个 420 会让 1280dp 的平板显得左栏偏窄（列表卡片挤成一条），
     * 而这一档的常见机型就是 10.5" ~ 12" 平板，属于主力大屏形态，值得单给一档。
     * 480 大约是「一个宽导航抽屉 + 两行卡片标题」的舒适宽度，对半后右栏仍有 800dp。
     */
    const val SPLIT_NAV_BAR_MAX_WIDTH_LARGE_DP: Float = 480f

    /**
     * 左栏上限（dp）在 [WindowWidthSizeClass.EXTRA_LARGE] 档（≥ 1600）取的值。
     *
     * 这一档是桌面显示器与展开后的大屏，对半会有 800dp 以上的列表栏，远超列表本身的需要
     * —— 官方 Tier 2 明确要求「不要让内容拉伸成全宽」。560 已经比多数平板整屏还宽，到此为止。
     */
    const val SPLIT_NAV_BAR_MAX_WIDTH_EXTRA_LARGE_DP: Float = 560f

    /**
     * 分栏后右栏的最小宽度（dp）。
     *
     * 只在**按铰链分栏**时用于否决：铰链若离左缘太近（左栏放不下）或离右缘太近（右栏被挤成
     * 一条缝），就放弃对齐铰链、退回对半。360 是常用的一条「能读」的底线，与左栏下限 320 对称。
     */
    const val CONTENT_PANE_MIN_WIDTH_DP: Float = 360f

    /**
     * 纯函数版判定：给定窗口尺寸（dp），算「当前窗口是否适合双栏」。
     *
     * @param widthDp 窗口可用宽度；首帧之前或异常回调可能喂进 0 或负数，由 [isNearSquare] 兜住。
     * @param heightDp 窗口可用高度；首帧为 0 时被高度闸门判否。
     */
    fun isWideLayout(widthDp: Float, heightDp: Float): Boolean {
        // 高度闸门是前置条件，对下面两条路径一起生效：官方把「高度紧凑」直接归为双窗格不适用，
        // 与宽度是从哪条路径来的无关。首帧 heightDp 可能是 0，同样落进这里判否。
        if (heightDp < MIN_SPLIT_HEIGHT_DP) return false

        val landscapeWide = widthDp >= WIDE_THRESHOLD_DP && widthDp > heightDp
        val squareWide = widthDp >= SQUARE_WIDE_THRESHOLD_DP && isNearSquare(widthDp, heightDp)
        return landscapeWide || squareWide
    }

    /** 窗口宽度落在哪一档。断点取官方值，见各常量的 KDoc。 */
    fun widthSizeClassOf(widthDp: Float): WindowWidthSizeClass = when {
        widthDp >= WIDTH_DP_EXTRA_LARGE_LOWER_BOUND -> WindowWidthSizeClass.EXTRA_LARGE
        widthDp >= WIDTH_DP_LARGE_LOWER_BOUND -> WindowWidthSizeClass.LARGE
        widthDp >= WIDE_THRESHOLD_DP -> WindowWidthSizeClass.EXPANDED
        widthDp >= WIDTH_DP_MEDIUM_LOWER_BOUND -> WindowWidthSizeClass.MEDIUM
        else -> WindowWidthSizeClass.COMPACT
    }

    /** 窗口高度落在哪一档。 */
    fun heightSizeClassOf(heightDp: Float): WindowHeightSizeClass = when {
        heightDp >= HEIGHT_DP_EXPANDED_LOWER_BOUND -> WindowHeightSizeClass.EXPANDED
        heightDp >= MIN_SPLIT_HEIGHT_DP -> WindowHeightSizeClass.MEDIUM
        else -> WindowHeightSizeClass.COMPACT
    }

    /**
     * 纯函数版分栏几何：算左栏多宽、两栏之间要不要给铰链留白。
     *
     * 优先级是「能对齐铰链就对齐」：
     * 1. 有**竖直且分隔**的铰链时（图书姿态半开、双屏设备），分栏线压到铰链左缘 —— 左栏正好
     *    到铰链为止，[SplitGeometry.hingeGapWidthDp] 留出铰链本身的物理宽度，右栏从铰链右缘起。
     *    这是唯一能让内容不被铰链切断的做法；铰链位置由设备上报，不必假设它在中线。
     * 2. 否则（直板、平板、桌面窗口，或铰链位置不合适）退回「对半 + 夹取」。
     *
     * @param unfolded 折叠屏是否处于「内屏铺开」形态。展开态豁免左栏上限，分割线才压得住折痕。
     * @param hinge 当前生效的竖直铰链；非折叠设备 / 桌面姿态传 null，见 [FoldPosture.verticalSplitHinge]。
     */
    fun splitGeometry(
        widthDp: Float,
        unfolded: Boolean,
        hinge: FoldHingeSpan? = null
    ): SplitGeometry {
        val maxNavBarWidth = maxNavBarWidthDp(widthDp, unfolded)

        if (hinge != null &&
            hinge.startDp >= SPLIT_NAV_BAR_MIN_WIDTH_DP &&
            hinge.startDp <= maxNavBarWidth &&
            widthDp - hinge.endDp >= CONTENT_PANE_MIN_WIDTH_DP
        ) {
            return SplitGeometry(navBarWidthDp = hinge.startDp, hingeGapWidthDp = hinge.widthDp)
        }

        // 先四舍五入再夹：不 round 的话 939 × 0.5 = 469.5 会原样带进布局，
        // 亚像素宽度在分割线上表现为时粗时细的一条缝（对齐鸿蒙的 Math.round）。
        val half = maxOf(round(widthDp * SPLIT_NAV_BAR_RATIO), SPLIT_NAV_BAR_MIN_WIDTH_DP)
        return SplitGeometry(navBarWidthDp = minOf(half, maxNavBarWidth), hingeGapWidthDp = 0f)
    }

    /**
     * 左栏宽度上限（dp），按 [WindowWidthSizeClass] 分档。
     *
     * 展开态返回 [Float.MAX_VALUE]，也就是「不封顶」—— 这不是偷懒，而是语义：折叠屏内屏的
     * 折痕必在中线，左栏一旦被封顶，分割线就偏离折痕，右栏内容会压在折痕上。豁免之后左栏取满
     * 对半（仍受左栏下限约束），见 [splitGeometry] 第二条路径。
     */
    private fun maxNavBarWidthDp(widthDp: Float, unfolded: Boolean): Float {
        if (unfolded) return Float.MAX_VALUE
        return when (widthSizeClassOf(widthDp)) {
            WindowWidthSizeClass.EXTRA_LARGE -> SPLIT_NAV_BAR_MAX_WIDTH_EXTRA_LARGE_DP
            WindowWidthSizeClass.LARGE -> SPLIT_NAV_BAR_MAX_WIDTH_LARGE_DP
            else -> SPLIT_NAV_BAR_MAX_WIDTH_DP
        }
    }

    /** 长边 / 短边是否落在近方形范围内。尺寸非法（≤0）时判否，顺带避开除零。 */
    internal fun isNearSquare(widthDp: Float, heightDp: Float): Boolean {
        val shortSide = minOf(widthDp, heightDp)
        val longSide = maxOf(widthDp, heightDp)
        if (shortSide <= 0f) return false
        return longSide / shortSide <= SQUARE_WIDE_RATIO_MAX
    }
}

/**
 * 一次分栏的几何结果（纯 float，不带 Compose 类型，便于单测）。
 *
 * @param navBarWidthDp 左栏宽度。
 * @param hingeGapWidthDp 左右两栏之间要空出来的宽度。只有存在**真占物理宽度**的铰链时才非零
 *   （Surface Duo 那类双屏设备）；可折叠屏的折痕只是一条线，通常只有 0 ~ 1px，四舍五入后近乎为 0。
 */
data class SplitGeometry(
    val navBarWidthDp: Float,
    val hingeGapWidthDp: Float
)
