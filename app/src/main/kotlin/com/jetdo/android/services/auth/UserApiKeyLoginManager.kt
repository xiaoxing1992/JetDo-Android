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
private const val KEY_PENDING_NONCE = "userApiKeyPendingNonce"
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
     * 确保 RSA 密钥对存在，返回公钥 PEM。私钥存 PKCS8 Base64。
     * 密钥生成数百毫秒，已在 [Dispatchers.Default] 上执行。
     */
    suspend fun ensurePublicKeyPem(context: Context): String = withContext(Dispatchers.Default) {
        prefs(context).getString(KEY_PRIVATE_KEY, null)?.let { stored ->
            runCatching {
                val kf = java.security.KeyFactory.getInstance("RSA")
                val spec = java.security.spec.PKCS8EncodedKeySpec(
                    java.util.Base64.getDecoder().decode(stored)
                )
                val publicKey = kf.generatePrivate(spec)
                    // 从私钥推公钥：RSAPrivateCrtKey 才带 modulus/publicExponent
                    .let { it as java.security.interfaces.RSAPrivateCrtKey }
                    .let {
                        java.security.KeyFactory.getInstance("RSA").generatePublic(
                            java.security.spec.RSAPublicKeySpec(it.modulus, it.publicExponent)
                        )
                    }
                return@withContext UserApiKeyCrypto.pemEncodePublic(publicKey)
            }.onFailure {
                Timber.w("%s stored private key unreadable, regenerate. msg=%s", LOG_TAG, it.message)
                prefs(context).edit().remove(KEY_PRIVATE_KEY).apply()
            }
        }
        val pair: KeyPair = UserApiKeyCrypto.generateKeyPair()
        prefs(context).edit()
            .putString(
                KEY_PRIVATE_KEY,
                java.util.Base64.getEncoder().encodeToString(pair.private.encoded)
            )
            .apply()
        UserApiKeyCrypto.pemEncodePublic(pair.public)
    }

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
     */
    suspend fun buildAuthorizeUrl(context: Context): String {
        val publicKeyPem = ensurePublicKeyPem(context)
        val clientId = ensureClientId(context, KEY_CLIENT_ID)
        val nonce = UUID.randomUUID().toString()
        prefs(context).edit().putString(KEY_PENDING_NONCE, nonce).apply()
        Timber.i("%s authorize url built clientId=%s nonce=%s", LOG_TAG, clientId, nonce.take(8))
        return UserApiKeyCrypto.buildAuthorizeUrl(
            baseUrl = AppConstants.BASE_URL,
            applicationName = APPLICATION_NAME,
            clientId = clientId,
            publicKeyPem = publicKeyPem,
            nonce = nonce
        )
    }

    /** 授权回调解析（含 nonce 比对）。Stale 结果调用方静默忽略。 */
    fun handleAuthRedirect(context: Context, dataString: String?): AuthRedirectResult {
        val pendingNonce = prefs(context).getString(KEY_PENDING_NONCE, null)
        val result = UserApiKeyCrypto.parseAuthRedirect(loadPrivateKey(context), pendingNonce, dataString)
        if (result is AuthRedirectResult.Authorized) {
            // 消费 nonce：此后同一回调重放（冷启动重放/系统补发）都会被判 stale。
            prefs(context).edit().remove(KEY_PENDING_NONCE).apply()
            Timber.i("%s auth redirect accepted hasOtp=%b", LOG_TAG, result.otp != null)
        } else {
            Timber.i("%s auth redirect not consumed (%s)", LOG_TAG, result::class.simpleName)
        }
        return result
    }

    /** 扫码出示端专用 client_id：与浏览器授权隔离，重新生成会撤销上一枚分享 key。 */
    fun ensureQrClientId(context: Context): String = ensureClientId(context, KEY_QR_CLIENT_ID)
}
