package com.arkdo.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.arkdo.android.navigation.ArkDoNavHost
import com.arkdo.android.services.challenge.ChallengeEventBus
import com.arkdo.android.services.challenge.ChallengeRecoveryCoordinator
import com.arkdo.android.services.challenge.ChallengeRecoveryEventBus
import com.arkdo.android.services.network.ArkWebNetworkBridge
import com.arkdo.android.services.session.AppStateEventBus
import com.arkdo.android.services.session.observeLifecycle
import com.arkdo.android.services.web.WebSessionManager
import com.arkdo.android.ui.components.ArkWebHost
import com.arkdo.android.ui.screens.PrivateDnsGuideScreen
import com.arkdo.android.ui.theme.ArkDoTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 主 Activity
 *
 * 单 Activity 架构,所有页面通过 Jetpack Compose Navigation 管理。
 *
 * 启动流程：
 * 1. 安装 Splash Screen（Android 12+ 自动适配）
 * 2. 启用 Edge-to-Edge 沉浸式布局
 * 3. 监听前后台状态（AppStateEventBus）
 * 4. 加载主题（跟随系统/强制浅色/强制深色）
 * 5. 处理推送通知深链接
 * 6. 渲染 RootNavHost（Tab + Navigation Stack）
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var challengeCoordinator: ChallengeRecoveryCoordinator? = null
    private var unsubscribeAppState: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Timber.i("[ArkDO][MainActivity] onCreate ts=%d", System.currentTimeMillis())

        // 监听前后台状态（onStart → 前台，onStop → 后台）
        AppStateEventBus.observeLifecycle(this)

        // 退后台把 WebView cookie(含 cf_clearance)刷盘,对齐鸿蒙 EntryAbility.onBackground
        // 的 saveCookies('onBackground')。否则进程被杀时未落盘的 clearance 丢失,
        // 下次冷启动被迫重新过盾。
        unsubscribeAppState = AppStateEventBus.subscribe { isForeground ->
            if (!isForeground) {
                lifecycleScope.launch {
                    WebSessionManager.saveCookies("onBackground")
                }
            }
        }

        // 设置 WebView 下载上下文
        ArkWebNetworkBridge.setDownloadContext(this)

        // 清理媒体缓存（冷启动）
        ArkWebNetworkBridge.clearMediaCacheDir(this)

        // Edge-to-Edge:沉浸式状态栏和导航栏。全 app 各页自行垫 statusBars/navigationBars inset
        // (RootTabScreen 已把 Scaffold 的 contentWindowInsets 关掉)。曾因 WebView 输入框不随键盘
        // 顶起而关掉过它,但关掉后状态栏区露出窗口背景(灰),首页不再沉浸;正确做法是保留
        // Edge-to-Edge,由承载 WebView 的页面自己 imePadding(见 LoginScreen/WebViewPage)。
        // 另:targetSdk 35+ 在 Android 15 上系统强制 Edge-to-Edge,关掉也不生效,键盘问题
        // 终究要靠 imePadding 解决。
        enableEdgeToEdge()

        setContent {
            ArkDoTheme {
                var isWebViewReady by remember { mutableStateOf(false) }
                var showPrivateDnsGuide by remember { mutableStateOf(false) }
                var showChallengeSheet by remember { mutableStateOf(false) }

                // 初始化 Challenge 恢复协调器 + 订阅挑战信号 / 手动验证请求。
                //
                // 用 DisposableEffect 而不是 LaunchedEffect：两个总线都是进程级单例，订阅句柄必须
                // 在 Activity 重建（切暗色、旋转）时退订，否则每次重建都往总线里多挂一份，
                // 点一次「手动验证」会同时跑多份回调，其中还夹着已销毁 Activity 的旧协调器。
                DisposableEffect(Unit) {
                    challengeCoordinator = ChallengeRecoveryCoordinator(
                        onRecovered = {
                            Timber.i("[ArkDO][MainActivity] Challenge resolved, reloading WebView")
                            showChallengeSheet = false
                            // 重新加载 WebView，完成后再广播 SessionChanged
                            com.arkdo.android.services.network.ArkWebNetworkBridge.reloadHost {
                                Timber.i("[ArkDO][MainActivity] WebView reload complete, broadcasting SessionChanged")
                                // 广播会话刷新——复查登录态、刷新列表
                                com.arkdo.android.services.session.SessionEventBus.notifySessionChanged()
                            }
                        },
                        onEscalateToSheet = {
                            Timber.i("[ArkDO][MainActivity] Challenge escalated to user interaction")
                            showChallengeSheet = true
                        }
                    )

                    // 订阅 Challenge 事件
                    val unsubscribeChallengeEvent = ChallengeEventBus.subscribe { event ->
                        Timber.i("[ArkDO][MainActivity] Challenge detected: source=${event.source} status=${event.statusCode}")
                        challengeCoordinator?.handleChallengeSignal(event.source)
                    }

                    // 用户点详情页顶栏「手动验证」：停掉后台静默轮询，直接把验证页交给用户。
                    // 对齐鸿蒙 RootTabPage.onOpenChallenge = challengeRecovery.forceEscalate() + openChallengeSheet()。
                    // forceEscalate 在 COOLDOWN（刚过完盾 30s 内）会拒绝启动新一轮恢复，但验证页照开 ——
                    // 用户主动要求看一眼验证页，不该被冷却期挡回去。
                    val unsubscribeManualChallenge = ChallengeRecoveryEventBus.subscribeManual {
                        Timber.i("[ArkDO][MainActivity] manual challenge requested")
                        challengeCoordinator?.forceEscalate()
                        showChallengeSheet = true
                    }

                    onDispose {
                        unsubscribeChallengeEvent()
                        unsubscribeManualChallenge()
                        // 旧协调器必须销毁：它自身持有轮询协程，不 dispose 的话轮询会一直跑下去，
                        // 回调和状态却落在这份已经作废的 composition 上。
                        challengeCoordinator?.dispose()
                        challengeCoordinator = null
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box {
                        // 隐藏的 WebView 宿主（在底层，可见但透明度很低）
                        // 必须可见且在屏幕内，否则 Cloudflare Turnstile 无法完成验证
                        ArkWebHost(
                            onReady = {
                                Timber.i("[ArkDO][MainActivity] WebView bridge ready")
                                isWebViewReady = true
                            },
                            onLoadFailed = {
                                Timber.e("[ArkDO][MainActivity] WebView bridge load failed")
                                showPrivateDnsGuide = true
                            },
                            modifier = Modifier
                                .size(400.dp, 300.dp)  // 足够大以完成 Turnstile 验证
                                .alpha(0.01f)          // 几乎透明，用户基本看不见
                        )

                        // 主界面（在上层，完全覆盖 WebView）
                        ArkDoNavHost(isWebViewReady = isWebViewReady)

                        // Private DNS 配置引导页面（全屏覆盖）
                        if (showPrivateDnsGuide) {
                            PrivateDnsGuideScreen(
                                onDismiss = { showPrivateDnsGuide = false },
                                onConfigured = {
                                    showPrivateDnsGuide = false
                                    // 用户配置完成后，可以触发 WebView 重新加载
                                    // 这里暂时只关闭引导页，WebView 会在下次冷启动时重试
                                }
                            )
                        }

                        // Cloudflare Challenge 验证页面（全屏覆盖）
                        if (showChallengeSheet) {
                            com.arkdo.android.ui.screens.CfChallengeScreen(
                                bridge = com.arkdo.android.services.network.ArkWebNetworkBridge,
                                onClose = {
                                    // 必须告知协调器用户放弃了：否则状态机停在 AWAITING_SHEET，
                                    // 而 forceEscalate() 恰好拒绝 AWAITING_SHEET —— 手动入口接上后
                                    // 「关一次验证页 = 手动验证永久失效」。这行是和入口配套的，不能少。
                                    challengeCoordinator?.notifySheetDismissed()
                                    showChallengeSheet = false
                                    Timber.i("[ArkDO][MainActivity] Challenge sheet closed by user")
                                },
                                onChallengeResolved = {
                                    showChallengeSheet = false
                                    Timber.i("[ArkDO][MainActivity] Challenge resolved, closing sheet")
                                    challengeCoordinator?.notifySheetResolved()
                                }
                            )
                        }
                    }
                }
            }
        }

        // 处理推送通知深链接（如果是从通知点击启动）
        handleNotificationIntent()
    }

    override fun onDestroy() {
        unsubscribeAppState?.invoke()
        unsubscribeAppState = null
        super.onDestroy()
    }

    private fun handleNotificationIntent() {
        val data = intent?.data
        if (data?.scheme == "arkdo" && data.host == "notification") {
            // 解析并导航到对应页面（Topic / Notification）
            val topicId = data.getQueryParameter("topicId")
            val postNumber = data.getQueryParameter("postNumber")
            Timber.i("[ArkDO][MainActivity] notification intent topicId=$topicId postNumber=$postNumber")
            // TODO: 通过 Navigation 跳转到详情页
        }
    }
}
