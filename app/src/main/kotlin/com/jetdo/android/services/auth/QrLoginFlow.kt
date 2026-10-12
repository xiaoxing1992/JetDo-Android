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
            QrLoginPayload(apiKey = apiKey, otp = otp, username = username, expiresAtMs = null)
        }.onFailure {
            Timber.w("%s create share payload failed msg=%s", LOG_TAG, it.message)
        }
    }

    /**
     * 出示端轮询：分享 key 还活着吗？消失 = 扫码端已登录并焚毁。
     * GET /u/{username}.json 的 user.user_api_keys[] 按 application_name 认领。
     * null = 查询失败（网络抖动），调用方不应当作「已消费」。
     */
    suspend fun isShareKeyAlive(username: String): Boolean? {
        return runCatching {
            val response = ArkWebNetworkBridge.getJson("/u/${URLEncoder.encode(username, "UTF-8")}.json")
            if (!response.success) return@runCatching null
            val body = response.data ?: return@runCatching null
            val user = Json.parseToJsonElement(body).jsonObject["user"]?.jsonObject
                ?: return@runCatching null
            val keys = user["user_api_keys"]?.jsonArray
                ?: return@runCatching false // 序列化器无存活 key 时置 null，视同已消失
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
