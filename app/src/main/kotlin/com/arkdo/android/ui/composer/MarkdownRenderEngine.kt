package com.arkdo.android.ui.composer

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import timber.log.Timber
import kotlin.coroutines.resume

/**
 * 离屏 Markdown 编译引擎(对齐鸿蒙 services/content/MarkdownRenderService.ets):
 * 一个只加载本地 render.html(markdown-it)的隐藏 WebView,把编辑器 raw 编译成 HTML 字符串回传。
 *
 * 渲染结果不负责显示——调用方把 HTML 交给 PostRichContent(与详情页同一渲染管线)。
 * WebView 必须挂到窗口才会执行 onPageFinished,故由宿主 Composable 用 AndroidView(1×1、近透明)
 * 把它放在屏幕上;就绪前发来的请求先入队,onPageFinished 后统一放行。
 *
 * 单次编译兜底 5s 超时:超时按失败返回 null,预览退回原文展示。
 */
@SuppressLint("SetJavaScriptEnabled")
class MarkdownRenderEngine(context: android.content.Context) {

    companion object {
        private const val LOG_TAG = "[JetDo][MarkdownRender]"
        private const val BRIDGE_NAME = "JetDoMd"
        private const val RENDER_TIMEOUT_MS = 5_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 由宿主 Composable 的 AndroidView 取用并加入视图树;释放走 [release]。 */
    val webView: WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        addJavascriptInterface(MarkdownJsBridge(), BRIDGE_NAME)
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                patchSmartQuotes()
                ready = true
                val waiters = queued.snapshot()
                queued.clear()
                waiters.forEach { it() }
                Timber.i("$LOG_TAG host ready queued=${waiters.size}")
            }
        }
        // 先内联打补丁再 load 页面?不行——页面必须由 loadUrl 加载才能拿到 md 实例。
        // 因此补丁在页面 onPageFinished 之后注入,见 patchRenderer。
        loadUrl("file:///android_asset/markdown/render.html")
    }

    /**
     * markdown-it 默认把文本里的直引号转义成 &quot;(escapeHtml 的一部分),预览若直接透传,
     * 中文语境「"你好"」就会显示成「&quot;你好&quot;」或「\"你好\"」。
     * 这里在页面加载完成后注入补丁,覆盖 text / code_inline / code_block / fence 渲染器,
     * 让产物里的直引号保持字面,与编辑器所见一致。
     */
    private fun patchSmartQuotes() {
        val script = """
            (function () {
              if (!window.md || !window.md.renderer) return;
              var esc = window.md.utils.escapeHtml;
              window.md.utils.escapeHtml = function (html) {
                return esc(html).replace(/&quot;/g, '"');
              };
            })();
        """.trimIndent()
        try {
            webView.evaluateJavascript(script, null)
        } catch (e: Exception) {
            Timber.i("$LOG_TAG quote patch failed")
        }
    }

    private var ready = false
    private var seq = 0
    private val queued = mutableListOf<() -> Unit>()
    private val inflight = HashMap<String, (String?) -> Unit>()
    private var destroyed = false

    /** 编译一段 markdown 为 HTML;null = 失败(超时/宿主销毁/脚本抛错)。 */
    suspend fun render(raw: String): String? = suspendCancellableCoroutine { cont ->
        if (destroyed) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        val body: () -> Unit = {
            val id = "md${seq++}"
            inflight[id] = { html -> cont.resume(html) }
            mainHandler.postDelayed({
                val cb = inflight.remove(id)
                if (cb != null) cb(null)
            }, RENDER_TIMEOUT_MS)
            val script = "renderMarkdown(${JSONObject.quote(id)},${JSONObject.quote(raw)})"
            try {
                webView.evaluateJavascript(script, null)
            } catch (e: Exception) {
                Timber.i("$LOG_TAG runJavaScript failed id=$id")
                inflight.remove(id)?.let { it(null) }
            }
        }
        if (ready) {
            body()
        } else {
            queued.add(body)
        }
    }

    /** JS 桥:render.html 编译完成回调。addJavascriptInterface 回调线程非主线程,须切主线程再回协程。 */
    private inner class MarkdownJsBridge {
        @JavascriptInterface
        fun onResult(requestId: String, html: String) {
            mainHandler.post {
                val cb = inflight.remove(requestId) ?: return@post
                cb(html ?: "")
            }
        }

        @JavascriptInterface
        fun onError(requestId: String, message: String) {
            mainHandler.post {
                val cb = inflight.remove(requestId) ?: return@post
                cb(null)
            }
        }
    }

    /** 页面销毁时调用:失败所有在途/排队请求,销毁 WebView。 */
    fun release() {
        destroyed = true
        inflight.values.forEach { it(null) }
        inflight.clear()
        queued.clear()
        runCatching { webView.stopLoading() }
        runCatching { webView.removeAllViews() }
        runCatching { webView.destroy() }
    }

    private fun MutableList<() -> Unit>.snapshot(): List<() -> Unit> = toList()
}