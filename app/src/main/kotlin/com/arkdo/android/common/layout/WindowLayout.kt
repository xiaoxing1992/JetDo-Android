package com.arkdo.android.common.layout

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.collectFoldingFeaturesAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import timber.log.Timber

/**
 * 当前窗口是不是双栏（宽屏）布局。
 *
 * 由 [WideLayoutHost] 在导航宿主根部下发一次，整棵树共用。之所以走 CompositionLocal 而不是
 * 逐层传参：读它的地方分散在很深的叶子里（如富文本图片的宽屏规则），沿途每一层都加一个
 * 布尔参数既啰嗦又容易漏。
 */
val LocalWideLayout = staticCompositionLocalOf { false }

/**
 * 图书姿态下**折痕（书脊）在窗口坐标系里的横向位置**（dp）。非图书姿态、或设备没上报位置时为 null。
 *
 * 存在的理由：折痕**不保证在中线上**。实测折叠 AVD 的铰链在 1148/2200 = 52.2%，而按窗口对半
 * 算出来是 50%，两者差 48px —— 双页阅读若照对半分栏，右页开头那一条内容正好压在铰链上。
 * 读它的地方（详情页双页版面）该用这个位置当分栏线，而不是拿总宽除以二。
 */
val LocalBookSpineCenter = staticCompositionLocalOf<Dp?> { null }

/**
 * 桌面姿态下**水平铰链在窗口坐标系里的纵向位置**（dp，铰链中心）。非桌面姿态、或设备没上报
 * 位置时为 null。
 *
 * 与 [LocalBookSpineCenter] 同构，方向换成纵向：桌面姿态下媒体类内容该「画面在上半屏、
 * 控件在下半屏」（官方对 tabletop 的指导），分界线就是这条铰链 —— 而**它不保证在窗口中线**，
 * 对半算和真实位置可能差几十像素。消费点（内联视频卡）拿自己量到的窗口纵坐标与这个值求交，
 * 算出上下分区的切点；设备没报位置时消费点整体退回原布局，不做「猜一个中线」的假适配。
 */
val LocalTabletopHingeCenter = staticCompositionLocalOf<Dp?> { null }

/**
 * 一次分栏的完整几何描述，由 [WideLayoutHost] 算好后交给导航宿主。
 *
 * @param isWide 当前是否双栏。窄屏时另外两个字段无意义（也不会被用到）。
 * @param navBarWidth 左栏宽度。
 * @param hingeGapWidth 左右两栏之间要空出来的宽度。只有存在**真占物理宽度**的铰链时才非零
 *   （Surface Duo 那类双屏设备）；可折叠屏的折痕是一条 1px 级的线，换算后接近于 0。
 *   调用点记得把这截空隙真的留出来，否则右栏内容会压在铰链上。
 */
data class SplitLayout(
    val isWide: Boolean,
    val navBarWidth: Dp,
    val hingeGapWidth: Dp
)

/**
 * 布局驱动的断点宿主：用**实际布局尺寸**算宽屏标志，而不是读 Configuration。
 *
 * 为什么不用 `LocalConfiguration.screenWidthDp`：那个值是「屏幕」宽度，左右分屏、自由窗口
 * 拖动时它并不等于本应用窗口的可用宽度，分屏下会误判成宽屏（真机上表现为半屏窗口里硬挤
 * 出双栏）。[BoxWithConstraints] 给的是父容器真正分下来的约束，旋转、分屏、折叠都会重新触发，
 * 与鸿蒙侧用 `onAreaChange` 喂尺寸是同一个思路。
 *
 * 折叠信号在本组件内部自行观察（[rememberFoldingFeatures]），**不再由调用点传入**：它只影响
 * 「左栏多宽、要不要给铰链留白」，与「分不分栏」无关（后者纯看窗口尺寸，理由见
 * [WindowBreakpoints.isWideLayout] 的 KDoc）。把它收进来之后，调用点少一个容易传错的参数，
 * 也避免出现「传了 false 却以为展开态已生效」这类静默退化。
 *
 * @param content 收到算好的 [SplitLayout] 后自行决定怎么摆。
 */
