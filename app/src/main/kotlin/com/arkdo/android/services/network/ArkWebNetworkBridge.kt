package com.arkdo.android.services.network

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.arkdo.android.common.constants.AppConstants
import com.arkdo.android.services.challenge.ChallengeEventBus
import com.arkdo.android.services.content.DownloadEventBus
import com.arkdo.android.services.content.DownloadResult
import com.arkdo.android.services.message.MessageBusEventBus
import com.arkdo.android.services.network.webscript.MessageBusScriptTemplate
import com.arkdo.android.services.web.WebSessionManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val MEDIA_DOWNLOAD_TIMEOUT_MS = 120_000L
private const val BODY_LOG_CHUNK_SIZE = 3000
private const val BODY_LOG_MAX_CHARS = 1500

// ==========================================================================
// 与 HarmonyOS 版 services/network/ArkWebNetworkBridge.ets 逐字段对齐。
// 注入式 fetch 桥：所有网络请求改为在 host WebView（linux.do 同源文档）内执行 JS fetch，
// 结果经 window.ArkDOBridge.onJsonResult(requestId, envelopeJson) 回传原生，
// 由 requestId → PendingJsonRequest 关联 Promise 兑现。
//
// Android 映射：
//   runJavaScript  → WebView.evaluateJavascript（主线程）
//   javaScriptProxy → WebView.addJavascriptInterface("ArkDOBridge")
//   WebDownloadDelegate → DownloadListener
//   WebviewController.startDownload(url) → webView.loadUrl(url) 触发下载导航
// ==========================================================================

interface ArkWebJsonExecutor {
    /** 运行脚本并返回 JS 侧回传的 payload（信封 JSON 字符串）。 */
    suspend fun run(script: String): String
}

interface ArkWebResultReceiver {
    fun onJsonResult(requestId: String, payload: String)
    fun onBusEvent(payload: String)
}

data class ImageFetchResult(
    val ok: Boolean,
    val base64: String,
    val contentType: String
)

private data class PendingJsonRequest(
    val deferred: CompletableDeferred<String>
)

data class DownloadIntent(
    val isMedia: Boolean,
    val url: String
)

/**
 * JS 桥代理。@JavascriptInterface 方法由 JS 线程调用，切回主线程后交 executor。
 * 方法名与 HarmonyOS 版 ArkDOBridge 完全一致（window.ArkDOBridge.onJsonResult/onBusEvent）。
 */
class ArkDoBridgeProxy(
    private val receiver: ArkWebResultReceiver,
    private val mainHandler: Handler
) {
    @JavascriptInterface
    fun onJsonResult(requestId: String, payload: String) {
        mainHandler.post { receiver.onJsonResult(requestId, payload) }
    }

    @JavascriptInterface
    fun onBusEvent(payload: String) {
        mainHandler.post { receiver.onBusEvent(payload) }
    }
}

/**
 * WebviewJsonExecutor：注入脚本执行器 + 结果接收者。
 * 必须在主线程构造（Handler 绑定主 Looper）。
 */
class WebviewJsonExecutor(
    private val webView: WebView,
    private val mainHandler: Handler = Handler(Looper.getMainLooper())
) : ArkWebJsonExecutor, ArkWebResultReceiver {

    private val callbackProxy = ArkDoBridgeProxy(this, mainHandler)
    private val pendingRequests = mutableMapOf<String, PendingJsonRequest>()
    private var requestSequence = 0L

    fun getCallbackProxy(): ArkDoBridgeProxy = callbackProxy

    override suspend fun run(script: String): String {
        val requestId = "arkdo_${requestSequence++}"
        Timber.i("[ArkDO][Network] executor start requestId=%s", requestId)

        val deferred = CompletableDeferred<String>()
        pendingRequests[requestId] = PendingJsonRequest(deferred)

        // 兜底超时：requestId 未在窗口内被 JS 回传则判失败。
        val timeoutJob = kotlinx.coroutines.MainScope().launch {
            kotlinx.coroutines.delay(AppConstants.DEFAULT_TIMEOUT_MS)
            pendingRequests.remove(requestId)?.let {
                if (!it.deferred.isCompleted) {
                    it.deferred.completeExceptionally(IllegalStateException("ArkWeb 请求超时。"))
                }
            }
        }

        val shouldRun = runCatching {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                mainHandler.post { evaluate(script, requestId) }
            } else {
                evaluate(script, requestId)
            }
        }.isFailure
        if (shouldRun) {
            rejectRequest(requestId, "ArkWeb 脚本执行失败。")
            timeoutJob.cancel()
        }

        try {
            return withTimeout(AppConstants.DEFAULT_TIMEOUT_MS) { deferred.await() }
        } finally {
            timeoutJob.cancel()
            pendingRequests.remove(requestId)
        }
    }

    private fun evaluate(script: String, requestId: String) {
        val patched = script.replace(REQUEST_ID_TOKEN, requestId)
        runCatching {
            webView.evaluateJavascript(patched, null)
        }.onFailure {
            rejectRequest(requestId, "ArkWeb 脚本执行失败。")
        }
    }

    override fun onJsonResult(requestId: String, payload: String) {
        val pending = pendingRequests.remove(requestId)
        if (pending == null) {
            Timber.i("[ArkDO][Network] callback ignored requestId=%s", requestId)
            return
        }
        Timber.i("[ArkDO][Network] callback received requestId=%s payloadLength=%d", requestId, payload.length)
        pending.deferred.complete(payload)
    }

    override fun onBusEvent(payload: String) {
        Timber.i("[ArkDO][MessageBus] event len=%d head=%s", payload.length, payload.take(160))
        MessageBusEventBus.publishPayload(payload)
    }

    private fun rejectRequest(requestId: String, message: String) {
        val pending = pendingRequests.remove(requestId) ?: return
        Timber.i("[ArkDO][Network] executor reject requestId=%s reason=%s", requestId, message)
        pending.deferred.completeExceptionally(IllegalStateException(message))
    }
}

