package com.arkdo.android.ui.screens

import androidx.compose.ui.res.stringResource
import com.arkdo.android.R
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.arkdo.android.services.network.ArkWebNetworkBridge
import com.arkdo.android.services.web.WebSessionManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

private const val LOG_TAG = "[ArkDO][ChallengeScreen]"
private const val CHALLENGE_POLL_INTERVAL_MS = 1000L
private const val MAX_CHALLENGE_POLL_COUNT = 90
private const val CHALLENGE_URL = "https://linux.do/challenge"
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

    // 系统返回键:本页是盖在根容器上的覆盖层,不是导航路由。不在这里消费返回键,
    // 它会穿透到 Activity 被系统当成"退到桌面"。语义与关闭键一致:关掉验证页、留在原地。
    BackHandler { onClose() }

    // 顶栏与 WebView 必须竖向排列,且 WebView 用 weight(1f) 吃掉剩余高度。
    // 之前用 Scaffold(topBar=...) 时内容区从顶栏下方开始排,但 Material3 的 TopAppBar
    // 会把状态栏 inset 也吃进自身高度,且 Scaffold 的 contentWindowInsets 在
    // Edge-to-Edge 下与 WebView 的渲染高度容易算不到一起,挑战页内容(尤其是
    // Turnstile 控件)会被顶栏盖住。这里改成自己拼 Row/Column,inset 显式处理,
    // 与 WebViewPage 同一套做法,父子高度关系一目了然。
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .imePadding()
    ) {
        // 顶栏:标题居中 + 右上角关闭。状态栏 inset 由这里垫,内容顶到屏幕顶。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(56.dp)
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = stringResource(R.string.challenge_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.align(Alignment.Center)
            )
            IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close))
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

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
                        text = stringResource(R.string.challenge_service_unreachable),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = stringResource(R.string.challenge_script_failed),
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

                        // 第三方 cookie：Turnstile 控件跑在 challenges.cloudflare.com
                        // 的 iframe 里，验证态 cookie 对主站是第三方；API21+ 默认拒绝，
                        // 不开启则验证控件存不下状态（WebViewPage/Login 页均已配置）。
                        WebSessionManager.configureCookieAccess(this, "CfChallengeScreen")

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

                        // 关键：loadUrl 必须在 WebView 附着到窗口后执行。
                        // factory 执行时 View 刚创建、未附着且尺寸为 0，此时 loadUrl
                        // 在部分 WebView 版本上请求会被丢弃，页面空白不渲染。
                        var hasLoaded = false
                        fun tryLoadUrl() {
                            if (!hasLoaded) {
                                hasLoaded = true
                                Timber.i("$LOG_TAG loadUrl after attach")
                                loadUrl(CHALLENGE_URL)
                            }
                        }

                        addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
                            override fun onViewAttachedToWindow(v: android.view.View) {
                                tryLoadUrl()
                            }

                            override fun onViewDetachedFromWindow(v: android.view.View) {
                                // 验证页关闭即销毁，无需处理
                            }
                        })

                        // 已附着时 onViewAttachedToWindow 不会再触发，走下一帧兜底。
                        if (isAttachedToWindow) {
                            post { tryLoadUrl() }
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
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

