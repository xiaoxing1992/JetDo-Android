package com.jetdo.android.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection.Companion.End
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection.Companion.Start
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut

/**
 * 页面级转场时长（push/pop 滑动）。
 *
 * internal 而非 private：共享元素的 bounds 补间要用**同一个值**（见 JetDoNavHost
 * 下发 SharedSearchTransition.boundsDurationMillis）。两边一旦不同步，页面停稳后
 * 图标还会自己动一段，看起来像多了一次回弹。
 */
internal const val SCREEN_TRANSITION_DURATION = 320

/** Tab 切换交叉淡入时长。 */
private const val TAB_TRANSITION_DURATION = 220

/**
 * push：新页从右缘进入、随旧页一同向左平移（towards = 移动方向，不是来源方向）。
 */
val slideEnterTransition: AnimatedContentTransitionScope<*>.() -> EnterTransition = {
    slideIntoContainer(towards = Start, animationSpec = tween(SCREEN_TRANSITION_DURATION))
}

/**
 * push：旧页向左退场，与新页同向平移，形成整层推进的效果。
 */
val slideExitTransition: AnimatedContentTransitionScope<*>.() -> ExitTransition = {
    slideOutOfContainer(towards = Start, animationSpec = tween(SCREEN_TRANSITION_DURATION))
}

/**
 * pop：回退目标页从左缘进入、随栈顶页一同向右平移。
 */
val slidePopEnterTransition: AnimatedContentTransitionScope<*>.() -> EnterTransition = {
    slideIntoContainer(towards = End, animationSpec = tween(SCREEN_TRANSITION_DURATION))
}

/**
 * pop：栈顶页向右退场。
 */
val slidePopExitTransition: AnimatedContentTransitionScope<*>.() -> ExitTransition = {
    slideOutOfContainer(towards = End, animationSpec = tween(SCREEN_TRANSITION_DURATION))
}

/**
 * Tab 切换：交叉淡入淡出，同层页面平移会显得错位，淡入更自然。
 */
val tabEnterTransition: AnimatedContentTransitionScope<*>.() -> EnterTransition = {
    fadeIn(animationSpec = tween(TAB_TRANSITION_DURATION))
}

val tabExitTransition: AnimatedContentTransitionScope<*>.() -> ExitTransition = {
    fadeOut(animationSpec = tween(TAB_TRANSITION_DURATION))
}

// ==========================================================================
// 双栏（宽屏）下的路由转场：无动画。
//
// 横向 push 表达的是「一层压一层」的空间关系 —— 新页从右缘推入、旧页让位，用户靠这个
// 位移读出「我进到更深一层了」。这个隐喻成立的前提是**页面占满整个可视区**。
//
// 一旦切成左右双栏，前提就不成立了：左栏（导航/列表）原地不动，位移只发生在右栏内部。
// 于是变成「左边钉死、右边自己抽走一块再塞回来」，读到的不是层级变化，只是一次抽搐。
// 而且右栏只有半屏宽，滑入距离被压缩一半，速度观感也更跳。
//
// 所以宽屏下改成内容区直接替换 —— 这也是 list-detail 规范布局的惯例：右侧详情区切换
// 不做横向位移，层级由左栏的选中态来体现，不需要再让内容飞一次。
// 注意只影响路由转场；左栏内部 tab 切换仍走 [tabEnterTransition] 的淡入，不受影响。
// ==========================================================================

/** 宽屏（双栏）push/enter：无动画，内容直接替换。 */
val noneEnterTransition: AnimatedContentTransitionScope<*>.() -> EnterTransition = {
    EnterTransition.None
}

/** 宽屏（双栏）pop/exit：无动画。 */
val noneExitTransition: AnimatedContentTransitionScope<*>.() -> ExitTransition = {
    ExitTransition.None
}

// ==========================================================================
// 内置浏览器（WebViewPage）不再单独定义转场。
//
// 曾经单开过一套纵向（自下方升起 / 向下沉出）的转场，理由是「网页是压在 App 上的
// 一层外部内容，用轴向区分层级」。实测被否掉了：全 App 只有这一个页面走纵向，
// 用户点站内链接时读到的是「方向不对」，而不是「这是浏览器」。轴向用来表达层级，
// 前提是这种区分本身对用户有意义 —— 这里没有，一致性优先。
//
// 现在 WEBVIEW 路由不声明任何 enter/exit/pop 参数（见 JetDoNavHost），
// 自动落到 NavHost 级的横向 push（slideEnterTransition / slideExitTransition），
// 与进入帖子详情、他人主页完全同向同时长。
//
// 打开浏览器时页面内容的首屏淡入仍保留，那是在 WebViewPage 内部做的，
// 与路由转场无关（解决的是「白块啪一下变网页」）。
// ==========================================================================
