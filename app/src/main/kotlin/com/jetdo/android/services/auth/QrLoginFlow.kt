package com.jetdo.android.services.auth

import android.content.Context
import com.jetdo.android.services.network.ArkWebNetworkBridge
import com.jetdo.android.services.network.WebFetchScriptFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import java.net.URI
import java.net.URLEncoder
import java.util.UUID

// ==========================================================================
// 扫码登录（设备间迁移，对齐 FluxDO QrLoginService）：
// 已登录设备 POST /user-api-key 创建一枚 one_time_password 分享 key →
// 服务端返回 redirect_url（含 RSA 加密的 payload + oneTimePassword）→
// 出示端解密得到 key + OTP 明文 → 编码成紧凑 JSON 画成二维码；
// 待登录设备扫码 → 校验 exp → POST /session/otp/{otp} 兑换自己的 _t →
// 兑换成功后焚毁分享 key（出示端轮询 key 存活即知「已被扫」）。
//
// 二维码内容是**明文 key+OTP**（一次性、10 分钟 TTL、兑换即焚），泄露窗口极小；
// 语义上等价于「把登录态递过去」，出示端用防截屏提示兜底（见 UI 层）。
// ==========================================================================

@Serializable
data class QrLoginPayload(
    @SerialName("v") val version: Int = CURRENT_VERSION,
    @SerialName("k") val apiKey: String,
    @SerialName("o") val otp: String,
    @SerialName("u") val username: String,
    @SerialName("exp") val expiresAtMs: Long? = null
) {
    val isExpired: Boolean
        get() = expiresAtMs != null && expiresAtMs > 0 && System.currentTimeMillis() > expiresAtMs

    companion object {
        const val CURRENT_VERSION = 1
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun encode(payload: QrLoginPayload): String = json.encodeToString(payload)

        /** 解析扫码内容；非本 App 的二维码 / 版本不识别 → null。 */
        fun parse(raw: String): QrLoginPayload? = runCatching {
            val payload = json.decodeFromString<QrLoginPayload>(raw.trim())
            if (payload.version != CURRENT_VERSION) null
            else if (payload.apiKey.isEmpty() || payload.otp.isEmpty()) null
            else payload
        }.getOrNull()
    }
}

object QrLoginFlow {

    private const val LOG_TAG = "[JetDo][QrLogin]"

    /**
     * 二维码有效期（毫秒）。**唯一真源** —— 出示端 UI 的倒计时读数也取它，别各写一份。
     *
     * 这个值必须烘进二维码载荷（见 [createSharePayload] 的 expiresAtMs），不能只做 UI 倒数：
     * 服务端的 user-api-key 本身**没有过期机制**，唯一回收手段是出示端主动 revoke。出示端
     * 若在出示后被强杀/崩溃/断网，revoke 就永远不会发生，这枚 key 会一直可用 —— 谁拿到那张
     * 二维码截图都能换到登录态。烘进载荷后，扫入端自己就能拒绝过期码，不依赖出示端存活。
     */
    const val OTP_WINDOW_MS = 10L * 60 * 1000

