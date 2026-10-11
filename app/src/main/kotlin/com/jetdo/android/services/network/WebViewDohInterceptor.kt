package com.jetdo.android.services.network

import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import okhttp3.Request
import timber.log.Timber

// ==========================================================================
// 鸿蒙版用 webview.WebviewController.setHttpDns 在 ArkWeb 引擎层开 DoH，一次下发全局生效。
// Android WebView 没有等价 API：android.webkit.WebSettings 从来没有过 setDnsOverHttpsServers
// 之类的方法（不是 API level 问题），系统级 Private DNS 又只吃 DoT 主机名、且是全局设置，
// 用不上 DohManager 里那些自定义 DoH URL。
//
// 所以这里换一条路：在 WebViewClient.shouldInterceptRequest 里接管请求，
// 用 DoH 解析出 IP 后由 OkHttp 发出，再把响应包成 WebResourceResponse 交回 WebView。
// WebView 的 cookie / 会话 / 浏览器指纹全部保留，只有域名解析这一步被换掉。
//
// OkHttp 的自定义 Dns 只替换地址解析，TLS 的 SNI 与证书校验仍按原域名走，
// 所以不需要手工改写 Host 头 —— 那样反而会破坏 SNI 和证书验证。
// ==========================================================================

/**
 * 把 WebView 的请求改道到走 DoH 的 OkHttp 客户端。
 *
 * 覆盖范围有两条边界，都是 WebResourceRequest 本身的限制：
 *  1. 只接管 GET。WebResourceRequest 不提供请求体，POST/PUT 无法重放，只能原样交回 WebView。
 *  2. 只接管 linux.do 及其子域。其余域名（尤其 challenges.cloudflare.com）交回系统 DNS —
 *     和鸿蒙版 DohManager.applyTransientMode 的取舍一致：CF 任播域基本不被污染、解得还更快。
 */
object WebViewDohInterceptor {

    private const val TAG = "[JetDo][DohIntercept]"
    private const val ROOT_HOST = "linux.do"

    /** OkHttp 会自己加 Accept-Encoding 并透明解压，转发 WebView 那份会让解压逻辑对不上。 */
    private val DROPPED_REQUEST_HEADERS = setOf("accept-encoding", "cookie", "host", "connection")

    /**
     * OkHttp 交出来的 body 已经解过压，这几个头会与实际内容不符，必须去掉：
     * 留着 Content-Encoding 会让 WebView 再解压一次拿到乱码，留着 Content-Length 会截断。
     * Set-Cookie 单独经 CookieManager 落库，不走 responseHeaders。
     */
    private val DROPPED_RESPONSE_HEADERS = setOf(
        "content-encoding", "content-length", "transfer-encoding", "connection", "set-cookie"
    )

    /**
     * 在 WebViewClient.shouldInterceptRequest 里调用。
     *
     * @return 接管成功时返回响应；返回 null 表示交回 WebView 按原路（系统 DNS）自己发。
     */
    fun intercept(request: WebResourceRequest?): WebResourceResponse? {
        val req = request ?: return null
        if (!DohService.isActive) return null

        val url = req.url ?: return null
        val scheme = url.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return null

        val host = url.host?.lowercase() ?: return null
        if (host != ROOT_HOST && !host.endsWith(".$ROOT_HOST")) return null

        // Cloudflare 自身的挑战基础设施(/cdn-cgi/challenge-platform/**)全部放行:
        // 它靠 302 + JS 自举多次跳转/上报,经 OkHttp 代发后 3xx 与 cookie 时序都会走样,
        // 是 Turnstile/hCaptcha 渲染不出来的直接原因。交给 WebView 原生网络栈。
        if (url.path?.contains("/cdn-cgi/challenge-platform") == true) return null

        // 主框架文档一律放行给 WebView 原生网络栈。
        // 原因:手工用 WebResourceResponse 塞回主文档会丢掉 Content-Length/Set-Cookie 等头,
        // 更关键的是会让文档的安全上下文走样 —— Turnstile 的验证控件跑在 about:srcdoc 的
        // iframe 里,日志实测其 origin 变成 'null'(「origin-keyed agent cluster」告警),
        // 环境自检必然失败、验证框渲染不出来。子资源(图片/JS/接口)继续走 DoH 接管,
        // 主域名的解析交给系统 DNS(能正常加载登录页即说明主站可达)。
        if (req.isForMainFrame) {
            Timber.i("$TAG main frame passed back to WebView %s", url)
            return null
        }

        // WebResourceRequest 不给请求体,POST/PUT/PATCH 没法原样重放。
        if (!req.method.equals("GET", ignoreCase = true)) return null

        val target = url.toString()
        return try {
            fetch(target, req)
        } catch (e: Exception) {
            // 交回 WebView 自己发。它多半也会失败（系统 DNS 被污染就是这个功能存在的理由），
            // 但至少不比不拦截更糟，且能让 WebView 走它自己的错误页/重试逻辑。
            Timber.w("$TAG 接管失败，交回 WebView：%s (%s)", target, e.message)
            null
        }
    }

