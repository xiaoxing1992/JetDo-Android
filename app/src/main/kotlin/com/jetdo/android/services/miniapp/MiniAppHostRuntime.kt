package com.jetdo.android.services.miniapp

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jetdo.android.common.constants.AppConstants
import com.jetdo.android.core.data.miniapp.MiniAppRepository
import com.jetdo.android.core.data.miniapp.MiniAppResult
import com.jetdo.android.core.model.MiniAppEndpoint
import com.jetdo.android.core.model.MiniAppLaunch
import com.jetdo.android.core.model.MiniAppPermissions
import com.jetdo.android.core.model.MiniAppProfile
import com.jetdo.android.core.model.MiniAppRelease
import com.jetdo.android.core.model.MiniAppWebSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber

/**
 * 微应用宿主运行时（原生侧）。
 *
 * 职责分工：
 *  - 页面侧 `assets/miniapp/host.html` 负责：跑解密 Worker、建 sandbox iframe、注入 miniApp 全局、
 *    执行 HTTP/WS 等需要论坛 origin 的请求；
 *  - 本类负责：启动接口、权限校验、端点白名单、原生能力实现（剪贴板/分享/存储/用户资料…）。
 *
 * 安全模型（与站点官方实现对齐）：
 *  - 内层 iframe 是 sandbox 无 same-origin 的，微应用拿不到论坛 Cookie 与宿主 DOM；
 *  - 宿主只放行 manifest 已声明（且审核通过）的端点与外链；
 *  - 权限以 release.permissions（审核结果）为准，未批准的权限一律 PERMISSION_DENIED；
 *  - 不做「静默成功」：宿主不支持的能力显式返回 API_UNSUPPORTED。
 *
 * 协议依据：github.com/linux-do/mapp-developer-guide
 */
