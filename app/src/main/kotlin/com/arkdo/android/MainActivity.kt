package com.arkdo.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.lifecycleScope
import com.arkdo.android.navigation.JetDoNavHost
import com.arkdo.android.navigation.DeepLinkEventBus
import com.arkdo.android.services.challenge.ChallengeEventBus
import com.arkdo.android.services.challenge.ChallengeRecoveryCoordinator
import com.arkdo.android.services.challenge.ChallengeRecoveryEventBus
import com.arkdo.android.services.logging.FileLoggerDaemon
import com.arkdo.android.services.network.ArkWebNetworkBridge
import com.arkdo.android.services.session.AppStateEventBus
import com.arkdo.android.services.session.observeLifecycle
import com.arkdo.android.services.upload.VideoTranscoder
import com.arkdo.android.services.web.WebSessionManager
import com.arkdo.android.ui.components.ArkWebHost
import com.arkdo.android.ui.screens.PrivateDnsGuideScreen
import com.arkdo.android.ui.theme.JetDoTheme
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
        // installSplashScreen 只为 pre-31 的主题交接（postSplashScreenTheme）；
        // 31+ 系统闪屏首帧即收，不做 keep-on-screen 扣留——那套机制在不同 API
        // 上行为分叉（35 上闪屏提前自撤；部分真机上 starting window 干扰
        // 弹窗窗口触摸派发，按钮点了没反应）。闪屏停留的视觉由内容层自绘。
        installSplashScreen()
        super.onCreate(savedInstanceState)

        Timber.i("[JetDo][MainActivity] onCreate ts=%d", System.currentTimeMillis())

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
                // 文件日志同步刷盘。进程退到后台后随时可能被系统回收，缓冲里最后这几秒
                // 不落盘就永远没了 —— 而这几秒往往正是出问题的那一刻。必须同步做，
                // 丢进协程的话协同进程一起被杀，等于没做。
                FileLoggerDaemon.onBackground()
            }
        }

        // 设置 WebView 下载上下文
        ArkWebNetworkBridge.setDownloadContext(this)

        // 清理媒体缓存（冷启动）
        ArkWebNetworkBridge.clearMediaCacheDir(this)
        // 同理清掉遗留的视频转码产物：压缩一段 1080p 要十几秒，期间切后台被系统回收或崩溃时
        // finally 不会跑，产物（可能几十 MB）会永久留在 cacheDir 里。只删我们自己的前缀。
        VideoTranscoder.clearStaleOutputs(this)

        // Edge-to-Edge:沉浸式状态栏和导航栏。全 app 各页自行垫 statusBars/navigationBars inset
        // (RootTabScreen 已把 Scaffold 的 contentWindowInsets 关掉)。曾因 WebView 输入框不随键盘
        // 顶起而关掉过它,但关掉后状态栏区露出窗口背景(灰),首页不再沉浸;正确做法是保留
        // Edge-to-Edge,由承载 WebView 的页面自己 imePadding(见 LoginScreen/WebViewPage)。
        // 另:targetSdk 35+ 在 Android 15 上系统强制 Edge-to-Edge,关掉也不生效,键盘问题
        // 终究要靠 imePadding 解决。
        enableEdgeToEdge()

        setContent {
            JetDoTheme {
                var isWebViewReady by remember { mutableStateOf(false) }
                var showPrivateDnsGuide by remember { mutableStateOf(false) }
                var showChallengeSheet by remember { mutableStateOf(false) }
                // 首启隐私同意（合规：先同意后采集）。读的是落盘版本号，
                // 政策实质变更（POLICY_VERSION 递增）后老用户也会重见弹窗。
                var privacyConsentNeeded by remember {
                    mutableStateOf(com.arkdo.android.services.compliance.PrivacyConsentStore.needsConsent(this@MainActivity))
                }
                var showConsentExitConfirm by remember { mutableStateOf(false) }

                // 初始化 Challenge 恢复协调器 + 订阅挑战信号 / 手动验证请求。
                //
                // 用 DisposableEffect 而不是 LaunchedEffect：两个总线都是进程级单例，订阅句柄必须
                // 在 Activity 重建（切暗色、旋转）时退订，否则每次重建都往总线里多挂一份，
                // 点一次「手动验证」会同时跑多份回调，其中还夹着已销毁 Activity 的旧协调器。
                DisposableEffect(Unit) {
                    challengeCoordinator = ChallengeRecoveryCoordinator(
                        onRecovered = {
                            Timber.i("[JetDo][MainActivity] Challenge resolved, reloading WebView")
                            showChallengeSheet = false
                            // 重新加载 WebView，完成后再广播 SessionChanged
                            com.arkdo.android.services.network.ArkWebNetworkBridge.reloadHost {
                                Timber.i("[JetDo][MainActivity] WebView reload complete, broadcasting SessionChanged")
                                // 广播会话刷新——复查登录态、刷新列表
                                com.arkdo.android.services.session.SessionEventBus.notifySessionChanged()
                            }
                        },
                        onEscalateToSheet = {
                            Timber.i("[JetDo][MainActivity] Challenge escalated to user interaction")
                            showChallengeSheet = true
                        }
                    )

                    // 订阅 Challenge 事件
                    val unsubscribeChallengeEvent = ChallengeEventBus.subscribe { event ->
                        Timber.i("[JetDo][MainActivity] Challenge detected: source=${event.source} status=${event.statusCode}")
                        challengeCoordinator?.handleChallengeSignal(event.source)
                    }

                    // 用户点详情页顶栏「手动验证」：停掉后台静默轮询，直接把验证页交给用户。
                    // 对齐鸿蒙 RootTabPage.onOpenChallenge = challengeRecovery.forceEscalate() + openChallengeSheet()。
                    // forceEscalate 在 COOLDOWN（刚过完盾 30s 内）会拒绝启动新一轮恢复，但验证页照开 ——
                    // 用户主动要求看一眼验证页，不该被冷却期挡回去。
                    val unsubscribeManualChallenge = ChallengeRecoveryEventBus.subscribeManual {
                        Timber.i("[JetDo][MainActivity] manual challenge requested")
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
                                Timber.i("[JetDo][MainActivity] WebView bridge ready")
                                isWebViewReady = true
                            },
                            onLoadFailed = {
                                Timber.e("[JetDo][MainActivity] WebView bridge load failed")
                                showPrivateDnsGuide = true
                            },
                            modifier = Modifier
                                .size(400.dp, 300.dp)  // 足够大以完成 Turnstile 验证
                                .alpha(0.01f)          // 几乎透明，用户基本看不见
                        )

                        // 主界面（在上层，完全覆盖 WebView）。
                        // 未同意隐私政策前不组合：否则主界面先渲染、弹窗只是浮层，
                        // 内容从 dialog 周围透出（观感差 + 「先进产品再弹同意」的合规倒置）。
                        // 此时只有一个纯色底 + 模态弹窗；过盾 WebView 宿主照常引导，
                        // 同意后 NavHost 首次组合时 isWebViewReady 往往已经就绪。
                        if (!privacyConsentNeeded) {
                            JetDoNavHost(isWebViewReady = isWebViewReady)
                        }

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
                                    Timber.i("[JetDo][MainActivity] Challenge sheet closed by user")
                                },
                                onChallengeResolved = {
                                    showChallengeSheet = false
                                    Timber.i("[JetDo][MainActivity] Challenge resolved, closing sheet")
                                    challengeCoordinator?.notifySheetResolved()
                                }
                            )
                        }

                        // 首启隐私同意（置顶遮罩，盖过上面所有覆盖层）。
                        // 自绘全屏层而不是 AlertDialog：图标居中复刻系统闪屏、卡片沉底，
                        // 「闪屏停留 + 弹窗悬浮」在所有 API 上视觉一致，且触摸派发
                        // 不经过系统 starting window（真机踩过按钮无响应）。
                        // 模态：onDismissRequest 留空 = 点外部/Back 不关闭，必须二选一。
                        // 同意前 Bugly 未初始化（无远程采集），这是闸门的全部意义。
                        if (privacyConsentNeeded) {
                            Dialog(
                                onDismissRequest = { /* 必须明确选择，不接受关闭 */ },
                                properties = DialogProperties(
                                    usePlatformDefaultWidth = false,
                                    decorFitsSystemWindows = false
                                )
                            ) {
                                Box(Modifier.fillMaxSize()) {
                                    // 复刻系统闪屏的居中图标：ic_splash_icon 是圆 art
                                    // 贴边的专用资源（无 adaptive 背景层），系统闪屏与
                                    // 这里用同一 drawable，深浅色模式下都是干净的圆形。
                                    // 192dp = 系统闪屏对 windowSplashScreenAnimatedIcon
                                    // 的规范渲染直径，两段图标同源同尺寸无缝衔接。
                                    Image(
                                        painter = painterResource(R.drawable.ic_splash_icon),
                                        contentDescription = null,
                                        modifier = Modifier
                                            .align(Alignment.Center)
                                            .size(192.dp)
                                    )
                                    Card(
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .fillMaxWidth()
                                            .padding(16.dp)
                                            .navigationBarsPadding()
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(24.dp),
                                            verticalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Text(
                                                stringResource(R.string.privacy_consent_title),
                                                style = MaterialTheme.typography.headlineSmall
                                            )
                                            Text(
                                                stringResource(R.string.privacy_consent_body),
                                                style = MaterialTheme.typography.bodyMedium,
                                                modifier = Modifier
                                                    .heightIn(max = 360.dp)
                                                    .verticalScroll(rememberScrollState())
                                            )
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.End
                                            ) {
                                                TextButton(onClick = { showConsentExitConfirm = true }) {
                                                    Text(stringResource(R.string.privacy_consent_decline))
                                                }
                                                TextButton(onClick = {
                                                    com.arkdo.android.services.compliance.PrivacyConsentStore.agree(this@MainActivity)
                                                    (application as JetDoApplication).initCrashReportIfAllowed()
                                                    Timber.i("[JetDo][MainActivity] privacy consent given, crash report enabled")
                                                    privacyConsentNeeded = false
                                                }) { Text(stringResource(R.string.privacy_consent_agree)) }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 拒绝同意的二次确认：不做无声强杀，把后果说清再退。
                        if (showConsentExitConfirm) {
                            androidx.compose.material3.AlertDialog(
                                onDismissRequest = { showConsentExitConfirm = false },
                                title = { Text(stringResource(R.string.privacy_consent_exit_title)) },
                                text = { Text(stringResource(R.string.privacy_consent_exit_body)) },
                                confirmButton = {
                                    TextButton(onClick = {
                                        Timber.i("[JetDo][MainActivity] user declined privacy consent, exiting")
                                        finishAffinity()
                                    }) { Text(stringResource(R.string.privacy_consent_exit_confirm)) }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showConsentExitConfirm = false }) {
                                        Text(stringResource(R.string.privacy_consent_exit_cancel))
                                    }
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

    /**
     * App 还活着时又点了一条通知（或从外部点开 `jetdo://` 链接）。
     *
     * 必须配合 Manifest 里的 `android:launchMode="singleTop"`：否则 standard 模式下
     * 系统会再建一个 MainActivity 实例，用户看到的是「点通知后 App 重开了一遍」——
     * Compose 那棵大树（含 WebView 会话、navController 栈）全部重建，过盾状态也可能丢。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 必须回写：handleNotificationIntent 读的是 activity.intent，
        // 不 setIntent 的话它拿到的还是启动时那个旧 intent。
        setIntent(intent)
        Timber.i("[JetDo][MainActivity] onNewIntent ts=%d", System.currentTimeMillis())
        handleNotificationIntent()
    }

    /**
     * 处理推送通知深链接（冷启动走 onCreate，热启动走 onNewIntent）。
     *
     * 这里只负责「解析 + 广播」，不做导航 —— 导航权在 JetDoNavHost 的 navController 上。
     * 冷启动时 NavHost 尚未组合，事件会先积压一份，等它订阅时补发（见 DeepLinkEventBus）。
     */
    private fun handleNotificationIntent() {
        val target = DeepLinkEventBus.parse(intent?.dataString) ?: return
        DeepLinkEventBus.dispatch(target)
    }
}
