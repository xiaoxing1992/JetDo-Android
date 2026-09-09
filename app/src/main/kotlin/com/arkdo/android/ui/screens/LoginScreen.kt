@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.arkdo.android.ui.screens

import android.app.Activity
import android.content.Context
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.arkdo.android.common.constants.AppConstants
import com.arkdo.android.services.network.ArkWebNetworkBridge
import com.arkdo.android.services.session.LOGIN_GATE_LOG_TAG
import com.arkdo.android.services.session.SessionEventBus
import com.arkdo.android.services.web.CookiePresenceSnapshot
import com.arkdo.android.services.web.WebSessionManager
import com.arkdo.android.ui.theme.ArkdoFixedColors
import com.arkdo.android.ui.theme.arkdoColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

// ==========================================================================
// 登录页（逐行对齐鸿蒙 views/pages/LoginPage.ets）。
//
// 顶栏（60dp，标题「登录」居中，底部分割线）+ WebView 加载站点 /login。
// 登录成功有三条检测链路，任一命中即走统一出口 signalLoginSuccess：
// 1. 登录态 cookie 轮询：每 800ms 查 `_t`；从「无」变「有」判成功 —— Discourse SPA
//    登录走客户端路由（pushState），不触发 onPageEnd，单靠 URL 判定会漏检。
// 2. onPageEnd URL 判定：落在站点域且不是 /login、/challenge 即成功。
// 3. CF 盾恢复：401/403/429（login/challenges.cloudflare.com）→ 每 1s 查 cf_clearance
//    （上限 90 次）→ 拿到即重载 /login（单次守卫）。
//
// 成功统一出口（单次守卫）：停两路轮询 → 落盘 cookie → onLoginSuccess
// （markHostReloading + SessionEventBus + 复查会话写 LoginGate + 关页），
// 对齐鸿蒙 RootTabPage.handleLoginSuccess。
//
// 收键盘桥：登录表单提交时若键盘还杵着，hCaptcha 验证弹层会被裁掉一截没法完成验证；
// 注入脚本在「点非输入元素」那一刻摘焦点收键盘（对齐 LoginKeyboardBridge，含桥名）。
// 鸿蒙把注入时机绑在「键盘弹起」（avoidAreaChange），Android 用 imeInsets 可见性同帧触发。
// ==========================================================================

private const val LOG_TAG = "[ArkDO][Login]"
private const val KBD_TAG = "[ArkDO][LoginKbd]"
private const val CHALLENGE_POLL_INTERVAL_MS = 1000L
private const val MAX_CHALLENGE_POLL_COUNT = 90
private const val LOGIN_POLL_INTERVAL_MS = 800L
private const val AUTH_COOKIE_NAME = "_t"

/**
 * 页面侧唯一能调到的东西：请求收起软键盘。无参、无返回值、拿不到任何应用数据。
 * 桥名取得长而怪是刻意的（对齐鸿蒙）：bridge/native/app 这类通名容易被无关脚本探到。
 */
private class LoginKeyboardProxy(private val onHideKeyboard: () -> Unit) {
    @JavascriptInterface
    fun dismissKeyboard(reason: String) {
        // reason 是触发来源(touchstart/submit/…),只在日志里截短,不参与任何逻辑。
        val tag = if (reason.length <= 32) reason else reason.substring(0, 32)
        Timber.i("%s dismissKeyboard by=%s", KBD_TAG, tag)
        onHideKeyboard()
    }
}

/** 注入脚本，1:1 转自鸿蒙 LoginKeyboardBridge.script()（含去重标记与事件顺序）。 */
private object LoginKeyboardScript {
    private const val BRIDGE_NAME = "arkdoLoginKeyboard7f3a"

    val SCRIPT: String = """
        (function () {
          if (window.__arkdoLoginKbdReady) { return; }
          window.__arkdoLoginKbdReady = true;

          function isEditable(node) {
            if (!node || !node.tagName) { return false; }
            var tag = node.tagName.toLowerCase();
            if (tag === 'input' || tag === 'textarea' || tag === 'select') { return true; }
            return node.isContentEditable === true;
          }

          var lastAt = 0;
          function dismiss(reason) {
            var now = Date.now();
            if (now - lastAt < 400) { return; }
            lastAt = now;
            try { $BRIDGE_NAME.dismissKeyboard(reason); } catch (e) {}
            try {
              var el = document.activeElement;
              if (el && typeof el.blur === 'function') { el.blur(); }
            } catch (e) {}
          }

          ['touchstart', 'pointerdown', 'mousedown'].forEach(function (type) {
            document.addEventListener(type, function (event) {
              if (!isEditable(event.target)) { dismiss(type); }
            }, true);
          });

          document.addEventListener('submit', function () { dismiss('submit'); }, true);
        })();
    """.trimIndent()
}

