package com.jetdo.android.services.auth

import android.content.Context
import com.jetdo.android.common.constants.AppConstants
import com.jetdo.android.services.challenge.ChallengeEventBus
import com.jetdo.android.services.network.ArkWebNetworkBridge
import com.jetdo.android.services.network.ChallengeDetector
import com.jetdo.android.services.network.ChallengeState
import com.jetdo.android.services.network.WebFetchScriptFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
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
     * 信封解析器：**必须容忍未知字段**。
     *
     * buildCsrfScript 回传的信封固定带六个字段
     * （`statusCode` / `contentType` / `cfMitigated` / `body` / `csrfSource` / `csrfLength`），
     * 而这里只关心两个。kotlinx.serialization 的**默认 Json 实例是
     * `ignoreUnknownKeys = false`** —— 写成 `Json.decodeFromString<ShareEnvelope>(raw)`
     * 会在遇到 contentType 时抛 `Unexpected JSON key`，于是**每一次**生成二维码都失败，
     * 而 UI 只有一句「二维码生成失败」，从现象完全看不出是解析问题。
     *
     * 桥内部解析信封（[com.jetdo.android.services.network.ArkWebNetworkBridge] 的
     * parseEnvelope）一直是带这个开关的，这个调用点当初漏了。
     */
    private val envelopeJson = Json { ignoreUnknownKeys = true }

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
     * 创建分享 key 的等待窗口：取共享的 [AppConstants.AUTH_WRITE_TIMEOUT_MS]（45s），
     * 不用 15s 的通用闸门 —— share 脚本内部是两次串行 fetch（取 CSRF + POST /user-api-key），
     * 理由与代价见该常量的注释。
     */
    private const val SHARE_KEY_TIMEOUT_MS = AppConstants.AUTH_WRITE_TIMEOUT_MS

    /**
     * 超时失败在异常 message 里的**稳定标记**。
     *
     * 不用本地化文案当标记：文案会随语言变，UI 层拿它做匹配等于把 i18n 和逻辑绑死
     * （`host not ready` 就是这个模式，见 [com.jetdo.android.services.network.ArkWebNetworkBridge.runRawScript]）。
     * UI 层据此换成给用户看的提示，日志里也是一眼能认的英文。
     */
    internal const val SHARE_KEY_TIMEOUT_TAG = "share key timeout"

    /**
     * 「宿主环境需要过盾」在失败原因里的稳定标记。
     *
     * 出码这条链路走 [ArkWebNetworkBridge.runRawScript]，它**不经过** ArkWebNetworkBridge
     * 里带挑战判定的 convertEnvelope，所以 Cloudflare 挑战得在这里自己认一次并广播 —— 否则
     * App 已有的过盾恢复（ChallengeRecoveryCoordinator 只订阅 ChallengeEventBus）永远不会被
     * 唤醒，用户看到的就只是一句干巴巴的 403，而真实情况是"环境该刷新了，刷新完就能出码"。
     */
    internal const val SHARE_KEY_CHALLENGE_TAG = "share key challenge"

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
                WebFetchScriptFactory.createUserApiKeyShareScript(formBody, "/u/$username"),
                SHARE_KEY_TIMEOUT_MS
            )
            val envelope = parseShareEnvelope(raw)
            // Cloudflare 挑战要单独认出来：它必须「广播 + 触发过盾恢复」，而不是当一次普通
            // HTTP 失败报掉。理由见 SHARE_KEY_CHALLENGE_TAG —— 这条链路绕开了带判定的
            // convertEnvelope，不自己认一次，协调器就永远收不到信号，用户只能自己去点验证。
            val shareChallenge = ChallengeDetector.detectChallenge(
                envelope.statusCode ?: 0,
                envelope.contentType.orEmpty(),
                envelope.body.orEmpty(),
                envelope.cfMitigated.orEmpty()
            )
            if (shareChallenge == ChallengeState.REQUIRED) {
                Timber.w("%s share key create blocked by cloudflare status=%s", LOG_TAG, envelope.statusCode)
                ChallengeEventBus.notifyChallengeRequired(
                    "/user-api-key",
                    envelope.statusCode ?: 0,
                    ChallengeState.REQUIRED
                )
                error(SHARE_KEY_CHALLENGE_TAG)
            }
            // 失败时把原因一起带出来，别只报一个 HTTP 码：
            // - statusCode = 0 是脚本自己 fail（取不到 CSRF / fetch 抛错）→ 详情在 errorMessage
            // - 4xx/5xx 是服务端拒了 → Discourse 的文案（BAD CSRF / 权限不足 / 参数非法）在 body
            if (envelope.statusCode !in 200..299) {
                val detail = envelope.errorMessage?.takeIf { it.isNotEmpty() }
                    ?: envelope.body.orEmpty().take(200)
                error("share key create http ${envelope.statusCode} $detail")
            }
            val body = envelope.body.orEmpty()
            val redirectUrl = parseShareResponse(body).redirectUrl
                ?: error("server did not return redirect_url ${body.take(200)}")
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
        }.recoverCatching { e ->
            // 超时（withTimeout 抛的 TimeoutCancellationException）自带的 message 是 kotlinx 的
            // 英文原文「Timed out waiting for 45000 ms」。它一路透到弹窗上就是用户报的那句
            // 「timed out wait for 15000」—— 对用户毫无意义，也说不清下一步该做什么。
            // 换成稳定标记，由 UI 层翻成可行动的提示（见 SHARE_KEY_TIMEOUT_TAG 的注释）。
            if (e is TimeoutCancellationException) {
                throw IllegalStateException(SHARE_KEY_TIMEOUT_TAG, e)
            }
            throw e
        }.onFailure {
            Timber.w("%s create share payload failed msg=%s", LOG_TAG, it.message)
        }
    }

    /**
     * 出示端轮询：分享 key 还活着吗？消失 = 扫码端已登录并焚毁。
     *
     * 数据源是 Discourse `UserSerializer#user_api_keys`（`/u/{name}.json`），源码里有两个
     * 决定判据怎么写的事实：
     *
     * ```ruby
     * private_attributes ..., :user_api_keys, ...        # 只在「本人 / staff」视角才渲染
     * def user_api_keys                                  # 无存活 key 时返回 nil，不是空数组
     *   keys.length > 0 ? keys : nil
     * end
     * ```
     *
     * 于是有三种「没有」必须分开处理，语义完全不同：
     * - 字段**没渲染** → 拿不到本人视角（会话异常等）→ **不知道**（unknown，继续轮询）
     * - 字段是 **null**   → 服务端明说「一枚存活 key 都没有」→ **已被消费**（false）
     * - 字段是**空数组** → 同上（兼容返回 `[]` 的旧版本）→ **已被消费**（false）
     *
     * 旧的 `?: return false` 把「没渲染」和「已消费」压成同一个 false —— 弹窗一打开 3 秒
     * 就跳到「已完成」，而对方根本没扫。这就是用户报的「对方设备提示已登录成功」。
     */
    suspend fun isShareKeyAlive(username: String): Boolean? {
        return runCatching {
            val response = ArkWebNetworkBridge.getJson("/u/${URLEncoder.encode(username, "UTF-8")}.json")
            if (!response.success) {
                Timber.w("%s alive probe http failed status=%d", LOG_TAG, response.statusCode)
                return@runCatching null
            }
            val body = response.data ?: return@runCatching null
            evaluateShareKeyAlive(body)
        }.getOrNull()
    }

    /**
     * 从 `/u/{name}.json` 响应体判定分享 key 存活（纯逻辑，可单测）。
     * 三态语义见 [isShareKeyAlive] 的注释：字段没渲染 = unknown，null / 空数组 / 不含目标 = 已消费。
     */
    internal fun evaluateShareKeyAlive(body: String): Boolean? {
        val user = runCatching {
            Json.parseToJsonElement(body).jsonObject["user"]?.jsonObject
        }.getOrNull() ?: return null
        val element = user["user_api_keys"] ?: run {
            // 字段根本不在 JSON 里：非本人视角（private_attributes）→ 谁也不知道。
            // 绝不能当成已消费，否则就是「弹窗 3 秒假报已完成」那条假阳性。
            Timber.w("%s alive probe: user_api_keys not rendered, unknown", LOG_TAG)
            return null
        }
        if (element is JsonNull) {
            Timber.i("%s alive probe: no live key -> consumed", LOG_TAG)
            return false
        }
        val keys = runCatching { element.jsonArray }.getOrNull() ?: return null
        if (keys.isEmpty()) {
            Timber.i("%s alive probe: empty key list -> consumed", LOG_TAG)
            return false
        }
        val names = keys.mapNotNull {
            it.jsonObject["application_name"]?.jsonPrimitive?.contentOrNull
        }
        val alive = names.contains(UserApiKeyLoginManager.QR_APPLICATION_NAME)
        Timber.i("%s alive probe count=%d names=%s alive=%s", LOG_TAG, keys.size, names, alive)
        return alive
    }

    /** 出示端：撤销未被消费的分享 key（弹层关闭/过期时清理；已被扫入端焚毁时服务端 403，静默）。 */
    suspend fun revokeSharedKey(apiKey: String) {
        runCatching {
            val raw = ArkWebNetworkBridge.runRawScript(
                WebFetchScriptFactory.createUserApiKeyRevokeScript(apiKey)
            )
            // 原来这里成功即静默，于是「撤销压根没发出去」这类故障在日志里完全不可见 ——
            // 真机滚动日志只看到 create、看不到任何 revoke，key 却一直挂在账号上。
            // 把状态码打出来，撤销从此可验证：
            //   200 = 已回收；403 = 已被扫入端焚毁（正常，服务端不会二次回收）。
            val env = parseShareEnvelope(raw)
            Timber.i(
                "%s revoke share key status=%s body=%s",
                LOG_TAG, env.statusCode, env.body?.take(120)
            )
        }.onFailure {
            Timber.w("%s revoke share key failed (ignored) msg=%s", LOG_TAG, it.message)
        }
    }

    /**
     * 撤销必须跑在一个**不随 UI 走**的 scope 上。
     *
     * 弹窗关闭时会立刻把自己移出组合树；此前用的是弹窗自己的 rememberCoroutineScope，
     * 协程还没来得及调度就跟着被 cancel 了 —— 设备上实测到
     * `revoke share key failed (ignored) msg=rememberCoroutineScope left the composition`。
     * 而服务端 user-api-key 没有过期机制，撤销是唯一回收手段，结果就是这把 key
     * **永久留在账号上**（真机日志里 `JetDo（扫码登录）` 已经攒了两把）。
     *
     * 用 Main.immediate 与 ChallengeRecoveryCoordinator / MathRenderService 同款：
     * runRawScript 要碰 WebView，只能在主线程。
     */
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * fire-and-forget 撤销，供 UI 在「关闭 / 重新生成」时调用 —— 调用之后 UI 马上就没了，
     * 所以这里不能返回 suspend 让调用方 await。
     */
    fun revokeSharedKeyDetached(apiKey: String) {
        cleanupScope.launch { revokeSharedKey(apiKey) }
    }

    /**
     * 桥回传的信封（只声明关心的字段，其余由 [envelopeJson] 容忍）。
     *
     * `errorMessage` 必须声明：脚本**在客户端就失败**时（取不到 CSRF、fetch 抛错）
     * statusCode 是 0、body 是空串，唯一能说明原因的就是这个字段 —— 不带上它，
     * UI 只剩一句「share key create http 0 」，等于什么都没说。
     */
    @Serializable
    internal data class ShareEnvelope(
        @SerialName("statusCode") val statusCode: Int? = null,
        @SerialName("body") val body: String? = null,
        @SerialName("errorMessage") val errorMessage: String? = null,
        // 挑战判定要用（见 SHARE_KEY_CHALLENGE_TAG）。CsrfScriptTemplate 一直在回传它们，
        // 只是这边此前没声明 —— ignoreUnknownKeys 会安静丢掉，于是挑战被当成普通 403。
        @SerialName("contentType") val contentType: String? = null,
        @SerialName("cfMitigated") val cfMitigated: String? = null
    )

    /** 信封解码。internal 供单测直接打 —— 这里踩过坑，必须有回归护栏。 */
    internal fun parseShareEnvelope(raw: String): ShareEnvelope = envelopeJson.decodeFromString(raw)

    @Serializable
    internal data class ShareResponse(
        @SerialName("redirect_url") val redirectUrl: String? = null
    )

    internal fun parseShareResponse(body: String): ShareResponse = envelopeJson.decodeFromString(body)
}
