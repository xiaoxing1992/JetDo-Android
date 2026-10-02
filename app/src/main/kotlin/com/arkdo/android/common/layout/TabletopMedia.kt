package com.arkdo.android.common.layout

/**
 * 桌面姿态下的**媒体上下分区**几何计算（纯函数，供单测覆盖）。
 *
 * 官方对 tabletop 姿态里媒体类应用的指导是「内容在上半屏、控件在下半屏」，分界线是那条
 * 水平铰链。与双页阅读不同，内联视频卡在**滚动列表里**、铰链在**窗口坐标系里** —— 两者的
 * 相对位置随滚动一直变，所以切点必须按「卡片在窗口里的实时位置 ∩ 铰链位置」现场求交：
 * 铰链划过卡片时上下分区、划不进卡片时退回普通布局。
 *
 * 数据来源：卡片纵坐标/高度来自 `onGloballyPositioned`（窗口坐标系），铰链中心来自
 * [LocalTabletopHingeCenter]（同一坐标系，由 [WideLayoutHost] 下发）。
 */
object TabletopMedia {

    /**
     * 分区点占卡片高度的最小比例。铰链贴近卡片顶缘时视频区只剩一条缝，没有观看价值；
     * 压到这个下限以下就不再分区（返回 null 退回普通布局），宁可不贴铰链也不要不可用。
     */
    const val MIN_FRACTION: Float = 0.35f

    /**
     * 分区点占卡片高度的最大比例。铰链贴近卡片底缘时控件区会被挤没 —— 控件是桌面姿态
     * 上下分区的全部意义（下半屏是手最方便够到的区域），同样保底。
     */
    const val MAX_FRACTION: Float = 0.75f

    /**
     * 求上下分区的切点比例（= 视频区占卡片高度的比例），切点不可用时返回 null。
     *
     * @param cardTopYDp 卡片顶缘在窗口坐标系里的纵坐标（dp）。尚未量到（首帧）传 NaN。
     * @param cardHeightDp 卡片自身高度（dp）。
     * @param hingeCenterYDp 水平铰链中心在窗口坐标系里的纵坐标（dp）。
     * @return 视频区比例，范围 [MIN_FRACTION, MAX_FRACTION]；铰链不划过卡片或输入不可用时 null。
     */
    fun splitFraction(cardTopYDp: Float, cardHeightDp: Float, hingeCenterYDp: Float): Float? {
        if (cardHeightDp <= 0f) return null
        if (cardTopYDp.isNaN()) return null
        val raw = (hingeCenterYDp - cardTopYDp) / cardHeightDp
        // 铰链在卡片上方（raw ≤ 0）或下方（raw ≥ 1）→ 这张卡片与铰链不相交，
        // 保持普通布局。滚动中每帧重算，划进/划出会自然切换，无需额外状态。
        if (raw <= 0f || raw >= 1f) return null
        // 铰链贴边时 clamp 会偏离真实位置几十 dp —— 这是刻意取舍：分区两侧各自保住可用
        // 面积，比严格贴铰链但只剩一条缝更符合「上下分区」的本意。
        return raw.coerceIn(MIN_FRACTION, MAX_FRACTION)
    }
}
