package com.jetdo.android.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索 q 串构建与高亮词解析的纯逻辑测试。
 *
 * 拼串规则逐条对齐 Discourse 官方(服务端 core `lib/search.rb` 的 advanced_filter 正则、
 * 前端 `search-advanced-options.gjs` 的拼串规则),这些用例把容易拼错的地方钉住。
 */
class SearchModelsTest {

    @Test
    fun `默认筛选无任何条件且不产出 token`() {
        val filters = SearchFilters()

        assertFalse(filters.hasAny())
        assertEquals(0, filters.activeCount())
        assertEquals("kotlin", buildSearchQuery("kotlin", filters))
    }

    @Test
    fun `关键词前后空白被裁掉`() {
        assertEquals("kotlin", buildSearchQuery("  kotlin  ", SearchFilters()))
    }

    @Test
    fun `分类优先拼 slug 简写`() {
        val filters = SearchFilters(categoryId = 42, categorySlug = "android")

        assertEquals("kotlin #android", buildSearchQuery("kotlin", filters))
    }

    @Test
    fun `子分类拼 父slug 冒号 子slug`() {
        val filters = SearchFilters(
            categoryId = 42,
            categorySlug = "android",
            categoryParentSlug = "dev"
        )

        assertEquals("kotlin #dev:android", buildSearchQuery("kotlin", filters))
    }

    @Test
    fun `无 slug 时退回 category 冒号 id`() {
        val filters = SearchFilters(categoryId = 42)

        assertEquals("kotlin category:42", buildSearchQuery("kotlin", filters))
    }

    @Test
    fun `标签任一命中用逗号 全部命中用加号`() {
        val anyOf = SearchFilters(tags = listOf("kotlin", "android"))
        val allOf = SearchFilters(tags = listOf("kotlin", "android"), tagsMatchAll = true)

        // 分隔符本身就是语义,不能互换。
        assertEquals("kotlin tags:kotlin,android", buildSearchQuery("kotlin", anyOf))
        assertEquals("kotlin tags:kotlin+android", buildSearchQuery("kotlin", allOf))
    }

    @Test
    fun `用户名拼成 at 前缀`() {
        assertEquals("kotlin @ada", buildSearchQuery("kotlin", SearchFilters(username = "ada")))
    }

    @Test
    fun `图片走 with 其余走 in`() {
        val filters = SearchFilters(inFilters = listOf("title", "images"))

        assertEquals("kotlin in:title with:images", buildSearchQuery("kotlin", filters))
    }

    @Test
    fun `时间与排序按官方关键词拼`() {
        val filters = SearchFilters(
            timeWhen = SearchFiltersOptions.TIME_AFTER,
            timeDate = "2026-01-01",
            order = "likes"
        )

        assertEquals("kotlin after:2026-01-01 order:likes", buildSearchQuery("kotlin", filters))
    }

    @Test
    fun `相关性不拼 order token`() {
        // 拼 order:relevance 会被服务端当未知排序忽略,所以空值必须什么都不拼。
        val filters = SearchFilters(order = "")

        assertEquals("kotlin", buildSearchQuery("kotlin", filters))
    }

    @Test
    fun `帖数与浏览量用 min max posts views 关键词`() {
        val filters = SearchFilters(
            minPosts = "5",
            maxPosts = "50",
            minViews = "100",
            maxViews = "9999"
        )

        // 注意是 posts 不是 post。
        assertEquals(
            "kotlin min_posts:5 max_posts:50 min_views:100 max_views:9999",
            buildSearchQuery("kotlin", filters)
        )
    }

    @Test
    fun `状态条件拼 status`() {
        assertEquals("kotlin status:solved", buildSearchQuery("kotlin", SearchFilters(status = "solved")))
    }

    @Test
    fun `token 顺序为关键词 分类 标签 at用户 in 状态 时间 数值 排序`() {
        val filters = SearchFilters(
            categoryId = 42,
            categorySlug = "android",
            tags = listOf("kotlin"),
            username = "ada",
            inFilters = listOf("title"),
            status = "open",
            timeDate = "2026-01-01",
            minPosts = "3",
            order = "latest"
        )

        assertEquals(
            "rx #android tags:kotlin @ada in:title status:open after:2026-01-01 min_posts:3 order:latest",
            buildSearchQuery("rx", filters)
        )
    }

    @Test
    fun `activeCount 统计生效条件条数`() {
        val filters = SearchFilters(
            categoryId = 42,
            tags = listOf("kotlin", "android"),
            inFilters = listOf("title", "images"),
            order = "likes"
        )

        // 分类 1 + 标签组 1 + in 多选 2 + 排序 1
        assertEquals(5, filters.activeCount())
        assertTrue(filters.hasAny())
    }

    @Test
    fun `digitsOnly 剥掉非数字字符`() {
        // min_posts 等服务端正则是 \d+,混进别的字符整个 token 会被当普通搜索词。
        assertEquals("123", SearchFiltersOptions.digitsOnly("1a2b3"))
        assertEquals("", SearchFiltersOptions.digitsOnly("abc"))
    }

    @Test
    fun `高亮词过滤掉高级语法`() {
        assertEquals(
            listOf("kotlin", "coroutine"),
            parseSearchHighlights("kotlin in:title #android @ada -excluded coroutine")
        )
    }

    @Test
    fun `高亮词保留单个汉字`() {
        // 站点调低了 min_search_term_length,单字也能搜;若这里要求 ≥2 字符
        // 就会出现「搜『搞』搜得到但结果里不高亮」。
        assertEquals(listOf("搞"), parseSearchHighlights("搞"))
    }

    @Test
    fun `空搜索词产出空高亮列表`() {
        assertEquals(emptyList<String>(), parseSearchHighlights("   "))
    }

    @Test
    fun `搜索类型从非法值回落到帖子`() {
        assertEquals(SearchType.TOPICS, SearchType.fromValue(null))
        assertEquals(SearchType.TOPICS, SearchType.fromValue("bogus"))
        assertEquals(SearchType.USERS, SearchType.fromValue("users"))
        assertEquals(SearchType.TOPICS, SearchType.fromValue("topics_posts"))
    }
}