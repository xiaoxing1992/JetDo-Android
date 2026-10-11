package com.jetdo.android.services.web

import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.jetdo.android.common.constants.AppConstants
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

// ==========================================================================
// 与 HarmonyOS 版 services/web/WebSessionManager.ets 逐字段对齐。
// WebView 会话 Cookie/UA 的集中管理。
//
// Android 差异：第三方 cookie 是 WebView 实例级的（setAcceptThirdPartyCookies），
// 故 configureCookieAccess / applyUserAgent 需传入 WebView 实例。
// saveCookies → CookieManager.flush()（异步写盘）。
// ==========================================================================

const val LOG_TAG = "[JetDo][WebSession]"

data class CookiePresenceSnapshot(
    val hasCookie: Boolean,
    val headerLength: Int
)

object WebSessionManager {
    // 串行化落盘：多个 WebView 的 onPageEnd 可能同时触发保存，后一次等前一次落盘完。
    private val cookieSaveMutex = Mutex()
    private var cookieSaveChain: kotlinx.coroutines.Deferred<Unit>? = null

    fun configureCookieAccess(webView: WebView, source: String) {
        runCatching {
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
            Timber.i("%s cookie access enabled source=%s", LOG_TAG, source)
        }.onFailure {
            Timber.i("%s cookie access configure failed source=%s error=%s", LOG_TAG, source, it.message)
        }
    }

    fun applyUserAgent(webView: WebView, source: String) {
        runCatching {
            webView.settings.userAgentString = UserAgentProvider.get(webView.context)
            Timber.i("%s user agent applied source=%s", LOG_TAG, source)
        }.onFailure {
            Timber.i("%s user agent apply failed source=%s error=%s", LOG_TAG, source, it.message)
        }
        applyBrowserIdentity(webView, source)
    }

