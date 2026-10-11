package com.arkdo.android.ui.credit

import android.annotation.SuppressLint
import android.os.Looper
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.arkdo.android.common.constants.AppConstants
import com.arkdo.android.services.network.ArkWebJsonExecutor
import com.arkdo.android.services.network.WebviewJsonExecutor
import com.arkdo.android.services.web.WebSessionManager
import timber.log.Timber

/**
 * credit.linux.do 同源的独立 WebView。
 * 接口不回 CORS 头,linux.do 的宿主 WebView 跨源 fetch 会被挡,所以取数和登录都走这里。
 * 取数时近透明,登录时全屏可点。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LdcCreditWebHost(
    initialUrl: String,
    navTarget: String,
    interactive: Boolean,
    onReady: () -> Unit,
    onLoadFailed: () -> Unit,
    onPageSettled: (String) -> Unit,
    onExecutor: (ArkWebJsonExecutor) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val holder = remember {
        LdcWebHolder(context).also { onExecutor(it.executor) }
    }

    DisposableEffect(holder) {
        onDispose { holder.destroy() }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { holder.webView },
        update = { web ->
            web.alpha = if (interactive) 1f else 0.01f
            holder.callbacks = LdcWebCallbacks(onReady, onLoadFailed, onPageSettled)
            holder.navigate(navTarget.ifEmpty { initialUrl })
        }
    )
}

private class LdcWebCallbacks(
    val onReady: () -> Unit,
    val onLoadFailed: () -> Unit,
    val onPageSettled: (String) -> Unit
)

@SuppressLint("SetJavaScriptEnabled")
private class LdcWebHolder(context: android.content.Context) {
    val webView: WebView = WebView(context)
    val executor: WebviewJsonExecutor
    var callbacks = LdcWebCallbacks({}, {}, {})
    private var loadedUrl: String? = null
    private var mainFrameFailed = false
    private var readyNotified = false

    init {
        val web = webView
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.databaseEnabled = true
        web.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        web.settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        web.settings.blockNetworkLoads = false
        web.settings.blockNetworkImage = false
        WebSessionManager.configureCookieAccess(web, "LdcCredit")
        WebSessionManager.applyUserAgent(web, "LdcCredit")
        web.setBackgroundColor(0x00000000)
        executor = WebviewJsonExecutor(web)
        web.addJavascriptInterface(executor.getCallbackProxy(), "JetDoBridge")
        web.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                Timber.i("[JetDo][Ldc] pageEnd url=%s", url)
                if (!mainFrameFailed && url != null && url.startsWith(AppConstants.CREDIT_BASE_URL)) {
                    if (!readyNotified) {
                        readyNotified = true
                        callbacks.onReady()
                    }
                }
                if (url != null) callbacks.onPageSettled(url)
            }

            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    mainFrameFailed = true
                    Timber.i("[JetDo][Ldc] main frame error code=%s", error?.errorCode)
                    callbacks.onLoadFailed()
                }
            }
        }
    }

    fun navigate(url: String) {
        if (url.isEmpty() || url == loadedUrl) return
        loadedUrl = url
        mainFrameFailed = false
        val load = {
            Timber.i("[JetDo][Ldc] load %s", url)
            webView.loadUrl(url)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) load() else webView.post(load)
    }

    fun destroy() {
        runCatching {
            webView.stopLoading()
            webView.destroy()
        }
    }
}
