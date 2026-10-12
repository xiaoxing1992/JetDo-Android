package com.jetdo.android.services.auth

import android.content.Context
import android.widget.Toast
import com.jetdo.android.R
import com.jetdo.android.common.constants.AppConstants
import com.jetdo.android.services.network.ArkWebNetworkBridge
import com.jetdo.android.services.network.WebFetchScriptFactory
import com.jetdo.android.services.session.LoginGate
import com.jetdo.android.services.session.SessionChecker
import com.jetdo.android.services.session.SessionEventBus
import timber.log.Timber

// ==========================================================================
// 站点授权登录流程编排（对齐 FluxDO UserApiKeyLoginFlow）。
//
// 发起：设置页授权入口 → 导航到 App 内 WebView 承载的授权页（BrowserAuthWebPage），
// 由它调用 UserApiKeyLoginManager.buildAuthorizeUrl 打开 /user-api-key/new
// —— 刻意不走 Custom Tabs：设备环境里外部浏览器没有 DoH、还会被厂商安全浏览策略拦，
// 点「授权」直接 403 且深链永不回程（见 BrowserAuthWebPage 顶部注释）。
// 回程：授权后 302 到 discourse://auth_redirect，App 内 WebView 的同名 scheme 钩子
// 把整条 URL 交给 [handleCallback]；外部浏览器仍可用时，MainActivity 的深链分发同样
// 会落到这里 —— 两条路共用同一个入口。
// 1. 解密 payload、校验 nonce（重放的旧回调按 stale 静默忽略）
// 2. 已有登录态：只是补授权 —— key 立即焚毁（scopes 无 write 是纯负债），toast 成功
// 3. 无登录态：用随行 OTP 兑换 _t（POST /session/otp/{otp}，走宿主 WebView fetch
//    —— CSRF/过盾/cookie 落库全部复用既有链路），复查会话翻 LoginGate，key 焚毁
//
// 全程不输密码；登录页开着时，既有 800ms `_t` cookie 轮询会自动检测到新会话并走
// signalLoginSuccess 统一收口。
// ==========================================================================

private const val LOG_TAG = "[JetDo][BrowserAuth]"

sealed interface BrowserAuthResult {
    /** 非本次流程的残留回调，静默忽略（不提示）。 */
    data object Ignored : BrowserAuthResult

    /** 登录成功（本次兑换或此前已登录的补授权）。 */
    data object Success : BrowserAuthResult

    /** 确认是本次流程但失败了，需要提示。 */
    data class Failed(val reasonRes: Int) : BrowserAuthResult
}

object BrowserAuthLoginFlow {

    private var handling = false

    /** 深链回调入口（App 内 WebView 的 scheme 钩子 / MainActivity onCreate·onNewIntent）。 */
    suspend fun handleCallback(activityContext: Context, dataString: String?): BrowserAuthResult {
        if (handling) {
            Timber.i("%s callback reentry, ignore", LOG_TAG)
            return BrowserAuthResult.Ignored
        }
        handling = true
        try {
            return doHandle(activityContext, dataString)
        } catch (e: Exception) {
            Timber.w("%s callback failed msg=%s", LOG_TAG, e.message)
            return BrowserAuthResult.Failed(R.string.auth_redirect_invalid)
        } finally {
            handling = false
        }
    }

    private suspend fun doHandle(context: Context, dataString: String?): BrowserAuthResult {
        return when (val result = UserApiKeyLoginManager.handleAuthRedirect(context, dataString)) {
            is AuthRedirectResult.Stale -> BrowserAuthResult.Ignored
            is AuthRedirectResult.Invalid -> BrowserAuthResult.Failed(R.string.auth_redirect_invalid)
            is AuthRedirectResult.Authorized -> {
                val otp = result.otp
                if (otp == null) {
                    Timber.w("%s authorized but no otp", LOG_TAG)
                    BrowserAuthResult.Failed(R.string.auth_redeem_failed)
                } else if (LoginGate.isLoggedIn(context)) {
                    // 已登录：补授权场景。key 无 write 纯负债，立即焚毁。
                    burnKey(context, result.apiKey)
                    Timber.i("%s re-authorize while logged in, key burned", LOG_TAG)
                    BrowserAuthResult.Success
                } else {
                    redeemAndFinish(context, result.apiKey, otp)
                }
            }
        }
    }

