package com.jetdo.android.ui.screens

import android.content.Context
import com.jetdo.android.common.constants.AppConstants
import com.jetdo.android.services.web.WebSessionManager

/**
 * 登录页 WebView 构造器。
 *
 * 固定使用系统 WebView：此前接入腾讯 X5(TBS) 内核做过 A/B 对照实验，结论是 X5 内核
 * （Chromium 77 系）比主流设备自带的系统 WebView 更老，对 Cloudflare Turnstile 没有改善，
 * 反而在部分机型上因内核未装载导致登录页白屏。因此 TBS 依赖已整体移除，
 * 这里只保留系统 WebView 这一条路径。
 *
 * 回调全部经参数注入，LoginScreen 只需拿到一个 View。
 */
object LoginWebViewFactory {
    fun create(
        context: Context,
        onPageFinished: (String) -> Unit,
        /** (url, statusCode, isForMainFrame) */
        onHttpError: (String, Int, Boolean) -> Unit,
        onLoadError: (String, Int, String) -> Unit,
        onConsole: (String) -> Unit,
        onLoadResource: (String) -> Unit,
        onHideKeyboard: () -> Unit
    ): android.webkit.WebView = android.webkit.WebView(context).apply {
        WebSessionManager.configureCookieAccess(this, "LoginPage")
        WebSessionManager.applyUserAgent(this, "LoginPage")
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        addJavascriptInterface(LoginKeyboardProxy(onHideKeyboard), "jetdoLoginKeyboard7f3a")

        webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(message: android.webkit.ConsoleMessage?): Boolean {
                if (message != null) {
                    onConsole("[${message.messageLevel()}] ${message.message()} src=${message.sourceId()} line=${message.lineNumber()}")
                }
                return true
            }
        }

        webViewClient = object : android.webkit.WebViewClient() {
            // 登录页完全不走 DoH/自定义拦截：系统浏览器能出人机验证，而本页不行，
            // 差异集中在我们的接管层 —— 先彻底归零(shouldInterceptRequest 直接交系统)，
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
        loadUrl(AppConstants.LOGIN_URL)
    }

    private class LoginKeyboardProxy(private val onHide: () -> Unit) {
        @android.webkit.JavascriptInterface
        fun dismissKeyboard(reason: String) {
            onHide()
        }
    }
}