@Composable
fun WideLayoutHost(
    /**
     * 强制按单栏处理。
     *
     * 唯一的使用场景是「双页阅读 + 图书姿态」：那时整块屏要交给详情页自己去分左右两页，
     * 外层再分一次栏（列表 | 详情）会把详情页压进一个半屏里，双页就没地方放了。
     * 关着开关或不在图书姿态时恒为 false，主布局行为与改动前完全一致。
     */
    forceSinglePane: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable (SplitLayout) -> Unit
) {
    val features = rememberFoldingFeatures()
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val widthDp = maxWidth.value
        val heightDp = maxHeight.value
        val isWide = !forceSinglePane && WindowBreakpoints.isWideLayout(widthDp, heightDp)
        val geometry = WindowBreakpoints.splitGeometry(
            widthDp = widthDp,
            unfolded = FoldPosture.isUnfolded(features),
            hinge = FoldPosture.verticalSplitHinge(features)
        )
        // 书脊位置 = 竖直铰链的中心。**只在强制单栏时给**：那是详情页占满整块屏的唯一形态，
        // 这个坐标才等于详情页自己的坐标。分栏时详情页只占右栏，直接拿窗口坐标当页宽会错位。
        // 设备没报位置时留 null，读的人自己退回对半。
        val bookSpineCenter = if (forceSinglePane) {
            FoldPosture.verticalSplitHinge(features)?.let { ((it.startDp + it.endDp) / 2f).dp }
        } else {
            null
        }
        // 桌面铰链中心 = 水平铰链的纵向中心，**不做 forceSinglePane 门控**：桌面姿态下窗口
        // 只有一个内容区（上下分区不涉及左右分栏），窗口坐标对左右栏同样成立；消费点自己量
        // 卡片在窗口里的位置来求交，坐标基准始终一致。
        val tabletopHingeCenter = FoldPosture.horizontalSplitHinge(features)
            ?.let { ((it.startDp + it.endDp) / 2f).dp }
        CompositionLocalProvider(
            LocalWideLayout provides isWide,
            LocalBookSpineCenter provides bookSpineCenter,
            LocalTabletopHingeCenter provides tabletopHingeCenter
        ) {
            content(
                SplitLayout(
                    isWide = isWide,
                    navBarWidth = geometry.navBarWidthDp.dp,
                    hingeGapWidth = geometry.hingeGapWidthDp.dp
                )
            )
        }
    }
}

/**
 * 观察当前窗口的折叠特征，映射成纯 Kotlin 的 [FoldFeature] 列表。
 *
 * 数据源是官方的 `collectFoldingFeaturesAsState()`（`androidx.window` 的 `WindowInfoTracker`
 * 之上的 Compose 封装）：它把折叠/展开、姿态变化都变成一次重组，不用自己管 Flow 收集与
 * 生命周期。非折叠设备返回空列表 → 恒 false，与改动前的行为一致。
 *
 * 判定本身在纯函数 [FoldPosture] 里，这里只做映射，方便单测覆盖各形态。
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun rememberFoldingFeatures(): List<FoldFeature> {
    val raw by collectFoldingFeaturesAsState()
    // 官方给的 bounds 是窗口坐标系下的**像素**，纯函数里一律用 dp。
    val density = LocalDensity.current.density
    return remember(raw, density) {
        val features = raw.map { it.toFoldFeature(density) }
        // 折叠形态在真机上极难复现，把每次姿态变化的原始特征落进日志：
        // 现场反馈「展开后没变双栏」「分栏线没压在折痕上」时，能直接从「设置 → 诊断日志」
        // 还原设备当时报了什么（模拟器完全展开时报空列表就是这个缺口暴露出来的）。
        Timber.tag(FOLD_TAG).d(
            "folding features=%s → unfolded=%s, hinge=%s, tabletop=%s",
            raw,
            FoldPosture.isUnfolded(features),
            FoldPosture.verticalSplitHinge(features),
            FoldPosture.horizontalSplitHinge(features)
        )
        features
    }
}

/**
 * 当前窗口是否处于**图书姿态**（竖直折痕 + 半开）。非折叠设备恒 false。
 *
 * 与 [WideLayoutHost] 内部的展开态判定共用同一个数据源（[rememberFoldingFeatures]），
 * 不会出现两边读到不同姿态的情况。
 */
@Composable
fun rememberBookPosture(): Boolean {
    val features = rememberFoldingFeatures()
    return remember(features) { FoldPosture.isBookPosture(features) }
}

/**
 * 当前窗口是否处于**桌面姿态**（水平折痕 + 半开）。非折叠设备恒 false。
 *
 * 与 [rememberBookPosture] 共用同一个数据源（[rememberFoldingFeatures]），不会出现
 * 两边读到不同姿态的情况。消费点：`VideoPlayer` 的上下分区分支。
 */
@Composable
fun rememberTabletopPosture(): Boolean {
    val features = rememberFoldingFeatures()
    return remember(features) { FoldPosture.isTabletopPosture(features) }
}

private const val FOLD_TAG = "FoldPosture"

/**
 * 官方 [FoldingFeature] → [FoldFeature] 的映射（见 [FoldFeature] 里为什么要有这一层）。
 *
 * 两个 `when` 都留了 `else` 分支：这两个枚举来自 Java 侧，将来若新增取值，宁可退化成
 * 「非铺平 / 水平折痕」这种偏保守的判断，也不要让 `when` 抛 `NoWhenBranchMatchedException`
 * 把整个界面带崩。
 */
private fun FoldingFeature.toFoldFeature(density: Float): FoldFeature = FoldFeature(
    state = when (state) {
        FoldingFeature.State.FLAT -> FoldState.FLAT
        else -> FoldState.HALF_OPENED
    },
    orientation = when (orientation) {
        FoldingFeature.Orientation.VERTICAL -> FoldOrientation.VERTICAL
        else -> FoldOrientation.HORIZONTAL
    },
    isSeparating = isSeparating,
    boundsDp = if (density > 0f) {
        FoldBounds(
            leftDp = bounds.left / density,
            topDp = bounds.top / density,
            rightDp = bounds.right / density,
            bottomDp = bounds.bottom / density
        )
    } else {
        null
    }
)
