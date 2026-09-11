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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.statusBars
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
// 3. CF 盾恢复：主文档 401/403/429（login/challenges.cloudflare.com）→ 每 1s 查 cf_clearance
//    （上限 90 次）→ 值变化且页面仍停在盾页才重载 /login（单次守卫）。
//    Android 差异：鸿蒙不分帧、只判 exists 就重载；这里收紧,避免子资源 403 触发的
//    静默换 cookie 把正常表单/hCaptcha 弹层冲掉。
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

    /**
     * hCaptcha iframe 尺寸兜底(复选框 + 图像挑战两个 iframe)。
     *
     * 实测(华为 WebView / Chromium 114):hCaptcha api.js 把 iframe 留成 display:inline,
     * 复选框 iframe 不写尺寸、挑战 iframe 写了内联 width/height(如 320×400)—— 这版内核里
     * 两者的布局高度都停在 0,弹层中间/挑战框整块空白。hCaptcha 官方 demo 页能渲染是因为
     * 它的容器没有 Discourse 弹层那套 fixed+overflow 布局。鸿蒙 ArkWeb 内核更新,无此问题。
     * 这里每 500ms 查一次可见且高度为 0 的 hCaptcha iframe:复选框补标准 302×76,
     * 挑战框按 api.js 自己写的内联尺寸,以 display:block + !important 钉上。
     * 挑战框被隐藏(visibility:hidden 挪到屏外)时不碰,不干扰 hCaptcha 的显隐切换。
     *
     * 循环不设上限:关掉验证弹层再点登录,Discourse 会重建弹层、hCaptcha 重新 render 出
     * 新的 iframe,任何"跑够 N 次就停"的守卫都会让第二次弹层空白。登录页关闭时文档销毁,
     * 定时器随之消失,无泄漏。另挂一个 MutationObserver 在 iframe 刚插入时立刻补一次,
     * 减少 500ms 的空白闪烁。
     */
    val FIX: String = """
        (function () {
          if (window.__arkdoCaptchaFix) { return; }
          window.__arkdoCaptchaFix = true;
          function fix() {
            // 两类 iframe 同一个根因(这版内核 display:inline 的 iframe 不吃内联 height):
            //  - 复选框(#frame=checkbox):api.js 连尺寸都不写,直接补标准的 302×76。
            //  - 图像挑战(#frame=challenge):api.js 已写 width/height(如 320×400)并切到可见,
            //    但实际布局高度仍是 0,表现为挑战框整块白屏。按它自己写的尺寸重新以块级钉上。
            var frames = document.querySelectorAll('iframe[src*="hcaptcha.com/captcha"]');
            frames.forEach(function (f) {
              var r = f.getBoundingClientRect();
              var cs = getComputedStyle(f);
              if (r.height > 0 || cs.visibility !== 'visible') { return; }
              var isChallenge = f.src.indexOf('frame=challenge') >= 0;
              var isCheckbox = f.src.indexOf('frame=checkbox') >= 0;
              if (!isChallenge && !isCheckbox) { return; }
              var w = isChallenge ? (f.style.width || '320px') : '302px';
              var h = isChallenge ? (f.style.height || '400px') : '76px';
              // 挑战框刚切可见时 api.js 还没写尺寸的话先等下一轮,不用默认值抢跑。
              if (isChallenge && !f.style.height) { return; }
              // api.js 会反复重写内联样式,单次赋值会被覆盖 —— 用 !important 且每轮都补。
              f.style.setProperty('display', 'block', 'important');
              f.style.setProperty('width', w, 'important');
              f.style.setProperty('height', h, 'important');
              f.style.setProperty('min-height', h, 'important');
              f.style.setProperty('border', '0', 'important');
              if (!f.__arkdoFixLogged) {
                f.__arkdoFixLogged = true;
                console.log('captchaFix applied kind=' + (isChallenge ? 'challenge' : 'checkbox') + ' size=' + w + 'x' + h);
              }
            });
          }
          setInterval(fix, 500);
          try {
            new MutationObserver(function (muts) {
              for (var i = 0; i < muts.length; i++) {
                if (muts[i].addedNodes && muts[i].addedNodes.length) { fix(); return; }
              }
            }).observe(document.documentElement, { childList: true, subtree: true });
          } catch (e) {}
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
    // CF 盾恢复基线:进页时的 cf_clearance 值。页面通常一进来就带着它(宿主过盾产物),
    // 只有它**发生变化**才说明登录流程里真的重新过了一次盾 —— 否则会把「本来就有」当成
    // 「刚过盾」而立即 reload,把正在初始化的 Turnstile 验证框打断(验证框空白的直接原因)。
    var initialCfClearance by remember { mutableStateOf("") }
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

    fun safeUrlLabel(url: String): String = runCatching {
        android.net.Uri.parse(url).host ?: url.take(60)
    }.getOrDefault(url.take(60))

    // 只认主文档被盾:子资源(接口/图片/CF 探针)回 401/403 也会触发 onReceivedHttpError,
    // 但那不代表页面被拦。之前不分帧一律开轮询,CF 后台静默换一次 cf_clearance 就把
    // 正常登录表单整个 reload,正在初始化的 hCaptcha 弹层随之被冲掉(验证框空白的时序根因)。
    fun isChallengeRelatedHttpError(url: String, statusCode: Int, isForMainFrame: Boolean): Boolean {
        if (!isForMainFrame) return false
        if (statusCode != 401 && statusCode != 403 && statusCode != 429) return false
        return url.startsWith(AppConstants.LOGIN_URL) || url.contains("challenges.cloudflare.com")
    }

    /** 当前主文档是否仍停在盾页/验证页(过盾后 reload 的前置条件)。 */
    fun isStuckOnChallenge(): Boolean {
        val current = webViewRef?.url.orEmpty()
        if (current.startsWith(AppConstants.CHALLENGE_URL)) return true
        if (current.contains("challenges.cloudflare.com") || current.contains("/cdn-cgi/")) return true
        // 盾页 URL 与 /login 相同时,只能看标题:CF 拦截页标题固定为 "Just a moment..."。
        val title = webViewRef?.title.orEmpty()
        return title.contains("Just a moment", ignoreCase = true) || title.contains("Attention Required", ignoreCase = true)
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
                // 只认「值变化」:登录页进页时通常已带着宿主的 cf_clearance,只判 exists 会在
                // Turnstile 刚初始化时立刻 reload 打断它(验证框空白)。
                val current = WebSessionManager.getCfClearanceValue()
                if (current.isNotEmpty() && current != initialCfClearance) {
                    WebSessionManager.saveCookies("LoginPage")
                    // 拿到新盾 cookie 但页面已经是正常登录表单(CF 自己完成了跳转),不再 reload,
                    // 否则会把用户正在填的表单/hCaptcha 弹层冲掉。
                    if (!isStuckOnChallenge()) {
                        Timber.i("%s cf_clearance changed but page already past challenge, skip reload url=%s",
                            LOG_TAG, safeUrlLabel(webViewRef?.url.orEmpty()))
                        hasReloadedAfterChallenge = true
                        return@launch
                    }
                    Timber.i("%s cf_clearance changed, reload login", LOG_TAG)
                    hasReloadedAfterChallenge = true
                    webViewRef?.loadUrl(AppConstants.LOGIN_URL)
                    return@launch
                }
                if (count % 5 == 0) {
                    Timber.i("%s cf_clearance pending attempt=%d/%d headerLength=%d",
                        LOG_TAG, count, MAX_CHALLENGE_POLL_COUNT,
                        WebSessionManager.getCookiePresenceSnapshot("cf_clearance").headerLength)
                }
            }
            Timber.i("%s cf_clearance polling timeout", LOG_TAG)
        }
    }

    // 进页先记 `_t` 基线，再开 800ms 登录态轮询（对齐 aboutToAppear）。
    LaunchedEffect(Unit) {
        initialAuthCookiePresent = WebSessionManager.hasCookie(AUTH_COOKIE_NAME)
        initialCfClearance = WebSessionManager.getCfClearanceValue()
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
    // hCaptcha 尺寸兜底脚本(FIX)不能只靠键盘触发:第二次点登录时表单已填好、键盘不弹,
    // 所以 onPageFinished 也注入一份;脚本内部有 __arkdoCaptchaFix 守卫,重复注入无副作用。
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) {
        if (imeVisible) {
            webViewRef?.evaluateJavascript(LoginKeyboardScript.SCRIPT, null)
            webViewRef?.evaluateJavascript(LoginKeyboardScript.FIX, null)
        }
    }

    
    // 弹层呈现:全屏页面盖在根容器上(替换原底部 88% 半弹窗)。全屏让登录 WebView 与其后的
    // Cloudflare Turnstile 人机验证框拿到完整可视区,不再被弹窗裁掉一截导致空白/无法交互。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.pageBg)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶栏:标题「登录」居中 + 右上角关闭。状态栏 inset 由这里垫(根 Scaffold 已关掉
            // 系统栏排布,内容顶到屏幕顶,与首页 HomeTitleBar 同一套处理)。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .height(60.dp)
                    .background(colors.pageBg)
                    .padding(horizontal = 16.dp)
            ) {
                Text(
                    text = "登录",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary,
                    modifier = Modifier.align(Alignment.Center)
                )
                Text(
                    text = "✕",
                    fontSize = 18.sp,
                    color = colors.textPrimary,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .clickable { onNavigateBack() }
                        .padding(8.dp)
                )
            }
            HorizontalDivider(thickness = 1.dp, color = colors.border)

            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                factory = { ctx ->
                    // X5 可用则用 X5,否则回落系统 WebView(见 LoginWebViewFactory)。
                    val f = LoginWebViewFactory(
                        onPageFinished = { url ->
                            Timber.i("%s page end url=%s", LOG_TAG, url)
                            scope.launch { WebSessionManager.saveCookies("LoginPage") }
                            logCfClearanceSnapshot("pageEnd")
                            // 只在站点自身文档上装 hCaptcha 尺寸兜底(三方 OAuth 跳转页不碰)。
                            if (url.startsWith(AppConstants.BASE_URL)) {
                                webViewRef?.evaluateJavascript(LoginKeyboardScript.FIX, null)
                            }
                            if (isLikelyLoggedInUrl(url)) signalLoginSuccess("pageEnd", url)
                        },
                        onHttpError = { url, statusCode, isForMainFrame ->
                            Timber.i("%s http error url=%s status=%d mainFrame=%b", LOG_TAG, safeUrlLabel(url), statusCode, isForMainFrame)
                            if (isChallengeRelatedHttpError(url, statusCode, isForMainFrame)) startChallengeCookiePolling()
                        },
                        onLoadError = { url, code, info ->
                            Timber.i("%s load error url=%s code=%d info=%s", LOG_TAG, safeUrlLabel(url), code, info)
                        },
                        onConsole = { msg ->
                            Timber.i("%s console %s", LOG_TAG, msg)
                        },
                        onLoadResource = { url ->
                            Timber.i("%s loadResource host=%s", LOG_TAG, safeUrlLabel(url))
                        },
                        onHideKeyboard = { hideNativeKeyboard() }
                    )
                    val wv = f.build(ctx)
                    webViewRef = wv as? WebView
                    wv
                }
            )
        }
    }
}