    /**
     * 扫码端：解析另一台设备出示的二维码（明文 key+OTP JSON）→ 兑换 → 焚毁分享 key。
     * 焚毁同时是出示端「已被扫」的信号（其轮询按 key 存活判断）。
     */
    suspend fun handleScannedPayload(context: Context, raw: String): BrowserAuthResult {
        val payload = QrLoginPayload.parse(raw)
            ?: return BrowserAuthResult.Failed(R.string.auth_qr_invalid)
        if (payload.isExpired) {
            Timber.w("%s scanned payload expired", LOG_TAG)
            return BrowserAuthResult.Failed(R.string.auth_qr_expired)
        }
        if (!UserApiKeyCrypto.isValidOtpHex(payload.otp)) {
            return BrowserAuthResult.Failed(R.string.auth_redeem_failed)
        }
        val result = redeemAndFinish(context, payload.apiKey, payload.otp)
        if (result is BrowserAuthResult.Success) {
            burnKey(context, payload.apiKey)
        }
        return result
    }

    /**
     * OTP 兑换 + 会话收口。兑换走宿主 WebView fetch（同源 POST /session/otp/{otp}），
     * Set-Cookie _t 自动落 WebView CookieManager —— 与密码登录同一会话载体。
     * 成功判据不是 302 响应本身，而是复查 /session/current.json 拿到 current_user。
     */
    private suspend fun redeemAndFinish(context: Context, apiKey: String, otp: String): BrowserAuthResult {
        if (!UserApiKeyCrypto.isValidOtpHex(otp)) {
            Timber.w("%s otp format invalid, refuse redeem", LOG_TAG)
            return BrowserAuthResult.Failed(R.string.auth_redeem_failed)
        }
        Timber.i("%s redeeming otp len=%d", LOG_TAG, otp.length)
        // 兑换请求本身对结果不重要（成功路径是 302 → /，fetch 跟随后 200）；
        // 登录态真源是紧随其后的会话复查。
        // 窗口取 AUTH_WRITE_TIMEOUT_MS 而不是通用 15s：这条同样走 buildCsrfScript 骨架、
        // 内部两次串行 fetch（取 CSRF + 发 POST），宿主刚被浏览器唤起、还在过盾时 15s 会误杀。
        runCatching {
            ArkWebNetworkBridge.postFormWithTimeout(
                "/session/otp/$otp",
                "",
                AppConstants.AUTH_WRITE_TIMEOUT_MS
            )
        }.onFailure { Timber.w("%s redeem request error msg=%s", LOG_TAG, it.message) }

        SessionChecker(AuthService(ArkWebNetworkBridge), context).checkCurrentUser()
        SessionEventBus.notifySessionChanged()

        return if (LoginGate.isLoggedIn(context)) {
            Timber.i("%s logged in via browser auth, burn key", LOG_TAG)
            burnKey(context, apiKey)
            BrowserAuthResult.Success
        } else {
            Timber.i("%s redeem finished but session check still anonymous", LOG_TAG)
            BrowserAuthResult.Failed(R.string.auth_redeem_failed)
        }
    }

    /** POST /user-api-key/revoke（带 User-Api-Key 头，无需 CSRF/会话）。失败静默。 */
    private suspend fun burnKey(context: Context, apiKey: String) {
        runCatching {
            ArkWebNetworkBridge.runRawScript(
                WebFetchScriptFactory.createUserApiKeyRevokeScript(apiKey)
            )
        }.onFailure {
            Timber.w("%s revoke failed (ignored) msg=%s", LOG_TAG, it.message)
        }
    }

    /** MainActivity 侧的统一提示出口（流程无 UI 依赖，结果由 Activity 落 toast）。 */
    fun toastResult(context: Context, result: BrowserAuthResult) {
        val resId = when (result) {
            BrowserAuthResult.Success -> R.string.auth_login_success
            BrowserAuthResult.Ignored -> return
            is BrowserAuthResult.Failed -> result.reasonRes
        }
        Toast.makeText(context, context.getString(resId), Toast.LENGTH_SHORT).show()
    }
}
