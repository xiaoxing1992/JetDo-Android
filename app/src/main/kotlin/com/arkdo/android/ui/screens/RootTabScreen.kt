package com.arkdo.android.ui.screens

import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.hilt.navigation.compose.hiltViewModel
import com.arkdo.android.navigation.Routes
import com.arkdo.android.services.session.LoginGate
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

    // 启动会话检查(对齐鸿蒙 RootTabPage handleArkWebReady:宿主就绪后才发 session 请求;
    // 提前发会命中「ArkWeb 宿主尚未就绪」白失败,头像一直停在未登录)。
    val sessionViewModel: SessionViewModel = hiltViewModel()
    LaunchedEffect(isWebViewReady) {
        if (isWebViewReady) {
            sessionViewModel.checkCurrentUser()
        }
    }


    // 抽屉包在最外层，才能盖住底部标签栏和上下系统栏。
    HomeDrawerHost(viewModel = homeViewModel) {
    Scaffold(
        // 内容顶到屏幕顶(状态栏后),各 Tab 页自行处理状态栏 inset 与背景:
        // 默认 contentWindowInsets 会把 NavHost 从状态栏下方排起,状态栏条恒为容器白,
        // 「我的」灰页永远够不到状态栏区。关掉后由 HomeTitleBar/ProfileScreen 自己垫 inset。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
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
                val state by homeViewModel.uiState.collectAsState()
                val context = androidx.compose.ui.platform.LocalContext.current
                // 登录态/当前用户镜像:会话检查成功后写入;首页右上角账号入口据此渲染。
                val currentUser by com.arkdo.android.services.session.CurrentUserHolder.currentUser.collectAsState()
                val loggedIn = currentUser != null
                HomeScreen(
                    isWebViewReady = isWebViewReady,
                    viewModel = homeViewModel,
                    onTopicClick = { topicId, targetPostNumber ->
                        // target 非空 = 未读行跳楼:详情页加载后滚动到该楼层。
                        val targetArg = targetPostNumber?.let { "?target=$it" } ?: ""
                        navController.navigate("${Routes.TOPIC_DETAIL}/$topicId$targetArg")
                    },
                    onProfileClick = {
                        // 首页右上角头像 → 个人中心(对齐鸿蒙 handleAccountClick→push PROFILE)。
                        // 「我的」页是根导航上的独立页,能盖住底部栏(Android 无底部栏,等效盖住 Tab 内容);
                        // 从首页 push,返回键/返回按钮回首页。
                        navController.navigate(Routes.PROFILE) {
                            launchSingleTop = true
                        }
                    },
                    isLoggedIn = loggedIn,
                    isCheckingSession = false,
                    accountUser = currentUser,
                    onComposeClick = {
                        // 发帖前登录闸门:未登录弹登录页,回来后用户自己再点发帖(对齐鸿蒙 openComposer)。
                        if (LoginGate.ensureLoggedIn(context)) {
                            val categoryId = state.listQuery.category?.id?.takeIf { it > 0 }
                            val arg = categoryId?.let { "?categoryId=$it" } ?: ""
                            navController.navigate("${Routes.NEW_TOPIC}$arg")
                        }
                    }
                )
            }
            composable(Routes.NOTIFICATION) {
                NotificationScreen(navController = navController)
            }
            composable(Routes.PROFILE) {
                // 底部 Tab「我的」:沉浸式内容(无返回/设置栏,顶到状态栏),与独立页共用 ProfileScreen。
                ProfileScreen(
                    onOpenLogin = {
                        // 未登录「立即登录」→ 请求根层弹登录半窗。
                        LoginGate.requestLogin()
                    },
                    showTopBar = false,
                    immersive = true
                )
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
