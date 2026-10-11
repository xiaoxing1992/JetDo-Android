package com.arkdo.android.ui.level

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
import com.arkdo.android.services.web.WebSessionManager
import timber.log.Timber

/**
 * 隐藏的 connect.linux.do WebView。connect 与 linux.do 不同源,宿主 WebView 跨域 fetch 会被 CORS 挡,
 * 所以本页自己真加载一次(经 SSO 复用会话),加载完用脚本把服务端渲染的数值抠出来。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ConnectWebHost(
    url: String,
    reloadToken: Int,
    onPageEnd: (WebView) -> Unit,
    onMainFrameError: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val holder = remember {
        ConnectWebHolder(context)
    }
    DisposableEffect(holder) {
        onDispose { holder.destroy() }
    }
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { holder.webView },
        update = { _ ->
            holder.callbacks = ConnectWebCallbacks(onPageEnd, onMainFrameError)
            holder.load(url, reloadToken)
        }
    )
}

private class ConnectWebCallbacks(
    val onPageEnd: (WebView) -> Unit,
    val onMainFrameError: () -> Unit
)

@SuppressLint("SetJavaScriptEnabled")
private class ConnectWebHolder(context: android.content.Context) {
    val webView: WebView = WebView(context)
    var callbacks = ConnectWebCallbacks({}, {})
    private var loadedKey: String? = null

    init {
        val web = webView
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.databaseEnabled = true
        web.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        web.settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        web.settings.blockNetworkLoads = false
        web.settings.blockNetworkImage = false
        WebSessionManager.configureCookieAccess(web, "LevelProgress")
        WebSessionManager.applyUserAgent(web, "LevelProgress")
        web.alpha = 0.01f
        web.setBackgroundColor(0x00000000)
        web.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                Timber.i("[JetDo][Level] pageEnd url=%s", url)
                view?.let { callbacks.onPageEnd(it) }
            }

            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    Timber.i("[JetDo][Level] main frame error code=%s", error?.errorCode)
                    callbacks.onMainFrameError()
                }
            }
        }
    }

    fun load(url: String, reloadToken: Int) {
        val key = "$reloadToken|$url"
        if (key == loadedKey) return
        loadedKey = key
        val load = {
            Timber.i("[JetDo][Level] load %s token=%d", url, reloadToken)
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
