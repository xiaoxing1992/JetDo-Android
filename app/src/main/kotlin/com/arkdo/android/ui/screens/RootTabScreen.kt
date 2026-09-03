package com.arkdo.android.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.arkdo.android.navigation.Routes
import androidx.hilt.navigation.compose.hiltViewModel
import com.arkdo.android.ui.home.HomeDrawerHost
import com.arkdo.android.ui.home.HomeScreen
import com.arkdo.android.ui.home.HomeViewModel

/**
 * Root Tab 容器
 *
 * 对齐 HarmonyOS 版本的 RootTabPage.ets 功能：
 * - 底部导航栏（首页、通知、个人中心）
 * - 管理子页面的导航栈
 * - 处理返回键逻辑（根级浮层优先消费）
 */
@Composable
fun RootTabScreen(
    isWebViewReady: Boolean = false,
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    val tabNavController = rememberNavController()
    val navBackStackEntry by tabNavController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // 首页 ViewModel 提到这一层持有：抽屉宿主要在标签栏之外，得和 HomeScreen 共用同一个实例。
    // 顺带的好处是切标签页时首页状态不再被销毁重建。
    val homeViewModel: HomeViewModel = hiltViewModel()

    // 抽屉包在最外层，才能盖住底部标签栏和上下系统栏。
    HomeDrawerHost(viewModel = homeViewModel) {
    Scaffold(
        bottomBar = {
            NavigationBar {
                TabItem.entries.forEach { tab ->
                    val isSelected = currentDestination?.hierarchy?.any {
                        it.route == tab.route
                    } == true

                    NavigationBarItem(
                        icon = {
                            Icon(
                                imageVector = if (isSelected) tab.selectedIcon else tab.unselectedIcon,
                                contentDescription = tab.label
                            )
                        },
                        label = { Text(tab.label) },
                        selected = isSelected,
                        onClick = {
                            tabNavController.navigate(tab.route) {
                                // 回到 Tab 栈的起点
                                popUpTo(tabNavController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        },
        modifier = modifier
    ) { innerPadding ->
        NavHost(
            navController = tabNavController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    isWebViewReady = isWebViewReady,
                    viewModel = homeViewModel,
                    onTopicClick = { topicId ->
                        navController.navigate("${Routes.TOPIC_DETAIL}/$topicId")
                    }
                )
            }
            composable(Routes.NOTIFICATION) {
                NotificationScreen(navController = navController)
            }
            composable(Routes.PROFILE) {
                ProfileScreen(navController = navController)
            }
        }
    }
    }
}

/**
 * Tab 项定义
 */
private enum class TabItem(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    HOME(
        route = Routes.HOME,
        label = "首页",
        selectedIcon = Icons.Filled.Home,
        unselectedIcon = Icons.Outlined.Home
    ),
    NOTIFICATION(
        route = Routes.NOTIFICATION,
        label = "通知",
        selectedIcon = Icons.Filled.Notifications,
        unselectedIcon = Icons.Outlined.Notifications
    ),
    PROFILE(
        route = Routes.PROFILE,
        label = "我的",
        selectedIcon = Icons.Filled.Person,
        unselectedIcon = Icons.Outlined.Person
    )
}
