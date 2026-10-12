package com.jetdo.android.services.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.URLEncoder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.Base64
import javax.crypto.Cipher

/**
 * UserApiKeyCrypto 纯逻辑单测：RSA 解密往返、PEM、授权 URL 编码、
 * 授权回调解析（nonce 防重放/缺 payload/otp 格式）。
 * 全程无 Android 依赖 —— Cipher/KeyPairGenerator/URI 在 JVM 上语义一致。
 */
class UserApiKeyCryptoTest {

    private fun freshKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun encryptToBase64(keyPair: KeyPair, plaintext: String): String {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, keyPair.public)
        return Base64.getEncoder().encodeToString(
            cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        )
    }

    private fun authUrl(payload: String, otp: String?) =
        "discourse://auth_redirect?payload=" + URLEncoder.encode(payload, "UTF-8") +
            (otp?.let { "&oneTimePassword=" + URLEncoder.encode(it, "UTF-8") } ?: "")

    @Test
    fun `decrypt round trips pkcs1 payload`() {
        val pair = freshKeyPair()
        val secret = """{"key":"abc123","nonce":"n-1"}"""
        val encrypted = encryptToBase64(pair, secret)
        assertEquals(secret, UserApiKeyCrypto.decryptPayload(pair.private, encrypted))
    }

    @Test
    fun `decrypt tolerates whitespace in base64`() {
        val pair = freshKeyPair()
        val secret = """{"key":"k","nonce":"n"}"""
        val raw = encryptToBase64(pair, secret)
        // Ruby Base64.encode64 每 60 字符插换行：人为断行 + 空格模拟 URL 传输损伤。
        val damaged = raw.chunked(9).joinToString("\n").replace("a", "a ").replace("\n", " \n ")
        assertEquals(secret, UserApiKeyCrypto.decryptPayload(pair.private, damaged))
    }

    @Test
    fun `decrypt returns null on garbage input`() {
        val pair = freshKeyPair()
        assertNull(UserApiKeyCrypto.decryptPayload(pair.private, "not-base64!!"))
    }

    @Test
    fun `pem has standard armor and parses back`() {
        val pair = freshKeyPair()
        val pem = UserApiKeyCrypto.pemEncodePublic(pair.public)
        assertTrue(pem.startsWith("-----BEGIN PUBLIC KEY-----"))
        assertTrue(pem.trimEnd().endsWith("-----END PUBLIC KEY-----"))
        val body = pem.lines()
            .filterNot { it.startsWith("-----") }
            .joinToString("")
        val parsed = java.security.KeyFactory.getInstance("RSA").generatePublic(
            java.security.spec.X509EncodedKeySpec(Base64.getMimeDecoder().decode(body))
        )
        assertEquals(pair.public, parsed)
    }

    @Test
    fun `authorize url encodes public key fully and round trips`() {
        val pair = freshKeyPair()
        val pem = UserApiKeyCrypto.pemEncodePublic(pair.public)
        val url = UserApiKeyCrypto.buildAuthorizeUrl(
            baseUrl = "https://linux.do",
            applicationName = "JetDo",
            clientId = "client-1",
            publicKeyPem = pem,
            nonce = "nonce-1"
        )
        assertTrue(url.startsWith("https://linux.do/user-api-key/new?"))
        // PEM 含 +/= 与换行，不允许裸出现在 URL 里。
        val query = URI(url).rawQuery
        assertNotNull(query)
        assertFalse("public_key must be url-encoded", query!!.contains("BEGIN PUBLIC KEY"))
        val params = query.split('&').mapNotNull {
            val eq = it.indexOf('=')
            if (eq <= 0) null else it.substring(0, eq) to java.net.URLDecoder.decode(it.substring(eq + 1), "UTF-8")
        }.toMap()
        assertEquals(pem, params["public_key"])
        assertEquals("one_time_password", params["scopes"])
        assertEquals("discourse://auth_redirect", params["auth_redirect"])
        assertEquals("nonce-1", params["nonce"])
    }

    @Test
    fun `parse auth redirect accepts fresh callback with otp`() {
        val pair = freshKeyPair()
        val nonce = "n-abc"
        val payloadJson = """{"key":"key-1","nonce":"$nonce"}"""
        val otp = "0a1b2c3d"
        val url = authUrl(encryptToBase64(pair, payloadJson), encryptToBase64(pair, otp))
        val result = UserApiKeyCrypto.parseAuthRedirect(pair.private, nonce, url)
        assertTrue(result is AuthRedirectResult.Authorized)
        assertEquals("key-1", (result as AuthRedirectResult.Authorized).apiKey)
        assertEquals(otp, result.otp)
    }

    @Test
    fun `parse auth redirect without oneTimePassword yields null otp`() {
        val pair = freshKeyPair()
        val nonce = "n-1"
        val url = authUrl(encryptToBase64(pair, """{"key":"k","nonce":"$nonce"}"""), null)
        val result = UserApiKeyCrypto.parseAuthRedirect(pair.private, nonce, url)
        assertTrue(result is AuthRedirectResult.Authorized)
        assertNull((result as AuthRedirectResult.Authorized).otp)
    }

    @Test
    fun `replayed callback with mismatched nonce is stale`() {
        val pair = freshKeyPair()
        val url = authUrl(encryptToBase64(pair, """{"key":"k","nonce":"old"}"""), null)
        assertEquals(
            AuthRedirectResult.Stale,
            UserApiKeyCrypto.parseAuthRedirect(pair.private, "different", url)
        )
    }

    @Test
    fun `callback without pending nonce is stale`() {
        val pair = freshKeyPair()
        val url = authUrl(encryptToBase64(pair, """{"key":"k","nonce":"n"}"""), null)
        assertEquals(
            AuthRedirectResult.Stale,
            UserApiKeyCrypto.parseAuthRedirect(pair.private, null, url)
        )
    }

    @Test
    fun `missing payload and garbage payload are stale`() {
        val pair = freshKeyPair()
        assertEquals(
            AuthRedirectResult.Stale,
            UserApiKeyCrypto.parseAuthRedirect(pair.private, "n", "discourse://auth_redirect")
        )
        assertEquals(
            AuthRedirectResult.Stale,
            UserApiKeyCrypto.parseAuthRedirect(pair.private, "n", authUrl("%%%garbage%%%", null))
        )
    }

    @Test
    fun `other schemes and hosts are stale`() {
        val pair = freshKeyPair()
        assertEquals(
            AuthRedirectResult.Stale,
            UserApiKeyCrypto.parseAuthRedirect(pair.private, "n", "jetdo://notification")
        )
        assertEquals(
            AuthRedirectResult.Stale,
            UserApiKeyCrypto.parseAuthRedirect(pair.private, "n", "discourse://other")
        )
    }

    @Test
    fun `missing private key is stale not crash`() {
        assertEquals(
            AuthRedirectResult.Stale,
            UserApiKeyCrypto.parseAuthRedirect(null, "n", "discourse://auth_redirect?payload=abc")
        )
    }

    @Test
    fun `non hex otp rejected as invalid`() {
        val pair = freshKeyPair()
        val nonce = "n-1"
        val url = authUrl(
            encryptToBase64(pair, """{"key":"k","nonce":"$nonce"}"""),
            encryptToBase64(pair, "zz-not-hex")
        )
        assertTrue(UserApiKeyCrypto.parseAuthRedirect(pair.private, nonce, url) is AuthRedirectResult.Invalid)
    }

    @Test
    fun `otp hex validation`() {
        assertTrue(UserApiKeyCrypto.isValidOtpHex("0a1b2c3d"))
        assertFalse(UserApiKeyCrypto.isValidOtpHex(""))
        assertFalse(UserApiKeyCrypto.isValidOtpHex("ABCDEF"))
        assertFalse(UserApiKeyCrypto.isValidOtpHex("12 34"))
    }
}