    /**
     * 补齐 UA 字符串之外的两处「WebView 身份泄露」。鸿蒙 ArkWeb 没有这两个信号,
     * Android Chromium WebView 却会在 UA 之外自报家门,CF 一对比就知道 UA 是改过的:
     *  - UA Client Hints:setUserAgentString 不影响 Sec-CH-UA,默认 brand 仍是 "Android WebView"。
     *    这里把 brand 改成 Chromium/Google Chrome,主版本与 UA 里的 Chrome 主版本一致。
     *  - X-Requested-With:WebView 给每个请求附带包名头,是识别 WebView 的经典信号。
     *    允许名单清空即不再发送。
     * 两者都按 WebViewFeature 探测,内核不支持时静默跳过(只留日志)。
     * 全 app 所有 WebView 必须走同一份,否则 cf_clearance 绑定的指纹会对不上。
     */
    private fun applyBrowserIdentity(webView: WebView, source: String) {
        val settings = webView.settings
        runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
                Timber.i("%s X-Requested-With disabled source=%s", LOG_TAG, source)
            } else {
                Timber.i("%s X-Requested-With allow list unsupported source=%s", LOG_TAG, source)
            }
        }.onFailure {
            Timber.i("%s X-Requested-With configure failed source=%s error=%s", LOG_TAG, source, it.message)
        }
        runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
                val ua = settings.userAgentString.orEmpty()
                val major = Regex("Chrome/(\\d+)").find(ua)?.groupValues?.get(1) ?: "120"
                val full = "$major.0.0.0"
                val brands = listOf(
                    UserAgentMetadata.BrandVersion.Builder()
                        .setBrand("Chromium").setMajorVersion(major).setFullVersion(full).build(),
                    UserAgentMetadata.BrandVersion.Builder()
                        .setBrand("Google Chrome").setMajorVersion(major).setFullVersion(full).build(),
                    UserAgentMetadata.BrandVersion.Builder()
                        .setBrand("Not_A Brand").setMajorVersion("24").setFullVersion("24.0.0.0").build()
                )
                val metadata = UserAgentMetadata.Builder()
                    .setBrandVersionList(brands)
                    .setFullVersion(full)
                    .setPlatform("Android")
                    .setPlatformVersion(Build.VERSION.RELEASE.orEmpty())
                    .setArchitecture("")
                    .setModel(Build.MODEL.orEmpty())
                    .setMobile(true)
                    .setBitness(UserAgentMetadata.BITNESS_DEFAULT)
                    .setWow64(false)
                    .build()
                WebSettingsCompat.setUserAgentMetadata(settings, metadata)
                Timber.i("%s UA client hints set brand=Chrome/%s source=%s", LOG_TAG, major, source)
            } else {
                Timber.i("%s UA client hints unsupported source=%s", LOG_TAG, source)
            }
        }.onFailure {
            Timber.i("%s UA client hints configure failed source=%s error=%s", LOG_TAG, source, it.message)
        }
    }

    /** 串行化落盘。调用方可 await 再关登录页。 */
    suspend fun saveCookies(source: String) {
        cookieSaveMutex.withLock {
            // 用 withLock 保证串行：前一次 flush 完成后才执行下一次。
            runCatching {
                // 保存前先记录当前 cookie 状态
                val beforeCfClearance = getCfClearanceValue()
                val beforeSnapshot = getCookiePresenceSnapshot("_t")
                Timber.i("%s BEFORE save: source=%s cf_clearance=%s authCookiePresent=%b cookieHeaderLength=%d",
                    LOG_TAG, source, beforeCfClearance.take(20), beforeSnapshot.hasCookie, beforeSnapshot.headerLength)

                CookieManager.getInstance().flush()

                // 保存后再检查
                val afterCfClearance = getCfClearanceValue()
                val afterSnapshot = getCookiePresenceSnapshot("_t")
                Timber.i("%s AFTER save: source=%s cf_clearance=%s authCookiePresent=%b cookieHeaderLength=%d",
                    LOG_TAG, source, afterCfClearance.take(20), afterSnapshot.hasCookie, afterSnapshot.headerLength)
            }.onFailure {
                Timber.i("%s cookie save failed source=%s error=%s", LOG_TAG, source, it.message)
            }
        }
    }

    fun hasCookie(cookieName: String): Boolean =
        getCookiePresenceSnapshot(cookieName).hasCookie

    fun getCookiePresenceSnapshot(cookieName: String): CookiePresenceSnapshot =
        getCookiePresenceSnapshotForUrl(AppConstants.BASE_URL, cookieName)

    fun hasCookieForUrl(url: String, cookieName: String): Boolean =
        getCookiePresenceSnapshotForUrl(url, cookieName).hasCookie

    fun getCookiePresenceSnapshotForUrl(url: String, cookieName: String): CookiePresenceSnapshot {
        val cookieHeader = runCatching { CookieManager.getInstance().getCookie(url) ?: "" }.getOrDefault("")
        return CookiePresenceSnapshot(
            hasCookie = containsCookie(cookieHeader, cookieName),
            headerLength = cookieHeader.length
        )
    }

    fun hasCfClearance(): Boolean = hasCookie("cf_clearance")

    /** 取 cf_clearance 的值（不含名字）。弹层用它做"值变化"判定。 */
    fun getCfClearanceValue(): String {
        val cookieHeader = runCatching { CookieManager.getInstance().getCookie(AppConstants.BASE_URL) ?: "" }.getOrDefault("")
        val prefix = "cf_clearance="
        for (part in cookieHeader.split(';')) {
            val trimmed = part.trim()
            if (trimmed.startsWith(prefix)) {
                return trimmed.substring(prefix.length)
            }
        }
        return ""
    }

    private fun containsCookie(cookieHeader: String, cookieName: String): Boolean {
        val prefix = "$cookieName="
        for (part in cookieHeader.split(';')) {
            if (part.trim().startsWith(prefix)) return true
        }
        return false
    }
}
