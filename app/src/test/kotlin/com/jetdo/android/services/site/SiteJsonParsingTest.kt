package com.jetdo.android.services.site

import com.jetdo.android.data.models.DiscourseSiteResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用 linux.do 真实的 /site.json 响应跑一遍解析。
 *
 * 用真实响应而不是手编样例，是因为这一处的失败模式正是「真实数据里某个字段的类型
 * 和模型对不上」——手编的样例只会照着模型写，永远测不出这类问题。
 * fixture 抓于 2026-09-02，17 个版块，其中 13 个的 uploaded_logo 是对象形态。
 */
class SiteJsonParsingTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun realPayload(): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("site_real.json")) {
            "缺少测试 fixture site_real.json"
        }.bufferedReader().use { it.readText() }

    @Test
    fun `真实响应能解析出全部版块`() {
        val parsed = json.decodeFromString<DiscourseSiteResponse>(realPayload())
        val categories = checkNotNull(parsed.categories) { "categories 为 null" }

        // 整份解析是成败一体的：只要有一个字段类型对不上，这里就是 0。
        assertEquals(17, categories.size)
    }

    @Test
    fun `对象形态的 uploaded_logo 被取成 url`() {
        val parsed = json.decodeFromString<DiscourseSiteResponse>(realPayload())
        val withLogo = parsed.categories!!.filter { !it.uploadedLogo.isNullOrEmpty() }

        assertEquals(13, withLogo.size)
        // 站点回的是协议相对地址，原样保留，补全交给 resolveBadgeAssetUrl。
        assertTrue(withLogo.all { it.uploadedLogo!!.startsWith("//") })
    }

    @Test
    fun `父子层级解析正确`() {
        val parsed = json.decodeFromString<DiscourseSiteResponse>(realPayload())
        val categories = parsed.categories!!

        val topLevel = categories.count { it.parentCategoryId == null }
        val children = categories.count { it.parentCategoryId != null }

        assertEquals(12, topLevel)
        assertEquals(5, children)
    }

    @Test
    fun `经 SiteService 映射后版块不丢失`() {
        val parsed = json.decodeFromString<DiscourseSiteResponse>(realPayload())
        val mapped = SiteService.mapCategoriesForTest(parsed.categories!!)

        // mapCategory 会丢掉缺 id 或缺 name 的项，真实数据里不该有。
        assertEquals(17, mapped.size)
        assertTrue(mapped.all { it.name.isNotEmpty() })
        assertTrue(mapped.all { it.color.isNotEmpty() })
    }
}
