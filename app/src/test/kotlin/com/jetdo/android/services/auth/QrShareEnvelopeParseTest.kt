package com.jetdo.android.services.auth

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 出示端信封解析 —— 「二维码生成失败」的真根因。
 *
 * CsrfScriptTemplate 回传的信封**固定带六个字段**
 * （statusCode / contentType / cfMitigated / body / csrfSource / csrfLength），
 * 而 ShareEnvelope 只声明了 statusCode/body。kotlinx.serialization 的**默认 Json 实例是
 * `ignoreUnknownKeys = false`** —— 裸 `Json.decodeFromString<ShareEnvelope>(raw)` 会在
 * contentType 上抛 `Unexpected JSON key`，于是**每一次**生成二维码都失败，UI 只有一句
 * 「二维码生成失败」，从现象完全看不出是解析问题（桥内部 parseEnvelope 一直是带开关的，
 * 只有这个调用点漏了）。
 */
class QrShareEnvelopeParseTest {

    /** 与 CsrfScriptTemplate 里 onJsonResult 成功路径的字段逐字对齐。 */
    private val successEnvelope = """
        {"statusCode":200,"contentType":"application/json; charset=utf-8","cfMitigated":"",
         "body":"{\"redirect_url\":\"discourse://auth_redirect?payload=AAA&oneTimePassword=BBB\"}",
         "csrfSource":"session","csrfLength":64}
    """.trimIndent()

    @Test
    fun `envelope with extra fields parses`() {
        val envelope = QrLoginFlow.parseShareEnvelope(successEnvelope)
        assertEquals(200, envelope.statusCode)
        assertEquals(true, envelope.body!!.contains("redirect_url"))
    }

    @Test
    fun `raw default Json rejects the same envelope`() {
        // 根因取证 + 防回退：默认 Json 解析同一份信封必然抛。谁把它改回裸 Json，这条会红。
        var thrown: Throwable? = null
        try {
            Json.decodeFromString<QrLoginFlow.ShareEnvelope>(successEnvelope)
        } catch (t: Throwable) {
            thrown = t
        }
        assertNotNull("默认 Json 必须拒绝带未知字段的信封", thrown)
    }

    @Test
    fun `js catch envelope has zero status`() {
        // 脚本 catch 分支：statusCode 0 + errorMessage，body 为空串。
        val raw = """{"statusCode":0,"contentType":"","body":"","errorMessage":"TypeError: Failed to fetch","csrfSource":"none","csrfLength":0}"""
        val envelope = QrLoginFlow.parseShareEnvelope(raw)
        assertEquals(0, envelope.statusCode)
        assertEquals("", envelope.body)
    }

    @Test
    fun `js catch envelope keeps errorMessage`() {
        // statusCode = 0 时 body 是空串，唯一能说明原因的就是 errorMessage。
        // 不把它带出来，UI 只剩「share key create http 0 」，等于什么都没说 ——
        // 用户复述一句也定不了性，而我们又拿不到那台设备的 logcat。
        val raw = """{"statusCode":0,"contentType":"","body":"","errorMessage":"csrf token missing","csrfSource":"none","csrfLength":0}"""
        val envelope = QrLoginFlow.parseShareEnvelope(raw)
        assertEquals(0, envelope.statusCode)
        assertEquals("csrf token missing", envelope.errorMessage)
    }

    @Test
    fun `timeout tag is a stable ascii marker`() {
        // UI 层用 contains(tag) 做分支、日志也靠它定位。它一旦被换成给人看的本地化文案，
        // 这套匹配就会随语言失效（切英文/中文时行为不同）。钉死它只允许是稳定 ASCII 标记。
        val tag = QrLoginFlow.SHARE_KEY_TIMEOUT_TAG
        assertEquals(true, tag.isNotBlank())
        assertEquals(true, tag.all { it.code in 32..126 })
        assertEquals(tag, tag.trim())
    }

    @Test
    fun `error body is preserved for diagnostics`() {
        // 失败要能把服务端原文带出来 —— 只报一个 HTTP 码等于什么都没说。
        val raw = """{"statusCode":403,"contentType":"text/html","cfMitigated":"challenge","body":"<html>Just a moment...</html>","csrfSource":"session","csrfLength":64}"""
        val envelope = QrLoginFlow.parseShareEnvelope(raw)
        assertEquals(403, envelope.statusCode)
        assertEquals(true, envelope.body!!.contains("Just a moment"))
    }

    @Test
    fun `share response parses redirect url`() {
        val response = QrLoginFlow.parseShareResponse("""{"redirect_url":"discourse://auth_redirect?a=1"}""")
        assertEquals("discourse://auth_redirect?a=1", response.redirectUrl)
    }

    @Test
    fun `share response without redirect url is null`() {
        assertNull(QrLoginFlow.parseShareResponse("""{"error":"oops"}""").redirectUrl)
    }
}
