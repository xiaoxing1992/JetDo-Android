package com.jetdo.android.services.miniapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 宿主 JSON 工具、URL 拼装与错误码约定测试。
 *
 * 这些函数是宿主 ↔ 页面侧之间唯一的数据通道，格式错一个字符，
 * 表现是微应用静默拿到 undefined 而不是报错 —— 极难排查，所以必须锁死。
 */
class MiniAppHostRuntimeTest {

    @Test
    fun `jsonString 转义双引号与反斜杠`() {
        assertEquals("\"a\\\"b\"", MiniAppHostRuntime.jsonString("a\"b"))
        assertEquals("\"a\\\\b\"", MiniAppHostRuntime.jsonString("a\\b"))
    }

    @Test
    fun `jsonString 转义控制字符`() {
        assertEquals("\"a\\nb\"", MiniAppHostRuntime.jsonString("a\nb"))
        assertEquals("\"a\\tb\"", MiniAppHostRuntime.jsonString("a\tb"))
        assertEquals("\"a\\u0001b\"", MiniAppHostRuntime.jsonString("a\u0001b"))
    }

    @Test
    fun `jsonString 处理 null 与中文`() {
        assertEquals("null", MiniAppHostRuntime.jsonString(null))
        assertEquals("\"中文\"", MiniAppHostRuntime.jsonString("中文"))
    }

    @Test
    fun `jsonArray 生成合法数组`() {
        assertEquals("[]", MiniAppHostRuntime.jsonArray(emptyList()))
        assertEquals("[\"a\",\"b\"]", MiniAppHostRuntime.jsonArray(listOf("a", "b")))
    }

    @Test
    fun `errorJson 带错误码与消息`() {
        val json = MiniAppHostRuntime.errorJson("PERMISSION_DENIED", "缺少权限")
        assertTrue(json.contains("\"__error\""))
        assertTrue(json.contains("\"code\":\"PERMISSION_DENIED\""))
        assertTrue(json.contains("\"message\":\"缺少权限\""))
    }

    @Test
    fun `base64 往返一致`() {
        val bytes = "微应用宿主".toByteArray(Charsets.UTF_8)
        val encoded = MiniAppHostRuntime.base64(bytes)
        val decoded = MiniAppHostRuntime.base64Decode(encoded)
        assertTrue(bytes.contentEquals(decoded))
    }

    @Test
    fun `urlEncode 按 RFC3986 编码而非加号空格`() {
        assertEquals("a%20b", MiniAppHostRuntime.urlEncode("a b"))
        assertEquals("a%2Bb", MiniAppHostRuntime.urlEncode("a+b"))
        assertEquals("safe-._~", MiniAppHostRuntime.urlEncode("safe-._~"))
        assertEquals("%E4%B8%AD%E6%96%87", MiniAppHostRuntime.urlEncode("中文"))
    }

    @Test
    fun `joinEndpointUrl 只接受声明过的动态 query`() {
        val url = MiniAppHostRuntime.joinEndpointUrl(
            url = "https://api.example.com/lookup",
            declared = listOf("key"),
            query = mapOf("key" to "abc")
        )
        assertEquals("https://api.example.com/lookup?key=abc", url)
    }

    @Test
    fun `joinEndpointUrl 未声明的 query 键被拒绝`() {
        val error = runCatching {
            MiniAppHostRuntime.joinEndpointUrl(
                url = "https://api.example.com/lookup",
                declared = listOf("key"),
                query = mapOf("evil" to "1")
            )
        }.exceptionOrNull()

        assertTrue(error is MiniAppHostRuntime.MiniAppHostException)
        assertEquals("PERMISSION_DENIED", (error as MiniAppHostRuntime.MiniAppHostException).code)
    }

    @Test
    fun `joinEndpointUrl 已有固定 query 时用 & 追加`() {
        val url = MiniAppHostRuntime.joinEndpointUrl(
            url = "https://api.example.com/lookup?v=2",
            declared = listOf("key"),
            query = mapOf("key" to "a b")
        )
        assertEquals("https://api.example.com/lookup?v=2&key=a%20b", url)
    }

    @Test
    fun `joinEndpointUrl 无动态 query 时原样返回`() {
        val url = MiniAppHostRuntime.joinEndpointUrl(
            url = "https://api.example.com/lookup",
            declared = listOf("key"),
            query = emptyMap()
        )
        assertEquals("https://api.example.com/lookup", url)
    }
}
