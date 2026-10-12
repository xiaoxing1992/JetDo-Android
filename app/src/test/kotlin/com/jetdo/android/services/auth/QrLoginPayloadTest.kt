package com.jetdo.android.services.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 扫码登录载荷：编码/解析往返、版本与过期校验。 */
class QrLoginPayloadTest {

    @Test
    fun `encode parse round trips`() {
        val payload = QrLoginPayload(apiKey = "key-1", otp = "0a1b", username = "neo")
        val raw = QrLoginPayload.encode(payload)
        val parsed = QrLoginPayload.parse(raw)
        assertEquals(payload, parsed)
    }

    @Test
    fun `parse rejects non jetdo content`() {
        assertNull(QrLoginPayload.parse("https://linux.do/t/123"))
        assertNull(QrLoginPayload.parse(""))
        assertNull(QrLoginPayload.parse("not json"))
    }

    @Test
    fun `parse rejects unknown version and blank fields`() {
        val raw = """{"v":99,"k":"key","o":"0a","u":"neo"}"""
        assertNull(QrLoginPayload.parse(raw))
        assertNull(QrLoginPayload.parse("""{"v":1,"k":"","o":"0a","u":"neo"}"""))
        assertNull(QrLoginPayload.parse("""{"v":1,"k":"key","o":"","u":"neo"}"""))
    }

    @Test
    fun `expiry gate works`() {
        val fresh = QrLoginPayload(apiKey = "k", otp = "0a", username = "u", expiresAtMs = null)
        assertFalse(fresh.isExpired)
        val future = QrLoginPayload(
            apiKey = "k", otp = "0a", username = "u",
            expiresAtMs = System.currentTimeMillis() + 60_000
        )
        assertFalse(future.isExpired)
        val stale = QrLoginPayload(
            apiKey = "k", otp = "0a", username = "u",
            expiresAtMs = System.currentTimeMillis() - 60_000
        )
        assertTrue(stale.isExpired)
    }

    @Test
    fun `encode carries expiry into payload`() {
        // 过期时间必须随二维码走：扫入端靠它自己拒收过期码，不依赖出示端存活去 revoke。
        val exp = System.currentTimeMillis() + QrLoginFlow.OTP_WINDOW_MS
        val raw = QrLoginPayload.encode(
            QrLoginPayload(apiKey = "k", otp = "0a", username = "u", expiresAtMs = exp)
        )
        assertTrue("载荷 JSON 必须带 exp 字段", raw.contains("\"exp\""))
        assertEquals(exp, QrLoginPayload.parse(raw)?.expiresAtMs ?: -1L)
    }

    @Test
    fun `otp hex validation shared with browser auth`() {
        assertTrue(UserApiKeyCrypto.isValidOtpHex("0a1b2c3d"))
        assertFalse(UserApiKeyCrypto.isValidOtpHex("0a1b2c3g"))
    }
}
