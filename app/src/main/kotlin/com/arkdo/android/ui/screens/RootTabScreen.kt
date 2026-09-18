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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.arkdo.android.navigation.Routes
import com.arkdo.android.navigation.tabEnterTransition
import com.arkdo.android.navigation.tabExitTransition
import com.arkdo.android.services.session.LoginGate
import com.arkdo.android.ui.components.ArkDoBottomBar
import com.arkdo.android.ui.components.ArkDoBottomBarItem
import com.arkdo.android.ui.components.AvatarTabIcon
import com.arkdo.android.ui.components.TabVectorIcon
import com.arkdo.android.ui.components.UserAvatar
import com.arkdo.android.ui.home.HomeDrawerHost
import com.arkdo.android.ui.home.HomeFeatureScreen
import com.arkdo.android.ui.home.HomeScreen
import com.arkdo.android.ui.home.HomeViewModel
import com.arkdo.android.ui.home.toHomeQuery
import com.arkdo.android.ui.home.toHomeUser
import com.arkdo.android.ui.composer.ensureComposerAccess
import com.arkdo.android.ui.notification.NotificationScreen

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
    val legacyHomeState by homeViewModel.uiState.collectAsState()
    LaunchedEffect(isWebViewReady) {
        homeViewModel.loadSiteMetadataIfNeeded(isWebViewReady)
    }

    // 启动会话检查(对齐鸿蒙 RootTabPage handleArkWebReady:宿主就绪后才发 session 请求;
    // 提前发会命中「ArkWeb 宿主尚未就绪」白失败,头像一直停在未登录。之后 WebView 就绪、
    // 会话广播、页面回到前台都各补一次 —— CurrentUserHolder 是头像/铃铛的唯一真源,
    // 只在 WebView 就绪那一次核对的话,未登录用户的登录态会一直是初始化时的残留值。
    val sessionViewModel: SessionViewModel = hiltViewModel()
    LaunchedEffect(isWebViewReady) {
        if (isWebViewReady) {
            sessionViewModel.checkCurrentUser()
        }
    }

    // 会话变化(登录/登出/换号)与页面回到前台都复查当前用户,刷新 CurrentUserHolder。
    // CurrentUserHolder 是首页头像/铃铛的唯一真源:若只在 WebView 就绪时核对一次,
    // 未登录用户(会话检查失败 → 不写 null)会一直渲染初始化残留的登录态。
    // rememberUpdatedState 保证 LaunchedEffect(Unit) 这种只组合一次的闭包里,读到的是最新 ready。
    val currentWebViewReady by rememberUpdatedState(isWebViewReady)
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(Unit) {
        com.arkdo.android.services.session.SessionEventBus.subscribe { _ ->
            if (currentWebViewReady) {
                sessionViewModel.checkCurrentUser()
            }
        }
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            if (currentWebViewReady) {
                sessionViewModel.checkCurrentUser()
            }
        }
    }


    // 抽屉包在最外层，才能盖住底部标签栏和上下系统栏。
    // 登录态镜像:标签栏「我的」头像据此渲染。和首页顶栏账号入口、个人中心页共用同一个
    // CurrentUserHolder,不额外发请求。
    val currentUser by com.arkdo.android.services.session.CurrentUserHolder.currentUser.collectAsState()

    // 通知未读数:NotificationEventBus 是服务层(轮询 + MessageBus 实时)写入的唯一真源,
    // 这里只订阅、不自己拉。首页顶栏的铃铛此前一直没接这个数(全项目只有 NotificationModule
    // 引用它),所以底栏角标也不另发请求 —— 谁先把铃铛接上,两边就一起活了。
    var unreadCount by remember { mutableStateOf(0) }
    DisposableEffect(Unit) {
        val unsubscribe = com.arkdo.android.services.notification.NotificationEventBus.subscribe { snapshot ->
            unreadCount = snapshot.unread
        }
        onDispose { unsubscribe() }
    }

    HomeDrawerHost(viewModel = homeViewModel) {
    Scaffold(
        // 底栏是悬浮胶囊,四周留白透出这块区域:容器色必须和页面底色一致,不能露出 Scaffold 的 surface。
        containerColor = com.arkdo.android.ui.theme.arkdoColors.pageBg,
        // 内容顶到屏幕顶(状态栏后),各 Tab 页自行处理状态栏 inset 与背景:
        // 默认 contentWindowInsets 会把 NavHost 从状态栏下方排起,状态栏条恒为容器白,
        // 「我的」灰页永远够不到状态栏区。关掉后由 HomeTitleBar/ProfileScreen 自己垫 inset。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            // 自定义标签栏(ui/components/ArkDoBottomBar),不用 M3 NavigationBar:
            // 固定 80dp 容器高度和内置 ripple 都与这套视觉冲突;选中胶囊由容器统一画,
            // 切换时整枚平移过去(doer 截图实测的系统行为)。
            val selectedTabIndex = rootTabDestinations.indexOfFirst { tab ->
                currentDestination?.hierarchy?.any { it.route == tab.route } == true
            }.let { if (it >= 0) it else 0 }

            ArkDoBottomBar(
                tabCount = rootTabDestinations.size,
                selectedIndex = selectedTabIndex
            ) { index ->
                val tab = rootTabDestinations[index]
                val isSelected = index == selectedTabIndex

                ArkDoBottomBarItem(
                    selected = isSelected,
                    label = tab.label,
                    // 角标只挂通知标签;其余标签没有角标来源,恒为 0。
                    badgeCount = if (tab == RootTabDestination.NOTIFICATION) unreadCount else 0,
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
                ) { tint ->
                    if (tab == RootTabDestination.PROFILE) {
                        // 「我的」用真实头像:与首页顶栏账号入口共用 UserAvatar 和
                        // CurrentUserHolder,登录/登出/换号自动跟随。
                        AvatarTabIcon(
                            selected = isSelected,
                            tint = tint,
                            fallbackIcon = if (isSelected) Icons.Filled.Person else Icons.Outlined.Person,
                            avatar = currentUser?.let { user ->
                                { size -> UserAvatar(user = user, avatarSize = size, showFlair = false) }
                            }
                        )
                    } else {
                        TabVectorIcon(
                            selected = isSelected,
                            tint = tint,
                            selectedIcon = tab.selectedIcon,
                            unselectedIcon = tab.unselectedIcon
                        )
                    }
                }
            }
        },
        modifier = modifier
    ) { innerPadding ->
        NavHost(
            navController = tabNavController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(innerPadding),
            enterTransition = tabEnterTransition,
            exitTransition = tabExitTransition,
            popEnterTransition = tabEnterTransition,
            popExitTransition = tabExitTransition
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
                    onNotificationsClick = {
                        // 首页顶栏铃铛 → 通知 tab(对齐鸿蒙 handleNotificationClick→switchTab NOTIFICATIONS)。
                        tabNavController.navigate(Routes.NOTIFICATION) {
                            popUpTo(tabNavController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    isLoggedIn = loggedIn,
                    isCheckingSession = false,
                    accountUser = currentUser,
                    onComposeClick = {
                        // 同时检查内存中的当前用户,避免持久化的旧登录标记在会话失效后
                        // 仍然放行发帖页。登录成功后由用户再次点击发帖。
                        if (ensureComposerAccess(context)) {
                            val categoryId = state.listQuery.category?.id?.takeIf { it > 0 }
                            val arg = categoryId?.let { "?categoryId=$it" } ?: ""
                            navController.navigate("${Routes.NEW_TOPIC}$arg")
                        }
                    },
                    onSearchClick = {
                        // 顶栏放大镜 → 搜索页(对齐鸿蒙 HomePage 的 openSearch)。
                        navController.navigate(Routes.SEARCH)
                    },
                    onDebugClick = {
                        // 顶栏虫形图标 → 调试日志页(排障用,无需登录,可分享/复制日志文件)。
                        navController.navigate(Routes.DEBUG_LOG)
                    }
                )
            }
            composable(Routes.NEW_HOME) {
                // 模块化首页(feature:home)过渡 tab:与旧首页并存对照,
                // 等视觉/交互对齐后再删掉旧首页。版块索引由 feature 内部自取。
                val currentUser by com.arkdo.android.services.session.CurrentUserHolder.currentUser.collectAsState()
                val context = androidx.compose.ui.platform.LocalContext.current
                val featureQuery = legacyHomeState.listQuery.toHomeQuery()
                HomeFeatureScreen(
                    query = featureQuery,
                    isWebViewReady = isWebViewReady,
                    isLoggedIn = currentUser != null,
                    isCheckingSession = false,
                    accountUser = currentUser?.toHomeUser(),
                    onTopicClick = { topicId, targetPostNumber ->
                        val targetArg = targetPostNumber?.let { "?target=$it" } ?: ""
                        navController.navigate("${Routes.TOPIC_DETAIL}/$topicId$targetArg")
                    },
                    onProfileClick = {
                        navController.navigate(Routes.PROFILE) { launchSingleTop = true }
                    },
                    onNotificationsClick = {
                        tabNavController.navigate(Routes.NOTIFICATION) {
                            popUpTo(tabNavController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onSearchClick = { navController.navigate(Routes.SEARCH) },
                    onDebugClick = { navController.navigate(Routes.DEBUG_LOG) },
                    onComposeClick = {
                        // 与旧首页同一条会话闸门,未拿到当前用户时不得进入编辑页。
                        if (ensureComposerAccess(context)) {
                            val arg = featureQuery.categoryId?.let { "?categoryId=$it" } ?: ""
                            navController.navigate("${Routes.NEW_TOPIC}$arg")
                        }
                    },
                    onMenuClick = { homeViewModel.openDrawer() }
                )
            }
            composable(Routes.NOTIFICATION) {
                NotificationScreen(
                    onOpenTopic = { topicId, slug, title, postNumber ->
                        val parts = mutableListOf<String>()
                        slug?.takeIf { it.isNotEmpty() }?.let { parts.add("slug=$it") }
                        title?.takeIf { it.isNotEmpty() }?.let { parts.add("title=${java.net.URLEncoder.encode(it, "UTF-8")}") }
                        postNumber?.let { parts.add("target=$it") }
                        val q = if (parts.isEmpty()) "" else "?" + parts.joinToString("&")
                        navController.navigate("${Routes.TOPIC_DETAIL}/$topicId$q")
                    },
                    onOpenUser = { uname, displayName, avatarTemplate ->
                        if (uname.isEmpty()) return@NotificationScreen
                        val q = buildString {
                            displayName?.takeIf { it.isNotEmpty() }?.let {
                                append("?displayName=${java.net.URLEncoder.encode(it, "UTF-8")}")
                            }
                            avatarTemplate?.takeIf { it.isNotEmpty() }?.let {
                                if (isNotEmpty()) append("&") else append("?")
                                append("avatar=${java.net.URLEncoder.encode(it, "UTF-8")}")
                            }
                        }
                        navController.navigate("${Routes.USER_PROFILE}/$uname$q")
                    }
                )
            }
            composable(Routes.PROFILE) {
                // 底部 Tab「我的」:沉浸式内容(无返回/设置栏,顶到状态栏),与独立页共用 ProfileScreen。
                ProfileScreen(
                    onOpenLogin = {
                        // 未登录「立即登录」→ 请求根层弹登录半窗。
                        LoginGate.requestLogin()
                    },
                    // 「全部 ›」/快捷入口的点击目标走根导航(徽章页、动态页、通用 WebView 页
                    // 都压在根栈上)。这些参数缺省时 ProfileScreen 会走空回调,点了没反应。
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
                    onOpenWeb = { url, title ->
                        if (url.isNotEmpty()) {
                            val q = title?.takeIf { it.isNotEmpty() }
                                ?.let { "?title=${java.net.URLEncoder.encode(it, "UTF-8")}" }
                                .orEmpty()
                            navController.navigate("${Routes.WEBVIEW}/${java.net.URLEncoder.encode(url, "UTF-8")}$q")
                        }
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
internal enum class RootTabDestination(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    LEGACY_HOME(
        route = Routes.HOME,
        label = "首页",
        selectedIcon = Icons.Filled.Home,
        unselectedIcon = Icons.Outlined.Home
    ),
    NEW_HOME(
        route = Routes.NEW_HOME,
        label = "新首页",
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

internal val rootTabDestinations = listOf(
    RootTabDestination.LEGACY_HOME,
    RootTabDestination.NEW_HOME,
    RootTabDestination.NOTIFICATION,
    RootTabDestination.PROFILE
)
