package com.jetdo.android.services.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLEncoder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.util.Base64
import javax.crypto.Cipher

// ==========================================================================
// Discourse User API Key 浏览器授权登录的**纯逻辑层**（无 Android 依赖，JVM 可测）。
//
// 流程（对齐 FluxDO UserApiKeyService/UserApiKeyLoginFlow，逐条读过其源码）：
// 1. 端上生成 RSA-2048 密钥对，公钥转 SubjectPublicKeyInfo PEM
// 2. 拉起浏览器打开 GET /user-api-key/new?application_name&client_id&scopes&public_key&nonce&auth_redirect
//    - scopes 只申请 one_time_password：linux.do 的 allow_user_api_key_scopes 收窄过，
//      带 write 会因 allowed_scopes.superset? 校验失败直接报 generic_error
//    - auth_redirect 固定 discourse://auth_redirect（站点 allowed_user_api_auth_redirects
//      内置放行的是它；自定义 scheme 要站点加白，不可控）
// 3. 用户在浏览器一键授权 → 302 到 discourse://auth_redirect?payload=<b64>&oneTimePassword=<b64>
//    - payload = Base64(RSA-PKCS1(JSON{key,nonce,...}))，私钥解密
//    - oneTimePassword = Base64(RSA-PKCS1(hex otp))，是「登录令牌」而非 API key 本体
// 4. POST /session/otp/{otp}（带 CSRF + X-Requested-With）→ 服务端 log_on_user →
//    Set-Cookie _t —— 拿到与密码登录完全相同的会话，收口走既有 SessionChecker
// 5. linux.do 的 key 无 write scope，落地即纯负债（永久有效+零用途），兑换成功后立即 revoke
//
// 关键坑（全部来自 FluxDO 实测注释）：
// - 相关性 nonce 必须持久化：冷启动 getInitialLink 会**重放**上次的 auth_redirect，
//   内存态 nonce 丢失会把每次重启都误报成「回调解析失败」——重放的旧回调按 stale 静默忽略。
// - Ruby Base64.encode64 每 60 字符插换行，URL 传输还可能引入空格：解密前先剥空白。
// ==========================================================================

/** 授权回调的三种结局。stale 表示「非本次流程」，调用方应静默忽略。 */
sealed interface AuthRedirectResult {
    /** 冷启动重放/nonce 不匹配/缺 payload 等残留场景，静默忽略。 */
    data object Stale : AuthRedirectResult

    /** 确认是本次流程，但内容不合法（无 key、otp 缺失等），需要提示用户。 */
    data class Invalid(val reason: String) : AuthRedirectResult

    /** 拿到 API key 与可选 OTP。 */
    data class Authorized(val apiKey: String, val otp: String?) : AuthRedirectResult
}

/** 解密后的授权 payload（Discourse 返回 {key, nonce, ...}）。 */
@Serializable
private data class AuthPayload(
    @SerialName("key") val key: String? = null,
    @SerialName("nonce") val nonce: String? = null
)

object UserApiKeyCrypto {

    const val SCOPES = "one_time_password"
    const val AUTH_REDIRECT = "discourse://auth_redirect"

    private val json = Json { ignoreUnknownKeys = true }

    /** RSA-2048 密钥对。耗时数百毫秒，调用方放后台线程。 */
    fun generateKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    /** 公钥 → SubjectPublicKeyInfo PEM（Ruby OpenSSL::PKey::RSA 直接可读）。 */
    fun pemEncodePublic(publicKey: PublicKey): String {
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(publicKey.encoded)
        return "-----BEGIN PUBLIC KEY-----\n$body\n-----END PUBLIC KEY-----"
    }

    /** Base64(RSA-PKCS1(payload)) → UTF-8 明文。剥空白（Ruby Base64 带换行）。 */
    fun decryptPayload(privateKey: PrivateKey, base64Payload: String): String? = runCatching {
        val normalized = base64Payload.replace(Regex("\\s+"), "")
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        String(cipher.doFinal(Base64.getDecoder().decode(normalized)), Charsets.UTF_8)
    }.getOrNull()

