package com.jetdo.android.services.content

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private const val LOG_TAG = "[JetDo][MathRender]"

/** 离屏页里 window.JetDoMath 的方法名(供 JS 回调)。 */
private const val BRIDGE_NAME = "JetDoMath"

/** 单条公式渲染兜底超时:超时即失败,渲染层退回显示裸 LaTeX。 */
private const val RENDER_TIMEOUT_MS = 8_000L

/** 引擎空闲多久后销毁(离开含公式帖子后不立刻拆,避免来回进出频繁重建)。 */
private const val IDLE_DISPOSE_MS = 20_000L

/** 位图 LRU 上限:数学重贴也就几十个公式,足够。 */
private const val CACHE_CAPACITY = 128

/**
 * 一条公式的渲染产物。
 *
 * [cssWidth]/[cssHeight] 是逻辑尺寸(dp),位图是 dpr 倍的清晰图;
 * [depth] 是基线以下的下沉量(dp),供行内公式对齐基线参考。
 */
data class MathImage(
    val bitmap: Bitmap,
    val cssWidth: Float,
    val cssHeight: Float,
    val depth: Float
)

/**
 * 单例离屏公式引擎,1:1 对齐鸿蒙 `services/content/MathRenderService.ets`。
 *
 * 一个只加载本地 MathJax 页的 WebView,把 LaTeX 渲染成 PNG 位图并做 LRU 缓存。
 * 与网络宿主(ArkWebNetworkBridge)完全分家:不导航站点、不随 session 重载、
 * 不在网络路径上、懒挂载空闲卸载。
 *
 * 页面侧契约(见 assets/mathjax/render.html,与鸿蒙同一份):
 * `renderMath(requestId, latex, display, scale)` → 页面回调
 * `JetDoMath.onResult(id, pngBase64, w, h, depth)` 或 `JetDoMath.onError(id, msg)`。
 * 这里通过 [MathBridge] 注入同名对象接收回调。
 */
object MathRenderService {

    private val seq = AtomicInteger(0)
    private var webView: WebView? = null
    private var ready = false

    /** 已就绪前入队的请求,attach 后统一下发。 */
    private val pendingRenders = ConcurrentHashMap<String, CompletableDeferred<MathImage>>()

    /**
     * 挂起的请求体:就绪后真正下发。用 [CompletableDeferred] 方便超时与失败统一处理。
     * 键是 requestId。
     */
    private val queued = mutableListOf<() -> Unit>()

    /** key -> 产物 的 LRU;key -> 进行中 Deferred 的去重表。 */
    private val cache = LinkedHashMap<String, MathImage>()
    private val inflight = ConcurrentHashMap<String, CompletableDeferred<MathImage>>()

    private var refCount = 0
    private var idleDisposeJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * 宿主是否应挂载。**全局唯一**一份离屏引擎 —— 一篇帖子里多楼都含公式时,
     * 若每楼各挂一个 WebView 会平白多出 N 个引擎实例,所以这里用引用计数 +
     * 单一挂载点([com.jetdo.android.ui.components.MathRenderHostSlot] 观察本流量)。
     */
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /** 设备像素比,出图清晰度用。 */
    @Volatile
    private var dpr: Float = 2f

    fun setDensity(density: Float) {
        dpr = density.coerceIn(1f, 3f)
    }

    /** 缓存 key:行内/块级 + dpr + 源码(displayMode 不同须分开缓存)。 */
    fun keyOf(latex: String, display: Boolean, dpr: Float): String =
        "${if (display) "d" else "i"}|$dpr|$latex"

    /** 离屏页 onPageFinished 调用:控制器就绪,放行排队请求。 */
    fun attach(webView: WebView) {
        this.webView = webView
        ready = true
        Timber.i("$LOG_TAG host ready dpr=%s queued=%d", dpr, queued.size)
        val waiters = queued.toList()
        queued.clear()
        waiters.forEach { runCatching { it() } }
    }

    private fun detach() {
        webView = null
        ready = false
        // 卸载时把仍在等待的请求全部失败掉,避免永久悬住。
        pendingRenders.keys.toList().forEach { fail(it, "math host detached") }
        queued.clear()
    }

    /** 帖子进入时 retain / 离开时 release。refCount 0→1 触发挂载,回到 0 后延迟卸载。 */
    fun retain() {
        refCount++
        idleDisposeJob?.cancel()
        idleDisposeJob = null
        _active.value = true
    }

