package com.jetdo.android.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WebView 导航分流的护栏。
 *
 * 钉住的是一条**二值判定**：谁能被 WebView 自己加载。它决定了授权回程深链
 * (discourse://auth_redirect) 是被接住还是被丢掉 —— 判错一侧，真机上就是「点了授权没反应」，
 * 而且没有任何日志能直接指到这里。所以宁可把判定抽出来单独测，也不让它埋在
 * WebViewClient 匿名对象里。
 */
class WebSchemeRoutingTest {

    // ---------- WebView 能加载：必须返回 true，否则站内跳转会被吞掉 ----------

    @Test
    fun `http and https are loadable by webview`() {
        assertTrue(isWebViewLoadable("http://linux.do/"))
        assertTrue(isWebViewLoadable("https://linux.do/user-api-key/new?application_name=JetDo"))
    }

    @Test
    fun `scheme matching is case insensitive`() {
        assertTrue("HTML 里大写 scheme 合法", isWebViewLoadable("HTTPS://Linux.DO/t/1"))
        assertTrue(isWebViewLoadable("HtTp://example.com"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertTrue("href 带换行/缩进是常态", isWebViewLoadable("\n  https://linux.do/t/1  \n"))
    }

    // ---------- WebView 加载不了：必须返回 false，交宿主 ----------

    @Test
    fun `auth redirect deep link is not loadable`() {
        val deepLink = "discourse://auth_redirect?payload=abc&oneTimePassword=def"
        assertFalse(
            "授权回程深链一旦被判成可加载，页面就会停在 WebView 打不开的 scheme 上",
            isWebViewLoadable(deepLink)
        )
    }

    @Test
    fun `other non http schemes are not loadable`() {
        assertFalse(isWebViewLoadable("mailto:someone@linux.do"))
        assertFalse(isWebViewLoadable("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(isWebViewLoadable("javascript:alert(1)"))
        assertFalse(isWebViewLoadable("about:blank"))
        assertFalse(isWebViewLoadable("tel:+8613800138000"))
    }

    @Test
    fun `blank input is not loadable`() {
        assertFalse(isWebViewLoadable(""))
        assertFalse(isWebViewLoadable("   "))
    }

    // ---------- 边界：不是 http 前缀的一律不算 ----------

    @Test
    fun `scheme-like text without the separator is not loadable`() {
        assertFalse("没有 `//` 的不是 URL 形式的导航", isWebViewLoadable("https:/linux.do"))
        assertFalse("前缀必须完整匹配", isWebViewLoadable("httpsx://linux.do"))
        assertFalse("相对路径不该被当成可加载", isWebViewLoadable("/t/12345"))
    }
}
