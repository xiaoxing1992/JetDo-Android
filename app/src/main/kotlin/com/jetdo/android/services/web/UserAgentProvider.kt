package com.jetdo.android.services.web

import android.content.Context
import android.webkit.WebSettings
import com.jetdo.android.common.constants.AppConstants
import timber.log.Timber

/**
 * 运行时 User-Agent 提供者。
 *
 * Cloudflare 的 JS 指纹会比对 UA 声称的浏览器版本与引擎真实特征;硬编码 Chrome/125
 * 而设备真实内核是 114(如华为 com.huawei.webview)时会被判定不一致,盾变得频繁且
 * 难以静默通过。故从 WebSettings.getDefaultUserAgent 取真实内核 UA,只做去 WebView
 * 标记的清洗:
 *  - "; wv"           WebView 标识
 *  - "Version/4.0 "   WebView 专有 token
 *  - " Build/xxx"     真实 Chrome UA 不带机型 Build 号
 *  - Chrome 小版本归零(a.b.c.d → a.0.0.0),与 Chrome 110+ 的 reduced UA 格式一致
 * Chrome 主版本保留真实值,保证 UA 与引擎 JS 特征一致。
 *
 * cf_clearance 与签发时的 UA 绑定:宿主桥、验证页、下载请求头必须都从这里取同一个值。
 */
object UserAgentProvider {
    @Volatile
    private var cached: String? = null

    fun get(context: Context): String {
        cached?.let { return it }
        val computed = runCatching {
            WebSettings.getDefaultUserAgent(context.applicationContext)
                .replace("; wv", "")
                .replace("Version/4.0 ", "")
                .replace(Regex(" Build/[^;)]+"), "")
                .replace(Regex("Chrome/(\\d+)\\.[\\d.]+"), "Chrome/$1.0.0.0")
        }.getOrElse {
            Timber.i("%s default UA unavailable, using fallback. error=%s", LOG_TAG, it.message)
            AppConstants.DEFAULT_USER_AGENT
        }
        cached = computed
        Timber.i("%s user agent resolved: %s", LOG_TAG, computed)
        return computed
    }
}