    fun release() {
        refCount = maxOf(0, refCount - 1)
        if (refCount > 0) return
        idleDisposeJob?.cancel()
        idleDisposeJob = scope.launch {
            delay(IDLE_DISPOSE_MS)
            if (refCount == 0) {
                detach()
                _active.value = false
                Timber.i("$LOG_TAG host disposed (idle)")
            }
        }
    }

    fun getCached(latex: String, display: Boolean): MathImage? =
        synchronized(cache) { cache[keyOf(latex, display, dpr)] }

    /**
     * 渲染一条公式。命中缓存/进行中则复用;否则待就绪后下发 runJavaScript,
     * 页面回调再解码成位图。
     *
     * 失败(超时/页面报错/宿主未就绪)抛异常,调用方退回显示裸 LaTeX。
     */
    suspend fun render(latex: String, display: Boolean): MathImage {
        val key = keyOf(latex, display, dpr)
        synchronized(cache) { cache[key] }?.let { return it }
        inflight[key]?.let { return it.await() }

        val deferred = CompletableDeferred<MathImage>()
        inflight[key] = deferred
        // 下发后拿到的 requestId:超时/失败时要按它把 pendingRenders 里的条目摘掉,
        // 否则页面稍后回调会往一个已废弃的 id 上写,条目永久残留。
        var requestId: String? = null

        try {
            val dispatch: () -> Unit = {
                val view = webView
                if (view == null) {
                    deferred.completeExceptionally(IllegalStateException("math host not ready"))
                } else {
                    val id = "m${seq.getAndIncrement()}"
                    requestId = id
                    pendingRenders[id] = deferred
                    view.evaluateJavascript("renderMath(${quote(id)},${quote(latex)},$display,$dpr)") { }
                }
            }
            if (ready) dispatch() else queue(dispatch)

            val image = withTimeout(RENDER_TIMEOUT_MS) { deferred.await() }
            putCache(key, image)
            return image
        } catch (e: TimeoutCancellationException) {
            requestId?.let { pendingRenders.remove(it) }
            throw IllegalStateException("render timeout", e)
        } finally {
            inflight.remove(key)
        }
    }

    private fun queue(dispatch: () -> Unit) {
        synchronized(queued) {
            // 入队与 attach 之间有竞态:attach 刚把队列清空并置 ready,这时再入队就永远不会被放行,
            // 所以拿到锁后要复查 ready。
            if (ready) dispatch() else queued.add(dispatch)
        }
    }

    /**
     * JS 回调桥。页面出图后调用 onResult / 出错调用 onError。
     * 方法名与签名必须和 render.html 里 `window.JetDoMath.onResult(...)` 的调用一致。
     */
    internal class MathBridge {
        @JavascriptInterface
        fun onResult(requestId: String, pngBase64: String, width: Float, height: Float, depth: Float) {
            val deferred = pendingRenders.remove(requestId) ?: return
            runCatching {
                val bytes = Base64.decode(pngBase64, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap == null) {
                    deferred.completeExceptionally(IllegalStateException("decode png failed"))
                } else {
                    deferred.complete(
                        MathImage(bitmap = bitmap, cssWidth = width, cssHeight = height, depth = depth)
                    )
                }
            }.onFailure {
                Timber.i("$LOG_TAG decode failed id=%s %s", requestId, it.message)
                deferred.completeExceptionally(it)
            }
        }

        @JavascriptInterface
        fun onError(requestId: String, message: String) {
            fail(requestId, message)
        }
    }

    private fun fail(requestId: String, message: String) {
        pendingRenders.remove(requestId)?.let {
            Timber.i("$LOG_TAG render failed id=%s msg=%s", requestId, message)
            it.completeExceptionally(IllegalStateException(message))
        }
    }

    private fun putCache(key: String, image: MathImage) {
        synchronized(cache) {
            cache[key] = image
            while (cache.size > CACHE_CAPACITY) {
                // LinkedHashMap 迭代按插入序:删最旧一个即 LRU 近似(不追求命中后置顶,数学场景足够)。
                val oldest = cache.keys.firstOrNull() ?: break
                cache.remove(oldest)
            }
        }
    }

    /** JS 字符串字面量转义:与 JSON.stringify 等价,但只处理字符串场景。 */
    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }

    /** 创建要 addJavascriptInterface 的桥对象。 */
    fun createBridge(): Any = MathBridge()
}