/**
 * 网络桥（单例）。挂在一个隐藏的 host WebView 上。
 */
object ArkWebNetworkBridge : JsonNetworkClient {

    private var webView: WebView? = null

    /** 恢复探测读宿主文档标题/HTML。没有宿主时返回 null，调用方不得当成“已放行”。 */
    fun peekWebView(): WebView? = webView

    private var executor: ArkWebJsonExecutor? = null
    private var webviewExecutor: WebviewJsonExecutor? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var reloading = false
    private val readyWaiters = mutableListOf<() -> Unit>()
    private val reloadCompleteCallbacks = mutableListOf<() -> Unit>()

    private var downloadContext: Context? = null
    private var downloadDelegateReady = false
    private val mediaWaiters = mutableMapOf<String, CompletableDeferred<String>>()
    private val mediaInflight = mutableMapOf<String, kotlinx.coroutines.Deferred<String>>()
    private val mediaGuidUrl = mutableMapOf<String, String>()
    private val mediaCache = mutableMapOf<String, String>()
    private val downloadIntents = mutableListOf<DownloadIntent>()

    // ---------- host 生命周期 ----------

    /**
     * 🚀 应用启动时预加载 WebView（在 UI 渲染前）
     *
     * 对齐鸿蒙版的启动策略：在应用启动时立即创建隐藏 WebView 并加载 bootstrap URL。
     *
     * 优势：
     * 1. 提前完成 WebView 初始化（~1-2s）
     * 2. 提前恢复/建立 Cookie（_t, cf_clearance）
     * 3. 提前完成 Cloudflare 挑战（如需要，静默在后台）
     * 4. 当用户看到首页时，WebView 已就绪，首次请求立即成功
     *
     * 必须在主线程调用（通常在 MainActivity.onCreate）。
     */
    fun preloadHostInBackground(context: Context) {
        if (webView != null) {
            Timber.i("[ArkDO][Network] preloadHostInBackground: WebView already exists, skipping")
            return
        }

        Timber.i("[ArkDO][Network] ========== Preloading WebView in Background ==========")
        Timber.i("[ArkDO][Network] preloadHostInBackground: start ts=%d", System.currentTimeMillis())

        // 检查是否在主线程
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Timber.e("[ArkDO][Network] preloadHostInBackground: NOT on main thread, posting to main thread")
            mainHandler.post { preloadHostInBackground(context) }
            return
        }

        // 直接调用 attachHost 创建 WebView
        // 注意：此时 WebView 不会附着到窗口，所以 loadUrl 会在 attachHost 内部等待附着
        val web = attachHost(context)

