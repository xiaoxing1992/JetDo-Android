package com.arkdo.android.data.models

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `/site.json` 的 uploaded_logo 字段解析。
 *
 * 这里之所以要有测试：这个字段曾被简化成 String，遇到对象形态会抛
 * SerializationException，异常又被 SiteService 的 catch 吞掉，
 * 最终表现是「整站版块一个都加载不出来」且没有任何报错。
 * 属于只看代码很难发现、只看现象也定位不到的那类问题。
 */
class UploadedLogoSerializerTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `对象形态取 url`() {
        val payload = """
            {"categories":[
              {"id":1,"name":"开发","uploaded_logo":{"url":"/uploads/a.png","width":120,"height":40}}
            ]}
        """.trimIndent()

        val parsed = json.decodeFromString<DiscourseSiteResponse>(payload)
        assertEquals("/uploads/a.png", parsed.categories!!.single().uploadedLogo)
    }

    @Test
    fun `字符串形态原样保留`() {
        val payload = """{"categories":[{"id":2,"name":"资源","uploaded_logo":"/uploads/b.png"}]}"""

        val parsed = json.decodeFromString<DiscourseSiteResponse>(payload)
        assertEquals("/uploads/b.png", parsed.categories!!.single().uploadedLogo)
    }

    @Test
    fun `null 与缺省都回 null`() {
        val payload = """
            {"categories":[
              {"id":3,"name":"甲","uploaded_logo":null},
              {"id":4,"name":"乙"}
            ]}
        """.trimIndent()

        val parsed = json.decodeFromString<DiscourseSiteResponse>(payload)
        parsed.categories!!.forEach { assertNull(it.uploadedLogo) }
    }

    /**
     * 关键回归点：混合形态不能让整份响应解析失败。
     * 只要有一个版块配了 logo，之前的实现就会让全部版块一起丢掉。
     */
    @Test
    fun `混合形态不影响其余版块`() {
        val payload = """
            {"categories":[
              {"id":1,"name":"甲","uploaded_logo":{"url":"/uploads/a.png"}},
              {"id":2,"name":"乙","uploaded_logo":null},
              {"id":3,"name":"丙","uploaded_logo":"/uploads/c.png"},
              {"id":4,"name":"丁"}
            ]}
        """.trimIndent()

        val parsed = json.decodeFromString<DiscourseSiteResponse>(payload)
        val categories = parsed.categories!!

        assertEquals(4, categories.size)
        assertEquals("/uploads/a.png", categories[0].uploadedLogo)
        assertNull(categories[1].uploadedLogo)
        assertEquals("/uploads/c.png", categories[2].uploadedLogo)
        assertNull(categories[3].uploadedLogo)
    }

    /** 空 url 视同没有 logo，避免下游拼出一个空地址去请求。 */
    @Test
    fun `空 url 视同没有`() {
        val payload = """{"categories":[{"id":5,"name":"戊","uploaded_logo":{"url":"  "}}]}"""

        val parsed = json.decodeFromString<DiscourseSiteResponse>(payload)
        assertNull(parsed.categories!!.single().uploadedLogo)
    }
}
