package com.jetdo.android.navigation

import com.jetdo.android.core.data.AppContextHolder
import androidx.compose.ui.res.stringResource
import com.jetdo.android.R
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.jetdo.android.core.designsystem.LocalSharedSearchTransition
import com.jetdo.android.core.designsystem.LocalAnimatedAvatarEnabled
import com.jetdo.android.core.designsystem.SEARCH_ICON_SHARED_KEY
import com.jetdo.android.core.designsystem.SharedSearchTransition
import com.jetdo.android.services.avatar.AnimatedAvatarPrefs
import com.jetdo.android.navigation.noneEnterTransition
import com.jetdo.android.navigation.noneExitTransition
import com.jetdo.android.navigation.slideEnterTransition
import com.jetdo.android.navigation.slideExitTransition
import com.jetdo.android.navigation.slidePopEnterTransition
import com.jetdo.android.navigation.slidePopExitTransition
import com.jetdo.android.services.session.LoginGate
import com.jetdo.android.services.session.CurrentUserHolder
import com.jetdo.android.services.network.ArkWebNetworkBridge
import com.jetdo.android.services.topic.TwoPageReadingPrefs
import com.jetdo.android.ui.composer.NewTopicResume
import com.jetdo.android.ui.composer.NewTopicScreen
import com.jetdo.android.ui.composer.canUseComposer
import com.jetdo.android.ui.composer.ensureComposerAccess
import com.jetdo.android.common.constants.AppConstants
import com.jetdo.android.common.layout.LocalWideLayout
import com.jetdo.android.common.layout.WideLayoutHost
import com.jetdo.android.common.layout.rememberBookPosture
import com.jetdo.android.ui.components.ContentEmptyPane
import com.jetdo.android.ui.drafts.DraftResume
import com.jetdo.android.ui.drafts.DraftsScreen
import com.jetdo.android.ui.screens.DebugLogScreen
import com.jetdo.android.ui.screens.ExperimentalSettingsScreen
import com.jetdo.android.ui.qr.QrScanScreen
import com.jetdo.android.ui.screens.LoginScreen
import com.jetdo.android.ui.screens.ProfileScreen
import com.jetdo.android.ui.screens.PushSettingsScreen
import com.jetdo.android.ui.screens.SettingsScreen
import com.jetdo.android.ui.screens.TopicListStyleSettingsScreen
import com.jetdo.android.ui.screens.RootTabScreen
import com.jetdo.android.ui.screens.WebViewPage
import com.jetdo.android.ui.network.DohSettingsScreen
import com.jetdo.android.ui.network.NetworkSettingsScreen
import com.jetdo.android.ui.bookmarks.BookmarksScreen
import com.jetdo.android.ui.bookmarks.ReadTopicsScreen
import com.jetdo.android.ui.level.LevelProgressScreen
import com.jetdo.android.ui.credit.LdcCreditScreen
import com.jetdo.android.ui.user.BadgesScreen
import com.jetdo.android.ui.user.ActivityScreen
import com.jetdo.android.ui.user.SolvedPostsScreen
import com.jetdo.android.ui.user.UserProfileScreen
import com.jetdo.android.ui.topic.TopicDetailScreen

