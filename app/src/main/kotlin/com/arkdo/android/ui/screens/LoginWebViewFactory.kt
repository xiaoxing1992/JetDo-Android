package com.arkdo.android.ui.screens

import android.content.Context
import android.view.View
import com.arkdo.android.common.constants.AppConstants
import com.arkdo.android.services.web.UserAgentProvider
import timber.log.Timber

/**
 * 登录页 WebView 构造器:X5(TBS)内核可用时用 X5,否则回落系统 WebView。
 *
 * 纯 A/B 实验(对比 CF Turnstile 在不同内核上的表现):
 *  - X5 内核(Chromium 77 系)比多数 Android 12 设备的系统 WebView(Chromium 100+)更老,
 *    WebGPU 等新特性同样缺失,预期不一定能改善 —— 本文件存在的意义是拿到实机对照数据,
 *    便于在"换内核 vs 换承载方式(系统浏览器/Custom Tab)"之间做决定。
 *  - 回调全部经构造参数注入,LoginScreen 只需拿到一个 View。
 */
class LoginWebViewFactory(
    private val onPageFinished: (String) -> Unit,
    /** (url, statusCode, isForMainFrame) */
    private val onHttpError: (String, Int, Boolean) -> Unit,
    private val onLoadError: (String, Int, String) -> Unit,
    private val onConsole: (String) -> Unit,
    private val onLoadResource: (String) -> Unit,
    private val onHideKeyboard: () -> Unit
) {
    fun build(context: Context): View {
        // 强制固定系统 WebView:先前接入 X5 后登录页白屏,大概率是 X5 内核未装载就被优先
        // 使用/装载失败所致。要做 X5 对照实验时,再把下面的 X5 分支放开。
        // X5LoginSupport.ensureInit(context)
        // X5LoginSupport.tryCreate(context)?.let { x5 ->
        //     Timber.i("%s 使用 X5 内核", LOG_TAG)
        //     configureX5(context, x5)
        //     setupX5Clients(x5)
        //     x5.loadUrl(AppConstants.LOGIN_URL)
        //     return x5
        // }
        Timber.i("%s 强制系统 WebView", LOG_TAG)
        val system = SystemLoginWebViewFactory.create(
            context, onPageFinished, onHttpError, onLoadError, onConsole, onLoadResource, onHideKeyboard
        )
        return system
    }

    // ==================== X5 分支 ====================

    private fun configureX5(context: Context, webView: com.tencent.smtt.sdk.WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = com.tencent.smtt.sdk.WebSettings.LOAD_DEFAULT
            // X5 WebSettings 没有 MIXED_CONTENT_ALWAYS_ALLOW 常量,直接传 0(对应兼容值)。
            mixedContentMode = 0
            userAgentString = UserAgentProvider.get(context)
        }
        com.tencent.smtt.sdk.CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.addJavascriptInterface(
            LoginWebViewFactory.X5KeyboardProxy(onHideKeyboard),
            "arkdoLoginKeyboard7f3a"
        )
    }

    private fun setupX5Clients(webView: com.tencent.smtt.sdk.WebView) {
        webView.webChromeClient = object : com.tencent.smtt.sdk.WebChromeClient() {
            override fun onConsoleMessage(message: com.tencent.smtt.export.external.interfaces.ConsoleMessage?): Boolean {
                if (message != null) {
                    onConsole("[${message.messageLevel()}] ${message.message()} src=${message.sourceId()} line=${message.lineNumber()}")
                }
                return true
            }
        }
        webView.webViewClient = object : com.tencent.smtt.sdk.WebViewClient() {
            override fun onPageFinished(view: com.tencent.smtt.sdk.WebView?, url: String?) {
                if (!url.isNullOrEmpty()) onPageFinished(url)
            }

            override fun onReceivedHttpError(
                view: com.tencent.smtt.sdk.WebView?,
                request: com.tencent.smtt.export.external.interfaces.WebResourceRequest?,
                errorResponse: com.tencent.smtt.export.external.interfaces.WebResourceResponse?
            ) {
                val url = runCatching { request?.url?.toString() }.getOrNull().orEmpty()
                onHttpError(url, errorResponse?.statusCode ?: 0, request?.isForMainFrame == true)
            }

            override fun onReceivedError(
                view: com.tencent.smtt.sdk.WebView?,
                request: com.tencent.smtt.export.external.interfaces.WebResourceRequest?,
                error: com.tencent.smtt.export.external.interfaces.WebResourceError?
            ) {
                val url = runCatching { request?.url?.toString() }.getOrNull().orEmpty()
                onLoadError(url, error?.errorCode ?: 0, error?.description?.toString().orEmpty())
            }

            override fun onLoadResource(view: com.tencent.smtt.sdk.WebView?, url: String?) {
                if (!url.isNullOrEmpty()) onLoadResource(url)
            }
        }
    }

    private class X5KeyboardProxy(private val onHide: () -> Unit) {
        @android.webkit.JavascriptInterface
        fun dismissKeyboard(reason: String) {
            onHide()
        }
    }

    private companion object {
        const val LOG_TAG = "[JetDo][X5Login]"
    }
}

