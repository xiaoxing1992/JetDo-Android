package com.arkdo.android.ui.topic

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 引用卡跳转箭头的落点判据（对齐鸿蒙 TopicDetailPage.jumpToQuote:1646-1654）。
 *
 * 钉住的核心是**优先级**：同帖 + 有效楼号 → 滚动定位；否则才看 fallbackUrl。
 * 写反（先看 href）的表现是「在本贴点引用卡箭头，却跳到另一篇帖子」。
 */
class QuoteJumpTargetTest {

    private val currentTopic = 1000L

    @Test
    fun `同帖且有楼号 走滚动定位`() {
        val target = resolveQuoteJump(
            topicId = currentTopic,
            postNumber = 12,
            fallbackUrl = "https://linux.do/t/x/1000/12",
            currentTopicId = currentTopic
        )
        assertEquals(QuoteJumpTarget.Post(12), target)
    }

    @Test
    fun `同帖但缺楼号 退回 href`() {
        // 没有 data-post 就没有目标可滚；href 指向本贴首页，仍是可接受的落点。
        val target = resolveQuoteJump(
            topicId = currentTopic,
            postNumber = 0,
            fallbackUrl = "https://linux.do/t/x/1000",
            currentTopicId = currentTopic
        )
        assertEquals(QuoteJumpTarget.Link("https://linux.do/t/x/1000"), target)
    }

    @Test
    fun `跨帖有楼号 仍走 href 而不是滚动到本贴同号楼层`() {
        // 最容易写错的一条：跨帖的楼号与本贴不在一个坐标系，绝不能拿去滚动本贴。
        val target = resolveQuoteJump(
            topicId = 2000L,
            postNumber = 12,
            fallbackUrl = "https://linux.do/t/y/2000/12",
            currentTopicId = currentTopic
        )
        assertEquals(QuoteJumpTarget.Link("https://linux.do/t/y/2000/12"), target)
    }

    @Test
    fun `跨帖且没有 href 时什么都不做`() {
        val target = resolveQuoteJump(
            topicId = 2000L,
            postNumber = 3,
            fallbackUrl = null,
            currentTopicId = currentTopic
        )
        assertEquals(QuoteJumpTarget.None, target)
    }

    @Test
    fun `空 href 等同缺失`() {
        val target = resolveQuoteJump(
            topicId = 2000L,
            postNumber = 3,
            fallbackUrl = "",
            currentTopicId = currentTopic
        )
        assertEquals(QuoteJumpTarget.None, target)
    }

    @Test
    fun `负楼号不当作有效目标`() {
        val target = resolveQuoteJump(
            topicId = currentTopic,
            postNumber = -1,
            fallbackUrl = null,
            currentTopicId = currentTopic
        )
        assertEquals(QuoteJumpTarget.None, target)
    }
}
