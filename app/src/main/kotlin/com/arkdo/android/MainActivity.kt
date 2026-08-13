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
import com.arkdo.android.navigation.ArkDoNavHost
import com.arkdo.android.services.challenge.ChallengeEventBus
import com.arkdo.android.services.challenge.ChallengeRecoveryCoordinator
import com.arkdo.android.services.network.ArkWebNetworkBridge
import com.arkdo.android.services.session.AppStateEventBus
import com.arkdo.android.services.session.observeLifecycle
import com.arkdo.android.ui.components.ArkWebHost
import com.arkdo.android.ui.screens.PrivateDnsGuideScreen
import com.arkdo.android.ui.theme.ArkDoTheme
import dagger.hilt.android.AndroidEntryPoint
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Timber.i("[ArkDO][MainActivity] onCreate ts=%d", System.currentTimeMillis())

        // 监听前后台状态（onStart → 前台，onStop → 后台）
        AppStateEventBus.observeLifecycle(this)

        // 设置 WebView 下载上下文
        ArkWebNetworkBridge.setDownloadContext(this)

        // 清理媒体缓存（冷启动）
        ArkWebNetworkBridge.clearMediaCacheDir(this)

        // Edge-to-Edge：沉浸式状态栏和导航栏
        enableEdgeToEdge()

        setContent {
            ArkDoTheme {
                var isWebViewReady by remember { mutableStateOf(false) }
                var showPrivateDnsGuide by remember { mutableStateOf(false) }
                var showChallengeSheet by remember { mutableStateOf(false) }

                // 初始化 Challenge 恢复协调器
                LaunchedEffect(Unit) {
                    challengeCoordinator = ChallengeRecoveryCoordinator(
                        onRecovered = {
                            Timber.i("[ArkDO][MainActivity] Challenge resolved, dismissing sheet")
                            showChallengeSheet = false
                        },
                        onEscalateToSheet = {
                            Timber.i("[ArkDO][MainActivity] Challenge escalated to user interaction")
                            showChallengeSheet = true
                        }
                    )

                    // 订阅 Challenge 事件
                    val unsubscribe = ChallengeEventBus.subscribe { event ->
                        Timber.i("[ArkDO][MainActivity] Challenge detected: source=${event.source} status=${event.statusCode}")
                        challengeCoordinator?.handleChallengeSignal(event.source)
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
                        ArkDoNavHost()

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
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                color = MaterialTheme.colorScheme.background
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    // 这里应该显示一个可见的 WebView 让用户完成验证
                                    // 暂时先显示提示信息
                                    androidx.compose.material3.Text(
                                        text = "正在完成 Cloudflare 验证...\n请稍候",
                                        modifier = Modifier.align(androidx.compose.ui.Alignment.Center),
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 处理推送通知深链接（如果是从通知点击启动）
        handleNotificationIntent()
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
