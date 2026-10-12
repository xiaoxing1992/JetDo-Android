package com.jetdo.android.services.auth

import android.content.Context
import android.widget.Toast
import com.jetdo.android.R
import com.jetdo.android.services.network.ArkWebNetworkBridge
import com.jetdo.android.services.network.WebFetchScriptFactory
import com.jetdo.android.services.session.LoginGate
import com.jetdo.android.services.session.SessionChecker
import com.jetdo.android.services.session.SessionEventBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.browser.customtabs.CustomTabsIntent
import android.net.Uri
import timber.log.Timber

// ==========================================================================
// 浏览器授权登录流程编排（对齐 FluxDO UserApiKeyLoginFlow）。
//
// 发起：登录页按钮 → [start] 拉起系统浏览器（Custom Tabs）打开 /user-api-key/new。
// 回程：授权后 302 到 discourse://auth_redirect，系统深链拉回 App，
// MainActivity 分发到 [handleCallback]：
// 1. 解密 payload、校验 nonce（重放的旧回调按 stale 静默忽略）
// 2. 已有登录态：只是补授权 —— key 立即焚毁（scopes 无 write 是纯负债），toast 成功
// 3. 无登录态：用随行 OTP 兑换 _t（POST /session/otp/{otp}，走宿主 WebView fetch
//    —— CSRF/过盾/cookie 落库全部复用既有链路），复查会话翻 LoginGate，key 焚毁
//
// 全程无可见 WebView、不输密码；登录页开着时，既有 800ms `_t` cookie 轮询会自动
// 检测到新会话并走 signalLoginSuccess 统一收口。
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

    /**
     * 拉起系统浏览器打开授权页。返回是否成功拉起。
     * RSA 密钥对首次生成在 Default 线程（可能数百毫秒）。
     *
     * 用 Custom Tabs 而不是 ACTION_VIEW external：Chrome 对已建立 App Links 关联的
     * 域名会把 external 打开的链接直接弹回本 App（「浏览器一闪就跳回」）；Custom Tabs
     * 在浏览器上下文内打开且共享 Chrome cookie，复用浏览器已有登录态（OAuth 标准做法）。
     */
    suspend fun start(context: Context): Boolean = withContext(Dispatchers.Main) {
        runCatching {
            val url = UserApiKeyLoginManager.buildAuthorizeUrl(context)
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
                .launchUrl(context, Uri.parse(url))
        }.onFailure {
            Timber.w("%s launch browser failed msg=%s", LOG_TAG, it.message)
        }.isSuccess
    }

    /** 深链回调入口（MainActivity onCreate/onNewIntent 分发）。主线程调用，内部挂起。 */
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
        runCatching { ArkWebNetworkBridge.postForm("/session/otp/$otp", "") }
            .onFailure { Timber.w("%s redeem request error msg=%s", LOG_TAG, it.message) }

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