        // 设置一个小尺寸（不需要显示，但需要满足 Cloudflare Turnstile 最小尺寸要求）
        web.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(300, android.view.View.MeasureSpec.EXACTLY)
        )
        web.layout(0, 0, 400, 300)

        Timber.i("[ArkDO][Network] preloadHostInBackground: WebView created, will load when attached to window")
        Timber.i("[ArkDO][Network] preloadHostInBackground: isAttachedToWindow=%s", web.isAttachedToWindow)
    }

    /**
     * 创建隐藏 host WebView 并挂桥。必须主线程调用。
     * 加载 HOST_BOOTSTRAP_URL 定 origin；onPageFinished → attachController + markHostReady。
     */
    fun attachHost(context: Context): WebView {
        Timber.i("[ArkDO][Network] attachHost: creating WebView")
        val web = WebView(context.applicationContext)

        // 基础设置
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.databaseEnabled = true
        web.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        web.settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT

        // DoH：鸿蒙版一句 webview.WebviewController.setHttpDns 就在引擎层全局生效，
        // Android WebView 没有等价 API，只能在下面的 shouldInterceptRequest 里逐请求接管。
        // 这里先确保引擎按 DohManager 的当前配置就绪，再 loadUrl。
        DohService.init(context)

        // 设置 User-Agent 必须与 CfChallengeScreen 完全一致
        // Cloudflare 的 cf_clearance cookie 与 User-Agent 绑定
        // (applyUserAgent 同时统一 UA Client Hints / X-Requested-With,全 app 一份)
        com.arkdo.android.services.web.WebSessionManager.applyUserAgent(web, "ArkWebHost")
        Timber.i("[ArkDO][Network] attachHost: User-Agent set to %s", web.settings.userAgentString)

        // 启用现代网络特性
        web.settings.setSupportMultipleWindows(false)
        web.settings.loadWithOverviewMode = true
        web.settings.useWideViewPort = true

        // 关键：禁用阻塞网络请求（某些 WebView 版本默认开启）
        web.settings.blockNetworkLoads = false
        web.settings.blockNetworkImage = false
        Timber.i("[ArkDO][Network] attachHost: network blocking disabled")

        web.alpha = 0.01f
        web.setBackgroundColor(0x00000000)
        Timber.i("[ArkDO][Network] attachHost: WebView settings configured")

        WebSessionManagerInternal.configure(web, "host")
        Timber.i("[ArkDO][Network] attachHost: WebSessionManager configured")

        web.webViewClient = object : WebViewClient() {
            /**
             * 请求改道走 DoH，替代鸿蒙版的 setHttpDns。在 Chromium 的工作线程上调用，
             * 阻塞式网络请求是允许的。返回 null 表示这条请求交回 WebView 自己发。
             */
            override fun shouldInterceptRequest(
                view: WebView?,
                request: android.webkit.WebResourceRequest?
            ): android.webkit.WebResourceResponse? =
                WebViewDohInterceptor.intercept(request) ?: super.shouldInterceptRequest(view, request)

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                Timber.i("[ArkDO][Network] attachHost: onPageStarted url=%s", url)

                // 检查当前 Cookie - 测试多个 URL
                val cookieManager = android.webkit.CookieManager.getInstance()
                val urlsToTest = listOf(
                    "https://linux.do",
                    "https://linux.do/",
                    "https://linux.do/session/csrf.json"
                )

                for (testUrl in urlsToTest) {
                    val cookies = cookieManager.getCookie(testUrl)
                    val hasCfClearance = cookies?.contains("cf_clearance=") == true
                    Timber.i("[ArkDO][Network] Cookie for '%s': hasCf=%b length=%d",
                        testUrl, hasCfClearance, cookies?.length ?: 0)
                }
                Timber.i("[ArkDO][Network] attachHost: onPageStarted User-Agent=%s", view?.settings?.userAgentString)

                markHostReloading()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                Timber.i("[ArkDO][Network] attachHost: onPageFinished url=%s", url)

                // 检查页面是否有有效的 origin（避免 CORS 错误）
                view?.evaluateJavascript("window.location.origin") { origin ->
                    Timber.i("[ArkDO][Network] attachHost: page origin=%s", origin)

                    if (origin == "\"null\"" || origin == "null" || origin.isNullOrEmpty()) {
                        Timber.w("[ArkDO][Network] attachHost: invalid origin, retrying bootstrap load in 2s")
                        Handler(Looper.getMainLooper()).postDelayed({
                            Timber.i("[ArkDO][Network] attachHost: retrying bootstrap load")
                            markHostReloading()
                            web.loadUrl(AppConstants.HOST_BOOTSTRAP_URL)
                        }, 2000)
                        return@evaluateJavascript
                    }

                    // Origin 有效，继续初始化
                    attachController(web)
                    markHostReady()
                    // 宿主页就绪后才注入长轮询。脚本自己幂等，重复 onPageFinished 不会再开一条。
                    startMessageBus()
                    Timber.i("[ArkDO][Network] attachHost: controller attached, host ready with origin=%s", origin)

                    // 检测页面是否是 Cloudflare 挑战页面
                    view.evaluateJavascript(
                        """
                        (function() {
                            const title = document.title || '';
                            const body = document.body ? document.body.innerText : '';
                            const url = window.location.href || '';
                            const hasChallengeKeywords = title.includes('Just a moment') ||
                                                         body.includes('Checking your browser') ||
                                                         body.includes('Verify you are human') ||
                                                         url.includes('/cdn-cgi/challenge-platform/') ||
                                                         document.querySelector('[name="cf-turnstile-response"]') !== null;
                            return JSON.stringify({
                                hasChallenge: hasChallengeKeywords,
                                title: title.substring(0, 50),
                                bodyPreview: body.substring(0, 100),
                                url: url
                            });
                        })();
                        """.trimIndent()
                    ) { result ->
                        Timber.i("[ArkDO][Network] attachHost: challenge detection raw result=%s", result)
                        // evaluateJavascript 回传的是 JS 值的 JSON 编码：内层 {"hasChallenge":true}
                        // 到这里变成字符串 "{\"hasChallenge\":true}"。直接 contains 字面 true 永远匹配不上，
                        // 挑战页会被当成干净页，过盾流程根本不启动。
                        val isChallengePage = result?.contains("\\\"hasChallenge\\\":true") == true
                            || result?.contains("\"hasChallenge\":true") == true

                        // 额外检查：如果标题是"请稍候…"也认为是挑战页面
                        val hasPendingTitle = result?.contains("请稍候") == true

                        Timber.i("[ArkDO][Network] attachHost: isChallengePage=%s hasPendingTitle=%s", isChallengePage, hasPendingTitle)

                        if ((isChallengePage || hasPendingTitle) && url?.contains("linux.do") == true) {
                            Timber.w("[ArkDO][Network] attachHost: Cloudflare challenge page detected, notifying ChallengeEventBus")
                            ChallengeEventBus.notifyChallengeRequired("bootstrap", 403, ChallengeState.REQUIRED)
                        } else if (url?.contains("linux.do") == true) {
                            // 页面干净(已过盾或本就无盾):立即把 cookie(含静默过盾拿到的
                            // cf_clearance)刷盘。此前只有可见验证页成功才刷盘,宿主静默过盾
                            // 的 clearance 进程一死就丢,导致每次冷启动都重新过盾。
                            kotlinx.coroutines.MainScope().launch {
                                WebSessionManager.saveCookies("hostReadyClean")
                            }
                        }
                    }
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                Timber.e("[ArkDO][Network] attachHost: onReceivedError url=%s code=%d msg=%s",
                    request?.url, error?.errorCode, error?.description)

                // 如果是主页面加载失败（ERR_CONNECTION_CLOSED 等），尝试重新加载
                // 这避免了 origin='null' 导致的 CORS 错误
                if (request?.isForMainFrame == true && error?.errorCode == -6) {
                    Timber.w("[ArkDO][Network] attachHost: main frame load failed, will retry on next fetch")
                    // 标记需要重新加载，但不立即重载（避免循环）
                    // onPageFinished 会设置 executor，但首次 fetch 失败后会触发 reloadHost
                }
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: android.webkit.SslErrorHandler?,
                error: android.net.http.SslError?
            ) {
                Timber.e("[ArkDO][Network] attachHost: onReceivedSslError primaryError=%d url=%s",
                    error?.primaryError, error?.url)
                // SSL 错误：取消加载
                handler?.cancel()
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                errorResponse: android.webkit.WebResourceResponse?
            ) {
                val url = request?.url?.toString()
                val statusCode = errorResponse?.statusCode ?: 0
                Timber.e("[ArkDO][Network] attachHost: onReceivedHttpError url=%s statusCode=%d",
                    url, statusCode)

                // 如果是主页面的 403 错误，很可能是 Cloudflare 挑战
                if (request?.isForMainFrame == true && statusCode == 403 && url?.contains("linux.do") == true) {
                    Timber.w("[ArkDO][Network] attachHost: 403 on main frame, likely Cloudflare challenge")
                    Handler(Looper.getMainLooper()).postDelayed({
                        // 延迟检测，给挑战页面时间渲染
                        view?.evaluateJavascript(
                            """
                            (function() {
                                const url = window.location.href || '';
                                const title = document.title || '';
                                const body = document.body ? document.body.innerText : '';
                                const hasChallenge = url.includes('/cdn-cgi/challenge-platform/') ||
                                                   title.includes('Just a moment') ||
                                                   title.includes('请稍候') ||
                                                   body.includes('Checking your browser') ||
                                                   body.includes('Verify you are human') ||
                                                   document.querySelector('[name="cf-turnstile-response"]') !== null;
                                return JSON.stringify({
                                    hasChallenge: hasChallenge,
                                    title: title,
                                    url: url
                                });
                            })();
                            """.trimIndent()
                        ) { result ->
                            Timber.i("[ArkDO][Network] attachHost: 403 challenge check result=%s", result)
                            // 同 onPageFinished：回调值是二次 JSON 编码，\"hasChallenge\":true 才是真实形态。
                            if (result?.contains("\\\"hasChallenge\\\":true") == true ||
                                result?.contains("\"hasChallenge\":true") == true
                            ) {
                                Timber.w("[ArkDO][Network] attachHost: Cloudflare challenge confirmed on 403, notifying")
                                ChallengeEventBus.notifyChallengeRequired("bootstrap", 403, ChallengeState.REQUIRED)
                            }
                        }
                    }, 1000)  // 延迟 1 秒让挑战页面渲染
                }
            }
        }

        // 添加 WebChromeClient 以获取 JS 控制台输出和页面加载进度
        web.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(message: android.webkit.ConsoleMessage?): Boolean {
                message?.let {
                    Timber.d("[ArkDO][WebView Console] [%s] %s:%d - %s",
                        it.messageLevel(), it.sourceId(), it.lineNumber(), it.message())
                }
                return true
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress % 25 == 0) {  // 每 25% 记录一次
                    Timber.i("[ArkDO][Network] attachHost: page loading progress=%d%%", newProgress)
                }
            }
        }

        web.addJavascriptInterface(ensureWebviewExecutor(web).getCallbackProxy(), "ArkDOBridge")
        Timber.i("[ArkDO][Network] attachHost: JavascriptInterface added")

        this.webView = web

        // 关键修复：确保 loadUrl 在 WebView 附着到窗口后执行
        // 策略：
        // 1. 如果 WebView 已经附着（isAttachedToWindow == true），立即在下一帧加载
        // 2. 如果 WebView 尚未附着，监听 onViewAttachedToWindow 回调
        var hasLoaded = false

        fun tryLoadUrl() {
            if (!hasLoaded) {
                hasLoaded = true
                Timber.i("[ArkDO][Network] attachHost: loading URL=%s", AppConstants.HOST_BOOTSTRAP_URL)
                Timber.i("[ArkDO][Network] attachHost: isAttachedToWindow=%s width=%d height=%d",
                    web.isAttachedToWindow, web.width, web.height)
                web.loadUrl(AppConstants.HOST_BOOTSTRAP_URL)
            }
        }

        web.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: android.view.View) {
                Timber.i("[ArkDO][Network] attachHost: onViewAttachedToWindow callback fired")
                tryLoadUrl()
            }

            override fun onViewDetachedFromWindow(v: android.view.View) {
                // 不需要处理
            }
        })

        // 如果 WebView 已经附着到窗口（AndroidView factory 执行时可能已附着），
        // 直接在下一帧加载，因为 onViewAttachedToWindow 不会再触发
        if (web.isAttachedToWindow) {
            Timber.i("[ArkDO][Network] attachHost: WebView already attached, loading in next frame")
            web.post { tryLoadUrl() }
        }

        return web
    }

    private fun ensureWebviewExecutor(webView: WebView): WebviewJsonExecutor {
        if (webviewExecutor == null) {
            webviewExecutor = WebviewJsonExecutor(webView)
        }
        return webviewExecutor!!
    }

    private fun attachController(webView: WebView) {
        Timber.i("[ArkDO][Network] attachController: before setExecutor, executor=%s", executor)
        this.webView = webView
        setExecutor(ensureWebviewExecutor(webView))
        Timber.i("[ArkDO][Network] attachController: after setExecutor, executor=%s", executor)
        configureDownloadDelegate()
    }

    /** host webview 是否已真正就绪到可发请求。 */
    fun canFetch(): Boolean {
        val result = executor != null
        Timber.v("[ArkDO][Network] canFetch: executor=%s result=%s", executor, result)
        return result
    }

    /** 记录用于保存下载文件的沙箱上下文（filesDir）。 */
    fun setDownloadContext(context: Context) {
        this.downloadContext = context
        configureDownloadDelegate()
    }

    /** 冷启动清空媒体缓存目录（filesDir/media_cache）。不碰附件目录 download。 */
    fun clearMediaCacheDir(context: Context) {
        val dir = File(context.filesDir, "media_cache")
        runCatching {
            if (dir.exists()) {
                dir.deleteRecursively()
                Timber.i("[ArkDO][Network] media cache cleared dir=%s", dir.absolutePath)
            }
        }.onFailure {
            Timber.i("[ArkDO][Network] media cache clear failed error=%s", it.message)
        }
    }



    // ---------- 下载 ----------

    /** 触发一次附件下载。结果经 DownloadEventBus 上抛。 */
    fun startDownload(url: String): Boolean {
        val web = webView ?: return false
        downloadIntents.add(DownloadIntent(false, url))
        return runCatching {
            web.loadUrl(url)
            true
        }.onFailure {
            downloadIntents.removeLastOrNull()
            Timber.i("[ArkDO][Network] startDownload failed msg=%s", it.message)
        }.getOrDefault(false)
    }

    /** 触发一次媒体下载（音视频），返回下到沙箱后的本地路径。走 host webview 下载委托过 CF。 */
    suspend fun downloadMedia(url: String): String {
        mediaCache[url]?.let { return it }
        mediaInflight[url]?.let { return it.await() }
        val web = webView ?: throw IllegalStateException("宿主尚未就绪")

        val result = CompletableDeferred<String>()
        mediaInflight[url] = result
        mediaWaiters[url] = result
        downloadIntents.add(DownloadIntent(true, url))
        runCatching { web.loadUrl(url) }.onFailure {
            downloadIntents.removeLastOrNull()
            settleMediaFailure(url, IllegalStateException("媒体下载启动失败"))
        }
        try {
            return withTimeout(MEDIA_DOWNLOAD_TIMEOUT_MS) { result.await() }
        } catch (e: Exception) {
            settleMediaFailure(url, e)
            throw e
        }
    }

    private fun settleMediaSuccess(url: String, path: String) {
        mediaInflight.remove(url)
        mediaCache[url] = path
        mediaWaiters.remove(url)?.complete(path)
    }

    private fun settleMediaFailure(url: String, reason: Throwable) {
        mediaInflight.remove(url)
        mediaWaiters.remove(url)?.completeExceptionally(reason)
    }

    /** 给 host 挂下载委托：onDownloadStart 选沙箱保存路径。完成/失败经 DownloadEventBus 通知页面。 */
    private fun configureDownloadDelegate() {
        if (downloadDelegateReady || webView == null || downloadContext == null) return
        val context = downloadContext!!
        val attachmentDir = File(context.filesDir, "download")
        val mediaDir = File(context.filesDir, "media_cache")

        webView?.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            // 按发起顺序出队意图，辨别媒体/附件。
            val intent = downloadIntents.firstOrNull()
            val isMedia = intent?.isMedia == true
            val dir = if (isMedia) mediaDir else attachmentDir
            runCatching { if (!dir.exists()) dir.mkdirs() }.onFailure {
                Timber.i("[ArkDO][Network] download dir failed msg=%s", it.message)
            }
            val suggested = suggestedFileName(contentDisposition, url)
            val fileName = if (suggested.isNotEmpty()) suggested else "download_${System.currentTimeMillis()}"
            downloadIntents.removeFirstOrNull()

            // 交给系统 DownloadManager 下到应用专属外部目录。
            startSystemDownload(context, url, fileName, dir, isMedia, intent?.url)
        })
        downloadDelegateReady = true
        Timber.i("[ArkDO][Network] download delegate configured")
    }

    private fun suggestedFileName(contentDisposition: String?, url: String): String {
        val cd = contentDisposition ?: return ""
        val marker = "filename=\""
        val idx = cd.indexOf(marker)
        if (idx >= 0) {
            return cd.substring(idx + marker.length).trim('"')
        }
        val after = cd.indexOf("filename=")
        if (after >= 0) {
            return cd.substring(after + "filename=".length).trim('"', ';', ' ')
        }
        return url.substringAfterLast('/').substringBefore('?')
    }

    private fun startSystemDownload(
        context: Context,
        url: String,
        fileName: String,
        dir: File,
        isMedia: Boolean,
        mediaUrl: String?
    ) {
        // 走系统 DownloadManager，下到应用专属外部目录；完成后再回调。
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
        val req = android.app.DownloadManager.Request(android.net.Uri.parse(url))
        req.setDestinationInExternalFilesDir(context, null, "${dir.name}/$fileName")
        req.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_HIDDEN)
        req.addRequestHeader("User-Agent", com.arkdo.android.services.web.UserAgentProvider.get(context))
        try {
            dm.enqueue(req)
            // 无回调通道，这里简化：附件经 DownloadEventBus 提示已开始下载。
            if (!isMedia) {
                DownloadEventBus.notify(
                    DownloadResult(
                        true,
                        fileName,
                        File(dir, fileName).absolutePath,
                        "已开始下载：$fileName"
                    )
                )
            }
        } catch (e: Exception) {
            if (isMedia && mediaUrl != null) {
                settleMediaFailure(mediaUrl, e)
            } else {
                DownloadEventBus.notify(DownloadResult(false, fileName, "", "下载失败"))
            }
        }
    }

    // ---------- 重载门闩 ----------

    fun markHostReloading() {
        reloading = true
    }

    /** 主动重载 host 首页：让 CF 挑战 JS 在真浏览器环境里运行以拿 cf_clearance。 */
    fun reloadHost(onComplete: (() -> Unit)? = null): Boolean {
        val web = webView ?: return false
        return runCatching {
            // 重新加载前检查 cookie 状态
            val cfClearance = WebSessionManager.getCfClearanceValue()
            val hasCfClearance = WebSessionManager.hasCfClearance()
            Timber.i("[ArkDO][Network] reloadHost: BEFORE reload cf_clearance=%s hasCfClearance=%b",
                cfClearance.take(20), hasCfClearance)

            markHostReloading()
            if (onComplete != null) {
                reloadCompleteCallbacks.add(onComplete)
            }
            web.loadUrl(AppConstants.HOST_BOOTSTRAP_URL)
            true
        }.onFailure {
            markHostReady()
            Timber.i("[ArkDO][Network] reloadHost failed msg=%s", it.message)
        }.getOrDefault(false)
    }

    /** host webview 重载完成（onPageFinished）：放行所有排队的 fetch。 */
    fun markHostReady() {
        reloading = false
        if (readyWaiters.isEmpty() && reloadCompleteCallbacks.isEmpty()) return

        // 放行所有排队的 fetch
        val waiters = readyWaiters.toList()
        readyWaiters.clear()
        waiters.forEach { it() }

        // 触发重新加载完成的回调
        val callbacks = reloadCompleteCallbacks.toList()
        reloadCompleteCallbacks.clear()
        callbacks.forEach { it() }
    }

    private suspend fun whenHostReady() {
        if (!reloading) return
        val deferred = CompletableDeferred<Unit>()
        val handler = mainHandler
        val timeout = handler.postDelayed({
            markHostReady()
        }, AppConstants.HOST_RELOAD_READY_TIMEOUT_MS)
        readyWaiters.add {
            handler.removeCallbacksAndMessages(null)
            if (deferred.isActive) deferred.complete(Unit)
        }
        try {
            withTimeout(AppConstants.HOST_RELOAD_READY_TIMEOUT_MS) { deferred.await() }
        } catch (e: Exception) {
            // 兜底超时已由 postDelayed 触发 markHostReady
        }
    }

    // ---------- MessageBus ----------

    fun startMessageBus() {
        val web = webView ?: run {
            Timber.i("[ArkDO][Network] MessageBus start skipped: controller not ready")
            return
        }
        runCatching { web.evaluateJavascript(MessageBusScriptTemplate.buildMessageBusScript(), null) }.onFailure {
            Timber.i("[ArkDO][Network] MessageBus script injection failed")
        }
    }

    fun stopMessageBus() {
        val web = webView ?: run {
            Timber.i("[ArkDO][Network] MessageBus stop skipped: controller not ready")
            return
        }
        runCatching {
            web.evaluateJavascript(
                "window.__arkdoMessageBusStopRequested = true; window.__arkdoMessageBusStarted = false; " +
                    "window.__arkdoMessageBusGeneration = (window.__arkdoMessageBusGeneration || 0) + 1;",
                null
            )
        }.onFailure {
            Timber.i("[ArkDO][Network] MessageBus stop injection failed")
        }
    }

    fun subscribeTopicChannel(topicId: Long, position: Long) {
        subscribeChannel("/topic/$topicId", position)
    }

    fun unsubscribeTopicChannel(topicId: Long) {
        unsubscribeChannel("/topic/$topicId")
    }

    fun subscribeChannel(channelName: String, position: Long) {
        val web = webView ?: run {
            Timber.i("[ArkDO][Network] MessageBus subscribeChannel skipped: controller not ready")
            return
        }
        val channel = jsonString(channelName)
        val pos = if (position != Long.MAX_VALUE && position != Long.MIN_VALUE) position.toInt() else -1
        val script =
            "if (window.__arkdoMessageBusAddChannel) { window.__arkdoMessageBusAddChannel($channel, $pos); } " +
                "else { window.__arkdoMessageBusChannels = window.__arkdoMessageBusChannels || {}; " +
                "if (!($channel in window.__arkdoMessageBusChannels)) { window.__arkdoMessageBusChannels[$channel] = $pos; } }"
        runCatching {
            web.evaluateJavascript(script, null)
            Timber.i("[ArkDO][Network] MessageBus subscribeChannel channel=%s pos=%d", channelName, pos)
        }.onFailure {
            Timber.i("[ArkDO][Network] MessageBus subscribeChannel injection failed")
        }
    }

    fun unsubscribeChannel(channelName: String) {
        val web = webView ?: run {
            Timber.i("[ArkDO][Network] MessageBus unsubscribeChannel skipped: controller not ready")
            return
        }
        val channel = jsonString(channelName)
        val script =
            "if (window.__arkdoMessageBusRemoveChannel) { window.__arkdoMessageBusRemoveChannel($channel); } " +
                "else if (window.__arkdoMessageBusChannels) { delete window.__arkdoMessageBusChannels[$channel]; }"
        runCatching { web.evaluateJavascript(script, null) }.onFailure {
            Timber.i("[ArkDO][Network] MessageBus unsubscribeChannel injection failed")
        }
    }

    // ---------- 通用请求 ----------

    private fun setExecutor(executor: ArkWebJsonExecutor) {
        this.executor = executor
    }

    override suspend fun getJson(path: String, options: JsonRequestOptions?): NetworkResult<String> {
        val requestPath = normalizePath(path)
        Timber.i("[ArkDO][Network] GET %s start", requestPath)
        if (!canFetch()) return hostNotReady()
        whenHostReady()
        return runCatching {
            val raw = executor!!.run(WebFetchScriptFactory.createFetchScript(requestPath))
            convertEnvelope(requestPath, parseEnvelope(raw), "GET", options)
        }.getOrElse {
            Timber.i("[ArkDO][Network] GET %s failed before response envelope", requestPath)
            networkFailed()
        }
    }

    override suspend fun getJsonWithHeaders(
        path: String,
        headers: List<HttpHeaderEntry>,
        referrer: String,
        options: JsonRequestOptions?
    ): NetworkResult<String> {
        val requestPath = normalizePath(path)
        Timber.i("[ArkDO][Network] GET(headered) %s start headerCount=%d", requestPath, headers.size)
        if (!canFetch()) return hostNotReady()
        whenHostReady()
        return runCatching {
            val raw = executor!!.run(WebFetchScriptFactory.createHeaderedFetchScript(requestPath, headers, referrer))
            convertEnvelope(requestPath, parseEnvelope(raw), "GET", options)
        }.getOrElse {
            Timber.i("[ArkDO][Network] GET(headered) %s failed before response envelope", requestPath)
            networkFailed()
        }
    }

    /** 经 host webview 取二进制资源（头像）并回传 base64。 */
    suspend fun getImageBase64(target: String): ImageFetchResult {
        if (!canFetch()) return ImageFetchResult(false, "", "")
        whenHostReady()
        return runCatching {
            val raw = executor!!.run(WebFetchScriptFactory.createImageFetchScript(target))
            val envelope = parseEnvelope(raw)
            val status = envelope.statusCode ?: 0
            val body = envelope.body ?: ""
            if (status in 200..299 && body.isNotEmpty()) {
                ImageFetchResult(true, body, envelope.contentType ?: "")
            } else {
                ImageFetchResult(false, "", "")
            }
        }.getOrElse { ImageFetchResult(false, "", "") }
    }

    override suspend fun postForm(path: String, body: String, options: JsonRequestOptions?): NetworkResult<String> {
        val requestPath = normalizePath(path)
        Timber.i("[ArkDO][Network] POST %s start bodyLength=%d", requestPath, body.length)
        if (!canFetch()) return hostNotReady()
        whenHostReady()
        return runCatching {
            val referrer = options?.referrer ?: ""
            val raw = executor!!.run(WebFetchScriptFactory.createFormScript("POST", requestPath, body, referrer))
            convertEnvelope(requestPath, parseEnvelope(raw), "POST", options)
        }.getOrElse {
            Timber.i("[ArkDO][Network] POST %s failed before response envelope", requestPath)
            networkFailed()
        }
    }

    override suspend fun postJson(path: String, body: String, options: JsonRequestOptions?): NetworkResult<String> {
        val requestPath = normalizePath(path)
        Timber.i("[ArkDO][Network] POST(json) %s start bodyLength=%d", requestPath, body.length)
        if (!canFetch()) return hostNotReady()
        whenHostReady()
        return runCatching {
            val referrer = options?.referrer ?: ""
            val raw = executor!!.run(WebFetchScriptFactory.createPostJsonScript(requestPath, body, referrer))
            convertEnvelope(requestPath, parseEnvelope(raw), "POST", options)
        }.getOrElse {
            Timber.i("[ArkDO][Network] POST(json) %s failed before response envelope", requestPath)
            networkFailed()
        }
    }

    override suspend fun uploadImage(
        base64: String,
        fileName: String,
        mimeType: String,
        options: JsonRequestOptions?
    ): NetworkResult<String> {
        val requestPath = "/uploads.json"
        Timber.i("[ArkDO][Network] UPLOAD %s start fileName=%s base64Length=%d", requestPath, fileName, base64.length)
        if (!canFetch()) return hostNotReady()
        whenHostReady()
        return runCatching {
            val referrer = options?.referrer ?: ""
            val raw = executor!!.run(WebFetchScriptFactory.createUploadScript(base64, fileName, mimeType, referrer))
            convertEnvelope(requestPath, parseEnvelope(raw), "POST", options)
        }.getOrElse {
            Timber.i("[ArkDO][Network] UPLOAD %s failed before response envelope", requestPath)
            networkFailed()
        }
    }

    override suspend fun putJson(path: String, options: JsonRequestOptions?): NetworkResult<String> {
        val requestPath = normalizePath(path)
        Timber.i("[ArkDO][Network] PUT %s start", requestPath)
        if (!canFetch()) return hostNotReady()
        whenHostReady()
        return runCatching {
            val referrer = options?.referrer ?: ""
            val raw = executor!!.run(WebFetchScriptFactory.createPutScript(requestPath, referrer))
            convertEnvelope(requestPath, parseEnvelope(raw), "PUT", options)
        }.getOrElse {
            Timber.i("[ArkDO][Network] PUT %s failed before response envelope", requestPath)
            networkFailed()
        }
    }

    override suspend fun putForm(path: String, body: String, options: JsonRequestOptions?): NetworkResult<String> =
        sendFormWithBody("PUT", path, body, options)

    override suspend fun deleteForm(path: String, body: String, options: JsonRequestOptions?): NetworkResult<String> =
        sendFormWithBody("DELETE", path, body, options)

    private suspend fun sendFormWithBody(
        method: String,
        path: String,
        body: String,
        options: JsonRequestOptions?
    ): NetworkResult<String> {
        val requestPath = normalizePath(path)
        Timber.i("[ArkDO][Network] %s %s start bodyLength=%d", method, requestPath, body.length)
        if (!canFetch()) return hostNotReady()
        whenHostReady()
        return runCatching {
            val referrer = options?.referrer ?: ""
            val raw = executor!!.run(WebFetchScriptFactory.createFormScript(method, requestPath, body, referrer))
            convertEnvelope(requestPath, parseEnvelope(raw), method, options)
        }.getOrElse {
            Timber.i("[ArkDO][Network] %s %s failed before response envelope", method, requestPath)
            networkFailed()
        }
    }

    // ---------- 信封处理 ----------

    @kotlinx.serialization.Serializable
    private data class WebFetchEnvelope(
        val statusCode: Int? = null,
        val contentType: String? = null,
        val cfMitigated: String? = null,
        val body: String? = null,
        val errorMessage: String? = null,
        val csrfSource: String? = null,
        val csrfLength: Int? = null
    )

    private fun parseEnvelope(raw: String): WebFetchEnvelope {
        return runCatching {
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            json.decodeFromString<WebFetchEnvelope>(raw)
        }.getOrElse { WebFetchEnvelope(errorMessage = "envelope parse failed: $raw") }
    }

    private fun convertEnvelope(
        path: String,
        envelope: WebFetchEnvelope,
        method: String,
        options: JsonRequestOptions?
    ): NetworkResult<String> {
        if (envelope.csrfSource != null || envelope.csrfLength != null) {
            Timber.i("[ArkDO][Network] %s %s csrf source=%s length=%d",
                method, path, envelope.csrfSource ?: "unknown", envelope.csrfLength ?: 0)
        }

        if (!envelope.errorMessage.isNullOrEmpty()) {
            Timber.i("[ArkDO][Network] %s %s envelope error=%s", method, path, envelope.errorMessage)
            // 写请求在宿主挑战页上读不到 token。空 token 发出去只会换来 Discourse 的
            // "BAD CSRF"（纯文本，ChallengeDetector 认不出），这里直接当成需要过盾。
            if (envelope.errorMessage == "csrf token missing" && options?.notifyChallenge != false) {
                ChallengeEventBus.notifyChallengeRequired(path, 0, ChallengeState.REQUIRED)
                return NetworkResult(
                    success = false,
                    statusCode = 0,
                    challengeState = ChallengeState.REQUIRED,
                    error = NetworkError(
                        NetworkErrorCode.CHALLENGE_REQUIRED,
                        "需要完成可见 Cloudflare 验证后重试。",
                        requiresChallenge = true
                    )
                )
            }
            return NetworkResult(
                success = false,
                statusCode = 0,
                challengeState = ChallengeState.NONE,
                error = NetworkError(NetworkErrorCode.NETWORK_FAILURE, envelope.errorMessage, false)
            )
        }

        val statusCode = envelope.statusCode ?: 0
        val contentType = envelope.contentType ?: ""
        val cfMitigated = envelope.cfMitigated ?: ""
        val body = envelope.body ?: ""
        val challengeState = ChallengeDetector.detectChallenge(statusCode, contentType, body, cfMitigated)
        Timber.i("[ArkDO][Network] %s %s response status=%d contentType=%s bodyLength=%d challenge=%s",
            method, path, statusCode, contentType, body.length, challengeState)
        logResponseBody(path, contentType, body, method, statusCode, challengeState)

        if (challengeState != ChallengeState.NONE) {
            if (options?.notifyChallenge != false) {
                ChallengeEventBus.notifyChallengeRequired(path, statusCode, challengeState)
            }
            return NetworkResult(
                success = false,
                statusCode = statusCode,
                challengeState = challengeState,
                error = NetworkError(
                    NetworkErrorCode.CHALLENGE_REQUIRED,
                    "需要完成可见 Cloudflare 验证后重试。",
                    requiresChallenge = true
                )
            )
        }

        if (statusCode !in 200..299) {
            return NetworkResult(
                success = false,
                statusCode = statusCode,
                challengeState = ChallengeState.NONE,
                error = NetworkError(
                    NetworkErrorCode.UNEXPECTED_RESPONSE,
                    "Linux.do 返回 HTTP $statusCode。",
                    requiresChallenge = false
                )
            )
        }

        return NetworkResult(
            success = true,
            statusCode = statusCode,
            challengeState = ChallengeState.NONE,
            data = body
        )
    }

    private fun logResponseBody(
        path: String,
        contentType: String,
        body: String,
        method: String,
        statusCode: Int,
        challengeState: ChallengeState
    ) {
        if (!shouldLogResponseBody(contentType, body, statusCode, challengeState)) return
        val formattedBody = formatBodyForLog(contentType, body)
        val loggedBody = if (formattedBody.length <= BODY_LOG_MAX_CHARS) {
            formattedBody
        } else {
            "${formattedBody.substring(0, BODY_LOG_MAX_CHARS)}...(truncated ${formattedBody.length - BODY_LOG_MAX_CHARS} chars)"
        }
        val chunkCount = maxOf(1, (loggedBody.length + BODY_LOG_CHUNK_SIZE - 1) / BODY_LOG_CHUNK_SIZE)
        Timber.i("[ArkDO][Network][Body] %s %s body begin contentType=%s rawLength=%d chunks=%d",
            method, path, contentType, body.length, chunkCount)
        for (start in loggedBody.indices step BODY_LOG_CHUNK_SIZE) {
            val chunkIndex = start / BODY_LOG_CHUNK_SIZE + 1
            val chunk = loggedBody.substring(start, minOf(start + BODY_LOG_CHUNK_SIZE, loggedBody.length))
            Timber.i("[ArkDO][Network][Body] %s %s body chunk %d/%d: %s", method, path, chunkIndex, chunkCount, chunk)
        }
    }

    private fun shouldLogResponseBody(contentType: String, body: String, statusCode: Int, challengeState: ChallengeState): Boolean {
        if (challengeState != ChallengeState.NONE || statusCode >= 400) return true
        if (body.isEmpty()) return false
        return !contentType.lowercase().contains("application/json")
    }

    private fun formatBodyForLog(contentType: String, body: String): String {
        val normalized = contentType.lowercase()
        val trimmed = body.trim()
        val looksJson = normalized.contains("application/json") || trimmed.startsWith("{") || trimmed.startsWith("[")
        if (!looksJson) return body
        return runCatching {
            kotlinx.serialization.json.Json { prettyPrint = true }
                .parseToJsonElement(body)
                .toString()
        }.getOrDefault(body)
    }

    private fun normalizePath(path: String): String =
        if (path.startsWith("/")) path else "/$path"

    private fun hostNotReady(): NetworkResult<String> = NetworkResult(
        success = false,
        statusCode = 0,
        challengeState = ChallengeState.NONE,
        error = NetworkError(NetworkErrorCode.NETWORK_FAILURE, "ArkWeb 宿主尚未就绪。", false)
    )

    private fun networkFailed(): NetworkResult<String> = NetworkResult(
        success = false,
        statusCode = 0,
        challengeState = ChallengeState.NONE,
        error = NetworkError(NetworkErrorCode.NETWORK_FAILURE, "ArkWeb 请求失败。", false)
    )

    private fun jsonString(value: String): String {
        val sb = StringBuilder("\"")
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        sb.append("\"")
        return sb.toString()
    }
}

private object WebSessionManagerInternal {
    fun configure(web: WebView, source: String) {
        com.arkdo.android.services.web.WebSessionManager.applyUserAgent(web, source)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
    }
}
