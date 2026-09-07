package com.arkdo.android.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.arkdo.android.services.session.LoginGate
import com.arkdo.android.ui.screens.DebugLogScreen
import com.arkdo.android.ui.screens.LoginScreen
import com.arkdo.android.ui.screens.RootTabScreen
import com.arkdo.android.ui.topic.TopicDetailScreen

/**
 * ArkDO 导航宿主
 *
 * 单 Activity 架构的根导航容器，管理所有页面的路由栈。
 *
 * 路由结构：
 * - Root（Tab 容器）
 *   - Home（首页：最新/热门/未读）
 *   - Notification（通知中心）
 *   - Profile（个人主页）
 * - TopicDetail（话题详情，独立栈）
 * - Settings（设置）
 * - Login（登录）
 * - ...
 */
@Composable
fun ArkDoNavHost(
    isWebViewReady: Boolean = false,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    startDestination: String = Routes.ROOT
) {
    // 登录弹层（对齐鸿蒙 RootTabPage 的 bindSheet 登录 sheet：isSheetVisible + RootSheet.LOGIN）。
    // 深层页面/楼层动作未登录点写操作 → LoginGate.requestLogin → 根层弹满高半弹窗。
    // 鸿蒙根容器常驻，Android 对应常驻层是 NavHost——订阅必须挂这里，放 RootTabScreen
    // 会在进详情页后失联。弹层开着时不重复置位（对齐 openLoginPage 的 isSheetVisible 守卫）。
    var showLoginSheet by remember { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(navController) {
        val unsubscribe = LoginGate.subscribe {
            if (!showLoginSheet) {
                showLoginSheet = true
            }
        }
        onDispose { unsubscribe() }
    }

    Box(modifier = modifier.fillMaxSize()) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
    ) {
        // Root Tab 容器（首页、通知、个人中心）
        composable(Routes.ROOT) {
            RootTabScreen(
                isWebViewReady = isWebViewReady,
                navController = navController
            )
        }

        // 话题详情页
        composable(
            route = Routes.TOPIC_DETAIL + "/{topicId}?slug={slug}&title={title}&target={target}",
            arguments = listOf(
                navArgument("topicId") { type = NavType.LongType },
                navArgument("slug") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("title") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                // 未读行跳楼目标楼层；普通打开不带该参数。
                // 注意：NavType.IntType 不允许 nullable（构图时 require 直接抛），
                // 可空参数必须走 StringType 再自己转 —— 这个错会让 App 启动即闪退。
                navArgument("target") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val topicId = backStackEntry.arguments?.getLong("topicId") ?: 0L
            val slug = backStackEntry.arguments?.getString("slug")
            val title = backStackEntry.arguments?.getString("title")
            val target = backStackEntry.arguments?.getString("target")?.toIntOrNull()

            TopicDetailScreen(
                topicId = topicId,
                slug = slug,
                title = title,
                targetPostNumber = target,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToTopic = { navTopicId, navSlug, navTarget ->
                    val parts = mutableListOf<String>()
                    navSlug?.let { parts.add("slug=$it") }
                    navTarget?.let { parts.add("target=$it") }
                    val query = if (parts.isEmpty()) "" else "?" + parts.joinToString("&")
                    navController.navigate("${Routes.TOPIC_DETAIL}/$navTopicId$query")
                }
            )
        }

        // 调试日志页（排障用，Profile 页 FAB 进入）
        composable(Routes.DEBUG_LOG) {
            DebugLogScreen(onNavigateBack = { navController.popBackStack() })
        }


        // TODO: 添加其他页面路由
        // composable(Routes.SETTINGS) { ... }
        }

        // 登录半弹窗盖在导航内容之上（bindSheet 同层语义：sheet 画在根容器顶）。
        if (showLoginSheet) {
            LoginScreen(
                onNavigateBack = { showLoginSheet = false },
                modifier = Modifier.zIndex(4f)
            )
        }
    }
}

/**
 * 路由常量定义
 */
object Routes {
    const val ROOT = "root"
    const val HOME = "home"
    const val NOTIFICATION = "notification"
    const val PROFILE = "profile"

    const val TOPIC_DETAIL = "topic_detail"
    const val USER_PROFILE = "user_profile"
    const val BOOKMARKS = "bookmarks"
    const val DRAFTS = "drafts"
    const val SETTINGS = "settings"
    const val LOGIN = "login"
    const val CF_CHALLENGE = "cf_challenge"
    const val DEBUG_LOG = "debug_log"

    // Settings 子页面
    const val SETTINGS_DOH = "settings_doh"
    const val SETTINGS_PUSH = "settings_push"
    const val SETTINGS_NETWORK = "settings_network"
    const val SETTINGS_EXPERIMENTAL = "settings_experimental"

    // 其他功能页面
    const val LEVEL_PROGRESS = "level_progress"
    const val BADGES = "badges"
    const val ACTIVITY_LIST = "activity_list"
    const val WEBVIEW = "webview"
}
