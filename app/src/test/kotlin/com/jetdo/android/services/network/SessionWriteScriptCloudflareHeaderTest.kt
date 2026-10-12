package com.jetdo.android.services.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话写脚本的「过盾必需头」回归。
 *
 * 背景（2026-10-12 真机实测，CDP 在宿主 WebView 里跑 header 变体矩阵，用无效 body
 * 以区分「被 CF 拦」与「进了 Rails」）：
 *
 * | 请求头 | 结果 |
 * |---|---|
 * | Content-Type + Accept: json + X-CSRF-Token + X-Requested-With | 403 `cf-mitigated: challenge`（Just a moment... 挑战页，没到 Rails） |
 * | 上一行 + Discourse-Present + Discourse-Logged-In | 403 `["BAD CSRF"]` (text/plain) —— 已穿过 CF，进 Rails 的 CSRF 校验 |
 * | 仅 Content-Type | 403 CF 挑战 |
 * | Content-Type + Accept: json | 403 CF 挑战 |
 *
 * 即 Cloudflare 对 `POST /user-api-key` 只认这两个 Discourse 标识头放行。
 * `createUserApiKeyShareScript` 曾是本文件里**唯一**漏掉它们的写脚本，
 * 于是「扫码登录 → 出示二维码」在真机上恒失败（403 挑战页 HTML 被当成响应体回传），
 * 与是否登录、是否已过盾都无关。这条测试把那两个头钉死在脚本上，避免日后被"整理"掉。
 */
class SessionWriteScriptCloudflareHeaderTest {

    private val passThroughHeaders = listOf(
        "'Discourse-Present': 'true'",
        "'Discourse-Logged-In': 'true'"
    )

    private fun assertPassThroughHeaders(name: String, script: String) {
        passThroughHeaders.forEach { header ->
            assertTrue(
                "$name 生成的脚本缺少过盾必需头 [$header]：" +
                    "Cloudflare 会对该 POST 回 403 + cf-mitigated: challenge，请求到不了 Rails",
                script.contains(header)
            )
        }
    }

    @Test
    fun `share key create script carries cloudflare pass-through headers`() {
        assertPassThroughHeaders(
            "createUserApiKeyShareScript",
            WebFetchScriptFactory.createUserApiKeyShareScript("a=1", "https://linux.do/")
        )
    }

    @Test
    fun `upload script carries cloudflare pass-through headers`() {
        assertPassThroughHeaders(
            "createUploadScript",
            WebFetchScriptFactory.createUploadScript("QUFB", "a.png", "image/png", "https://linux.do/")
        )
    }

    @Test
    fun `share key create script keeps csrf and xhr markers alongside them`() {
        val script = WebFetchScriptFactory.createUserApiKeyShareScript("a=1", "https://linux.do/")
        assertTrue("share 脚本必须带 CSRF 头", script.contains("'X-CSRF-Token': csrf"))
        assertTrue(
            "share 脚本必须带 XHR 标记：服务端据此按 JSON 协商，否则 302 到自定义 scheme",
            script.contains("'X-Requested-With': 'XMLHttpRequest'")
        )
        assertTrue(
            "share 脚本必须带 JSON Accept",
            script.contains("'Accept': 'application/json, text/javascript, */*; q=0.01'")
        )
    }

    /**
     * revoke 走另一套骨架（不带 CSRF），它的过盾条件是 **credentials**：
     * omit 一律 403 + challenge，include 直接穿过（实测见 createUserApiKeyRevokeScript 注释）。
     */
    @Test
    fun `revoke script sends cookies so cloudflare lets it through`() {
        val script = WebFetchScriptFactory.createUserApiKeyRevokeScript("deadbeefdeadbeef")
        assertTrue(
            "revoke 脚本必须带 cookie（credentials: 'include'）：omit 会被 Cloudflare 挑战，" +
                "焚毁静默失败 → 出示端一直显示「等待扫描」",
            script.contains("credentials: 'include'")
        )
        assertFalse(
            "revoke 脚本不能退回 credentials: 'omit'",
            script.contains("credentials: 'omit'")
        )
    }
}