/**
 * JetDo 导航宿主
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
fun JetDoNavHost(
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

    // 通知深链切 tab 的信号。用自增数字而不是布尔值：连着点两条通知时布尔一直是 true、
    // LaunchedEffect 的 key 不变就不会重启，第二次点等于没反应。
    var notificationTabSignal by remember { mutableIntStateOf(0) }

    // 推送通知深链（jetdo://notification[?topicId=N&postNumber=M]）。
    // 订阅挂在这里而不是 RootTabScreen：RootTabScreen 只在 ROOT 目的地组合，
    // 用户正停在详情页/设置页时点通知，它根本不在组合里，事件就没人收了。
    androidx.compose.runtime.DisposableEffect(navController) {
        val unsubscribe = DeepLinkEventBus.subscribe { target ->
            when (target) {
                is DeepLinkTarget.Topic -> {
                    val query = target.postNumber?.let { "?target=$it" }.orEmpty()
                    navController.navigate("${Routes.TOPIC_DETAIL}/${target.topicId}$query")
                }
                DeepLinkTarget.NotificationInbox -> {
                    // 先把 ROOT 之上的页面摘掉再切 tab：用户点通知的预期是「回到通知中心」，
                    // 不是在原来的详情页/设置页之上再压一层。
                    navController.popBackStack(Routes.ROOT, inclusive = false)
                    notificationTabSignal++
                }
            }
        }
        onDispose { unsubscribe() }
    }

    // 动态头像开关：根部一次提供，全 App 的头像 / flair 渲染点读 LocalAnimatedAvatarEnabled
    // （app 的 UserAvatar 与 designsystem 的 TopicAvatar 各自消费，默认开）。
    val animatedAvatarEnabled by AnimatedAvatarPrefs.enabled.collectAsState()
    CompositionLocalProvider(LocalAnimatedAvatarEnabled provides animatedAvatarEnabled) {
    Box(modifier = modifier.fillMaxSize()) {
    // 共享元素转场需要有一个 SharedTransitionLayout 作为共同祖先：两端在这个布局的
    // 坐标系里各自报出 bounds，转场期间由它接管绘制（默认画在 overlay 上）。
    // 它必须包住 NavHost、而不是包住某一个页面 —— 两端分属两个目的地，
    // 只有同一个 scope 才能把它们认成一件事。
    SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
    val sharedScope = this
    // 同一把 key 只 remember 一次、两端共用。若两端各自 remember，会拿到两个互不相认的
    // SharedContentState：元素照常渲染但完全不产生动画，且不报任何错（最难查的一种）。
    val searchIconState = rememberSharedContentState(SEARCH_ICON_SHARED_KEY)
    // ── 双栏（平板横屏 / 折叠屏展开）──
    // 左栏必须提到 NavHost **外面**：NavHost 一次只显示一个目的地，左栏若要常驻就不能是它的
    // 目的地之一。提到 Row 里之后，所有 navController.navigate(TOPIC_DETAIL/…) 调用点一行都
    // 不用改 —— 它们驱动的仍是同一个 controller，变化的只是「目的地渲染在哪一栏」。
    //
    // 关键：下面 Box(weight(1f)) 是 NavHost 的**唯一调用点**（窄屏时不画左栏，它就占满整屏）。
    // 若把 NavHost 写进 if/else 两个分支，宽窄切换会因调用点位置变化而整体重建，
    // 导航栈、WebView 会话、各页滚动位置会丢一次。
    // 折叠信号不再由这里传入：它决定的是「左栏多宽、铰链要不要留白」，与「分不分栏」无关
    // —— 后者纯看窗口尺寸（见 WindowBreakpoints.isWideLayout，里面刻意不含折叠参数）。
    // WideLayoutHost 内部自己观察折叠特征，调用点因此少一个容易传错、退化后还静默的参数。
    //
    // 只有一个例外 —— 「双页阅读 + 图书姿态」：那时整块屏要交给详情页自己去分左右两页，
    // 外层再分一次栏会把详情页压进一个半屏里，双页就没地方放了。此时强制按单栏走。
    // 开关关着、或设备没折起来时恒为 false，主布局行为与改动前完全一致。
    //
    // 注意「强制单栏」的生效范围：**只有详情页在前台时**。双页阅读要用整块屏的是详情页，
    // 首页/通知/我的没有这个需求；若按全局算，开关一开整 App 都塌成单栏（实测在折叠 AVD 上
    // 三列 tab 全部变单栏），读起来像「App 被切成单屏幕了」，与「双页阅读」这个开关名对不上。
    // 详情页 pop 回 ROOT 时这里自动恢复双栏，不需要额外收尾。
    val twoPageReadingEnabled by TwoPageReadingPrefs.enabled.collectAsState()
    val currentEntry by navController.currentBackStackEntryAsState()
    val onTopicDetail =
        currentEntry?.destination?.route?.startsWith(Routes.TOPIC_DETAIL) == true
    val forceSinglePane = onTopicDetail && twoPageReadingEnabled && rememberBookPosture()
    WideLayoutHost(forceSinglePane = forceSinglePane) { split ->
    Row(modifier = Modifier.fillMaxSize()) {
        if (split.isWide) {
            // 左栏：当前 tab 的内容常驻。顶栏放大镜在这里**不**参与共享元素转场 ——
            // visibilityScope 属于 NavHost 内部的目的地，本层拿不到；
            // SharedSearchTransition 三字段全空时装饰器会退化成 no-op，天然降级，不必特判。
            Box(
                modifier = Modifier
                    .width(split.navBarWidth)
                    .fillMaxHeight()
            ) {
                RootTabScreen(
                    isWebViewReady = isWebViewReady,
                    navController = navController,
                    notificationTabSignal = notificationTabSignal
                )
            }
            // 铰链留白：左右分栏的分割线已经压到铰链左缘（见 WindowBreakpoints.splitGeometry），
            // 铰链若真占物理宽度（Surface Duo 那类双屏），这截空隙必须真的留出来，否则右栏内容
            // 会压在铰链上被切断。可折叠屏的折痕是 1px 级的线，这里恒为 0，等于没插。
            if (split.hingeGapWidth > 0.dp) {
                Spacer(
                    modifier = Modifier
                        .width(split.hingeGapWidth)
                        .fillMaxHeight()
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .weight(1f)
        ) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier.fillMaxSize(),
        // 宽屏（左右双栏）下右栏不做横向位移、内容直接替换：左栏原地不动时，
        // 「整页从右缘滑入」读起来不是层级变化而是抽搐（完整理由见 ScreenTransitions.kt）。
        // 窄屏保持原有 push/pop 滑动。
        enterTransition = if (split.isWide) noneEnterTransition else slideEnterTransition,
        exitTransition = if (split.isWide) noneExitTransition else slideExitTransition,
        popEnterTransition = if (split.isWide) noneEnterTransition else slidePopEnterTransition,
        popExitTransition = if (split.isWide) noneExitTransition else slidePopExitTransition
    ) {
        // Root Tab 容器（首页、通知、个人中心）
        composable(Routes.ROOT) {
            if (LocalWideLayout.current) {
                // 宽屏：左栏已由 Row 常驻渲染，本目的地此刻只代表「右栏还没选内容」这一种情形，
                // 显示品牌空态即可。若在这里再渲染一次 RootTabScreen，屏幕上会同时出现两份左栏。
                ContentEmptyPane()
            } else {
            // visibilityScope 逐目的地不同（它就是本目的地这次转场的作用域），
            // 只能在目的地内部就地提供 —— 见 SharedSearchTransition 的字段注释。
            val visibilityScope = this
            CompositionLocalProvider(
                LocalSharedSearchTransition provides
                    SharedSearchTransition(
                        scope = sharedScope,
                        contentState = searchIconState,
                        visibilityScope = visibilityScope,
                        boundsDurationMillis = SCREEN_TRANSITION_DURATION
                    )
            ) {
                RootTabScreen(
                    isWebViewReady = isWebViewReady,
                    navController = navController,
                    notificationTabSignal = notificationTabSignal
                )
            }
            }
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
                // 未登录卡片下方的「扫码登录」→ 相机扫码页（压在根栈上）。
                onScanQr = { navController.navigate(Routes.QR_SCAN) },
                // 最近活动行 → 原生详情页并定位到该动态所在楼层（对齐鸿蒙 ProfilePage.onOpenTopic）。
                onOpenTopic = { topicId, title, postNumber ->
                    val parts = mutableListOf<String>()
                    title.takeIf { it.isNotBlank() }?.let { parts.add("title=${java.net.URLEncoder.encode(it, "UTF-8")}") }
                    postNumber?.let { parts.add("target=$it") }
                    val q = if (parts.isEmpty()) "" else "?" + parts.joinToString("&")
                    navController.navigate("${Routes.TOPIC_DETAIL}/$topicId$q")
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
        // 顶栏放大镜与搜索条放大镜之间是共享元素转场：转场期间 ROOT 与 SEARCH 同时在场，
        // 由上面的 SharedTransitionLayout 把两个 20dp / 16dp 的图标当作同一个元素补间。
        composable(Routes.SEARCH) {
            val visibilityScope = this
            CompositionLocalProvider(
                LocalSharedSearchTransition provides
                    SharedSearchTransition(
                        scope = sharedScope,
                        contentState = searchIconState,
                        visibilityScope = visibilityScope,
                        boundsDurationMillis = SCREEN_TRANSITION_DURATION
                    )
            ) {
                com.jetdo.android.ui.search.SearchFeatureScreen(
                    isWebViewReady = isWebViewReady,
                    onBack = { navController.popBackStack() },
                    onOpenTopic = { topicId, targetPostNumber ->
                        val q = targetPostNumber?.let { "?target=$it" } ?: ""
                        navController.navigate("${Routes.TOPIC_DETAIL}/$topicId$q")
                    },
                    onOpenUser = { username, userId ->
                        if (username.isNotEmpty()) {
                            navController.navigate("${Routes.USER_PROFILE}/$username?userId=$userId")
                        }
                    }
                )
            }
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
                    // userId 必须透传：头像方圆按 id==1 判，种子缺 id 会在资料到达后闪一下。
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
                            if (isNotEmpty()) append("&") else append("?")
                            append("userId=${user.id}")
                        }
                        navController.navigate("${Routes.USER_PROFILE}/$username$q")
                    }
                },
                // 正文里的站内非帖子页 / 外站链接 → 内置 WebView 页（对齐鸿蒙 openWebPage）。
                // 目标是完整 URL，必须整段作为单个路径段编码：& / ? 不编码会被导航拆成 query 参数。
                onOpenWeb = { url, title ->
                    if (url.isNotEmpty()) {
                        val q = title?.takeIf { it.isNotEmpty() }
                            ?.let { "?title=${java.net.URLEncoder.encode(it, "UTF-8")}" }
                            .orEmpty()
                        navController.navigate("${Routes.WEBVIEW}/${java.net.URLEncoder.encode(url, "UTF-8")}$q")
                    }
                },
                // 正文 @提及 / /u/<username> 链接 → 原生他人主页。LinkRouter 只给出 username，
                // 没有展示名与头像可透传，主页自己按 username 拉数据。
                onOpenUserProfile = { username ->
                    if (username.isNotEmpty()) {
                        navController.navigate("${Routes.USER_PROFILE}/$username")
                    }
                }
            )
        }

        // 扫码登录·扫入端（「我的」页未登录卡片下方的入口）：相机扫码 → 兑换另一台设备的 OTP
        composable(Routes.QR_SCAN) {
            QrScanScreen(onNavigateBack = { navController.popBackStack() })
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
            route = Routes.USER_PROFILE + "/{username}?displayName={displayName}&avatar={avatar}&userId={userId}",
            arguments = listOf(
                navArgument("username") { type = NavType.StringType },
                navArgument("displayName") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("avatar") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("userId") { type = NavType.LongType; defaultValue = 0L }
            )
        ) { entry ->
            val args = entry.arguments
            UserProfileScreen(
                username = args?.getString("username").orEmpty(),
                seedDisplayName = args?.getString("displayName"),
                seedAvatarTemplate = args?.getString("avatar"),
                seedUserId = args?.getLong("userId") ?: 0L,
                onNavigateBack = { navController.popBackStack() },
                onOpenUser = { uname, dname, avatarTemplate, uid ->
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
                            if (isNotEmpty()) append("&") else append("?")
                            append("userId=$uid")
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
                navArgument("title") { type = NavType.StringType; nullable = true; defaultValue = AppContextHolder.getString(R.string.user_activity) }
            )
        ) { entry ->
            val args = entry.arguments
            ActivityScreen(
                username = args?.getString("username").orEmpty(),
                filter = args?.getString("filter") ?: "4,5",
                pageTitle = args?.getString("title") ?: stringResource(R.string.user_activity),
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
                        com.jetdo.android.services.session.SessionEventBus.notifySessionChanged()
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
        // 不声明任何 enter/exit/pop:沿用 NavHost 级的横向 push,与进入帖子详情同向同时长。
        // (曾试过纵向专属转场,实测全 App 只此一处纵向会被读成「方向不对」,已回退。)
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
                onOpenTopicListStyle = { navController.navigate(Routes.SETTINGS_TOPIC_LIST_STYLE) },
                onOpenNetworkSettings = { navController.navigate(Routes.SETTINGS_NETWORK) },
                onOpenPushSettings = { navController.navigate(Routes.SETTINGS_PUSH) }
            )
        }
        composable(Routes.SETTINGS_EXPERIMENTAL) {
            ExperimentalSettingsScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS_TOPIC_LIST_STYLE) {
            TopicListStyleSettingsScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS_NETWORK) {
            NetworkSettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onOpenDoh = { navController.navigate(Routes.SETTINGS_DOH) }
            )
        }
        // 通知子页。Routes.SETTINGS_PUSH 这个常量从建立之初就存在，但一直没注册过 composable
        // ——「声明了没人用」的悬空路由，正好在迁移通知页时接上，不另起新名字。
        composable(Routes.SETTINGS_PUSH) {
            PushSettingsScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS_DOH) {
            DohSettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                // DoH 配置变更后重载宿主 WebView：新的 DNS 只对之后发出的请求生效，
                // 已加载的页面不重载就还在用旧的解析结果。
                onDohApplied = { ArkWebNetworkBridge.reloadHost() }
            )
        }
        }
    }
        }
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
    } // CompositionLocalProvider(LocalAnimatedAvatarEnabled)
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
    const val DEBUG_LOG = "debug_log"
    const val QR_SCAN = "qr_scan"

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
