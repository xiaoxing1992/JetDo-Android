package com.arkdo.android.services.network

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import timber.log.Timber

/**
 * 把宿主 WebView 的会话 cookie 接给 Coil 的 OkHttp。
 *
 * 为什么必须有：linux.do 的同源上传件（`/uploads/short-url/…`，短码 `upload://` 换出来的就是
 * 它）直连会被 CF 挡，**必须带 WebView 会话**才拿得到字节 —— 这条规则在别处早已踩过并写明
 * （见 ArkWebNetworkBridge.startSystemDownload 里给 DownloadManager 补 Cookie 的注释）。
 * 音视频走 `downloadMedia` 补了 cookie，所以能下能播；而 Coil 自带的 OkHttp **没有 cookie jar**，
 * 图片请求裸奔 → 服务端对未登录访客返回 404 → 表现就是「上传成功、正文 markdown 也对，
 * 但预览里图片一片空白」。
 *
 * 作用域是天然的：`CookieManager.getCookie(url)` 只返回该 url 的域/路径能匹配上的 cookie，
 * 所以 linux.do 的会话不会泄给第三方 CDN，跨源 CDN 请求也只会带上 CDN 自己的 cookie。
 */
class WebViewCookieJar : CookieJar {

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        // Coil 的请求跑在 OkHttp 的后台线程上。CookieManager.getInstance() 触发 WebView 初始化，
        // 正常时序下宿主 WebView 早就在主线程建好了；这里 runCatching 只是防「宿主尚未就绪」
        // 这类极端时序，拿不到 cookie 就当匿名请求，不要因为取 cookie 把图片加载整条打挂。
        val header = runCatching { CookieManager.getInstance().getCookie(url.toString()) }
            .onFailure { Timber.i("[ArkDO][Cookie] 读取 cookie 失败 url=%s msg=%s", url, it.message) }
            .getOrNull()
        if (header.isNullOrEmpty()) return emptyList()
        return header.split(';').mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            val name = part.substring(0, eq).trim()
            val value = part.substring(eq + 1).trim()
            if (name.isEmpty()) return@mapNotNull null
            runCatching {
                Cookie.Builder()
                    .name(name)
                    .value(value)
                    .domain(url.host)
                    .path("/")
                    .build()
            }.getOrNull()
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        // 服务端在图片响应里刷新的 cookie（如 cf_clearance）要回写，否则 WebView 侧永远是旧值。
        cookies.forEach { cookie ->
            runCatching { CookieManager.getInstance().setCookie(url.toString(), cookie.toString()) }
        }
    }
}