class MiniAppHostRuntime(
    private val context: Context,
    hostWebView: WebView,
    private val repository: MiniAppRepository,
    private val mainHandler: Handler = Handler(Looper.getMainLooper())
) {

    companion object {
        private const val LOG_TAG = "[JetDo][MiniApp]"

        /** 宿主页面在 assets 中的路径。 */
        const val HOST_ASSET = "miniapp/host.html"

        /**
         * 论坛下发的 DMA 解密 Worker（同源脚本）。
         *
         * URL 里的 hash 是 **插件全局固定值**，不是每个包/每次发布变化的 zip_sha256。
         * 出处：`https://linux.do/apps/<id>` 页面内嵌的
         * `window.__DISCOURSE_BOOTSTRAP__` → `runtimeAssets.packageWorker`。
         * 站点升级插件后该 hash 会变，届时同步更新此常量即可（真机日志若出现
         * `worker error` / 404，第一件事就是核对这里）。
         */
        const val PACKAGE_WORKER_PATH =
            "/mini-app-api/workers/package-worker-b5926ccf2c81ade939a9aab9b57dba5e14ce59c02e8ea14f404fe59c446ab381.js"

        fun errorJson(code: String, message: String): String =
            "{\"__error\":{\"code\":${jsonString(code)},\"message\":${jsonString(message)}}}"

        /** JSON 字符串转义。宿主手写 JSON，避免为这几处引入序列化依赖。 */
        fun jsonString(value: String?): String {
            if (value == null) return "null"
            val sb = StringBuilder("\"")
            for (ch in value) {
                when (ch) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    '\b' -> sb.append("\\b")
                    '' -> sb.append("\\f")
                    else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
                }
            }
            sb.append('"')
            return sb.toString()
        }

        /** 去掉 jsonString 外层的引号，用于拼进已加引号的位置。 */
        fun escape(value: String): String =
            jsonString(value).removePrefix("\"").removeSuffix("\"")

        fun jsonArray(items: List<String>): String =
            items.joinToString(",", prefix = "[", postfix = "]") { jsonString(it) }

        // 用 java.util.Base64 而不是 android.util.Base64：后者在 JVM 单测里未 mock，
        // 会直接抛 RuntimeException，导致这层协议工具无法被测试覆盖。
        fun base64(bytes: ByteArray): String =
            java.util.Base64.getEncoder().encodeToString(bytes)

        fun base64Decode(value: String): ByteArray =
            java.util.Base64.getDecoder().decode(value)

        /**
         * RFC 3986 查询串编码。
         *
         * 不能用 URLEncoder：它把空格编成 `+`，而 query 里的 `+` 会被服务端解成空格以外的字面量，
         * 与官方 SDK 的行为不一致。
         */
        fun urlEncode(value: String): String {
            val sb = StringBuilder()
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val ch = byte.toInt().toChar()
                if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' ||
                    ch == '-' || ch == '_' || ch == '.' || ch == '~'
                ) {
                    sb.append(ch)
                } else {
                    sb.append('%').append("%02X".format(byte.toInt() and 0xFF))
                }
            }
            return sb.toString()
        }

        /**
         * 拼接带动态 query 的端点地址。
         *
         * 官方约定：调用方只能传**声明过的** query 键，未声明的一律拒绝 ——
         * 否则应用就能借端点 URL 往开发者服务塞任意参数。
         *
         * @param url 端点声明的固定地址（可能已带固定 query）
         * @param declared 声明允许的动态 query 键
         * @param query 调用方传入的动态 query
         */
        fun joinEndpointUrl(url: String, declared: Collection<String>, query: Map<String, String>): String {
            val allowed = declared.toSet()
            val pairs = LinkedHashMap<String, String>()
            query.forEach { (key, value) ->
                if (key !in allowed) {
                    throw MiniAppHostException("PERMISSION_DENIED", "query 未声明：$key")
                }
                pairs[key] = value
            }
            if (pairs.isEmpty()) return url
            val encoded = pairs.entries.joinToString("&") { (k, v) ->
                "${urlEncode(k)}=${urlEncode(v)}"
            }
            val separator = if (url.contains('?')) "&" else "?"
            return url + separator + encoded
        }
    }

    /** 宿主异常：带 SDK 错误码。 */
    class MiniAppHostException(val code: String, message: String) : Exception(message)

    /** 当前运行的微应用上下文；未启动时为 null。 */
    data class Session(
        val launch: MiniAppLaunch,
        val appId: String,
        val launchQuery: Map<String, List<String>>,
        val launchHash: String
    ) {
        val manifest get() = launch.release.manifest

        /** 审核通过的权限集合。宿主的一切能力都以它为准。 */
        val grantedPermissions: Set<String> = launch.release.permissions.toSet()
    }

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private var session: Session? = null
    private var pendingLaunch: LaunchRequest? = null

    /** 宿主页面是否已请求加载（避免 attach 与 start 重复触发）。 */
    private var hostPageRequested = false
    /** 宿主页面是否已回报 origin（决定能否加载解密 Worker）。 */
    private var originVerified = false
    /** 启动信息是否已推给页面。 */
    private var launchInfoPushed = false
    /** 是否已退化到「引导页 + document.write」路径。 */
    private var usingFallbackBootstrap = false
    private var fallbackInjected = false

    /** 宿主状态，供 UI 观察。 */
    var state by mutableStateOf<State>(State.Idle)
        private set

    sealed interface State {
        data object Idle : State
        data class Loading(val message: String) : State
        data class Running(val appName: String) : State
        data class Failed(val message: String) : State
    }

    /** 宿主使用的 WebView。由 UI 挂进 Compose 树。 */
    val webView: WebView = hostWebView

    /** 注入 WebView 的桥对象。 */
    val bridge = Bridge()

    init {
        webView.addJavascriptInterface(bridge, "JetDoMiniApp")
        // 引导页加载完成 → 注入宿主页面。
        // 这里自己挂 WebViewClient（而不是交给 UI 层），因为「加载完成 → 注入宿主页」
        // 是宿主运行时的内部时序，UI 层不该知道。
        webView.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                onPageFinishedInternal(url)
            }
        }
    }

    // ---------------------------------------------------------------------
    // 启动流程
    // ---------------------------------------------------------------------

    /**
     * 启动一个微应用。
     *
     * 流程：加载宿主页面（带论坛 origin）→ 页面回报 origin → 推送启动信息 →
     * 页面下载包、跑解密 Worker、挂载到 sandbox iframe。
     *
     * @param appId 微应用 ID（UUID）
     * @param query 启动 query
     * @param hash 启动 fragment
     */
    fun start(appId: String, query: Map<String, List<String>> = emptyMap(), hash: String = "") {
        state = State.Loading("正在准备微应用…")
        pendingLaunch = LaunchRequest(appId, query, hash)
        hostPageRequested = false
        originVerified = false
        launchInfoPushed = false
        usingFallbackBootstrap = false
        fallbackInjected = false
        loadHostPage()
    }

    /**
     * WebView 已 attach 到窗口。
     *
     * WebView 在 attach 之前调 loadUrl 会失败（Android 的已知行为），
     * 所以启动动作要等 attach 之后再发。
     */
    fun onWebViewAttached() {
        loadHostPage()
    }

    /**
     * 加载宿主页面。
     *
     * 用 `loadDataWithBaseURL` 把本地 HTML 挂到论坛 origin 下：
     *  - 解密 Worker 必须与宿主页面**同源**（Worker 脚本是论坛下发的 https URL），
     *    file:// 页面发跨源 Worker 必被拒；
     *  - 走引导页 + document.write 的老办法会被论坛页面的 CSP 拦掉内联脚本，
     *    也会被论坛自身的脚本干扰；
     *  - loadDataWithBaseURL 既拿到正确 origin，又不带任何响应头策略。
     *
     * 页面加载后会回报自己的 origin，原生侧据此确认环境正确；
     * 万一拿不到同源环境，会退化到引导页路径并最终报错，不会静默卡死。
     */
    private fun loadHostPage() {
        if (hostPageRequested) return
        if (!webView.isAttachedToWindow) return
        val html = readHostAsset() ?: return
        hostPageRequested = true
        mainHandler.post {
            webView.loadDataWithBaseURL(
                "${AppConstants.BASE_URL}/",
                html,
                "text/html",
                "utf-8",
                null
            )
        }
    }

    private fun readHostAsset(): String? = runCatching {
        context.assets.open(HOST_ASSET).bufferedReader().use { it.readText() }
    }.getOrElse {
        Timber.e(it, "%s read host asset failed", LOG_TAG)
        state = State.Failed("读取宿主页面失败")
        null
    }

    private fun onPageFinishedInternal(url: String?) {
        if (usingFallbackBootstrap) {
            if (!fallbackInjected) {
                fallbackInjected = true
                injectHostHtml()
            }
            return
        }
        // 页面自报告 origin；若它没来（脚本报错或环境异常），退化到引导页路径。
        if (!originVerified) {
            mainHandler.postDelayed({
                if (!originVerified) fallbackToBootstrap()
            }, 600)
        }
    }

    /** 退化路径：加载论坛引导页拿到真实 origin，再把宿主页面写进去。 */
    private fun fallbackToBootstrap() {
        if (usingFallbackBootstrap || originVerified) return
        usingFallbackBootstrap = true
        Timber.w("%s host origin unverified, fallback to bootstrap page", LOG_TAG)
        mainHandler.post {
            webView.loadUrl(AppConstants.HOST_BOOTSTRAP_URL)
        }
    }

    private fun injectHostHtml() {
        val html = readHostAsset() ?: return
        mainHandler.post {
            webView.evaluateJavascript(
                "document.open();document.write(${jsonString(html)});document.close();",
                null
            )
        }
    }

    /**
     * 把启动信息推给页面侧（页面回报 origin 后调用）。
     *
     * 页面拿到后开始下载包、跑解密 Worker。
     */
    fun pushLaunchInfo() {
        val sess = session ?: return
        if (launchInfoPushed) return
        launchInfoPushed = true
        val payload = buildString {
            append("{")
            append("\"packageUrl\":").append(jsonString(sess.launch.release.packageUrl)).append(",")
            append("\"workerUrl\":").append(jsonString(workerUrlFor(sess))).append(",")
            // Worker 用包内嵌元数据做 RELEASE_MISMATCH 精确校验，必须透传站点原文；
            // 只有拿不到原文（老缓存）时才退回按结构化字段重建。
            val releaseRaw = sess.launch.releaseRawJson
            append("\"release\":").append(
                if (releaseRaw.isNotBlank()) releaseRaw else releaseJson(sess.launch.release)
            ).append(",")
            append("\"runtimeToken\":").append(jsonString(sess.launch.runtimeToken)).append(",")
            append("\"runtimeContext\":").append(jsonString(sess.launch.runtimeContext)).append(",")
            append("\"policy\":").append(policyJson(sess))
            append("}")
        }
        mainHandler.post {
            webView.evaluateJavascript(
                "window.__miniAppSetLaunch && window.__miniAppSetLaunch($payload);",
                null
            )
        }
    }

    /**
     * 页面侧策略快照。
     *
     * 权限是**同步**语义（官方 SDK 的 permissions.list()/has() 不返回 Promise），
     * 所以宿主必须在注入 SDK 之前就知道批准结果 —— 这份快照就是给页面侧直接用的。
     */
    private fun policyJson(sess: Session): String {
        val m = sess.manifest
        return buildString {
            append("{\"permissions\":").append(jsonArray(sess.grantedPermissions.toList()))
            append(",\"media\":{")
            append(m.media.entries.joinToString(",") { (k, v) ->
                "${jsonString(k)}:{\"kind\":${jsonString(v.kind)},\"mime\":${jsonString(v.mime)},\"path\":${jsonString(v.path)}}"
            })
            append("}")
            append(",\"endpoints\":{")
            append(m.endpoints.entries.joinToString(",") { (k, v) ->
                "${jsonString(k)}:{\"method\":${jsonString(v.method)},\"query\":${jsonArray(v.query)},\"auth\":${jsonString(v.auth)}}"
            })
            append("}")
            append(",\"websockets\":{")
            append(m.websockets.entries.joinToString(",") { (k, v) ->
                "${jsonString(k)}:{\"query\":${jsonArray(v.query)},\"auth\":${jsonString(v.auth)},\"protocols\":${jsonArray(v.protocols)}}"
            })
            append("}")
            append(",\"links\":").append(jsonArray(m.links))
            append(",\"calls\":").append(jsonArray(m.calls))
            append(",\"code\":{\"scripts\":").append(jsonArray(m.code.scripts))
            append(",\"modules\":").append(jsonArray(m.code.modules))
            append(",\"workers\":").append(jsonArray(m.code.workers))
            append(",\"moduleBundle\":").append(jsonString(m.code.moduleBundle)).append("}")
            append(",\"remoteAssets\":{")
            append(m.remoteAssets.entries.joinToString(",") { (k, v) ->
                "${jsonString(k)}:{\"url\":${jsonString(v.url)},\"sha256\":${jsonString(v.sha256)},\"size\":${v.size},\"mime\":${jsonString(v.mime)}}"
            })
            append("}}")
        }
    }

    /**
     * 把 release 序列化成页面侧解密 Worker 需要的形状。
     *
     * Worker 收到 `{buffer, release}` 后会用 release 里的 package_password /
     * public_key / zip_sha256 做解密与验签 —— 字段名必须与站点一致，不能改名。
     */
    private fun releaseJson(release: MiniAppRelease): String {
        val m = release.manifest
        return buildString {
            append("{")
            append("\"id\":").append(jsonString(release.id)).append(",")
            append("\"app_id\":").append(jsonString(release.appId)).append(",")
            append("\"name\":").append(jsonString(release.name)).append(",")
            append("\"channel\":").append(jsonString(release.channel)).append(",")
            append("\"version\":").append(jsonString(release.version)).append(",")
            append("\"release_notes\":").append(jsonString(release.releaseNotes)).append(",")
            append("\"manifest\":{")
            append("\"format\":").append(m.format).append(",")
            append("\"id\":").append(jsonString(m.id)).append(",")
            append("\"name\":").append(jsonString(m.name)).append(",")
            append("\"version\":").append(jsonString(m.version)).append(",")
            append("\"entry\":").append(jsonString(m.entry)).append(",")
            append("\"script\":").append(jsonString(m.script)).append(",")
            append("\"style\":").append(jsonString(m.style)).append(",")
            append("\"permissions\":").append(jsonArray(m.permissions))
            append("},")
            append("\"permissions\":").append(jsonArray(release.permissions)).append(",")
            append("\"package_url\":").append(jsonString(release.packageUrl)).append(",")
            append("\"package_password\":").append(jsonString(release.packagePassword)).append(",")
            append("\"package_size\":").append(release.packageSize).append(",")
            append("\"zip_sha256\":").append(jsonString(release.zipSha256)).append(",")
            append("\"public_key\":").append(jsonString(release.publicKey))
            append("}")
        }
    }

    /**
     * 解密 Worker 的地址。
     *
     * Worker 是同源脚本，必须由宿主文档同源加载（跨源 Worker 会被 CSP / 同源策略挡住）。
     * URL 用站点下发的固定 hash（[PACKAGE_WORKER_PATH]）——早期误用 zip_sha256 拼装，
     * 但该 hash 是「解密后 zip」的摘要，与 Worker 脚本版本无关，必然 404。
     */
    private fun workerUrlFor(session: Session): String =
        "${AppConstants.BASE_URL}$PACKAGE_WORKER_PATH"

    // ---------------------------------------------------------------------
    // 权限校验
    // ---------------------------------------------------------------------
    private fun requirePermission(name: String) {
        val sess = session
        if (sess == null) throw MiniAppHostException("APP_UNLOADED", "微应用尚未启动")
        if (name !in sess.grantedPermissions) {
            throw MiniAppHostException("PERMISSION_DENIED", "未获授权：$name")
        }
    }

    // ---------------------------------------------------------------------
    // JS 桥
    // ---------------------------------------------------------------------
    inner class Bridge {

        /**
         * 页面侧 RPC。
         *
         * @JavascriptInterface 的方法跑在 WebView 的 JS 线程上，必须切回主线程；
         * 部分能力（如用户资料查询）需要网络往返，故整体做成挂起调用。
         */
        @JavascriptInterface
        fun request(requestId: String, method: String, paramsJson: String) {
            scope.launch {
                val result = runCatching { dispatch(method, paramsJson) }
                    .fold(
                        onSuccess = { it },
                        onFailure = {
                            errorJson(
                                (it as? MiniAppHostException)?.code ?: "UNKNOWN",
                                it.message ?: "宿主错误"
                            )
                        }
                    )
                withContext(Dispatchers.Main) {
                    webView.evaluateJavascript(
                        "window.__miniAppResolve(${jsonString(requestId)}, ${jsonString(result)});",
                        null
                    )
                }
            }
        }

        /** 宿主页面就绪自报告：带上当前文档 origin，供原生侧确认同源环境。 */
        @JavascriptInterface
        fun onHostReady(infoJson: String) {
            mainHandler.post {
                val origin = runCatching {
                    Json.parseToJsonElement(infoJson).jsonObject["origin"]?.jsonPrimitive?.contentOrNull
                }.getOrNull().orEmpty()
                Timber.i("%s host ready origin=%s", LOG_TAG, origin)
                originVerified = true
                if (origin != AppConstants.BASE_URL) {
                    state = State.Failed("宿主环境异常：origin=$origin")
                    return@post
                }
                pushLaunchInfo()
            }
        }

        /** 页面侧报告解密完成 —— 宿主据此进入运行态。 */
        @JavascriptInterface
        fun onPackageReady(infoJson: String) {
            mainHandler.post {
                val count = runCatching {
                    Json.parseToJsonElement(infoJson).jsonObject["count"]?.jsonPrimitive?.intOrNull
                }.getOrNull() ?: 0
                session?.let { state = State.Running(it.launch.release.name) }
                Timber.i("%s package ready files=%d", LOG_TAG, count)
            }
        }
    }

    // ---------------------------------------------------------------------
    // API 分发
    // ---------------------------------------------------------------------
    private suspend fun dispatch(method: String, paramsJson: String): String {
        val params: JsonObject = runCatching {
            Json.parseToJsonElement(paramsJson).jsonObject
        }.getOrElse { JsonObject(emptyMap()) }
        return when (method) {
            "host.ping" -> "null"
            // 内层微应用的 console/error 转发到这里，便于真机排查渲染问题。
            "host.log" -> {
                val level = params["level"]?.jsonPrimitive?.contentOrNull ?: "log"
                val msg = params["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val line = "$LOG_TAG [app:$level] $msg"
                when (level) {
                    "error" -> Timber.e(line)
                    "warn" -> Timber.w(line)
                    else -> Timber.i(line)
                }
                "null"
            }
            "permissions.list" -> jsonArray(session?.grantedPermissions?.toList().orEmpty())
            "capabilities.get" -> capabilitiesJson()
            "lifecycle.ready" -> {
                state = State.Running(session?.launch?.release?.name.orEmpty())
                "null"
            }
            "user.getCurrent" -> { requirePermission(MiniAppPermissions.USER_READ); currentUserJson() }
            "user.getById" -> { requirePermission(MiniAppPermissions.USER_LOOKUP); userByIdJson(params) }
            "launch.getParameters" -> { requirePermission(MiniAppPermissions.LAUNCH_READ); launchParamsJson() }
            "theme.get" -> { requirePermission(MiniAppPermissions.THEME_READ); themeJson() }
            "localStorage.getItem" -> { requirePermission(MiniAppPermissions.STORAGE_READ); storageGet(params) }
            "localStorage.setItem" -> { requirePermission(MiniAppPermissions.STORAGE_WRITE); storageSet(params) }
            "localStorage.removeItem" -> { requirePermission(MiniAppPermissions.STORAGE_WRITE); storageRemove(params) }
            "localStorage.clear" -> { requirePermission(MiniAppPermissions.STORAGE_WRITE); storageClear() }
            "localStorage.exportData" -> { requirePermission(MiniAppPermissions.STORAGE_READ); storageExport() }
            "localStorage.importData" -> { requirePermission(MiniAppPermissions.STORAGE_WRITE); storageImport(params) }
            "cookies.get" -> { requirePermission(MiniAppPermissions.COOKIES_READ); cookiesGet(params) }
            "cookies.set" -> { requirePermission(MiniAppPermissions.COOKIES_WRITE); cookiesSet(params) }
            "cookies.remove" -> { requirePermission(MiniAppPermissions.COOKIES_WRITE); cookiesRemove(params) }
            "clipboard.readText" -> { requirePermission(MiniAppPermissions.CLIPBOARD_READ); clipboardRead() }
            "clipboard.writeText" -> { requirePermission(MiniAppPermissions.CLIPBOARD_WRITE); clipboardWrite(params) }
            "fullscreen.request" -> { requirePermission(MiniAppPermissions.FULLSCREEN); "null" }
            "fullscreen.exit" -> { requirePermission(MiniAppPermissions.FULLSCREEN); "null" }
            "http.request" -> { requirePermission(MiniAppPermissions.NETWORK_REQUEST); httpRequest(params) }
            "webSocket.connect" -> {
                requirePermission(MiniAppPermissions.NETWORK_WEBSOCKET)
                webSocketConnect(params)
            }
            "links.open" -> { requirePermission(MiniAppPermissions.LINKS_OPEN); linksOpen(params) }
            "files.save" -> { requirePermission(MiniAppPermissions.FILES_SAVE); filesSave(params) }
            "sharing.share" -> { requirePermission(MiniAppPermissions.SHARING_SHARE); sharingShare(params) }
            "screen.setKeepAwake" -> { requirePermission(MiniAppPermissions.SCREEN_KEEP_AWAKE); screenKeepAwake(params) }
            "app.call" -> { requirePermission(MiniAppPermissions.APP_CALL); appCall(params) }
            "app.return" -> { requirePermission(MiniAppPermissions.APP_CALL); appReturn(params) }
            "notifications.request" -> { requirePermission(MiniAppPermissions.NOTIFICATIONS); notificationsRequest(params) }
            else -> throw MiniAppHostException("API_UNSUPPORTED", "宿主不支持：$method")
        }
    }

    // ---------------------------------------------------------------------
    // 各项能力实现
    // ---------------------------------------------------------------------
    private fun capabilitiesJson(): String {
        val granted = session?.grantedPermissions.orEmpty()
        val supported = mapOf(
            "camera" to (MiniAppPermissions.CAMERA in granted),
            "microphone" to (MiniAppPermissions.MICROPHONE in granted),
            "screenCapture" to (MiniAppPermissions.SCREEN_CAPTURE in granted),
            "mediaRecorder" to true,
            "location" to (MiniAppPermissions.LOCATION_READ in granted),
            "usb" to false,
            "bluetooth" to false
        )
        return buildString {
            append("{\"supported\":{")
            append(supported.entries.joinToString(",") { "\"${it.key}\":${it.value}" })
            append("},\"granted\":")
            append(jsonArray(granted.toList()))
            append("}")
        }
    }

    private fun currentUserJson(): String {
        val user = com.jetdo.android.services.session.CurrentUserHolder.currentUser.value
            ?: return "null"
        return profileJson(
            MiniAppProfile(
                id = user.id,
                username = user.username,
                name = user.name?.takeIf { it.isNotBlank() } ?: user.username,
                initials = user.username.take(1).uppercase(),
                trustLevel = null,
                avatarUrl = null
            )
        )
    }

    private suspend fun userByIdJson(params: JsonObject): String {
        val userId = params["userId"]?.jsonPrimitive?.longOrNullSafe()
            ?: throw MiniAppHostException("INVALID_ARGUMENTS", "缺少 userId")
        return when (val result = repository.userProfile(userId)) {
            is MiniAppResult.Success -> profileJson(result.data)
            is MiniAppResult.Failure -> throw MiniAppHostException(
                MiniAppErrorCodesCompat.USER_UNAVAILABLE,
                result.message
            )
        }
    }

    private fun profileJson(profile: MiniAppProfile): String = buildString {
        append("{\"id\":").append(profile.id).append(",")
        append("\"username\":").append(jsonString(profile.username)).append(",")
        append("\"name\":").append(jsonString(profile.name)).append(",")
        append("\"initials\":").append(jsonString(profile.initials)).append(",")
        append("\"trustLevel\":").append(profile.trustLevel?.toString() ?: "null").append(",")
        append("\"avatarUrl\":").append(jsonString(absoluteUrl(profile.avatarUrl))).append("}")
    }

    private fun absoluteUrl(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return if (path.startsWith("http://") || path.startsWith("https://")) path
        else AppConstants.BASE_URL + (if (path.startsWith("/")) path else "/$path")
    }

    private fun launchParamsJson(): String {
        val sess = session ?: return "{\"query\":{},\"hash\":\"\"}"
        val query = sess.launchQuery.entries.joinToString(",") { (k, v) ->
            "\"${escape(k)}\":[" + v.joinToString(",") { jsonString(it) } + "]"
        }
        return "{\"query\":{$query},\"hash\":${jsonString(sess.launchHash)}}"
    }

    private fun themeJson(): String {
        val night = (context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val background = if (night) "#121212" else "#FFFFFF"
        val text = if (night) "#ECECEC" else "#1A1A1A"
        val accent = if (night) "#7CB9FF" else "#2A6CFF"
        val border = if (night) "#2C2C2E" else "#E3E3E6"
        val reducedMotion = !android.animation.ValueAnimator.areAnimatorsEnabled()
        return buildString {
            append("{\"mode\":").append(jsonString(if (night) "dark" else "light")).append(",")
            append("\"background\":").append(jsonString(background)).append(",")
            append("\"text\":").append(jsonString(text)).append(",")
            append("\"accent\":").append(jsonString(accent)).append(",")
            append("\"border\":").append(jsonString(border)).append(",")
            append("\"locale\":").append(jsonString(localeTag())).append(",")
            append("\"reducedMotion\":").append(reducedMotion).append(",")
            append("\"viewport\":{\"width\":").append(webView.width).append(",\"height\":").append(webView.height).append("}}")
        }
    }

    /** Discourse locale 形如 zh_CN / en。 */
    private fun localeTag(): String {
        val tag = runCatching {
            context.resources.configuration.locales[0].toLanguageTag()
        }.getOrNull().orEmpty()
        if (tag.isBlank()) return "zh_CN"
        return tag.replace('-', '_')
    }

    private fun storageGet(params: JsonObject): String {
        val key = params["key"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return jsonString(storage.read(key))
    }

    private fun storageSet(params: JsonObject): String {
        requirePermission(MiniAppPermissions.STORAGE_WRITE)
        val key = params["key"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val value = params["value"]?.jsonPrimitive?.contentOrNull.orEmpty()
        storage.write(key, value)
        return "null"
    }

    private fun storageRemove(params: JsonObject): String {
        requirePermission(MiniAppPermissions.STORAGE_WRITE)
        val key = params["key"]?.jsonPrimitive?.contentOrNull.orEmpty()
        storage.remove(key)
        return "null"
    }

    private fun storageClear(): String {
        storage.clear()
        return "null"
    }

    private fun storageExport(): String {
        val entries = storage.export().entries.joinToString(",") { (k, v) ->
            "[" + jsonString(k) + "," + jsonString(v) + "]"
        }
        return "{\"format\":1,\"entries\":[$entries]}"
    }

    private fun storageImport(params: JsonObject): String {
        val snapshot = params["snapshot"]?.jsonObject ?: return "null"
        val replace = params["replace"]?.jsonPrimitive?.booleanOrNull ?: false
        val entries = snapshot["entries"]?.jsonArray.orEmpty().mapNotNull { element ->
            val pair = element.jsonArray
            if (pair.size >= 2) {
                pair[0].jsonPrimitive.contentOrNull to pair[1].jsonPrimitive.contentOrNull
            } else null
        }
        storage.import(entries, replace)
        return "null"
    }

    // ---------------------------------------------------------------------
    // Cookie：按应用隔离的小键值对，与 localStorage 同源存储但独立命名空间
    // ---------------------------------------------------------------------
    private fun cookieKey(name: String): String = "cookie:$name"

    private fun cookiesGet(params: JsonObject): String {
        val name = params["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (!validCookieName(name)) {
            throw MiniAppHostException("INVALID_ARGUMENTS", "Cookie 名非法")
        }
        return jsonString(storage.read(cookieKey(name)))
    }

    private fun cookiesSet(params: JsonObject): String {
        val name = params["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (!validCookieName(name)) {
            throw MiniAppHostException("INVALID_ARGUMENTS", "Cookie 名非法")
        }
        val value = params["value"]?.jsonPrimitive?.contentOrNull.orEmpty()
        storage.write(cookieKey(name), value)
        return "null"
    }

    private fun cookiesRemove(params: JsonObject): String {
        val name = params["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (!validCookieName(name)) {
            throw MiniAppHostException("INVALID_ARGUMENTS", "Cookie 名非法")
        }
        storage.remove(cookieKey(name))
        return "null"
    }

    /** 官方约定：键只接受字母、数字、点、下划线、连字符。 */
    private fun validCookieName(name: String): Boolean =
        name.isNotEmpty() && name.length <= 128 && name.all {
            it.isLetterOrDigit() && it.code < 128 || it == '.' || it == '_' || it == '-'
        }

    private fun clipboardRead(): String {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        return jsonString(text)
    }

    private fun clipboardWrite(params: JsonObject): String {
        val text = params["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("JetDo", text))
        return "null"
    }

    /**
     * HTTP 请求的原生侧职责：端点白名单校验 + 动态 query 收敛 + 拼出最终地址。
     *
     * 实际请求由外层同源页面执行：开发者服务要求「论坛 origin 的 CORS」，
     * 由同源页面发起才能拿到正确行为；同时不携带论坛 Cookie。
     */
    private fun httpRequest(params: JsonObject): String {
        val endpointName = params["endpoint"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val sess = session ?: throw MiniAppHostException("APP_UNLOADED", "微应用尚未启动")
        val endpoint = sess.manifest.endpoints[endpointName]
            ?: throw MiniAppHostException("PERMISSION_DENIED", "端点未声明：$endpointName")
        val options = params["options"]?.jsonObject ?: JsonObject(emptyMap())

        // 方法必须与声明一致（官方：可选 method 必须等于声明值）。
        val method = options["method"]?.jsonPrimitive?.contentOrNull?.uppercase()
        if (method != null && method != endpoint.method.uppercase()) {
            throw MiniAppHostException("INVALID_ARGUMENTS", "method 与声明不一致")
        }

        val url = buildEndpointUrl(endpoint, options["query"]?.jsonObject)
        val token = resolveToken(endpoint.auth)

        return buildString {
            append("{\"url\":").append(jsonString(url)).append(",")
            append("\"method\":").append(jsonString(endpoint.method)).append(",")
            append("\"headers\":{")
            append(endpoint.headers.entries.joinToString(",") { (k, v) ->
                "${jsonString(k)}:${jsonString(v)}"
            })
            append("},\"auth\":").append(jsonString(endpoint.auth)).append(",")
            append("\"token\":").append(jsonString(token))
            append("}")
        }
    }

    /**
     * 拼接端点地址：只接受声明过的动态 query 键，未声明的一律拒绝。
     */
    private fun buildEndpointUrl(endpoint: MiniAppEndpoint, query: JsonObject?): String {
        val pairs = LinkedHashMap<String, String>()
        query?.forEach { (key, value) ->
            val text = (value as? JsonPrimitive)?.contentOrNull ?: value.toString()
            pairs[key] = text
        }
        return joinEndpointUrl(endpoint.url, endpoint.query, pairs)
    }

    /**
     * 解析端点授权模式对应的平台令牌。
     *
     * - none：不带 JWT；
     * - platform：必须登录并携带 JWT；
     * - auto：登录时携带，匿名时不带。
     *
     * 平台 JWT 由启动接口下发（runtime_token），有效期 15 分钟。
     */
    private fun resolveToken(auth: String): String? {
        val sess = session ?: throw MiniAppHostException("APP_UNLOADED", "微应用尚未启动")
        val token = sess.launch.runtimeToken
        return when (auth) {
            "platform" -> {
                if (token.isNullOrBlank()) {
                    throw MiniAppHostException("PERMISSION_DENIED", "端点要求登录身份")
                }
                if (MiniAppPermissions.USER_READ !in sess.grantedPermissions) {
                    throw MiniAppHostException("PERMISSION_DENIED", "端点要求 user.read 权限")
                }
                token
            }
            "auto" -> token
            else -> null
        }
    }

    /**
     * WebSocket 连接。
     *
     * 端点必须在 manifest.websockets 里声明（审核过），这里只把已批准的
     * 连接信息交给外层页面，由它发起真正的 WS 连接 —— 内层是 null origin，
     * 自己 new WebSocket 会被 CORS 挡。
     *
     * 平台认证的 WS 会在握手 URL 上带 `mini_app_token`（官方约定）。
     */
    private fun webSocketConnect(params: JsonObject): String {
        val endpointName = params["endpoint"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val sess = session ?: throw MiniAppHostException("APP_UNLOADED", "微应用尚未启动")
        val endpoint: MiniAppWebSocket = sess.manifest.websockets[endpointName]
            ?: throw MiniAppHostException("PERMISSION_DENIED", "端点未声明：$endpointName")
        val options = params["options"]?.jsonObject ?: JsonObject(emptyMap())
        val url = buildWebSocketUrl(endpoint, options["query"]?.jsonObject)
        val token = resolveToken(endpoint.auth)
        return buildString {
            append("{\"url\":").append(jsonString(url)).append(",")
            append("\"protocols\":").append(jsonArray(endpoint.protocols)).append(",")
            append("\"auth\":").append(jsonString(endpoint.auth)).append(",")
            append("\"token\":").append(jsonString(token))
            append("}")
        }
    }

    private fun buildWebSocketUrl(endpoint: MiniAppWebSocket, query: JsonObject?): String {
        val pairs = LinkedHashMap<String, String>()
        query?.forEach { (key, value) ->
            pairs[key] = (value as? JsonPrimitive)?.contentOrNull ?: value.toString()
        }
        return joinEndpointUrl(endpoint.url, endpoint.query, pairs)
    }

    /**
     * 打开外链。
     *
     * 只放行 manifest.links 里逐字声明过的 URL —— 站点规范要求 query 与
     * fragment 也须完全一致，这里做严格比对而不是前缀匹配。
     */
    private fun linksOpen(params: JsonObject): String {
        val url = params["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val sess = session ?: throw MiniAppHostException("APP_UNLOADED", "微应用尚未启动")
        if (url.isBlank() || sess.manifest.links.none { it == url }) {
            throw MiniAppHostException("PERMISSION_DENIED", "外链未声明：$url")
        }
        openExternalUrl(url)
        return "null"
    }

    /** 保存文件到应用私有目录（需 files.save）。 */
    private fun filesSave(params: JsonObject): String {
        val name = params["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (name.isBlank() || name.contains("..") || name.contains('/') || name.contains('\\')) {
            throw MiniAppHostException("INVALID_ARGUMENTS", "文件名非法")
        }
        val data = params["data"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val dir = java.io.File(context.filesDir, "miniapp_exports").apply { mkdirs() }
        val file = java.io.File(dir, name)
        file.writeBytes(base64Decode(data))
        return "null"
    }

    /** 调起系统分享（需 sharing.share）。 */
    private fun sharingShare(params: JsonObject): String {
        val data = params["data"]?.jsonObject ?: return "null"
        val title = data["title"]?.jsonPrimitive?.contentOrNull
        val text = data["text"]?.jsonPrimitive?.contentOrNull
        val url = data["url"]?.jsonPrimitive?.contentOrNull
        val payload = listOfNotNull(title, text, url).joinToString("\n")
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_SUBJECT, title.orEmpty())
            putExtra(android.content.Intent.EXTRA_TEXT, payload)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = android.content.Intent.createChooser(intent, "分享").apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
        return "null"
    }

    /** 保持屏幕常亮（需 screen.keepAwake）。 */
    private fun screenKeepAwake(params: JsonObject): String {
        val enabled = params["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
        webView.keepScreenOn = enabled
        return "{\"active\":$enabled}"
    }

    /**
     * 调用另一个微应用（需 app.call）。
     *
     * 目标必须在 manifest.calls 里声明。
     */
    private fun appCall(params: JsonObject): String {
        val targetId = params["appId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val sess = session ?: throw MiniAppHostException("APP_UNLOADED", "微应用尚未启动")
        if (targetId !in sess.manifest.calls) {
            throw MiniAppHostException("APP_NOT_ALLOWED", "目标应用未声明：$targetId")
        }
        return "{\"appId\":${jsonString(targetId)}}"
    }

    /** 返回到调用栈中的祖先（需 app.call）。 */
    private fun appReturn(params: JsonObject): String {
        requirePermission(MiniAppPermissions.APP_CALL)
        return "null"
    }

    /**
     * 申请通知模板投递（需 notifications 权限）。
     *
     * 站点的通知以论坛私信送达；本宿主暂未接通私信发送链路，
     * 显式返回不支持而不是假装成功 —— 微应用可据此降级。
     */
    private fun notificationsRequest(params: JsonObject): String {
        val templateId = params["templateId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val sess = session ?: throw MiniAppHostException("APP_UNLOADED", "微应用尚未启动")
        if (templateId !in sess.manifest.notifications) {
            throw MiniAppHostException("INVALID_ARGUMENTS", "通知模板未声明：$templateId")
        }
        throw MiniAppHostException("API_UNSUPPORTED", "通知投递尚未接通")
    }

    /** 打开外部链接（走系统浏览器）。 */
    private fun openExternalUrl(url: String) {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { Timber.w(it, "%s open external url failed", LOG_TAG) }
    }

    // ---------------------------------------------------------------------
    // 会话与存储
    // ---------------------------------------------------------------------

    /**
     * 设置已解析的启动信息（由 ViewModel 在拿到 launch 响应后调用）。
     *
     * 这一步必须在宿主页面加载**之前**完成 —— 页面回报 origin 后会立刻
     * 索取启动信息，那时原生侧必须已经能给出发起信息。
     */
    fun setSession(launch: MiniAppLaunch, query: Map<String, List<String>>, hash: String) {
        val newSession = Session(
            launch,
            launch.release.appId.ifBlank { launch.release.manifest.id },
            query,
            hash
        )
        session = newSession
        storage.currentAppId = newSession.appId
    }

    /** 当前会话；未启动为 null。 */
    fun currentSession(): Session? = session

    /** 微应用存储：按 appId 隔离。 */
    private val storage: MiniAppStorage = MiniAppStorage(context)

    /** 清空会话（退出微应用）。 */
    fun release() {
        session = null
        pendingLaunch = null
        launchInfoPushed = false
        originVerified = false
        hostPageRequested = false
        state = State.Idle
    }

    // ---------------------------------------------------------------------
    // 工具
    // ---------------------------------------------------------------------
    private data class LaunchRequest(
        val appId: String,
        val query: Map<String, List<String>>,
        val hash: String
    )

    private fun JsonPrimitive.longOrNullSafe(): Long? =
        contentOrNull?.toLongOrNull()?.takeIf { it > 0 }
}

/** 宿主用到的、官方错误码集合里没有单独常量但确实会返回的码。 */
private object MiniAppErrorCodesCompat {
    const val USER_UNAVAILABLE = "APP_UNAVAILABLE"
}
