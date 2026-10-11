package com.jetdo.android.services.credit

import com.jetdo.android.common.constants.AppConstants
import com.jetdo.android.data.models.LdcUserInfo
import com.jetdo.android.services.web.WebSessionManager

// ==========================================================================
// 与 HarmonyOS 版 services/credit/LdcCreditSession.ets 对齐。
// 登录态只看 linux_do_credit_session_id 这枚 cookie 在不在,值不读、不存、不打日志。
// 余额缓存 5 分钟:个人中心每次进入都起一个 webview 代价太大,窗口内直接用缓存。
// ==========================================================================

object LdcCreditSession {
    private const val CACHE_TTL_MS = 5 * 60 * 1000L

    private var cachedInfo: LdcUserInfo? = null
    private var cachedAt: Long = 0L

    fun hasSession(): Boolean =
        WebSessionManager.hasCookieForUrl(AppConstants.CREDIT_BASE_URL, AppConstants.CREDIT_SESSION_COOKIE)

    /** 不看新鲜度:先把上次的数字画出来,随后后台刷新覆盖。 */
    fun getCachedInfo(): LdcUserInfo? = cachedInfo

    fun isCacheFresh(now: Long = System.currentTimeMillis()): Boolean =
        cachedInfo != null && now - cachedAt < CACHE_TTL_MS

    fun setCachedInfo(info: LdcUserInfo, now: Long = System.currentTimeMillis()) {
        cachedInfo = info
        cachedAt = now
    }

    fun clearCache() {
        cachedInfo = null
        cachedAt = 0L
    }
}
