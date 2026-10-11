package com.jetdo.android.common.layout

/**
 * 一条折叠特征的**最小投影**：只留「铺平 / 半开」「折痕方向」「是否把屏切成两块」以及
 * 「折痕/铰链在哪」这几件事。
 *
 * 为什么不直接拿 `androidx.window.layout.FoldingFeature` 当入参：那个类继承自 `DisplayFeature`，
 * 字段里带 `android.graphics.Rect`，在纯 JVM 单测里一构造就抛 "Stub!"，判定逻辑就钉不进单测。
 * 抽成这层投影之后 [FoldPosture] 是 100% 纯 Kotlin，`Rect → dp` 的密度换算只发生在 Composable
 * 那一步（见 `rememberFoldingFeatures`）。
 *
 * 与官方的对应关系：
 * - [FoldState] ← `FoldingFeature.State`（FLAT / HALF_OPENED）
 * - [FoldOrientation] ← `FoldingFeature.Orientation`（VERTICAL / HORIZONTAL）
 * - [isSeparating] ← `FoldingFeature.isSeparating`
 * - [boundsDp] ← `FoldingFeature.bounds`（官方给的是**窗口坐标系**下的像素，这里已折成 dp）
 */
data class FoldFeature(
    val state: FoldState,
    val orientation: FoldOrientation,
    /**
     * 是否把显示区切成两块。官方注解：`HALF_OPENED` **必然**为 true；双屏设备跨屏时也恒为 true。
     * 默认 false 是保守取值 —— 拿不准时当作「没切分」，分栏退回按窗口对半，不会压到铰链上。
     */
    val isSeparating: Boolean = false,
    /** 折痕/铰链在窗口坐标系中的位置（dp）。设备没上报 bounds 时为 null。 */
    val boundsDp: FoldBounds? = null
)

/** 折痕/铰链矩形，单位 dp，坐标系原点在**窗口**左上角（不是屏幕）。 */
data class FoldBounds(
    val leftDp: Float,
    val topDp: Float,
    val rightDp: Float,
    val bottomDp: Float
)

/**
 * 一条**竖直**折痕/铰链的横向占用区间（dp）。左栏该多宽、要不要给铰链留白都从它算出来。
 */
data class FoldHingeSpan(
    val startDp: Float,
    val endDp: Float
) {
    /** 铰链自身占的物理宽度。可折叠屏的折痕是 1px 级的线，这里换算后接近于 0。 */
    val widthDp: Float get() = (endDp - startDp).coerceAtLeast(0f)
}

/**
 * 折叠状态。
 *
 * 官方只有这两档，没有「已合上」——合上时那条屏根本不显示，也就不会有折叠特征上报，
 * 列表自然为空。
 */
enum class FoldState {
    /** 完全铺平。`FoldingFeature.State.FLAT`。 */
    FLAT,

    /** 半开，介于全开与全合之间。`FoldingFeature.State.HALF_OPENED`。 */
    HALF_OPENED
}

/**
 * 折痕方向。
 *
 * 容易读反的一点：它描述的是**折痕/铰链自身**的方向，不是设备朝向。官方原文是
 * 「HALF_OPENED 时有两种姿态，取决于折痕方向 —— 桌面姿态（水平折痕）与图书姿态（垂直折痕）」。
 */
enum class FoldOrientation {
    /** 折痕竖直，屏幕左右开合 —— 半开时即**图书姿态**。 */
    VERTICAL,

    /** 折痕水平，屏幕上下开合 —— 半开时即**桌面姿态**。 */
    HORIZONTAL
}

/**
 * 折叠姿态 → 布局决策的纯函数。
 *
 * 两个判定各管一件事，别混用：
 * - [isUnfolded] 管「要不要分栏 / 左栏上限要不要豁免」；
 * - [verticalSplitHinge] 管「分栏线画在哪、铰链要不要留白」（缺口 C）。
 *
 * 它们**都不是** `isWideLayout` 的输入 —— 分不分栏由窗口尺寸决定，理由见
 * [WindowBreakpoints.isWideLayout] 的 KDoc。
 */
object FoldPosture {

    /** 当前窗口是否处于「内屏铺开」形态。非折叠设备传空列表，恒 false。 */
    fun isUnfolded(features: List<FoldFeature>): Boolean = features.any { feature ->
        when (feature.state) {
            FoldState.FLAT -> true
            FoldState.HALF_OPENED -> feature.orientation == FoldOrientation.VERTICAL
        }
    }