/** X5(TBS)加载与探测工具。 */
object X5LoginSupport {
    private const val TAG = "[JetDo][X5]"
    @Volatile private var initStarted = false

    /** 在 Application 预热:初始化 TBS(可能触发内核下载/加载,失败无害)。 */
    fun ensureInit(context: Context) {
        if (initStarted) return
        initStarted = true
        runCatching {
            com.tencent.smtt.sdk.QbSdk.initX5Environment(
                context.applicationContext,
                object : com.tencent.smtt.sdk.QbSdk.PreInitCallback {
                    override fun onCoreInitFinished() {}
                    override fun onViewInitFinished(ok: Boolean) {
                        val ver = runCatching { com.tencent.smtt.sdk.QbSdk.getTbsVersion(context.applicationContext) }.getOrDefault(-1)
                        Timber.i("$TAG view init finished success=%b version=%d", ok, ver)
                    }
                }
            )
        }.onFailure { e -> Timber.i("$TAG init failed %s", e.message) }
    }

    /** X5 可用时返回一个 X5 WebView 实例;不可用返回 null。 */
    fun tryCreate(context: Context): com.tencent.smtt.sdk.WebView? = runCatching {
        val version = com.tencent.smtt.sdk.QbSdk.getTbsVersion(context.applicationContext)
        if (version <= 0) {
            Timber.i("$TAG tbs not loaded version=%d", version)
            return null
        }
        Timber.i("$TAG create x5 webview version=%d", version)
        com.tencent.smtt.sdk.WebView(context)
    }.getOrElse {
        Timber.i("$TAG create x5 failed %s", it.message)
        null
    }
}

/**
 * 系统 WebView 分支的构造器(把原 LoginScreen 内联实现原样搬出,供回落路径复用)。
 * 回调语义与 X5 分支一致。
 */
object SystemLoginWebViewFactory {
    fun create(
        context: Context,
        onPageFinished: (String) -> Unit,
        onHttpError: (String, Int, Boolean) -> Unit,
        onLoadError: (String, Int, String) -> Unit,
        onConsole: (String) -> Unit,
        onLoadResource: (String) -> Unit,
        onHideKeyboard: () -> Unit
    ): android.webkit.WebView = android.webkit.WebView(context).apply {
        com.arkdo.android.services.web.WebSessionManager.configureCookieAccess(this, "LoginPage")
        com.arkdo.android.services.web.WebSessionManager.applyUserAgent(this, "LoginPage")
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        addJavascriptInterface(LoginKeyboardProxyCompat(onHideKeyboard), "arkdoLoginKeyboard7f3a")

        webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(message: android.webkit.ConsoleMessage?): Boolean {
                if (message != null) {
                    onConsole("[${message.messageLevel()}] ${message.message()} src=${message.sourceId()} line=${message.lineNumber()}")
                }
                return true
            }
        }

        webViewClient = object : android.webkit.WebViewClient() {
            // 登录页完全不走 DoH/自定义拦截:系统浏览器能出人机验证,而本页不行,
            // 差异集中在我们的接管层 —— 先彻底归零(shouldInterceptRequest 直接交系统),
            // 把「自家网络层是否干扰 CF 判定」排除。宿主桥等其它页面保留 DoH 不变。
            override fun shouldInterceptRequest(
                view: android.webkit.WebView?,
                request: android.webkit.WebResourceRequest?
            ): android.webkit.WebResourceResponse? = super.shouldInterceptRequest(view, request)

            override fun onPageFinished(view: android.webkit.WebView, url: String) {
                onPageFinished(url)
            }

            override fun onReceivedError(
                view: android.webkit.WebView,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                val url = request?.url?.toString().orEmpty()
                onLoadError(url, error?.errorCode ?: 0, error?.description?.toString().orEmpty())
            }

            override fun onReceivedHttpError(
                view: android.webkit.WebView,
                request: android.webkit.WebResourceRequest,
                response: android.webkit.WebResourceResponse
            ) {
                val url = request.url?.toString().orEmpty()
                onHttpError(url, response.statusCode, request.isForMainFrame)
            }

            override fun onLoadResource(view: android.webkit.WebView?, url: String?) {
                if (!url.isNullOrEmpty()) onLoadResource(url)
            }
        }

        // 关键:建好、配好之后必须发起导航,否则 WebView 是空白(此前拆出该工厂时漏了这一行,
        // 导致登录页整屏白屏)。
        loadUrl(com.arkdo.android.common.constants.AppConstants.LOGIN_URL)
    }

    private class LoginKeyboardProxyCompat(private val onHide: () -> Unit) {
        @android.webkit.JavascriptInterface
        fun dismissKeyboard(reason: String) {
            onHide()
        }
    }
}