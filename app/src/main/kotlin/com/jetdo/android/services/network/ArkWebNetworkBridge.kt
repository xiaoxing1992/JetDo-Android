package com.jetdo.android.services.network

import com.jetdo.android.R
import com.jetdo.android.core.data.AppContextHolder
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.jetdo.android.common.constants.AppConstants
import com.jetdo.android.services.challenge.ChallengeEventBus
import com.jetdo.android.services.content.DownloadEventBus
import com.jetdo.android.services.content.DownloadResult
import com.jetdo.android.services.content.MediaSource
import com.jetdo.android.services.message.MessageBusEventBus
import com.jetdo.android.services.network.webscript.MessageBusScriptTemplate
import com.jetdo.android.services.web.WebSessionManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val MEDIA_DOWNLOAD_TIMEOUT_MS = 120_000L

/**
 * 附件下载的等待窗口，比音视频更宽。
 *
 * 音视频要的是「尽快起播」，超时短反而是保护；附件则是一次性把整个包拉下来 —— 论坛的
 * 上传上限可到几十 MB，弱网下 2 分钟并不够。等待期间界面上有「正在下载」的提示，
 * 卡住也不会让人以为没反应。
 */
private const val ATTACHMENT_DOWNLOAD_TIMEOUT_MS = 300_000L

/**
 * 上传单独放宽。走的是同一条 WebView fetch 通道，但上传要先 base64 拼进脚本、服务端还要
 * 转码落盘，15s 的通用闸门实测会误杀（本项目一张 320KB 的竖屏截图稳定失败：
 * 日志停在 `UPLOAD /uploads.json start` 后再无声息，15s 后 `failed before response envelope`）。
 */
private const val UPLOAD_TIMEOUT_MS = 60_000L
private const val BODY_LOG_CHUNK_SIZE = 3000
private const val BODY_LOG_MAX_CHARS = 1500

// ==========================================================================
// 与 HarmonyOS 版 services/network/ArkWebNetworkBridge.ets 逐字段对齐。
// 注入式 fetch 桥：所有网络请求改为在 host WebView（linux.do 同源文档）内执行 JS fetch，
// 结果经 window.JetDoBridge.onJsonResult(requestId, envelopeJson) 回传原生，
// 由 requestId → PendingJsonRequest 关联 Promise 兑现。
//
// Android 映射：
//   runJavaScript  → WebView.evaluateJavascript（主线程）
//   javaScriptProxy → WebView.addJavascriptInterface("JetDoBridge")
//   WebDownloadDelegate → DownloadListener
//   WebviewController.startDownload(url) → webView.loadUrl(url) 触发下载导航
// ==========================================================================

interface ArkWebJsonExecutor {
    /** 运行脚本并返回 JS 侧回传的 payload（信封 JSON 字符串）。 */
    suspend fun run(script: String, timeoutMs: Long = AppConstants.DEFAULT_TIMEOUT_MS): String
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
    val url: String,
    /**
     * 调用方在**等落盘路径**（`downloadMedia` / `downloadAttachment`）。
     *
     * 旧路径 `startDownload` 是「页面触发的下载」，只在 enqueue 时往 [DownloadEventBus] 抛一条
     * 「已开始下载」，不认领完成事件；awaited 的调用则要进结算表、由系统广播 settle 回来。
     */
    val awaited: Boolean = false
)

/**
 * JS 桥代理。@JavascriptInterface 方法由 JS 线程调用，切回主线程后交 executor。
 * 方法名与 HarmonyOS 版 JetDoBridge 完全一致（window.JetDoBridge.onJsonResult/onBusEvent）。
 */