    /** 解密出的 payload JSON → (key, nonce)。解析失败返回 null。 */
    fun parsePayloadJson(json: String): Pair<String, String>? = runCatching {
        val payload = this.json.decodeFromString<AuthPayload>(json)
        val key = payload.key
        val nonce = payload.nonce
        if (key.isNullOrEmpty() || nonce.isNullOrEmpty()) null else key to nonce
    }.getOrNull()

    /** OTP 是 hex 串（服务端路由约束 [0-9a-f]+），格式不对直接拒绝兑换。 */
    fun isValidOtpHex(otp: String): Boolean = otp.isNotEmpty() && otp.all { it in '0'..'9' || it in 'a'..'f' }

    /**
     * 构建授权页 URL。public_key 里的 +/= 与换行必须走标准 URL 编码——
     * PEM 按行拆了也没用，Ruby 端拿到的必须是原样还原的单串。
     */
    fun buildAuthorizeUrl(
        baseUrl: String,
        applicationName: String,
        clientId: String,
        publicKeyPem: String,
        nonce: String,
        authRedirect: String = AUTH_REDIRECT,
        scopes: String = SCOPES
    ): String {
        val enc = { value: String ->
            URLEncoder.encode(value, Charsets.UTF_8.name())
        }
        return baseUrl.trimEnd('/') + "/user-api-key/new" +
            "?application_name=" + enc(applicationName) +
            "&client_id=" + enc(clientId) +
            "&scopes=" + enc(scopes) +
            "&public_key=" + enc(publicKeyPem) +
            "&nonce=" + enc(nonce) +
            "&auth_redirect=" + enc(authRedirect)
    }

    /**
     * 授权回调完整解析管线：scheme/host 判定 → payload 解密 → payload JSON → nonce 比对 →
     * key 提取 → oneTimePassword 解密。
     *
     * nonce 规则（对齐 FluxDO）：
     * - 无待处理 nonce 或与 payload 不符 → [AuthRedirectResult.Stale]（冷启动重放旧深链）
     * - 缺 payload / 解密失败（多为公钥已轮换的残留回调）→ 同样按 Stale 静默
     * - nonce 匹配后才算「本次流程」，后续失败才是 [AuthRedirectResult.Invalid]
     */
    fun parseAuthRedirect(
        privateKey: PrivateKey?,
        pendingNonce: String?,
        dataString: String?
    ): AuthRedirectResult {
        val uri = dataString?.trim()?.let { runCatching { URI(it) }.getOrNull() }
            ?: return AuthRedirectResult.Stale
        if (!"discourse".equals(uri.scheme, ignoreCase = true)) return AuthRedirectResult.Stale
        // ⚠️ 不能用 uri.host：java.net.URI 按 RFC 2396 解析 host，"auth_redirect" 带
        // 下划线不是合法 hostname → getHost() 返回 null（jetdo://notification 纯字母
        // 所以没踩过）。authority 才是原始值。
        val host = uri.host ?: uri.authority
        if (!"auth_redirect".equals(host, ignoreCase = true)) return AuthRedirectResult.Stale

        val params = uri.rawQuery?.split('&')?.mapNotNull { pair ->
            val eq = pair.indexOf('=')
            if (eq <= 0) null
            else decode(pair.substring(0, eq)) to decode(pair.substring(eq + 1))
        }?.toMap() ?: emptyMap()

        val payloadParam = params["payload"]
        if (payloadParam.isNullOrEmpty()) return AuthRedirectResult.Stale

        if (privateKey == null) return AuthRedirectResult.Stale
        val decrypted = decryptPayload(privateKey, payloadParam) ?: return AuthRedirectResult.Stale
        val (key, nonce) = parsePayloadJson(decrypted) ?: return AuthRedirectResult.Stale

        if (pendingNonce.isNullOrEmpty() || nonce != pendingNonce) return AuthRedirectResult.Stale

        val otp = params["oneTimePassword"]?.takeIf { it.isNotEmpty() }?.let {
            decryptPayload(privateKey, it)
        }
        if (otp != null && !isValidOtpHex(otp)) {
            return AuthRedirectResult.Invalid("otp format")
        }
        return AuthRedirectResult.Authorized(apiKey = key, otp = otp)
    }

    private fun decode(value: String): String =
        runCatching { java.net.URLDecoder.decode(value, Charsets.UTF_8) }.getOrDefault(value)
}
