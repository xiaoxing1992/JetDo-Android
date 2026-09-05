package com.arkdo.android.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.arkdo.android.ui.screens.DebugLogScreen
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
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // 调试日志页（排障用，Profile 页 FAB 进入）
        composable(Routes.DEBUG_LOG) {
            DebugLogScreen(onNavigateBack = { navController.popBackStack() })
        }

        // TODO: 添加其他页面路由
        // composable(Routes.SETTINGS) { ... }
        // composable(Routes.LOGIN) { ... }
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
