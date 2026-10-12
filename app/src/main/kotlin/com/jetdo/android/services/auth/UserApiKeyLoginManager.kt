package com.jetdo.android.services.auth

import android.content.Context
import com.jetdo.android.common.constants.AppConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.security.KeyPair
import java.security.PrivateKey
import java.util.UUID

// ==========================================================================
// 浏览器授权登录的 Android 侧持久化薄层：RSA 私钥 / 待处理 nonce / client_id。
// 纯逻辑在 [UserApiKeyCrypto]（JVM 可测），这里只管「存取 + 组装」。
//
// nonce 为什么落盘：冷启动 getInitialLink 会重放上次的 auth_redirect 深链，
// 内存态 nonce 活不过进程重启 —— 不落盘的话每次冷启动都会把重放回调误报成失败。
// ==========================================================================

private const val LOG_TAG = "[JetDo][BrowserAuth]"

private const val PREF_STORE = "app_settings"
private const val KEY_PRIVATE_KEY = "userApiKeyPrivateKey"
private const val KEY_PUBLIC_PEM = "userApiKeyPublicPem"
private const val KEY_PENDING_NONCES = "userApiKeyPendingNonces"

/** 同时保留的待处理 nonce 上限。入口重复点击不会互相顶掉，见 [buildAuthorizeUrl]。 */
private const val MAX_PENDING_NONCES = 5
private const val KEY_CLIENT_ID = "userApiKeyClientId"
private const val KEY_QR_CLIENT_ID = "userApiKeyQrClientId"

object UserApiKeyLoginManager {

    /** 授权页展示的应用名（用户在浏览器上看到「JetDo 请求访问」）。 */
    const val APPLICATION_NAME = "JetDo"

