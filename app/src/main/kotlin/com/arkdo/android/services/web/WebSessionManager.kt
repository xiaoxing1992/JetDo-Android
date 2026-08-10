package com.arkdo.android.services.web

import android.webkit.CookieManager
import android.webkit.WebView
import com.arkdo.android.common.constants.AppConstants
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

const val LOG_TAG = "[ArkDO][WebSession]"

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
            webView.settings.userAgentString = AppConstants.DEFAULT_USER_AGENT
            Timber.i("%s user agent applied source=%s", LOG_TAG, source)
        }.onFailure {
            Timber.i("%s user agent apply failed source=%s error=%s", LOG_TAG, source, it.message)
        }
    }

    /** 串行化落盘。调用方可 await 再关登录页。 */
    suspend fun saveCookies(source: String) {
        cookieSaveMutex.withLock {
            // 用 withLock 保证串行：前一次 flush 完成后才执行下一次。
            runCatching {
                CookieManager.getInstance().flush()
                val snapshot = getCookiePresenceSnapshot("_t")
                Timber.i("%s cookie save success source=%s authCookiePresent=%b cookieHeaderLength=%d",
                    LOG_TAG, source, snapshot.hasCookie, snapshot.headerLength)
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
