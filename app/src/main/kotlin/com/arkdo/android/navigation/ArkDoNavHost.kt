package com.arkdo.android.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.arkdo.android.navigation.slideEnterTransition
import com.arkdo.android.navigation.slideExitTransition
import com.arkdo.android.navigation.slidePopEnterTransition
import com.arkdo.android.navigation.slidePopExitTransition
import com.arkdo.android.services.session.LoginGate
import com.arkdo.android.services.session.CurrentUserHolder
import com.arkdo.android.ui.composer.NewTopicResume
import com.arkdo.android.ui.composer.NewTopicScreen
import com.arkdo.android.ui.composer.canUseComposer
import com.arkdo.android.ui.composer.ensureComposerAccess
import com.arkdo.android.common.constants.AppConstants
import com.arkdo.android.ui.drafts.DraftResume
import com.arkdo.android.ui.drafts.DraftsScreen
import com.arkdo.android.ui.screens.DebugLogScreen
import com.arkdo.android.ui.screens.ExperimentalSettingsScreen
import com.arkdo.android.ui.screens.LoginScreen
import com.arkdo.android.ui.screens.ProfileScreen
import com.arkdo.android.ui.screens.SettingsScreen
import com.arkdo.android.ui.screens.TopicListStyleSettingsScreen
import com.arkdo.android.ui.screens.RootTabScreen
import com.arkdo.android.ui.screens.WebViewPage
import com.arkdo.android.ui.bookmarks.BookmarksScreen
import com.arkdo.android.ui.bookmarks.ReadTopicsScreen
import com.arkdo.android.ui.level.LevelProgressScreen
import com.arkdo.android.ui.credit.LdcCreditScreen
import com.arkdo.android.ui.user.BadgesScreen
import com.arkdo.android.ui.user.ActivityScreen
import com.arkdo.android.ui.user.SolvedPostsScreen
import com.arkdo.android.ui.user.UserProfileScreen
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
        modifier = modifier.fillMaxSize(),
        enterTransition = slideEnterTransition,
        exitTransition = slideExitTransition,
        popEnterTransition = slidePopEnterTransition,
        popExitTransition = slidePopExitTransition
    ) {
        // Root Tab 容器（首页、通知、个人中心）
        composable(Routes.ROOT) {
            RootTabScreen(
                isWebViewReady = isWebViewReady,
                navController = navController
            )
        }

        // 个人中心:首页右上角头像 push 进来的根级全屏页(对齐鸿蒙 AppRouteName.PROFILE)。
        // 它压在 ROOT(含底部导航)之上,能盖住底栏;返回 pop 回 ROOT。
        composable(Routes.PROFILE) {
            ProfileScreen(
                onNavigateBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenLogin = {
                    // 未登录「立即登录」→ 请求根层弹登录半窗。
                    LoginGate.requestLogin()
                },
                // 「全部 ›」/快捷入口/互动圈等点击目标。不接的话这些 entry 会走
                // ProfileScreen 的默认空回调,表现成「点了没反应」。
                onOpenBadges = { uname, count ->
                    if (uname.isNotEmpty()) {
                        navController.navigate("${Routes.BADGES}/$uname?badgeCount=$count")
                    }
                },
                onOpenActivity = { uname, filter, title ->
                    if (uname.isNotEmpty()) {
                        navController.navigate(
                            "${Routes.ACTIVITY_LIST}/$uname" +
                                "?filter=${java.net.URLEncoder.encode(filter, "UTF-8")}" +
                                "&title=${java.net.URLEncoder.encode(title, "UTF-8")}"
                        )
                    }
                },
                onOpenDrafts = { navController.navigate(Routes.DRAFTS) },
                onOpenBookmarks = { navController.navigate(Routes.BOOKMARKS) },
                onOpenReadTopics = { navController.navigate(Routes.READ_TOPICS) },
                onOpenLevelProgress = { navController.navigate(Routes.LEVEL_PROGRESS) },
                onOpenLdcCredit = { startLogin ->
                    navController.navigate("${Routes.LDC_CREDIT}?startLogin=$startLogin")
                },
                onOpenWeb = { url, title ->
                    if (url.isNotEmpty()) {
                        val q = title?.takeIf { it.isNotEmpty() }
                            ?.let { "?title=${java.net.URLEncoder.encode(it, "UTF-8")}" }
                            .orEmpty()
                        navController.navigate("${Routes.WEBVIEW}/${java.net.URLEncoder.encode(url, "UTF-8")}$q")
                    }
                }
            )
        }
        // 搜索页:首页顶栏放大镜进入(对齐鸿蒙 SearchOverlay 全屏覆盖)。
        // 压在 ROOT(含底部导航)之上,返回 pop 回原页面。
        composable(Routes.SEARCH) {
            com.arkdo.android.ui.search.SearchFeatureScreen(
                isWebViewReady = isWebViewReady,
                onBack = { navController.popBackStack() },
                onOpenTopic = { topicId, targetPostNumber ->
                    val q = targetPostNumber?.let { "?target=$it" } ?: ""
                    navController.navigate("${Routes.TOPIC_DETAIL}/$topicId$q")
                },
                onOpenUser = { username ->
                    if (username.isNotEmpty()) {
                        navController.navigate("${Routes.USER_PROFILE}/$username")
                    }
                }
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
            val draftResume = navController.previousBackStackEntry
                ?.savedStateHandle
                ?.get<DraftResume>(DRAFT_REPLY_RESUME_KEY)

            TopicDetailScreen(
                topicId = topicId,
                slug = slug,
                title = title,
                targetPostNumber = target,
                draftResume = draftResume,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToTopic = { navTopicId, navSlug, navTarget ->
                    val parts = mutableListOf<String>()
                    navSlug?.let { parts.add("slug=$it") }
                    navTarget?.let { parts.add("target=$it") }
                    val query = if (parts.isEmpty()) "" else "?" + parts.joinToString("&")
                    navController.navigate("${Routes.TOPIC_DETAIL}/$navTopicId$query")
                },
                onNavigateToUser = { user ->
                    // 点作者/评论楼层/boost 头像 → 他人主页（对齐鸿蒙 openUserProfile）。
                    val name = user.name?.takeIf { it.isNotEmpty() }
                    val avatar = user.avatarTemplate
                    val username = user.username
                    if (username.isNotEmpty()) {
                        val q = buildString {
                            name?.let { append("?displayName=${java.net.URLEncoder.encode(it, "UTF-8")}") }
                            avatar?.takeIf { a -> a.isNotEmpty() }?.let {
                                if (isNotEmpty()) append("&") else append("?")
                                append("avatar=${java.net.URLEncoder.encode(it, "UTF-8")}")
                            }
                        }
                        navController.navigate("${Routes.USER_PROFILE}/$username$q")
                    }
                }
            )
        }

        // 调试日志页（设置 → 诊断日志）
        composable(Routes.DEBUG_LOG) {
            DebugLogScreen(
                onNavigateBack = { navController.popBackStack() },
                onOpenUrl = { url, title ->
                    navController.navigate(
                        "${Routes.WEBVIEW}/${java.net.URLEncoder.encode(url, "UTF-8")}?title=${java.net.URLEncoder.encode(title, "UTF-8")}"
                    )
                }
            )
        }
        // 他人主页:帖子里点作者/评论楼层/boost 头像进入。
        composable(
            route = Routes.USER_PROFILE + "/{username}?displayName={displayName}&avatar={avatar}",
            arguments = listOf(
                navArgument("username") { type = NavType.StringType },
                navArgument("displayName") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("avatar") { type = NavType.StringType; nullable = true; defaultValue = null }
            )
        ) { entry ->
            val args = entry.arguments
            UserProfileScreen(
                username = args?.getString("username").orEmpty(),
                seedDisplayName = args?.getString("displayName"),
                seedAvatarTemplate = args?.getString("avatar"),
                onNavigateBack = { navController.popBackStack() },
                onOpenUser = { uname, dname, avatarTemplate ->
                    // 互动圈头像可点:递归 push 同一路由(对齐鸿蒙 openUserProfile 递归跳转),
                    // 返回栈逐层回退,子页不重新拉取父页数据。
                    if (uname.isNotEmpty()) {
                        val q = buildString {
                            dname?.takeIf { it.isNotEmpty() }?.let {
                                append("?displayName=${java.net.URLEncoder.encode(it, "UTF-8")}")
                            }
                            avatarTemplate?.takeIf { it.isNotEmpty() }?.let {
                                if (isNotEmpty()) append("&") else append("?")
                                append("avatar=${java.net.URLEncoder.encode(it, "UTF-8")}")
                            }
                        }
                        navController.navigate("${Routes.USER_PROFILE}/$uname$q")
                    }
                },
                onOpenTopic = { topicId, title, postNumber ->
                    val parts = mutableListOf<String>()
                    title.takeIf { it.isNotBlank() }?.let { parts.add("title=${java.net.URLEncoder.encode(it, "UTF-8")}") }
                    postNumber?.let { parts.add("target=$it") }
                    val q = if (parts.isEmpty()) "" else "?" + parts.joinToString("&")
                    navController.navigate("${Routes.TOPIC_DETAIL}/$topicId$q")
                },
                onOpenBadges = { uname, count ->
                    navController.navigate("${Routes.BADGES}/$uname?badgeCount=$count")
                },
                onOpenActivity = { uname, filter, title ->
                    navController.navigate("${Routes.ACTIVITY_LIST}/$uname?filter=${java.net.URLEncoder.encode(filter, "UTF-8")}&title=${java.net.URLEncoder.encode(title, "UTF-8")}")
                },
                onOpenSolved = { uname, count ->
                    navController.navigate("${Routes.SOLVED}/$uname?solvedCount=$count")
                },
                onOpenWeb = { url, title ->
                    // 私信/网页打开:走原生通用 WebView 页(对齐鸿蒙 openWebPage → AppRouteName.WEB)。
                    // 目标整段 URL 都要作为单个路径段编码(& / ? 会让导航把它拆成 query 参数)。
                    if (url.isNotEmpty()) {
                        val q = buildString {
                            title?.takeIf { it.isNotEmpty() }?.let {
                                append("?title=${java.net.URLEncoder.encode(it, "UTF-8")}")
                            }
                        }
                        navController.navigate("${Routes.WEBVIEW}/${java.net.URLEncoder.encode(url, "UTF-8")}$q")
                    }
                }
            )
        }


        // 全部徽章页。
        composable(
            route = Routes.BADGES + "/{username}?badgeCount={badgeCount}",
            arguments = listOf(
                navArgument("username") { type = NavType.StringType },
                navArgument("badgeCount") { type = NavType.StringType; nullable = true; defaultValue = "0" }
            )
        ) { entry ->
            val args = entry.arguments
            BadgesScreen(
                username = args?.getString("username").orEmpty(),
                badgeCount = args?.getString("badgeCount")?.toIntOrNull() ?: 0,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // 个人动态列表页。
        composable(
            route = Routes.ACTIVITY_LIST + "/{username}?filter={filter}&title={title}",
            arguments = listOf(
                navArgument("username") { type = NavType.StringType },
                navArgument("filter") { type = NavType.StringType; nullable = true; defaultValue = "4,5" },
                navArgument("title") { type = NavType.StringType; nullable = true; defaultValue = "动态" }
            )
        ) { entry ->
            val args = entry.arguments
            ActivityScreen(
                username = args?.getString("username").orEmpty(),
                filter = args?.getString("filter") ?: "4,5",
                pageTitle = args?.getString("title") ?: "动态",
                onNavigateBack = { navController.popBackStack() },
                onOpenTopic = { tid, title, postNum ->
                    val parts = mutableListOf<String>()
                    title.takeIf { it.isNotBlank() }?.let { parts.add("title=${java.net.URLEncoder.encode(it, "UTF-8")}") }
                    postNum?.let { parts.add("target=$it") }
                    val q = if (parts.isEmpty()) "" else "?" + parts.joinToString("&")
                    navController.navigate("${Routes.TOPIC_DETAIL}/$tid$q")
                }
            )
        }

        composable(
            route = Routes.SOLVED + "/{username}?solvedCount={solvedCount}",
            arguments = listOf(
                navArgument("username") { type = NavType.StringType },
                navArgument("solvedCount") { type = NavType.IntType; defaultValue = 0 }
            )
        ) { entry ->
            val args = entry.arguments
            SolvedPostsScreen(
                username = args?.getString("username").orEmpty(),
                solvedCount = args?.getInt("solvedCount") ?: 0,
                onNavigateBack = { navController.popBackStack() },
                onOpenTopic = { tid, title, postNum ->
                    val parts = mutableListOf<String>()
                    title.takeIf { it.isNotBlank() }?.let { parts.add("title=${java.net.URLEncoder.encode(it, "UTF-8")}") }
                    parts.add("target=$postNum")
                    navController.navigate("${Routes.TOPIC_DETAIL}/$tid?" + parts.joinToString("&"))
                }
            )
        }

        composable(Routes.BOOKMARKS) {
            BookmarksScreen(
                username = CurrentUserHolder.currentUser.value?.username.orEmpty(),
                onNavigateBack = { navController.popBackStack() },
                onOpenTopic = { topicId, title, postNumber ->
                    val parts = mutableListOf<String>()
                    title.takeIf { it.isNotBlank() }?.let { parts.add("title=${java.net.URLEncoder.encode(it, "UTF-8")}") }
                    postNumber?.let { parts.add("target=$it") }
                    val query = if (parts.isEmpty()) "" else "?" + parts.joinToString("&")
                    navController.navigate("${Routes.TOPIC_DETAIL}/$topicId$query")
                }
            )
        }

        composable(Routes.READ_TOPICS) {
            ReadTopicsScreen(
                onNavigateBack = { navController.popBackStack() },
                onOpenTopic = { topicId, title, postNumber ->
                    val parts = mutableListOf<String>()
                    title?.takeIf { it.isNotBlank() }?.let { parts.add("title=${java.net.URLEncoder.encode(it, "UTF-8")}") }
                    postNumber?.let { parts.add("target=$it") }
                    val query = if (parts.isEmpty()) "" else "?" + parts.joinToString("&")
                    navController.navigate("${Routes.TOPIC_DETAIL}/$topicId$query")
                }
            )
        }

        composable(Routes.LEVEL_PROGRESS) {
            LevelProgressScreen(
                isLoggedIn = CurrentUserHolder.currentUser.value != null,
                onNavigateBack = { navController.popBackStack() },
                onOpenWeb = { url, title ->
                    val q = "?title=${java.net.URLEncoder.encode(title, "UTF-8")}"
                    navController.navigate("${Routes.WEBVIEW}/${java.net.URLEncoder.encode(url, "UTF-8")}$q")
                }
            )
        }

        composable(
            route = Routes.LDC_CREDIT + "?startLogin={startLogin}",
            arguments = listOf(
                navArgument("startLogin") { type = NavType.BoolType; defaultValue = false }
            )
        ) { entry ->
            LdcCreditScreen(
                startLogin = entry.arguments?.getBoolean("startLogin") ?: false,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // 我的草稿。新主题续写走原生发帖页，其余类型仍打开网页。
        composable(Routes.DRAFTS) {
            DraftsScreen(
                onNavigateBack = { navController.popBackStack() },
                username = CurrentUserHolder.currentUser.value?.username.orEmpty(),
                onResumeTopic = { resume ->
                    navController.currentBackStackEntry?.savedStateHandle?.set(DRAFT_RESUME_KEY, resume)
                    navController.navigate(Routes.NEW_TOPIC)
                },
                onResumeReply = { topicId, title, resume ->
                    openTopicWithDraft(navController, topicId, title, resume)
                },
                onResumeEdit = { topicId, title, resume ->
                    openTopicWithDraft(navController, topicId, title, resume)
                },
                onOpenWeb = { url ->
                    navController.navigate("${Routes.WEBVIEW}/${java.net.URLEncoder.encode(url, "UTF-8")}?title=${java.net.URLEncoder.encode("我的草稿", "UTF-8")}")
                }
            )
        }

        // 发新主题页(首页 FAB 进入;发帖成功跳进新帖详情)。
        composable(
            route = Routes.NEW_TOPIC + "?categoryId={categoryId}",
            arguments = listOf(
                navArgument("categoryId") { type = NavType.LongType; defaultValue = -1L }
            )
        ) { entry ->
            val seedCategory = entry.arguments?.getLong("categoryId")?.takeIf { it > 0 }
            val resume = navController.previousBackStackEntry?.savedStateHandle?.get<NewTopicResume>(DRAFT_RESUME_KEY)
            val currentUser by CurrentUserHolder.currentUser.collectAsState()
            val context = androidx.compose.ui.platform.LocalContext.current
            if (canUseComposer(currentUser, LoginGate.isLoggedIn(context))) {
                NewTopicScreen(
                    seedCategoryId = seedCategory,
                    resume = resume,
                    onBack = { navController.popBackStack() },
                    onPublished = { topicId ->
                        // 发帖成功:广播会话变化让首页列表刷新,弹掉发帖页再进新帖详情,
                        // 使从详情返回时回到首页(而不是一张已发出的发帖页)。
                        com.arkdo.android.services.session.SessionEventBus.notifySessionChanged()
                        navController.navigate("${Routes.TOPIC_DETAIL}/$topicId") {
                            popUpTo(Routes.NEW_TOPIC) { inclusive = true }
                        }
                    }
                )
            } else {
                LaunchedEffect(Unit) {
                    ensureComposerAccess(context)
                    navController.popBackStack()
                }
            }
        }

        // 通用 WebView 页(对齐鸿蒙 AppRouteName.WEB:私信 / 外站 / 站内非帖子页)。
        composable(
            route = Routes.WEBVIEW + "/{url}?title={title}",
            arguments = listOf(
                navArgument("url") { type = NavType.StringType },
                navArgument("title") { type = NavType.StringType; nullable = true; defaultValue = null }
            )
        ) { entry ->
            val args = entry.arguments
            val rawUrl = args?.getString("url").orEmpty()
            // URL 里可能带编码的 & / ?,取回后先 URL 解码还原完整地址。
            val decoded = java.net.URLDecoder.decode(rawUrl, "UTF-8")
            WebViewPage(
                url = decoded,
                pageTitle = args?.getString("title").orEmpty(),
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onOpenFeedback = {
                    navController.navigate("${Routes.TOPIC_DETAIL}/${AppConstants.FEEDBACK_TOPIC_ID}")
                },
                onOpenDiagnostics = { navController.navigate(Routes.DEBUG_LOG) },
                onOpenExperimental = { navController.navigate(Routes.SETTINGS_EXPERIMENTAL) },
                onOpenTopicListStyle = { navController.navigate(Routes.SETTINGS_TOPIC_LIST_STYLE) }
            )
        }
        composable(Routes.SETTINGS_EXPERIMENTAL) {
            ExperimentalSettingsScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS_TOPIC_LIST_STYLE) {
            TopicListStyleSettingsScreen(onNavigateBack = { navController.popBackStack() })
        }
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
    const val SEARCH = "search"
    const val NOTIFICATION = "notification"
    const val PROFILE = "profile"

    const val TOPIC_DETAIL = "topic_detail"
    const val USER_PROFILE = "user_profile"
    const val BOOKMARKS = "bookmarks"
    const val READ_TOPICS = "read_topics"
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
    const val SETTINGS_TOPIC_LIST_STYLE = "settings_topic_list_style"

    // 其他功能页面
    const val LEVEL_PROGRESS = "level_progress"
    const val LDC_CREDIT = "ldc_credit"
    const val BADGES = "badges"
    const val ACTIVITY_LIST = "activity_list"
    const val SOLVED = "solved"
    const val NEW_TOPIC = "new_topic"
    const val WEBVIEW = "webview"
}

private const val DRAFT_RESUME_KEY = "draft_resume"
private const val DRAFT_REPLY_RESUME_KEY = "draft_reply_resume"

private fun openTopicWithDraft(
    navController: NavHostController,
    topicId: Long,
    title: String,
    resume: DraftResume
) {
    navController.currentBackStackEntry?.savedStateHandle?.set(DRAFT_REPLY_RESUME_KEY, resume)
    val encoded = java.net.URLEncoder.encode(title, "UTF-8")
    navController.navigate("${Routes.TOPIC_DETAIL}/$topicId?title=$encoded")
}
