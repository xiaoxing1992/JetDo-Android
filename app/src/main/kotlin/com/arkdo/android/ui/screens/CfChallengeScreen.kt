package com.arkdo.android.ui.screens

import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.arkdo.android.services.network.ArkWebNetworkBridge
import com.arkdo.android.services.web.WebSessionManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

private const val LOG_TAG = "[ArkDO][ChallengeScreen]"
private const val CHALLENGE_POLL_INTERVAL_MS = 1000L
private const val MAX_CHALLENGE_POLL_COUNT = 90
private const val CHALLENGE_URL = "https://linux.do/session/csrf.json"
private const val CHALLENGE_RESOURCE_FAIL_HINT_THRESHOLD = 8

/**
 * Cloudflare Challenge 验证页面
 *
 * 对等 HarmonyOS 版 CfChallengePage.ets。
 * 功能：
 * - 加载挑战页面（显示 CF Turnstile 控件）
 * - 轮询检测 cf_clearance cookie 变化
 * - 监控 404 响应（验证成功标志）
 * - 检测挑战控件资源加载
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CfChallengeScreen(
    bridge: ArkWebNetworkBridge,
    onClose: () -> Unit,
    onChallengeResolved: () -> Unit
) {
    var hasResolved by remember { mutableStateOf(false) }
    var pollCount by remember { mutableIntStateOf(0) }
    var sawChallengeWidget by remember { mutableStateOf(false) }
    var challengeResourceFailCount by remember { mutableIntStateOf(0) }
    var showConnectivityHint by remember { mutableStateOf(false) }
    val initialCfClearance = remember { WebSessionManager.getCfClearanceValue() }
    val coroutineScope = rememberCoroutineScope()

    // 轮询 cf_clearance cookie
    LaunchedEffect(Unit) {
        Timber.i("$LOG_TAG cf_clearance polling start")
        while (!hasResolved && pollCount < MAX_CHALLENGE_POLL_COUNT) {
            delay(CHALLENGE_POLL_INTERVAL_MS)
            pollCount++

            val current = WebSessionManager.getCfClearanceValue()
            if (current.isNotEmpty() && current != initialCfClearance) {
                hasResolved = true
                Timber.i("$LOG_TAG cf_clearance changed pollCount=$pollCount")
                coroutineScope.launch {
                    WebSessionManager.saveCookies("CfChallengeScreen")
                }
                onChallengeResolved()
                break
            }

            if (pollCount % 5 == 0) {
                Timber.i("$LOG_TAG cf_clearance pending attempt=$pollCount/$MAX_CHALLENGE_POLL_COUNT")
            }
        }

        if (pollCount >= MAX_CHALLENGE_POLL_COUNT) {
            Timber.i("$LOG_TAG cf_clearance polling timeout")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Cloudflare 验证",
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                actions = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "关闭")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                // Scaffold 只垫系统栏,键盘 inset 另补:验证页偶有输入(如短信/邮箱验证码)。
                .imePadding()
        ) {
            // 连接失败提示条
            if (showConnectivityHint) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer,
                    tonalElevation = 2.dp
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "验证服务连不上",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = "Cloudflare 验证脚本多次无法加载,当前网络下验证可能无法完成。挂上代理后重新打开通常可解决。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
                HorizontalDivider()
            }

            // WebView 显示挑战页面
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                        // 应用统一 User-Agent
                        WebSessionManager.applyUserAgent(this, "CfChallengeScreen")

                        webViewClient = object : android.webkit.WebViewClient() {
                            /**
                             * 过盾页本身是 linux.do，同样要走 DoH 才解析得到。
                             * Turnstile 组件来自 challenges.cloudflare.com，拦截器会放行给系统 DNS，
                             * 与鸿蒙版 applyTransientMode 的取舍一致。
                             */
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: android.webkit.WebResourceRequest?
                            ): android.webkit.WebResourceResponse? =
                                com.arkdo.android.services.network.WebViewDohInterceptor.intercept(request)
                                    ?: super.shouldInterceptRequest(view, request)

                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                Timber.i("$LOG_TAG page begin url=${safeUrlLabel(url)}")
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                Timber.i("$LOG_TAG page end url=${safeUrlLabel(url)}")
                                coroutineScope.launch {
                                    WebSessionManager.saveCookies("CfChallengeScreen")
                                }

                                // 检查 cf_clearance 是否存在
                                val current = WebSessionManager.getCfClearanceValue()
                                if (!hasResolved && current.isNotEmpty() && current != initialCfClearance) {
                                    hasResolved = true
                                    Timber.i("$LOG_TAG cf_clearance changed at pageEnd")
                                    coroutineScope.launch {
                                        WebSessionManager.saveCookies("CfChallengeScreen")
                                    }
                                    onChallengeResolved()
                                }
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                super.onReceivedError(view, request, error)
                                val url = request?.url?.toString() ?: ""
                                val errorCode = error?.errorCode ?: 0
                                Timber.i("$LOG_TAG load error url=${safeUrlLabel(url)} code=$errorCode")

                                // 统计挑战资源失败次数
                                if (safeUrlLabel(url) == "cloudflare_challenge_resource") {
                                    challengeResourceFailCount++
                                    if (challengeResourceFailCount >= CHALLENGE_RESOURCE_FAIL_HINT_THRESHOLD) {
                                        showConnectivityHint = true
                                        Timber.i("$LOG_TAG challenge resource unreachable — hint shown failCount=$challengeResourceFailCount")
                                    }
                                }
                            }

                            override fun onReceivedHttpError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                errorResponse: WebResourceResponse?
                            ) {
                                super.onReceivedHttpError(view, request, errorResponse)
                                val url = request?.url?.toString() ?: ""
                                val statusCode = errorResponse?.statusCode ?: 0
                                val isMainFrame = request?.isForMainFrame ?: false

                                Timber.i("$LOG_TAG http error url=${safeUrlLabel(url)} status=$statusCode mainFrame=$isMainFrame")

                                // 挑战资源可达时清零失败计数
                                if (safeUrlLabel(url) == "cloudflare_challenge_resource") {
                                    challengeResourceFailCount = 0
                                    if (showConnectivityHint) {
                                        showConnectivityHint = false
                                    }
                                }

                                // 检测挑战完成（404 响应）
                                if (!hasResolved && statusCode == 404 && isMainFrame && sawChallengeWidget) {
                                    hasResolved = true
                                    Timber.i("$LOG_TAG challenge resolved by source 404")
                                    coroutineScope.launch {
                                        WebSessionManager.saveCookies("CfChallengeScreen")
                                    }
                                    onChallengeResolved()
                                }
                            }

                            override fun onLoadResource(view: WebView?, url: String?) {
                                super.onLoadResource(view, url)
                                // 检测 CF 挑战控件资源
                                if (safeUrlLabel(url) == "cloudflare_challenge_resource" && !sawChallengeWidget) {
                                    sawChallengeWidget = true
                                    Timber.i("$LOG_TAG CF challenge widget resource loaded — CF IS challenging")
                                }
                            }
                        }

                        loadUrl(CHALLENGE_URL)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }
}

private fun safeUrlLabel(url: String?): String {
    if (url.isNullOrEmpty()) return "empty"

    return when {
        url.startsWith("https://linux.do/session/csrf") -> "linuxdo_challenge"
        url == "https://linux.do" || url == "https://linux.do/" -> "linuxdo_home"
        url.startsWith("https://linux.do") -> "linuxdo_other"
        url.contains("challenges.cloudflare.com") -> "cloudflare_challenge_resource"
        url.contains("cloudflare") -> "cloudflare_other"
        else -> "other"
    }
}

