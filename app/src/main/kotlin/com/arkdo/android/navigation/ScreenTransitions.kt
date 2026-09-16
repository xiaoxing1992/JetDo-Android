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
