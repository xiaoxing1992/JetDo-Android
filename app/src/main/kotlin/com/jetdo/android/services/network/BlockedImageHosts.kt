package com.jetdo.android.services.network

import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import java.io.IOException
import java.util.concurrent.TimeoutException
import timber.log.Timber

// 对齐鸿蒙版 services/network/BlockedImageHosts.ets（语义按平台差异改写，见类注释）。
//
// 鸿蒙判据是「原生取不到 + 桥能取到」才记证据；Android 图片加载只有 Coil 一条路、
// 没有 webview 取图桥，所以证据改判为「网络层失败」：Coil 的失败里分得清
// 「网络被封」（DNS 解析失败 / 连接超时 / TLS 握手失败等 IOException 族）
// 和「这张图 404」（服务端明确应答 HttpException）—— 404 在哪条路上都取不到，
// 天然不构成「域名被封」的证据，与鸿蒙「桥也取不到就不记」的门槛同义。

/** 同一域名上有这么多张**不同的图**网络层失败，才判定「这个域名当前走不通」。 */
private const val FAIL_THRESHOLD = 2

private const val LOG_TAG = "[JetDo][ImageHost]"

/**
 * 记住「Coil 走不通」的图片域名（仅本次运行内存态，冷启动自动复位）。
 *
 * 哪些域名被封因网络而异（有的用户 ldstatic.com 直连没问题，有的全挂），写死名单必然漏，
 * 所以改成运行时学：够多张不同的图印证过网络层失败，就记下来，之后同域名的图直接判失败，
 * 不用每张都先白等一次注定失败的网络请求（LazyColumn 回滚重挂载时尤其明显）。
 *
 * 不落盘是有意的：网络环境会变（换 WiFi / 出国 / 运营商恢复），重启即给网络一次重新证明
 * 自己的机会。鸿蒙还有一条「桥也取不到就撤销标记」的对冲，Android 没有取图桥，这条不搬。
 */
object BlockedImageHosts {
    private val failedUrls = LinkedHashMap<String, MutableSet<String>>()
    private val blocked = mutableSetOf<String>()

    /** 是否已有任何域名被判定走不通。健康网络下恒 false，调用方据此整段跳过兜底逻辑。 */
    fun hasAny(): Boolean = blocked.isNotEmpty()

    /** 取 URL 的 host（小写）。取不到返回空串。对齐鸿蒙 hostOf()。 */
    fun hostOf(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return ""
        val rest = url.substring(schemeEnd + 3)
        val slash = rest.indexOf('/')
        val authority = if (slash >= 0) rest.substring(0, slash) else rest
        val at = authority.lastIndexOf('@')
        val hostPort = if (at >= 0) authority.substring(at + 1) else authority
        val colon = hostPort.lastIndexOf(':')
        val host = if (colon > 0) hostPort.substring(0, colon) else hostPort
        return host.lowercase()
    }

    /** 该 URL 所在域名是否已判定走不通（→ 直接判失败，别再发注定失败的网络请求）。 */
    fun isBlocked(url: String): Boolean {
        val host = hostOf(url)
        return host.isNotEmpty() && host in blocked
    }

    /**
     * 记一条「网络层失败」的证据。累计到 [FAIL_THRESHOLD] 张不同的图即判定该域名走不通。
     * 只有 [isNetworkLevelFailure] 为 true 的失败才允许调用。
     */
    fun markNetworkFailure(url: String) {
        val host = hostOf(url)
        if (host.isEmpty() || host in blocked) return
        val urls = failedUrls.getOrPut(host) { mutableSetOf() }
        urls += url
        if (urls.size < FAIL_THRESHOLD) return
        blocked += host
        failedUrls.remove(host)
        Timber.i("%s image host blocked host=%s failed=%d", LOG_TAG, host, urls.size)
    }

    /** 仅供单测复位内存态。 */
    fun reset() {
        failedUrls.clear()
        blocked.clear()
    }
}

/**
 * 失败分类：网络层失败（域名可能被封，记证据）还是这张图本身没了（404/410 等，
 * 不记证据）。对齐鸿蒙「onError 分不清两种失败，所以靠桥能不能取到来分辨」的门槛，
 * Android 在 Coil 的异常类型上就能分清，无需第二条例证。
 */
internal fun isNetworkLevelFailure(throwable: Throwable?): Boolean {
    var current: Throwable? = throwable
    while (current != null) {
        // 服务端有明确应答：图真没了 / 无权限，跟域名通不通无关。
        if (current is coil3.network.HttpException) return false
        if (current is IOException || current is TimeoutException) return true
        current = current.cause
    }
    return false
}

/**
 * Coil 拦截器：加载前查黑名单，命中直接抛 [BlockedImageHostException] 短路 ——
 * RealImageLoader 的 catch 会兜成 ErrorResult 喂给调用方，走普通加载失败路径
 * （退候选 / 露首字母 / 占位图）。失败后按分类记证据。挂到全局 ImageLoader 上，
 * 头像 / 富文本图 / 图片查看器等所有走 Coil 的加载点一次性全覆盖
 * （对齐鸿蒙 PostRichContent / UserAvatar / ImageViewer / ImageShareService 四个消费方）。
 */
class BlockedImageHostInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val url = when (val data = request.data) {
            is String -> data
            is coil3.Uri -> data.toString()
            else -> return chain.proceed()
        }
        if (BlockedImageHosts.isBlocked(url)) {
            val host = BlockedImageHosts.hostOf(url)
            Timber.i("%s short-circuit blocked host=%s", LOG_TAG, host)
            throw BlockedImageHostException(host)
        }
        val result = chain.proceed()
        if (result is ErrorResult && isNetworkLevelFailure(result.throwable)) {
            BlockedImageHosts.markNetworkFailure(url)
        }
        return result
    }
}

/** 黑名单短路时喂给调用方的失败原因（调用方按普通加载失败处理：退候选 / 露首字母 / 占位图）。 */
class BlockedImageHostException(val host: String) :
    IOException("Image host blocked: $host")
