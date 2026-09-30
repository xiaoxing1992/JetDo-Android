package com.arkdo.android.core.designsystem

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
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
 */
@Immutable
data class SharedSearchTransition(
    val scope: SharedTransitionScope? = null,
    val contentState: SharedTransitionScope.SharedContentState? = null,
    val visibilityScope: AnimatedVisibilityScope? = null
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
const val SEARCH_ICON_SHARED_KEY = "arkdo.shared.search-icon"

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
    return if (scope != null && state != null && visible != null) {
        // sharedElement 是 SharedTransitionScope 的成员扩展，必须先 with(scope) 才能调。
        with(scope) { this@searchIconSharedElement.sharedElement(state, visible) }
    } else {
        this
    }
}