    /**
     * 出示端：创建分享 key 并解出 (apiKey, otp)。
     * POST /user-api-key（会话 CSRF，Accept json → 200 {redirect_url}），
     * redirect_url 形如 discourse://auth_redirect?payload=...&oneTimePassword=...，
     * 两者都用本机 RSA 私钥解密 —— 与浏览器授权回调同一套解密管线。
     */
    suspend fun createSharePayload(
        context: Context,
        username: String
    ): Result<QrLoginPayload> = withContext(Dispatchers.Default) {
        runCatching {
            val publicKeyPem = UserApiKeyLoginManager.ensurePublicKeyPem(context)
            val clientId = UserApiKeyLoginManager.ensureQrClientId(context)
            val nonce = UUID.randomUUID().toString()
            val formBody = listOf(
                "application_name" to UserApiKeyLoginManager.QR_APPLICATION_NAME,
                "client_id" to clientId,
                "scopes" to UserApiKeyCrypto.SCOPES,
                "public_key" to publicKeyPem,
                "nonce" to nonce,
                "auth_redirect" to UserApiKeyCrypto.AUTH_REDIRECT
            ).joinToString("&") { (k, v) ->
                k + "=" + URLEncoder.encode(v, Charsets.UTF_8.name())
            }
            val raw = ArkWebNetworkBridge.runRawScript(
                WebFetchScriptFactory.createUserApiKeyShareScript(formBody, "/u/$username")
            )
            val envelope = Json.decodeFromString<ShareEnvelope>(raw)
            if (envelope.statusCode !in 200..299) {
                error("share key create http ${envelope.statusCode}")
            }
            val body = envelope.body.orEmpty()
            val redirectUrl = Json.decodeFromString<ShareResponse>(body).redirectUrl
                ?: error("server did not return redirect_url")
            val params = URI(redirectUrl).rawQuery.orEmpty().split('&').mapNotNull { pair ->
                val eq = pair.indexOf('=')
                if (eq <= 0) null
                else java.net.URLDecoder.decode(pair.substring(0, eq), "UTF-8") to
                    java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
            }.toMap()
            val privateKey = UserApiKeyLoginManager.loadPrivateKey(context)
                ?: error("no private key")
            val payloadEnc = params["payload"] ?: error("redirect_url missing payload")
            val otpEnc = params["oneTimePassword"] ?: error("redirect_url missing oneTimePassword")
            val decrypted = UserApiKeyCrypto.decryptPayload(privateKey, payloadEnc)
                ?: error("payload decrypt failed")
            val (apiKey, returnedNonce) = UserApiKeyCrypto.parsePayloadJson(decrypted)
                ?: error("payload json invalid")
            if (returnedNonce != nonce) error("nonce mismatch")
            val otp = UserApiKeyCrypto.decryptPayload(privateKey, otpEnc)?.takeIf {
                UserApiKeyCrypto.isValidOtpHex(it)
            } ?: error("otp decrypt failed")
            Timber.i("%s share key created keyLen=%d otpLen=%d", LOG_TAG, apiKey.length, otp.length)
            QrLoginPayload(
                apiKey = apiKey,
                otp = otp,
                username = username,
                // 过期时间随二维码走，扫入端自己判 —— 出示端被杀也拦得住。
                expiresAtMs = System.currentTimeMillis() + OTP_WINDOW_MS
            )
        }.onFailure {
            Timber.w("%s create share payload failed msg=%s", LOG_TAG, it.message)
        }
    }

    /**
     * 出示端轮询：分享 key 还活着吗？消失 = 扫码端已登录并焚毁。
     * GET /u/{username}.json 的 user.user_api_keys[] 按 application_name 认领。
     *
     * 三态而不是布尔：true=还在，false=确认已被消费，null=查不到（网络/会话异常）。
     * **字段缺失必须归 null 而不是 false** —— `user_api_keys` 只在「看自己」时由
     * UserSerializer 渲染，一旦会话失效或权限变化导致该字段不出现，说明我们什么都不知道，
     * 此时若按 false 处理，弹窗会在 3 秒内直接跳到「已完成」，而对方根本没扫。
     */
    suspend fun isShareKeyAlive(username: String): Boolean? {
        return runCatching {
            val response = ArkWebNetworkBridge.getJson("/u/${URLEncoder.encode(username, "UTF-8")}.json")
            if (!response.success) return@runCatching null
            val body = response.data ?: return@runCatching null
            val user = Json.parseToJsonElement(body).jsonObject["user"]?.jsonObject
                ?: return@runCatching null
            val keys = user["user_api_keys"]?.jsonArray ?: return@runCatching null
            keys.any { it.jsonObject["application_name"]?.jsonPrimitive?.contentOrNull == UserApiKeyLoginManager.QR_APPLICATION_NAME }
        }.getOrNull()
    }

    /** 出示端：撤销未被消费的分享 key（弹层关闭/过期时清理；已被扫入端焚毁时服务端 403，静默）。 */
    suspend fun revokeSharedKey(apiKey: String) {
        runCatching {
            ArkWebNetworkBridge.runRawScript(
                WebFetchScriptFactory.createUserApiKeyRevokeScript(apiKey)
            )
        }.onFailure {
            Timber.w("%s revoke share key failed (ignored) msg=%s", LOG_TAG, it.message)
        }
    }

    @Serializable
    private data class ShareEnvelope(
        @SerialName("statusCode") val statusCode: Int? = null,
        @SerialName("body") val body: String? = null
    )

    @Serializable
    private data class ShareResponse(
        @SerialName("redirect_url") val redirectUrl: String? = null
    )
}
