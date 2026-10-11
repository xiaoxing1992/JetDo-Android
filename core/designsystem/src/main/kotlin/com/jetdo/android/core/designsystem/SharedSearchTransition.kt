package com.jetdo.android.core.designsystem

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * 顶栏「放大镜 → 搜索页」共享元素转场的两端接线盒。
 *
 * ## 为什么是 CompositionLocal 而不是逐层传参
 *
 * 两端都埋得很深：源端在
 * `RootTabScreen → HomeFeatureScreen → HomeRoute → HomeScreen → HomeTitleBar → HomeBarButton`，
 * 目的端在 `SearchFeatureScreen → SearchRoute → SearchScreen → SearchBar`。
 * 为**一个图标**把这两串签名各加两个参数（状态 + 可见性作用域），性价比极低；
 * 与 `AttachmentClick.kt`（图片点击）和 `ui/components/QuoteJump.kt`（引用卡跳楼）同款理由。
 *
 * ## 三个字段缺一不可
 *
 * - [scope]：`Modifier.sharedElement` 是 `SharedTransitionScope` 的**成员扩展**，
 *   没有它连方法都调不到。由宿主在 `SharedTransitionLayout` 里取 `this` 下发一次。
 * - [contentState]：两端靠**同一个 key** 认亲。必须由宿主 `rememberSharedContentState`
 *   记住一份、两端共用；各自 remember 会得到两个互不相认的状态，表现为「转了但没动画」。
 * - [visibilityScope]：这块内容属于**哪一次页面转场**。它**逐个目的地都不同**
 *   （`composable(...) { }` 的 receiver 就是），所以只能在每个目的地里就地提供，
 *   不能像 [scope] 那样在 NavHost 外层下发 —— 这一条是最容易写错的地方。
 *
 * ## 三个都为空时
 *
 * 装饰器退化成 `this`（原样返回），因此**单独预览/单测不受影响**：没接宿主就只是普通图标。
 * [Modifier.searchIconSharedElement] 因此可以安全地挂在任意一处放大镜上。
 *
 * ## 为什么要显式指定 [boundsDurationMillis]
 *
 * `Modifier.sharedElement` 若不传 `boundsTransform`，走的是 Compose 内置默认值
 * `spring(dampingRatio = NoBouncy, stiffness = StiffnessMediumLow)` —— 这个 spring 在这段
 * 位移下要 ~400~500ms 才 settle 到 1px 以内，**明显长于页面自身的 320ms 滑动**。
 *
 * 两个后果：
 * 1. 页面已经停稳，图标还在做最后一段"贴上去"的位移 —— 观感上像到位后又动了一下；
 * 2. 用共享元素做逐相位采样取证时，把页面转场拉长后**图标不会跟着变慢**（spring 时长与
 *    那个常量无关），于是视频里会出现"图标早早飞到空位上、等容器慢慢滑过来"的假象。
 *
 * 所以这里显式换成与页面转场**同一条 tween 曲线、同一个时长**，两个动画共用一个时钟。
 */
@Immutable
data class SharedSearchTransition(
    val scope: SharedTransitionScope? = null,
    val contentState: SharedTransitionScope.SharedContentState? = null,
    val visibilityScope: AnimatedVisibilityScope? = null,
    /**
     * 图标 bounds 补间时长（毫秒），**必须与页面级转场时长一致**
     * （app 模块 `ScreenTransitions.SCREEN_TRANSITION_DURATION`），由 NavHost 下发。
     */
    val boundsDurationMillis: Int = 320
)

/** 未接入共享元素转场时的空值（默认值），装饰器据此原样放行。 */
private val NoSharedSearchTransition = SharedSearchTransition()

/**
 * 未接宿主时为空，[Modifier.searchIconSharedElement] 会原样返回 —— 所以本地的
 * `@Preview` 与单测不需要任何脚手架。
 */
val LocalSharedSearchTransition = compositionLocalOf { NoSharedSearchTransition }

/**
 * 共享元素 key：顶栏放大镜 ↔ 搜索页放大镜。两端**必须**用这一个值。
 *
 * 取值不影响任何逻辑，只是 `SharedContentState` 的索引；用带命名空间的长字符串，
 * 是为了将来接第二个共享元素时不至于撞名。
 */
const val SEARCH_ICON_SHARED_KEY = "jetdo.shared.search-icon"

/**
 * 把当前节点标记为「放大镜」共享元素。
 *
 * 挂在两端**放大镜图标自身**上（而不是外面那层圆形按钮 / 胶囊容器）：
 * 共享元素的几何取的是被标记节点的 bounds，挂在按钮上会让整个 40dp 圆底一起飞过去，
 * 而目的端那侧根本没有圆底。
 *
 * 未接宿主时（[LocalSharedSearchTransition] 是空值）返回原 Modifier，不做任何事。
 */
@Composable
fun Modifier.searchIconSharedElement(): Modifier {
    val transition = LocalSharedSearchTransition.current
    val scope = transition.scope
    val state = transition.contentState
    val visible = transition.visibilityScope
    if (scope == null || state == null || visible == null) return this
    // 与页面转场同曲线同时长：不显式指定的话会落到 Compose 默认的
    // spring(StiffnessMediumLow)，它比 320ms 的页面滑动长一截（见类注释）。
    val duration = transition.boundsDurationMillis
    val boundsTransform = remember(duration) {
        BoundsTransform { _, _ ->
            tween(durationMillis = duration, easing = FastOutSlowInEasing)
        }
    }
    // sharedElement 是 SharedTransitionScope 的成员扩展，必须先 with(scope) 才能调。
    return with(scope) {
        this@searchIconSharedElement.sharedElement(
            sharedContentState = state,
            animatedVisibilityScope = visible,
            boundsTransform = boundsTransform
        )
    }
}
