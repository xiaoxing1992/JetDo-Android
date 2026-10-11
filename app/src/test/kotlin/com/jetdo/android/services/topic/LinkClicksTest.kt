package com.jetdo.android.services.topic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 链接点击数建表与查表，对齐鸿蒙 LinkClicks.ets（官方 _showLinkCounts / isValidLink 语义）。
 */
class LinkClicksTest {

    private val counts = listOf(
        DiscourseLinkCount(url = "https://example.com/post/1", clicks = 340),
        DiscourseLinkCount(url = "https://linux.do/t/topic/9", clicks = 12),
        DiscourseLinkCount(url = "/uploads/default/x.png", clicks = 7),
        // 反链：别的帖引用了本帖，不显示
        DiscourseLinkCount(url = "https://linux.do/t/other/8", clicks = 99, reflection = true),
        // 0 次不显示
        DiscourseLinkCount(url = "https://example.com/zero", clicks = 0)
    )

    @Test
    fun `精确匹配挂角标`() {
        val map = buildLinkClicks(
            """<p>看 <a href="https://example.com/post/1">这个帖子</a></p>""",
            counts
        )!!

        assertEquals(340, map["https://example.com/post/1"])
        assertEquals(340, clicksForHref(map, "https://example.com/post/1"))
    }

    @Test
    fun `去 query 匹配站内链接`() {
        val map = buildLinkClicks(
            """<p><a href="https://linux.do/t/topic/9?u=neo">站内帖</a></p>""",
            counts
        )!!

        assertEquals(12, clicksForHref(map, "https://linux.do/t/topic/9?u=neo"))
    }

    @Test
    fun `附件按包含匹配相对路径`() {
        val map = buildLinkClicks(
            """<p><a href="https://linux.do/uploads/default/x.png">图</a></p>""",
            counts
        )!!

        assertEquals(7, clicksForHref(map, "https://linux.do/uploads/default/x.png"))
    }

    @Test
    fun `reflection 反链与零次不进表`() {
        val map = buildLinkClicks(
            """<p><a href="https://linux.do/t/other/8">反链</a><a href="https://example.com/zero">零</a></p>""",
            counts
        )

        assertNull(clicksForHref(map, "https://linux.do/t/other/8").let { map }?.get("https://linux.do/t/other/8"))
        assertNull(map?.get("https://example.com/zero"))
    }

    @Test
    fun `引用卡与灯箱链接不建表`() {
        val map = buildLinkClicks(
            """<aside class="quote"><a href="https://example.com/post/1">引用里的链接</a></aside>
               <a class="lightbox" href="https://linux.do/uploads/big.png">灯箱</a>""",
            counts
        )

        assertNull(map)
    }
}