@Composable
fun LoginScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    sessionViewModel: SessionViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = arkdoColors

    // 登录态轮询基线：进页时 `_t` 是否已在（常态为无），只在其后新出现时才判成功。
    var initialAuthCookiePresent by remember { mutableStateOf(false) }
    var hasSignaledLoginSuccess by remember { mutableStateOf(false) }
    var hasReloadedAfterChallenge by remember { mutableStateOf(false) }
    var challengePollJob by remember { mutableStateOf<Job?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    fun logCfClearanceSnapshot(stage: String) {
        val snapshot: CookiePresenceSnapshot = WebSessionManager.getCookiePresenceSnapshot("cf_clearance")
        Timber.i("%s cf_clearance snapshot stage=%s exists=%b cookieHeaderLength=%d",
            LOG_TAG, stage, snapshot.hasCookie, snapshot.headerLength)
    }

    fun isLikelyLoggedInUrl(url: String): Boolean {
        if (!url.startsWith(AppConstants.BASE_URL)) return false
        if (url.startsWith(AppConstants.LOGIN_URL) || url.startsWith(AppConstants.CHALLENGE_URL)) return false
        return true
    }

    fun isChallengeRelatedHttpError(url: String, statusCode: Int): Boolean {
        if (statusCode != 401 && statusCode != 403 && statusCode != 429) return false
        return url.startsWith(AppConstants.LOGIN_URL) || url.contains("challenges.cloudflare.com")
    }

    fun hideNativeKeyboard() {
        (context as? Activity)?.currentFocus?.let { view ->
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    /**
     * 登录成功统一出口：单次守卫，停掉两路轮询、落盘 cookie，再通知上层
     * （对齐鸿蒙 signalLoginSuccess + handleLoginSuccess：关登录页 + 刷新会话）。
     */
    fun signalLoginSuccess(source: String, url: String) {
        if (hasSignaledLoginSuccess) return
        hasSignaledLoginSuccess = true
        Timber.i("%s login success signaled source=%s url=%s", LOG_TAG, source, url)
        challengePollJob?.cancel()
        challengePollJob = null
        scope.launch {
            WebSessionManager.saveCookies("LoginPage")
            // 对齐 RootTabPage.handleLoginSuccess：先关就绪门闩，再广播会话变化，
            // 并立即复查 /session/current.json 把 LoginGate 翻成已登录。
            ArkWebNetworkBridge.markHostReloading()
            SessionEventBus.notifySessionChanged()
            sessionViewModel.checkCurrentUser()
            onNavigateBack()
        }
    }

    // CF 盾恢复：cf_clearance 一到就重载 /login（单次守卫，上限 90 次）。
    fun startChallengeCookiePolling() {
        if (challengePollJob?.isActive == true || hasReloadedAfterChallenge) return
        Timber.i("%s cf_clearance polling start", LOG_TAG)
        challengePollJob = scope.launch {
            var count = 0
            while (isActive && count < MAX_CHALLENGE_POLL_COUNT) {
                delay(CHALLENGE_POLL_INTERVAL_MS)
                count++
                val snapshot = WebSessionManager.getCookiePresenceSnapshot("cf_clearance")
                if (snapshot.hasCookie) {
                    Timber.i("%s cf_clearance detected, reload login", LOG_TAG)
                    WebSessionManager.saveCookies("LoginPage")
                    hasReloadedAfterChallenge = true
                    webViewRef?.loadUrl(AppConstants.LOGIN_URL)
                    return@launch
                }
                if (count % 5 == 0) {
                    Timber.i("%s cf_clearance pending attempt=%d/%d cookieHeaderLength=%d",
                        LOG_TAG, count, MAX_CHALLENGE_POLL_COUNT, snapshot.headerLength)
                }
            }
            Timber.i("%s cf_clearance polling timeout", LOG_TAG)
        }
    }

    // 进页先记 `_t` 基线，再开 800ms 登录态轮询（对齐 aboutToAppear）。
    LaunchedEffect(Unit) {
        initialAuthCookiePresent = WebSessionManager.hasCookie(AUTH_COOKIE_NAME)
        while (isActive) {
            delay(LOGIN_POLL_INTERVAL_MS)
            val hasAuthCookie = WebSessionManager.hasCookie(AUTH_COOKIE_NAME)
            if (hasAuthCookie && !initialAuthCookiePresent) {
                signalLoginSuccess("cookiePoll", "")
            }
        }
    }

    // 收键盘脚本注入时机：绑在「软键盘弹起」上（对齐鸿蒙 avoidAreaChange 监听）。
    // 键盘弹起意味着用户已点进表单输入框：文档一定是最终那个且可交互。每次弹起都注入，
    // 脚本自身有 __arkdoLoginKbdReady 标记挡重复。
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) {
        if (imeVisible) {
            webViewRef?.evaluateJavascript(LoginKeyboardScript.SCRIPT, null)
        }
    }

    // 弹层呈现：底部半弹窗、默认停在约 88% 高度（大于半屏、不到全屏），可下拉滑落关闭，
    // 也保留继续上滑到满高的能力（鸿蒙 bindSheet 是 height 100% 满高；按用户要求改为
    // 默认近满高的 modal 档位，视觉对齐苹果模态）。拖动条 dragBar、遮罩 SCRIM、
    // 底色 surface_card、点外部关闭。
    ModalBottomSheet(
        onDismissRequest = onNavigateBack,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceMuted,
        scrimColor = ArkdoFixedColors.Scrim,
        dragHandle = { androidx.compose.material3.BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(fraction = 0.88f)
                .background(colors.surfaceMuted)
        ) {
        // 顶栏：标题「登录」居中（20sp Medium）+ 右上角关闭（bindSheet showClose），
        // 底部 1dp 分割线。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .background(colors.surfaceMuted)
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = "登录",
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.align(Alignment.Center)
            )
            Text(
                text = "✕",
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .clickable { onNavigateBack() }
                    .padding(8.dp)
            )
        }
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            factory = { ctx ->
                WebView(ctx).apply {
                    // Cookie/UA 配置(对齐 configureCookieAccess / applyUserAgent)+ Web 开关
                    // 对齐 Web(...) 的 javaScriptAccess/domStorageAccess/databaseAccess 等参数。
                    WebSessionManager.configureCookieAccess(this, "LoginPage")
                    WebSessionManager.applyUserAgent(this, "LoginPage")
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    // 人机验证弹层(Challenges iframe)需要跨域 cookie 与 DOM 存储,这里放行
                    // 三方 cookie,否则 Cloudflare Turnstile 页面一片空白什么都渲染不出来。
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    addJavascriptInterface(LoginKeyboardProxy { hideNativeKeyboard() }, "arkdoLoginKeyboard7f3a")

                    webChromeClient = WebChromeClient()

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            Timber.i("%s page end url=%s", LOG_TAG, url)
                            scope.launch { WebSessionManager.saveCookies("LoginPage") }
                            logCfClearanceSnapshot("pageEnd")
                            if (isLikelyLoggedInUrl(url)) {
                                signalLoginSuccess("pageEnd", url)
                            }
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            val url = request?.url?.toString().orEmpty()
                            val code = error?.errorCode ?: 0
                            val info = error?.description?.toString().orEmpty()
                            Timber.i("%s load error url=%s code=%d info=%s", LOG_TAG, url, code, info)
                        }

                        override fun onReceivedHttpError(
                            view: WebView,
                            request: WebResourceRequest,
                            response: WebResourceResponse
                        ) {
                            val url = request.url?.toString().orEmpty()
                            val statusCode = response.statusCode
                            Timber.i("%s http error url=%s status=%d reason=%s",
                                LOG_TAG, url, statusCode, response.reasonPhrase.orEmpty())
                            if (isChallengeRelatedHttpError(url, statusCode)) {
                                startChallengeCookiePolling()
                            }
                        }
                    }
                    webViewRef = this
                    loadUrl(AppConstants.LOGIN_URL)
                }
            },
            onRelease = { webViewRef = null }
        )
        }
    }
}
