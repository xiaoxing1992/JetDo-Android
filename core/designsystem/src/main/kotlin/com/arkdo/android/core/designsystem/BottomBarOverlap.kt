package com.arkdo.android.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 悬浮底部标签栏的"内容避让高度":底栏容器高 + 底部间隙 + 系统手势区。
 *
 * 底栏是悬浮胶囊,页面内容全出血、从底栏下方穿过(沉浸式);滚动容器的
 * contentPadding.bottom 必须垫上这个值,否则滚到底时最后一条内容会被底栏遮住。
 *
 * 由 RootTabScreen 在挂底栏的地方 provide;不在根 tab 树里的页面(如根级个人主页)
 * 读到默认 0,不会多垫。feature 模块的列表也读它,所以定义在 core 层而不是 app 层。
 */
val LocalBottomBarClearance = staticCompositionLocalOf { Dp.Unspecified }

/** 底栏避让高度的组合读取口。未提供底栏时返回 0,调用方垫 0 无副作用。 */
val bottomBarClearance: Dp
    @Composable
    @ReadOnlyComposable
    get() = LocalBottomBarClearance.current.takeIf { it != Dp.Unspecified } ?: 0.dp