    private fun fetch(target: String, req: WebResourceRequest): WebResourceResponse? {
        val cookieManager = CookieManager.getInstance()

        val builder = Request.Builder().url(target).get()
        req.requestHeaders?.forEach { (name, value) ->
            if (name.lowercase() !in DROPPED_REQUEST_HEADERS) builder.addHeader(name, value)
        }
        // Chromium 是在网络栈更下层才贴 cookie 的，requestHeaders 里通常没有，
        // 绕过网络栈就必须自己从 CookieManager 取（cf_clearance / _t 都在这）。
        cookieManager.getCookie(target)?.takeIf { it.isNotEmpty() }?.let {
            builder.header("Cookie", it)
        }

        val response = DohService.client.newCall(builder.build()).execute()

        // Set-Cookie 回写，否则 CF 过盾拿到的 clearance 会丢。
        // flush 是同步落盘，只在真有 Set-Cookie 时做，不然每张图每个接口都白写一次。
        val setCookies = response.headers("Set-Cookie")
        if (setCookies.isNotEmpty()) {
            setCookies.forEach { cookieManager.setCookie(target, it) }
            cookieManager.flush()
        }

        val statusCode = response.code
        if (statusCode !in 100..599) {
            response.close()
            Timber.w("$TAG 状态码越界 code=%d url=%s", statusCode, target)
            return null
        }

        val responseHeaders = mutableMapOf<String, String>()
        response.headers.forEach { (name, value) ->
            if (name.lowercase() !in DROPPED_RESPONSE_HEADERS) responseHeaders[name] = value
        }

        val contentType = response.header("Content-Type")
        val mimeType = contentType?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() }
        val encoding = contentType?.substringAfter("charset=", "")
            ?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() }

        // 3xx 交回 WebView 自己跟:WebResourceResponse 不允许 300~399 状态码(构造会抛
        // IllegalArgumentException),包成响应塞回会让整条拦截失败、请求被丢弃 —— 尤其会打断
        // CF challenge 平台的 302 链路(登录/验证空白的一处根因)。
        if (statusCode in 300..399) {
            response.close()
            Timber.i("$TAG %d redirect passed back to WebView %s", statusCode, target)
            return null
        }

        // 204/304 按规范不能带 body,塞流进去某些 WebView 版本会报错。
        val body = if (statusCode == 204 || statusCode == 304) {
            response.close()
            null
        } else {
            // 不整块读进内存:WebResourceResponse 会接管这个流并在读完后关闭它。
            response.body.byteStream()
        }

        Timber.i("$TAG %d %s", statusCode, target)

        return WebResourceResponse(
            mimeType ?: "text/plain",
            encoding ?: "utf-8",
            statusCode,
            // 空 reasonPhrase 会让 WebResourceResponse 抛 IllegalArgumentException。
            response.message.ifBlank { defaultReason(statusCode) },
            responseHeaders,
            body
        )
    }

    private fun defaultReason(statusCode: Int): String = when (statusCode) {
        in 200..299 -> "OK"
        in 300..399 -> "Redirect"
        in 400..499 -> "Client Error"
        else -> "Server Error"
    }
}