class JetDoBridgeProxy(
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

    private val callbackProxy = JetDoBridgeProxy(this, mainHandler)
    private val pendingRequests = mutableMapOf<String, PendingJsonRequest>()
    private var requestSequence = 0L

    fun getCallbackProxy(): JetDoBridgeProxy = callbackProxy

    override suspend fun run(script: String, timeoutMs: Long): String {
        val requestId = "jetdo_${requestSequence++}"
        Timber.i("[JetDo][Network] executor start requestId=%s", requestId)

        val deferred = CompletableDeferred<String>()
        pendingRequests[requestId] = PendingJsonRequest(deferred)

        // 兜底超时：requestId 未在窗口内被 JS 回传则判失败。
        val timeoutJob = kotlinx.coroutines.MainScope().launch {
            kotlinx.coroutines.delay(timeoutMs)
            pendingRequests.remove(requestId)?.let {
                if (!it.deferred.isCompleted) {
                    it.deferred.completeExceptionally(IllegalStateException(AppContextHolder.getString(R.string.err_request_timeout)))
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
            rejectRequest(requestId, AppContextHolder.getString(R.string.err_script_failed))
            timeoutJob.cancel()
        }

        try {
            return withTimeout(timeoutMs) { deferred.await() }
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
            rejectRequest(requestId, AppContextHolder.getString(R.string.err_script_failed))
        }
    }

    override fun onJsonResult(requestId: String, payload: String) {
        val pending = pendingRequests.remove(requestId)
        if (pending == null) {
            Timber.i("[JetDo][Network] callback ignored requestId=%s", requestId)
            return
        }
        Timber.i("[JetDo][Network] callback received requestId=%s payloadLength=%d", requestId, payload.length)
        pending.deferred.complete(payload)
    }

    override fun onBusEvent(payload: String) {
        Timber.i("[JetDo][MessageBus] event len=%d head=%s", payload.length, payload.take(160))
        MessageBusEventBus.publishPayload(payload)
    }

    private fun rejectRequest(requestId: String, message: String) {
        val pending = pendingRequests.remove(requestId) ?: return
        Timber.i("[JetDo][Network] executor reject requestId=%s reason=%s", requestId, message)
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
    // 按源 URL 等「下载完成 → 落盘路径」。媒体与附件共用一张表：两者 URL 不会撞，
    // 且它们的调用方（播放器 / 附件打开器）本来就是在等同一个东西。
    private val fileWaiters = mutableMapOf<String, CompletableDeferred<String>>()
    private val mediaInflight = mutableMapOf<String, kotlinx.coroutines.Deferred<String>>()
    private val mediaCache = mutableMapOf<String, String>()
    private val downloadIntents = mutableListOf<DownloadIntent>()

    // 下载结算表:DownloadManager enqueue id → (源 URL, 落盘路径)。
    // 下载委托是「loadUrl 触发下载导航 → 系统下载」的两段式,完成事件只能靠系统广播接回,
    // ACTION_DOWNLOAD_COMPLETE 里按 id 查回源 URL,才能 settle 到 fileWaiters 的 waiter。
    private val downloadJobs = mutableMapOf<Long, FileDownloadJob>()
    private var downloadReceiverRegistered = false

    private data class FileDownloadJob(val url: String, val localPath: String)

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
            Timber.i("[JetDo][Network] preloadHostInBackground: WebView already exists, skipping")
            return
        }

        Timber.i("[JetDo][Network] ========== Preloading WebView in Background ==========")
        Timber.i("[JetDo][Network] preloadHostInBackground: start ts=%d", System.currentTimeMillis())

        // 检查是否在主线程
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Timber.e("[JetDo][Network] preloadHostInBackground: NOT on main thread, posting to main thread")
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

        Timber.i("[JetDo][Network] preloadHostInBackground: WebView created, will load when attached to window")
        Timber.i("[JetDo][Network] preloadHostInBackground: isAttachedToWindow=%s", web.isAttachedToWindow)
    }

    /**
     * 创建隐藏 host WebView 并挂桥。必须主线程调用。
     * 加载 HOST_BOOTSTRAP_URL 定 origin；onPageFinished → attachController + markHostReady。
     */
    fun attachHost(context: Context): WebView {
        Timber.i("[JetDo][Network] attachHost: creating WebView")
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
        com.jetdo.android.services.web.WebSessionManager.applyUserAgent(web, "ArkWebHost")
        Timber.i("[JetDo][Network] attachHost: User-Agent set to %s", web.settings.userAgentString)

        // 启用现代网络特性
        web.settings.setSupportMultipleWindows(false)
        web.settings.loadWithOverviewMode = true
        web.settings.useWideViewPort = true

        // 关键：禁用阻塞网络请求（某些 WebView 版本默认开启）
        web.settings.blockNetworkLoads = false
        web.settings.blockNetworkImage = false
        Timber.i("[JetDo][Network] attachHost: network blocking disabled")

        web.alpha = 0.01f
        web.setBackgroundColor(0x00000000)
        Timber.i("[JetDo][Network] attachHost: WebView settings configured")

        WebSessionManagerInternal.configure(web, "host")
        Timber.i("[JetDo][Network] attachHost: WebSessionManager configured")

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
                Timber.i("[JetDo][Network] attachHost: onPageStarted url=%s", url)

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
                    Timber.i("[JetDo][Network] Cookie for '%s': hasCf=%b length=%d",
                        testUrl, hasCfClearance, cookies?.length ?: 0)
                }
                Timber.i("[JetDo][Network] attachHost: onPageStarted User-Agent=%s", view?.settings?.userAgentString)

                markHostReloading()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                Timber.i("[JetDo][Network] attachHost: onPageFinished url=%s", url)

                // 检查页面是否有有效的 origin（避免 CORS 错误）
                view?.evaluateJavascript("window.location.origin") { origin ->
                    Timber.i("[JetDo][Network] attachHost: page origin=%s", origin)

                    if (origin == "\"null\"" || origin == "null" || origin.isNullOrEmpty()) {
                        Timber.w("[JetDo][Network] attachHost: invalid origin, retrying bootstrap load in 2s")
                        Handler(Looper.getMainLooper()).postDelayed({
                            Timber.i("[JetDo][Network] attachHost: retrying bootstrap load")
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
                    Timber.i("[JetDo][Network] attachHost: controller attached, host ready with origin=%s", origin)

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
                        Timber.i("[JetDo][Network] attachHost: challenge detection raw result=%s", result)
                        // evaluateJavascript 回传的是 JS 值的 JSON 编码：内层 {"hasChallenge":true}
                        // 到这里变成字符串 "{\"hasChallenge\":true}"。直接 contains 字面 true 永远匹配不上，
                        // 挑战页会被当成干净页，过盾流程根本不启动。
                        val isChallengePage = result?.contains("\\\"hasChallenge\\\":true") == true
                            || result?.contains("\"hasChallenge\":true") == true

                        // 额外检查：如果标题是"请稍候…"也认为是挑战页面
                        val hasPendingTitle = result?.contains("请稍候") == true

                        Timber.i("[JetDo][Network] attachHost: isChallengePage=%s hasPendingTitle=%s", isChallengePage, hasPendingTitle)

                        if ((isChallengePage || hasPendingTitle) && url?.contains("linux.do") == true) {
                            Timber.w("[JetDo][Network] attachHost: Cloudflare challenge page detected, notifying ChallengeEventBus")
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
                Timber.e("[JetDo][Network] attachHost: onReceivedError url=%s code=%d msg=%s",
                    request?.url, error?.errorCode, error?.description)

                // 如果是主页面加载失败（ERR_CONNECTION_CLOSED 等），尝试重新加载
                // 这避免了 origin='null' 导致的 CORS 错误
                if (request?.isForMainFrame == true && error?.errorCode == -6) {
                    Timber.w("[JetDo][Network] attachHost: main frame load failed, will retry on next fetch")
                    // 标记需要重新加载，但不立即重载（避免循环）
                    // onPageFinished 会设置 executor，但首次 fetch 失败后会触发 reloadHost
                }
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: android.webkit.SslErrorHandler?,
                error: android.net.http.SslError?
            ) {
                Timber.e("[JetDo][Network] attachHost: onReceivedSslError primaryError=%d url=%s",
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
                Timber.e("[JetDo][Network] attachHost: onReceivedHttpError url=%s statusCode=%d",
                    url, statusCode)

                // 如果是主页面的 403 错误，很可能是 Cloudflare 挑战
                if (request?.isForMainFrame == true && statusCode == 403 && url?.contains("linux.do") == true) {
                    Timber.w("[JetDo][Network] attachHost: 403 on main frame, likely Cloudflare challenge")
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
                            Timber.i("[JetDo][Network] attachHost: 403 challenge check result=%s", result)
                            // 同 onPageFinished：回调值是二次 JSON 编码，\"hasChallenge\":true 才是真实形态。
                            if (result?.contains("\\\"hasChallenge\\\":true") == true ||
                                result?.contains("\"hasChallenge\":true") == true
                            ) {
                                Timber.w("[JetDo][Network] attachHost: Cloudflare challenge confirmed on 403, notifying")
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
                    Timber.d("[JetDo][WebView Console] [%s] %s:%d - %s",
                        it.messageLevel(), it.sourceId(), it.lineNumber(), it.message())
                }
                return true
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress % 25 == 0) {  // 每 25% 记录一次
                    Timber.i("[JetDo][Network] attachHost: page loading progress=%d%%", newProgress)
                }
            }
        }

        web.addJavascriptInterface(ensureWebviewExecutor(web).getCallbackProxy(), "JetDoBridge")
        Timber.i("[JetDo][Network] attachHost: JavascriptInterface added")

        this.webView = web

        // 关键修复：确保 loadUrl 在 WebView 附着到窗口后执行
        // 策略：
        // 1. 如果 WebView 已经附着（isAttachedToWindow == true），立即在下一帧加载
        // 2. 如果 WebView 尚未附着，监听 onViewAttachedToWindow 回调
        var hasLoaded = false

        fun tryLoadUrl() {
            if (!hasLoaded) {
                hasLoaded = true
                Timber.i("[JetDo][Network] attachHost: loading URL=%s", AppConstants.HOST_BOOTSTRAP_URL)
                Timber.i("[JetDo][Network] attachHost: isAttachedToWindow=%s width=%d height=%d",
                    web.isAttachedToWindow, web.width, web.height)
                web.loadUrl(AppConstants.HOST_BOOTSTRAP_URL)
            }
        }

        web.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: android.view.View) {
                Timber.i("[JetDo][Network] attachHost: onViewAttachedToWindow callback fired")
                tryLoadUrl()
            }

            override fun onViewDetachedFromWindow(v: android.view.View) {
                // 不需要处理
            }
        })

        // 如果 WebView 已经附着到窗口（AndroidView factory 执行时可能已附着），
        // 直接在下一帧加载，因为 onViewAttachedToWindow 不会再触发
        if (web.isAttachedToWindow) {
            Timber.i("[JetDo][Network] attachHost: WebView already attached, loading in next frame")
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
        Timber.i("[JetDo][Network] attachController: before setExecutor, executor=%s", executor)
        this.webView = webView
        setExecutor(ensureWebviewExecutor(webView))
        Timber.i("[JetDo][Network] attachController: after setExecutor, executor=%s", executor)
        configureDownloadDelegate()
    }

    /** host webview 是否已真正就绪到可发请求。 */
    fun canFetch(): Boolean {
        val result = executor != null
        Timber.v("[JetDo][Network] canFetch: executor=%s result=%s", executor, result)
        return result
    }

    /** 记录用于保存下载文件的沙箱上下文（filesDir）。 */
    fun setDownloadContext(context: Context) {
        this.downloadContext = context
        configureDownloadDelegate()
    }

    /** 冷启动清空媒体缓存目录。目录与 DownloadManager 落盘位置一致(getExternalFilesDir/media_cache)。 */
    fun clearMediaCacheDir(context: Context) {
        val mediaBase = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(mediaBase, "media_cache")
        runCatching {
            if (dir.exists()) {
                dir.deleteRecursively()
                Timber.i("[JetDo][Network] media cache cleared dir=%s", dir.absolutePath)
            }
        }.onFailure {
            Timber.i("[JetDo][Network] media cache clear failed error=%s", it.message)
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
            Timber.i("[JetDo][Network] startDownload failed msg=%s", it.message)
        }.getOrDefault(false)
    }

    /**
     * 用 host webview 发起一次「下载导航」。
     *
     * WebView 的方法**只能在主线程**调用；而媒体/附件的触发点可能来自任意调度器
     * （附件点击就跑在 `Dispatchers.IO` 上）。在错误线程上调 `loadUrl` 不会抛异常，
     * Android 只打一条 `WebViewMethodCalledOnWrongThreadViolation` 警告然后**什么也不做** ——
     * 表现为「点了没反应，最后超时报下载失败」，极难定位。所以这里统一收口到主线程。
     */
    private suspend fun loadForDownload(web: android.webkit.WebView, url: String) {
        withContext(Dispatchers.Main.immediate) { web.loadUrl(url) }
    }

    /** 触发一次媒体下载（音视频），返回下到沙箱后的本地路径。走 host webview 下载委托过 CF。 */
    suspend fun downloadMedia(url: String): String {
        mediaCache[url]?.let { return it }
        mediaInflight[url]?.let { return it.await() }
        val web = webView ?: throw IllegalStateException(AppContextHolder.getString(R.string.bridge_host_not_ready))

        val result = CompletableDeferred<String>()
        mediaInflight[url] = result
        fileWaiters[url] = result
        downloadIntents.add(DownloadIntent(true, url, awaited = true))
        runCatching { loadForDownload(web, url) }.onFailure {
            downloadIntents.removeLastOrNull()
            settleFileFailure(url, IllegalStateException(AppContextHolder.getString(R.string.common_media_download_start_failed)))
        }
        try {
            return withTimeout(MEDIA_DOWNLOAD_TIMEOUT_MS) { result.await() }
        } catch (e: Exception) {
            settleFileFailure(url, e)
            throw e
        }
    }

    /**
     * 触发一次附件下载，返回下到沙箱后的本地路径。
     *
     * 与 [downloadMedia] 同一条路（host webview 的下载委托 + 会话 Cookie 过 CF），差别只在
     * 落盘目录与时长：附件落 `<ext>/download/`；且附件常常是个几十 MB 的包，比音视频更需要
     * 宽裕的等待窗口。**不做缓存** —— 附件是一次性消费（下完立刻交系统打开/分享），
     * 缓存只会白占空间。
     */
    suspend fun downloadAttachment(url: String): String {
        fileWaiters[url]?.let { return it.await() }
        val web = webView ?: throw IllegalStateException(AppContextHolder.getString(R.string.bridge_host_not_ready))

        val result = CompletableDeferred<String>()
        fileWaiters[url] = result
        downloadIntents.add(DownloadIntent(false, url, awaited = true))
        runCatching { loadForDownload(web, url) }.onFailure {
            downloadIntents.removeLastOrNull()
            settleFileFailure(url, IllegalStateException(AppContextHolder.getString(R.string.common_attachment_download_start_failed)))
        }
        try {
            return withTimeout(ATTACHMENT_DOWNLOAD_TIMEOUT_MS) { result.await() }
        } catch (e: Exception) {
            settleFileFailure(url, e)
            throw e
        }
    }

    private fun settleFileSuccess(url: String, path: String) {
        mediaInflight.remove(url)
        mediaCache[url] = path
        fileWaiters.remove(url)?.complete(path)
    }

    private fun settleFileFailure(url: String, reason: Throwable) {
        mediaInflight.remove(url)
        fileWaiters.remove(url)?.completeExceptionally(reason)
    }

    /** 给 host 挂下载委托：onDownloadStart 选沙箱保存路径。完成/失败经 DownloadEventBus 通知页面。 */
    private fun configureDownloadDelegate() {
        if (downloadDelegateReady || webView == null || downloadContext == null) return
        val context = downloadContext!!.applicationContext
        // 媒体缓存目录必须与 DownloadManager 实际落盘目录一致(getExternalFilesDir);
        // 之前用 filesDir 推路径,内/外目录对不上,缓存命中与冷启动清理都落空。
        val mediaBase = context.getExternalFilesDir(null) ?: context.filesDir
        // 附件目录也必须在**外置**专属目录：真实落盘由 startSystemDownload 的
        // setDestinationInExternalFilesDir 决定（外置），这里若按 filesDir 预建，目录会白建，
        // 且 FileProvider 的 external-files-path 映射也会落空。
        val attachmentDir = File(mediaBase, "download")
        val mediaDir = File(mediaBase, "media_cache")

        webView?.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            // 按发起顺序出队意图，辨别媒体/附件，以及调用方是否在等落盘路径。
            val intent = downloadIntents.firstOrNull()
            val isMedia = intent?.isMedia == true
            val awaited = intent?.awaited == true
            val dir = if (isMedia) mediaDir else attachmentDir
            runCatching { if (!dir.exists()) dir.mkdirs() }.onFailure {
                Timber.i("[JetDo][Network] download dir failed msg=%s", it.message)
            }
            val suggested = suggestedFileName(contentDisposition, url)
            val fileName = if (suggested.isNotEmpty()) suggested else "download_${System.currentTimeMillis()}"
            downloadIntents.removeFirstOrNull()

            // 交给系统 DownloadManager 下到应用专属外部目录。
            startSystemDownload(context, url, fileName, dir, isMedia, intent?.url, awaited)
        })
        registerMediaDownloadReceiver(context)
        downloadDelegateReady = true
        Timber.i("[JetDo][Network] download delegate configured")
    }

    /** 注册系统下载完成广播:媒体与附件下载的统一结算出口(settleFileSuccess 的唯一触发点)。 */
    private fun registerMediaDownloadReceiver(appContext: Context) {
        if (downloadReceiverRegistered) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                val job = synchronized(downloadJobs) { downloadJobs.remove(id) }
                // 「结算表命中与否」是区分两类故障的分界点（下载没完成 vs 广播/派发对不上），
                // 出问题时这行是唯一能一眼定性的证据，保留。
                Timber.i("[JetDo][Network] download-complete id=%s jobFound=%s", id, job != null)
                job ?: return
                val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                runCatching {
                    dm.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                        val success = cursor.moveToFirst() &&
                            cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) ==
                                DownloadManager.STATUS_SUCCESSFUL
                        if (success) {
                            // 优先用系统回给的真实落盘位置：重名时 DownloadManager 会自行改名
                            // （`x.zip` → `x-1.zip`），我们预推的路径就对不上了。预推路径仍在
                            // 且存在时优先用它（媒体热路径，行为与本轮之前完全一致）。
                            val resolved = resolveLocalPath(cursor, job.localPath)
                            Timber.i("[JetDo][Network] download complete url=%s path=%s", job.url, resolved)
                            settleFileSuccess(job.url, resolved)
                        } else {
                            Timber.i("[JetDo][Network] download failed url=%s", job.url)
                            settleFileFailure(job.url, IllegalStateException(AppContextHolder.getString(R.string.common_download_failed)))
                        }
                    }
                }.onFailure {
                    Timber.i("[JetDo][Network] download query failed msg=%s", it.message)
                    settleFileFailure(job.url, it)
                }
            }
        }
        runCatching {
            // **必须 EXPORTED**：ACTION_DOWNLOAD_COMPLETE 由系统 DownloadManagerService 发出，
            // 而 RECEIVER_NOT_EXPORTED 的接收者只收同 UID 的广播 —— 实测本 ROM 上收不到它。
            // 症状极具迷惑性：下载其实已完成、文件已完整落盘，但结算回调从不触发，
            // 播放器永远停在「加载中」直到 120s 超时（日志里连一条媒体相关记录都没有）。
            // 安全性：onReceive 里还会用 DownloadManager.query(id) 复核真实状态，伪造的 id
            // 过不了状态校验；本 receiver 不带 intent-filter、只活在进程内。
            appContext.registerReceiver(
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_EXPORTED
            )
            downloadReceiverRegistered = true
            Timber.i("[JetDo][Network] media download receiver registered")
        }.onFailure {
            Timber.i("[JetDo][Network] media download receiver register failed msg=%s", it.message)
        }
    }

    private fun suggestedFileName(contentDisposition: String?, url: String): String {
        val cd = contentDisposition ?: return ""
        // 取 `filename="..."` 时**必须截到闭合引号**，不能一路取到串尾：服务端常按 RFC 5987
        // 同时给两种写法 —— `attachment; filename="x.mp4"; filename*=UTF-8''x.mp4` ——
        // 取串尾会把 `"; filename*=UTF-8''x.mp4` 也吞进文件名，落盘名变成
        // `x.mp4"; filename*=UTF-8''x.mp4`，扩展名不再是 mp4，ExoPlayer 认不出容器 →
        // `player error code=2005 Source error`（预览/详情页里点播放直接失败）。
        val marker = "filename=\""
        val idx = cd.indexOf(marker)
        if (idx >= 0) {
            val rest = cd.substring(idx + marker.length)
            val end = rest.indexOf('"')
            val name = (if (end >= 0) rest.substring(0, end) else rest).trim()
            if (name.isNotEmpty()) return name
        }
        val after = cd.indexOf("filename=")
        if (after >= 0) {
            // 无引号写法：值以 `;` 或串尾结束。
            val name = cd.substring(after + "filename=".length).substringBefore(';').trim().trim('"')
            if (name.isNotEmpty()) return name
        }
        return url.substringAfterLast('/').substringBefore('?')
    }

    /**
     * 系统回给的真实落盘位置。
     *
     * 为什么需要它：`setDestinationInExternalFilesDir` 只是「建议路径」，重名时
     * DownloadManager 会自行改名（`x.zip` → `x-1.zip`），此时我们预先推出来的路径就是错的，
     * 拿它去 `File.exists()` 会落空、交给系统打开会报「文件不存在」。
     *
     * 但媒体热路径（缓存命中、播放器路径）依赖的一直是预推路径，且它实测可用，
     * 所以判据是「预推路径真的存在就用它」，只有当它落空时才回头问系统要真路径。
     */
    private fun resolveLocalPath(cursor: android.database.Cursor, fallback: String): String {
        if (File(fallback).exists()) return fallback
        val idx = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
        if (idx < 0 || cursor.isNull(idx)) return fallback
        val raw = cursor.getString(idx) ?: return fallback
        val path = if (raw.startsWith("file://")) android.net.Uri.parse(raw).path else raw
        return if (!path.isNullOrEmpty() && File(path).exists()) path else fallback
    }

    /**
     * 把下载交给系统 DownloadManager，落进应用专属外部目录。
     *
     * [awaited] 为 true 时（媒体播放 / 附件打开）登记结算表，由 ACTION_DOWNLOAD_COMPLETE
     * 广播 reclaim 回 [requestUrl] 对应的 waiter；否则按旧行为只在 enqueue 时向
     * [DownloadEventBus] 抛一条「已开始下载」（页面触发的下载不需要等结果）。
     */
    private fun startSystemDownload(
        context: Context,
        url: String,
        fileName: String,
        dir: File,
        isMedia: Boolean,
        requestUrl: String?,
        awaited: Boolean
    ) {
        // 走系统 DownloadManager 下到应用专属外部目录;媒体完成后经 ACTION_DOWNLOAD_COMPLETE 广播结算。
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
        val req = android.app.DownloadManager.Request(android.net.Uri.parse(url))
        // 真实落盘位置 = getExternalFilesDir(null)/relative。结算回给播放器的必须是这个路径,
        // 不能拿 filesDir 推——内/外目录不同,推出来的路径播放器打不开。
        val relative = "${dir.name}/$fileName"
        val localFile = File(context.getExternalFilesDir(null) ?: context.filesDir, relative)
        req.setDestinationInExternalFilesDir(context, null, relative)
        // 媒体是缓存到沙箱内部消费的，本不该出现在系统「下载」列表里；但 **VISIBILITY_HIDDEN
        // 在 Android 10+ 已废弃**：系统会把它改写成 VISIBILITY_VISIBLE_NOTIFY_COMPLETED(2)，
        // 而该 ROM 的 DownloadProvider 又判 2 非法 ——
        //   java.lang.SecurityException: Invalid value for visibility: 2
        //     at DownloadProvider.enforceAllowedValues → checkInsertPermissions → insert
        // 结果是 enqueue 直接抛异常、媒体永远下不来（表现：预览/详情页里点播放 → 「加载失败」）。
        // 实测这台设备只接受 VISIBILITY_VISIBLE(0)，故显式设它，不再用废弃的 HIDDEN。
        req.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE)
        req.addRequestHeader("User-Agent", com.jetdo.android.services.web.UserAgentProvider.get(context))
        // 论坛同源媒体/附件(xz 上传件、附件包)直连会被 CF 挡:补 webview 会话 cookie
        // (cf_clearance/_t)，与宿主同 UA 才拿得到字节。跨源 CDN 的下载请求不附加 ——
        // CookieManager 按域返回，天然只带该域自己的 cookie，不会把论坛会话泄给第三方。
        // 附件与媒体一样要过这道：linux.do 的 `/uploads/short-url/*` 对匿名请求直接 404。
        if (MediaSource.needsDownloadBridge(url)) {
            val cookie = CookieManager.getInstance().getCookie(url)
            if (!cookie.isNullOrEmpty()) {
                req.addRequestHeader("Cookie", cookie)
            }
        }
        try {
            val id = dm.enqueue(req)
            Timber.i("[JetDo][Network] download enqueued id=%s isMedia=%s awaited=%s url=%s", id, isMedia, awaited, requestUrl)
            if (requestUrl != null && (isMedia || awaited)) {
                synchronized(downloadJobs) {
                    downloadJobs[id] = FileDownloadJob(requestUrl, localFile.absolutePath)
                }
            } else {
                DownloadEventBus.notify(
                    DownloadResult(
                        true,
                        fileName,
                        localFile.absolutePath,
                        AppContextHolder.getString(R.string.common_download_started_file, fileName)
                    )
                )
            }
        } catch (e: Exception) {
            if (requestUrl != null && (isMedia || awaited)) {
                settleFileFailure(requestUrl, e)
            } else {
                DownloadEventBus.notify(DownloadResult(false, fileName, "", AppContextHolder.getString(R.string.common_download_failed)))
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
            Timber.i("[JetDo][Network] reloadHost: BEFORE reload cf_clearance=%s hasCfClearance=%b",
                cfClearance.take(20), hasCfClearance)

            markHostReloading()
            if (onComplete != null) {
                reloadCompleteCallbacks.add(onComplete)
            }
            web.loadUrl(AppConstants.HOST_BOOTSTRAP_URL)
            true
        }.onFailure {
            markHostReady()
            Timber.i("[JetDo][Network] reloadHost failed msg=%s", it.message)
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
            Timber.i("[JetDo][Network] MessageBus start skipped: controller not ready")
            return
        }
        runCatching { web.evaluateJavascript(MessageBusScriptTemplate.buildMessageBusScript(), null) }.onFailure {
            Timber.i("[JetDo][Network] MessageBus script injection failed")
        }
    }

    fun stopMessageBus() {
        val web = webView ?: run {
            Timber.i("[JetDo][Network] MessageBus stop skipped: controller not ready")
            return
        }
        runCatching {
            web.evaluateJavascript(
                "window.__jetdoMessageBusStopRequested = true; window.__jetdoMessageBusStarted = false; " +
                    "window.__jetdoMessageBusGeneration = (window.__jetdoMessageBusGeneration || 0) + 1;",
                null
            )
        }.onFailure {
            Timber.i("[JetDo][Network] MessageBus stop injection failed")
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
            Timber.i("[JetDo][Network] MessageBus subscribeChannel skipped: controller not ready")
            return
        }
        val channel = jsonString(channelName)
        val pos = if (position != Long.MAX_VALUE && position != Long.MIN_VALUE) position.toInt() else -1
        val script =
            "if (window.__jetdoMessageBusAddChannel) { window.__jetdoMessageBusAddChannel($channel, $pos); } " +
                "else { window.__jetdoMessageBusChannels = window.__jetdoMessageBusChannels || {}; " +
                "if (!($channel in window.__jetdoMessageBusChannels)) { window.__jetdoMessageBusChannels[$channel] = $pos; } }"
        runCatching {
            web.evaluateJavascript(script, null)
            Timber.i("[JetDo][Network] MessageBus subscribeChannel channel=%s pos=%d", channelName, pos)
        }.onFailure {
            Timber.i("[JetDo][Network] MessageBus subscribeChannel injection failed")
        }
    }

    fun unsubscribeChannel(channelName: String) {
        val web = webView ?: run {
            Timber.i("[JetDo][Network] MessageBus unsubscribeChannel skipped: controller not ready")
            return
        }
        val channel = jsonString(channelName)
        val script =
            "if (window.__jetdoMessageBusRemoveChannel) { window.__jetdoMessageBusRemoveChannel($channel); } " +
                "else if (window.__jetdoMessageBusChannels) { delete window.__jetdoMessageBusChannels[$channel]; }"
        runCatching { web.evaluateJavascript(script, null) }.onFailure {
            Timber.i("[JetDo][Network] MessageBus unsubscribeChannel injection failed")
        }
    }

    // ---------- 通用请求 ----------

    private fun setExecutor(executor: ArkWebJsonExecutor) {
        this.executor = executor
    }

    /**
     * 执行一段自定义 fetch 脚本并返回原始信封 JSON（不做 NetworkResult 包装）。
     *
     * 给不走标准 GET/POST 语义的特殊请求用：浏览器授权登录的
     * `/user-api-key/revoke`（User-Api-Key 头自证、无 CSRF、无会话）这类。
     * 调用方自己解析信封里的 statusCode/body；宿主未就绪时与 getJson 同语义等待。
     */
    suspend fun runRawScript(script: String): String {
        if (!canFetch()) throw IllegalStateException("host not ready")
        whenHostReady()
        return executor!!.run(script)
    }

    override suspend fun getJson(path: String, options: JsonRequestOptions?): NetworkResult<String> {
        val requestPath = normalizePath(path)
        Timber.i("[JetDo][Network] GET %s start", requestPath)
        if (!canFetch()) return hostNotReady()
        whenHostReady()
        return runCatching {
            val raw = executor!!.run(WebFetchScriptFactory.createFetchScript(requestPath))
            convertEnvelope(requestPath, parseEnvelope(raw), "GET", options)
        }.getOrElse {
            Timber.i("[JetDo][Network] GET %s failed before response envelope", requestPath)
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
        Timber.i("[JetDo][Network] GET(headered) %s start headerCount=%d", requestPath, headers.size)
        if (!canFetch()) return hostNotReady()
        whenHostReady()
        return runCatching {
            val raw = executor!!.run(WebFetchScriptFactory.createHeaderedFetchScript(requestPath, headers, referrer))
            convertEnvelope(requestPath, parseEnvelope(raw), "GET", options)
        }.getOrElse {
            Timber.i("[JetDo][Network] GET(headered) %s failed before response envelope", requestPath)
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
        Timber.i("[JetDo][Network] POST %s start bodyLength=%d", requestPath, body.length)
        if (!canFetch()) return hostNotReady()
        whenHostReady()
        return runCatching {
            val referrer = options?.referrer ?: ""
            val raw = executor!!.run(WebFetchScriptFactory.createFormScript("POST", requestPath, body, referrer))
            convertEnvelope(requestPath, parseEnvelope(raw), "POST", options)
        }.getOrElse {
            Timber.i("[JetDo][Network] POST %s failed before response envelope", requestPath)
            networkFailed()
        }
    }

    override suspend fun postJson(path: String, body: String, options: JsonRequestOptions?): NetworkResult<String> {
        val requestPath = normalizePath(path)
        Timber.i("[JetDo][Network] POST(json) %s start bodyLength=%d", requestPath, body.length)
        if (!canFetch()) return hostNotReady()
        whenHostReady()
        return runCatching {
            val referrer = options?.referrer ?: ""
            val raw = executor!!.run(WebFetchScriptFactory.createPostJsonScript(requestPath, body, referrer))
            convertEnvelope(requestPath, parseEnvelope(raw), "POST", options)
        }.getOrElse {
            Timber.i("[JetDo][Network] POST(json) %s failed before response envelope", requestPath)
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
        Timber.i("[JetDo][Network] UPLOAD %s start fileName=%s base64Length=%d", requestPath, fileName, base64.length)
        if (!canFetch()) return hostNotReady()
        whenHostReady()
        return runCatching {
            val referrer = options?.referrer ?: ""
            val raw = executor!!.run(
                WebFetchScriptFactory.createUploadScript(base64, fileName, mimeType, referrer),
                UPLOAD_TIMEOUT_MS
            )
            convertEnvelope(requestPath, parseEnvelope(raw), "POST", options)
        }.getOrElse {
            Timber.i("[JetDo][Network] UPLOAD %s failed before response envelope", requestPath)
            networkFailed()
        }
    }

    override suspend fun putJson(path: String, options: JsonRequestOptions?): NetworkResult<String> {
        val requestPath = normalizePath(path)
        Timber.i("[JetDo][Network] PUT %s start", requestPath)
        if (!canFetch()) return hostNotReady()
        whenHostReady()
        return runCatching {
            val referrer = options?.referrer ?: ""
            val raw = executor!!.run(WebFetchScriptFactory.createPutScript(requestPath, referrer))
            convertEnvelope(requestPath, parseEnvelope(raw), "PUT", options)
        }.getOrElse {
            Timber.i("[JetDo][Network] PUT %s failed before response envelope", requestPath)
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
        Timber.i("[JetDo][Network] %s %s start bodyLength=%d", method, requestPath, body.length)
        if (!canFetch()) return hostNotReady()
        whenHostReady()
        return runCatching {
            val referrer = options?.referrer ?: ""
            val raw = executor!!.run(WebFetchScriptFactory.createFormScript(method, requestPath, body, referrer))
            convertEnvelope(requestPath, parseEnvelope(raw), method, options)
        }.getOrElse {
            Timber.i("[JetDo][Network] %s %s failed before response envelope", method, requestPath)
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
            Timber.i("[JetDo][Network] %s %s csrf source=%s length=%d",
                method, path, envelope.csrfSource ?: "unknown", envelope.csrfLength ?: 0)
        }

        if (!envelope.errorMessage.isNullOrEmpty()) {
            Timber.i("[JetDo][Network] %s %s envelope error=%s", method, path, envelope.errorMessage)
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
                        AppContextHolder.getString(R.string.err_challenge_needed),
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
        Timber.i("[JetDo][Network] %s %s response status=%d contentType=%s bodyLength=%d challenge=%s",
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
                    AppContextHolder.getString(R.string.err_challenge_needed),
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
                    AppContextHolder.getString(R.string.err_http_status, statusCode),
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
        Timber.i("[JetDo][Network][Body] %s %s body begin contentType=%s rawLength=%d chunks=%d",
            method, path, contentType, body.length, chunkCount)
        for (start in loggedBody.indices step BODY_LOG_CHUNK_SIZE) {
            val chunkIndex = start / BODY_LOG_CHUNK_SIZE + 1
            val chunk = loggedBody.substring(start, minOf(start + BODY_LOG_CHUNK_SIZE, loggedBody.length))
            Timber.i("[JetDo][Network][Body] %s %s body chunk %d/%d: %s", method, path, chunkIndex, chunkCount, chunk)
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
        error = NetworkError(NetworkErrorCode.NETWORK_FAILURE, AppContextHolder.getString(R.string.err_host_not_ready), false)
    )

    private fun networkFailed(): NetworkResult<String> = NetworkResult(
        success = false,
        statusCode = 0,
        challengeState = ChallengeState.NONE,
        error = NetworkError(NetworkErrorCode.NETWORK_FAILURE, AppContextHolder.getString(R.string.err_request_failed), false)
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
        com.jetdo.android.services.web.WebSessionManager.applyUserAgent(web, source)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
    }
}