    /** 扫码登录出示端创建的 key 用独立应用名，出示端轮询按它识别存活。 */
    const val QR_APPLICATION_NAME = "JetDo（扫码登录）"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_STORE, Context.MODE_PRIVATE)

    /**
     * 确保 RSA 密钥对存在，返回公钥 PEM。
     *
     * ⚠️ 公钥必须**单独落盘**，不能从私钥反推。Android 上
     * `KeyFactory.generatePrivate(PKCS8EncodedKeySpec)` 返回的是 Conscrypt 的
     * `OpenSSLRSAPrivateKey`，它**不实现** `java.security.interfaces.RSAPrivateCrtKey`
     * —— 真机实测报 `OpenSSLRSAPrivateKey cannot be cast to RSAPrivateCrtKey`，
     * 拿不到 `publicExponent` 就推不出公钥。
     *
     * 原先正是走这条反推路径，于是**每次调用都判「私钥不可读」→ 删旧私钥 →
     * 重新生成一对**。后果不是"多花几百毫秒"这么轻：密钥对因此变得**不稳定**——
     * 只要在浏览器授权发起之后、回调到达之前再触发一次本函数（出码、二次点击入口
     * 都会），上一步发出去的公钥就作废了，而服务端返回的 payload 是用那把旧公钥加密的，
     * 现在的私钥解不开 → 判 Stale 静默忽略 → 用户侧表现成「授权完返回 App 什么都没发生」。
     * 现在改成幂等：有公钥就直接用，缺失才补/生成。
     */
    suspend fun ensurePublicKeyPem(context: Context): String = withContext(Dispatchers.Default) {
        prefs(context).getString(KEY_PUBLIC_PEM, null)?.takeIf { it.isNotBlank() }?.let {
            return@withContext it
        }
        // 存量数据（旧版本只落了私钥）：能反推出公钥就补上并沿用原密钥对，
        // 推不出（Android 上的常态，见上）才生成新的一对。
        val recovered = prefs(context).getString(KEY_PRIVATE_KEY, null)?.let { stored ->
            recoverPublicPemFromStoredPrivateKey(stored)
        }
        if (recovered != null) {
            prefs(context).edit().putString(KEY_PUBLIC_PEM, recovered).apply()
            return@withContext recovered
        }
        val pair: KeyPair = UserApiKeyCrypto.generateKeyPair()
        prefs(context).edit()
            .putString(
                KEY_PRIVATE_KEY,
                java.util.Base64.getEncoder().encodeToString(pair.private.encoded)
            )
            .putString(KEY_PUBLIC_PEM, UserApiKeyCrypto.pemEncodePublic(pair.public))
            .apply()
        UserApiKeyCrypto.pemEncodePublic(pair.public)
    }

    /**
     * 尽力从存量私钥反推公钥 PEM。仅在 `RSAPrivateCrtKey` 分支能成
     * （JVM/BouncyCastle 上成立，Android Conscrypt 上返回 null），失败返回 null。
     */
    private fun recoverPublicPemFromStoredPrivateKey(stored: String): String? = runCatching {
        val spec = java.security.spec.PKCS8EncodedKeySpec(
            java.util.Base64.getDecoder().decode(stored)
        )
        val privateKey = java.security.KeyFactory.getInstance("RSA").generatePrivate(spec)
        val crt = privateKey as? java.security.interfaces.RSAPrivateCrtKey ?: return null
        val publicKey = java.security.KeyFactory.getInstance("RSA").generatePublic(
            java.security.spec.RSAPublicKeySpec(crt.modulus, crt.publicExponent)
        )
        UserApiKeyCrypto.pemEncodePublic(publicKey)
    }.getOrNull()

    /** 解密授权 payload / 创建分享 key 都要用：RSA 私钥。可能为 null（尚未生成）。 */
    fun loadPrivateKey(context: Context): PrivateKey? =
        prefs(context).getString(KEY_PRIVATE_KEY, null)?.let { stored ->
            runCatching {
                val kf = java.security.KeyFactory.getInstance("RSA")
                kf.generatePrivate(
                    java.security.spec.PKCS8EncodedKeySpec(
                        java.util.Base64.getDecoder().decode(stored)
                    )
                )
            }.getOrNull()
        }

    private fun ensureClientId(context: Context, key: String): String {
        prefs(context).getString(key, null)?.takeIf { it.isNotEmpty() }?.let { return it }
        val fresh = UUID.randomUUID().toString()
        prefs(context).edit().putString(key, fresh).apply()
        return fresh
    }

    /**
     * 构建授权 URL 并记下待处理 nonce。
     * client_id 稳定复用：重新生成会让服务端视为新应用，旧授权在用户 Apps 列表里成孤儿。
     *
     * nonce **追加**而不是覆盖：用户「点了没反应又点一次」是常态，覆盖会让第一次那发
     * 授权回来的回调失配、被静默判成 Stale（表现＝"授权完返回 App 什么都没发生"）。
     * 只保留最近 [MAX_PENDING_NONCES] 枚，避免无限增长。
     */
    suspend fun buildAuthorizeUrl(context: Context): String {
        val publicKeyPem = ensurePublicKeyPem(context)
        val clientId = ensureClientId(context, KEY_CLIENT_ID)
        val nonce = UUID.randomUUID().toString()
        appendPendingNonce(context, nonce)
        Timber.i("%s authorize url built clientId=%s nonce=%s pending=%d", LOG_TAG, clientId, nonce.take(8), readPendingNonces(context).size)
        return UserApiKeyCrypto.buildAuthorizeUrl(
            baseUrl = AppConstants.BASE_URL,
            applicationName = APPLICATION_NAME,
            clientId = clientId,
            publicKeyPem = publicKeyPem,
            nonce = nonce
        )
    }

    /** 待处理 nonce（逗号分隔，按写入顺序）。 */
    private fun readPendingNonces(context: Context): List<String> =
        prefs(context).getString(KEY_PENDING_NONCES, null)
            ?.split(',')
            ?.filter { it.isNotBlank() }
            .orEmpty()

    private fun appendPendingNonce(context: Context, nonce: String) {
        val next = (readPendingNonces(context) + nonce).takeLast(MAX_PENDING_NONCES)
        prefs(context).edit().putString(KEY_PENDING_NONCES, next.joinToString(",")).apply()
    }

    /** 本次回调已被接受：所有挂起 nonce 一起作废（授权流程已结束，剩下的也用不上了）。 */
    private fun clearPendingNonces(context: Context) {
        prefs(context).edit().remove(KEY_PENDING_NONCES).apply()
    }

    /** 授权回调解析（含 nonce 比对）。Stale 结果调用方静默忽略。 */
    fun handleAuthRedirect(context: Context, dataString: String?): AuthRedirectResult {
        val pendingNonces = readPendingNonces(context)
        val result = UserApiKeyCrypto.parseAuthRedirect(
            loadPrivateKey(context),
            pendingNonces,
            dataString
        )
        if (result is AuthRedirectResult.Authorized) {
            // 消费 nonce：此后同一回调重放（冷启动重放/系统补发）都会被判 stale。
            clearPendingNonces(context)
            Timber.i("%s auth redirect accepted hasOtp=%b", LOG_TAG, result.otp != null)
        } else {
            Timber.i(
                "%s auth redirect not consumed (%s) pending=%d",
                LOG_TAG,
                result::class.simpleName,
                pendingNonces.size
            )
        }
        return result
    }

    /** 扫码出示端专用 client_id：与浏览器授权隔离，重新生成会撤销上一枚分享 key。 */
    fun ensureQrClientId(context: Context): String = ensureClientId(context, KEY_QR_CLIENT_ID)
}