    /**
     * 当前窗口是否处于**图书姿态**（官方叫 book posture）：竖直折痕 + 半开。
     *
     * 与 [isUnfolded] 的差别就在 `FLAT` 这一支：[isUnfolded] 把「铺平的内屏」也算展开
     * （那是**一个**连续的大屏，适合并排两栏），而图书姿态要的是「**两个**被折痕分开的半屏」——
     * 铺平时没有折痕分隔，左右两页之间就失去物理依据，只是把一块屏幕人为锯成两半。
     *
     * 三折叠永远进不来这里：官方明确它不产生 `HALF_OPENED`，只走尺寸阶梯。
     */
    fun isBookPosture(features: List<FoldFeature>): Boolean = features.any { feature ->
        feature.state == FoldState.HALF_OPENED && feature.orientation == FoldOrientation.VERTICAL
    }

    /**
     * 当前窗口是否处于**桌面姿态**（官方叫 tabletop posture）：水平折痕 + 半开。
     *
     * 与 [isBookPosture] 完全对称，只是折痕方向换成 HORIZONTAL —— 屏幕被切成**上下**两块，
     * 下半屏（铰链靠桌面那一半）正是手最方便够到的区域。官方对媒体类应用在桌面姿态下的
     * 指导就是「内容在上半屏、控件在下半屏」，消费点见 `VideoPlayer` 的桌面分支。
     *
     * 不必另查 `supportedPostures`：那是旧版 Window API 路线的前置检查；本工程走
     * material3-adaptive 的真实折叠特征 —— 设备**报出**「半开 + 水平折痕」本身就意味着
     * 它支持并正在桌面姿态，没有可额外过滤的假阳性。
     */
    fun isTabletopPosture(features: List<FoldFeature>): Boolean = features.any { feature ->
        feature.state == FoldState.HALF_OPENED && feature.orientation == FoldOrientation.HORIZONTAL
    }

    /**
     * 当前生效的**水平分隔铰链**：拿它当上下分区的分界线。
     *
     * 返回值的坐标语义随方向翻转：[FoldHingeSpan.startDp] 是铰链**顶缘**、[endDp] 是**底缘**
     * （窗口坐标系，dp）——「垂直于折痕方向的占用区间」在水平折痕下就是纵向区间。
     * 筛选条件与 [verticalSplitHinge] 同构：必须 HORIZONTAL、必须分隔、必须上报了位置。
     */
    fun horizontalSplitHinge(features: List<FoldFeature>): FoldHingeSpan? {
        // 先按 bounds 顶缘排序再取首个：设备上报的顺序没有约定，不排序就变成「看运气」。
        var best: FoldHingeSpan? = null
        features.forEach { feature ->
            if (feature.orientation != FoldOrientation.HORIZONTAL) return@forEach
            if (!feature.isSeparating) return@forEach
            val bounds = feature.boundsDp ?: return@forEach
            val span = FoldHingeSpan(startDp = bounds.topDp, endDp = bounds.bottomDp)
            val current = best
            if (current == null || span.startDp < current.startDp) best = span
        }
        return best
    }

    /**
     * 当前生效的**竖直分隔铰链**：拿它当左右分栏的分界线。
     *
     * 三条筛选条件，缺一不可：
     * - [FoldOrientation.VERTICAL]：水平折痕是桌面姿态，它切的是**上下**，与左右分栏无关；
     * - [FoldFeature.isSeparating]：不切分屏的折痕（如某些设备把折痕报成非分隔）压上去没有意义；
     * - `boundsDp != null`：设备必须真的报了位置，否则 [WindowBreakpoints.splitGeometry]
     *   无从对齐，会退回对半。
     *
     * ⚠️ **三折叠只取第一条铰链**：官方明确 `hingeList` 是**列表**（双折 1 个、三折 2 个），
     * 三折叠展开后左右会有两条折痕。当前布局只有「左栏 + 右栏」两栏，第二条铰链没有对应的
     * 第三栏去承接，所以取最靠左的那条 —— 结果仍是「内容不压在左侧铰链上」，但右侧那条
     * 目前不避让。做三栏布局时这里要改成返回列表。三折叠机型暂无可验证的靶子，如实记在
     * `docs/adaptive-devices.md` 缺口表里。
     */
    fun verticalSplitHinge(features: List<FoldFeature>): FoldHingeSpan? {
        // 先按 bounds 左缘排序再取首个：设备上报的顺序没有约定，不排序就变成「看运气」。
        var best: FoldHingeSpan? = null
        features.forEach { feature ->
            if (feature.orientation != FoldOrientation.VERTICAL) return@forEach
            if (!feature.isSeparating) return@forEach
            val bounds = feature.boundsDp ?: return@forEach
            val span = FoldHingeSpan(startDp = bounds.leftDp, endDp = bounds.rightDp)
            val current = best
            if (current == null || span.startDp < current.startDp) best = span
        }
        return best
    }
}
