package com.arkdo.android.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection.Companion.End
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection.Companion.Start
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut

/** 页面级转场时长（push/pop 滑动）。 */
private const val SCREEN_TRANSITION_DURATION = 320

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
// 内置浏览器（WebViewPage）不再单独定义转场。
//
// 曾经单开过一套纵向（自下方升起 / 向下沉出）的转场，理由是「网页是压在 App 上的
// 一层外部内容，用轴向区分层级」。实测被否掉了：全 App 只有这一个页面走纵向，
// 用户点站内链接时读到的是「方向不对」，而不是「这是浏览器」。轴向用来表达层级，
// 前提是这种区分本身对用户有意义 —— 这里没有，一致性优先。
//
// 现在 WEBVIEW 路由不声明任何 enter/exit/pop 参数（见 ArkDoNavHost），
// 自动落到 NavHost 级的横向 push（slideEnterTransition / slideExitTransition），
// 与进入帖子详情、他人主页完全同向同时长。
//
// 打开浏览器时页面内容的首屏淡入仍保留，那是在 WebViewPage 内部做的，
// 与路由转场无关（解决的是「白块啪一下变网页」）。
// ==========================================================================
