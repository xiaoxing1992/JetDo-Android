package com.jetdo.android.ui.components

import com.jetdo.android.services.content.RichBlock
import com.jetdo.android.services.content.RichBlockType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 引用卡右上角跳转箭头的朝向。
 *
 * 这条测试存在的理由：方向是「同帖 / 跨帖 × 被引用楼在本楼之前还是之后」的四象限，
 * 其中**跨帖**那一支最容易被顺手写成也按楼号判方向。跨帖引用打开的是另一个帖子，
 * 与楼层前后毫无关系，必须恒为 arrow-right（对齐官网 quoted-content.gjs 的 navigateToPostIcon）。
 *
 * 全部测试在 2026-10 重新对照鸿蒙 PostRichContent.ets:692-698 的 quoteJumpIcon 核过一遍。
 */
class QuoteJumpIconTest {

    private fun quote(isReply: Boolean, postNumber: Int? = null) = RichBlock(
        type = RichBlockType.TOPIC_QUOTE,
        quoteIsReply = isReply,
        quotePostNumber = postNumber
    )

    @Test
    fun `跨帖引用恒为 arrow-right —— 与楼层前后无关`() {
        // 即便带了楼号也不该按楼号判方向：跨帖的楼号与「本楼」不在一个坐标系里。
        assertEquals("arrow-right", quoteJumpIcon(quote(isReply = false, postNumber = 1), currentPostNumber = 50))
        assertEquals("arrow-right", quoteJumpIcon(quote(isReply = false, postNumber = 999), currentPostNumber = 3))
        assertEquals("arrow-right", quoteJumpIcon(quote(isReply = false), currentPostNumber = 0))
    }

    @Test
    fun `同帖引用 被引用楼在本楼之后 朝下`() {
        assertEquals("arrow-down", quoteJumpIcon(quote(isReply = true, postNumber = 12), currentPostNumber = 5))
    }

    @Test
    fun `同帖引用 被引用楼在本楼之前 朝上`() {
        assertEquals("arrow-up", quoteJumpIcon(quote(isReply = true, postNumber = 2), currentPostNumber = 5))
    }

    @Test
    fun `同帖引用 被引用楼就是本楼 朝上`() {
        // 判据是 quoted > current，同楼不满足，归到「之前」那一支（与鸿蒙一致）。
        assertEquals("arrow-up", quoteJumpIcon(quote(isReply = true, postNumber = 5), currentPostNumber = 5))
    }

    @Test
    fun `无楼层上下文时退回 arrow-up`() {
        // 编辑器预览、公告弹层：currentPostNumber = 0，拿不到前后关系。
        assertEquals("arrow-up", quoteJumpIcon(quote(isReply = true, postNumber = 9), currentPostNumber = 0))
        assertEquals("arrow-up", quoteJumpIcon(quote(isReply = true, postNumber = 9), currentPostNumber = -1))
    }

    @Test
    fun `缺 data-post 时不崩 按 0 楼处理`() {
        // quotePostNumber = null 时取 0 参与比较，当前楼 > 0 一律朝上。
        assertEquals("arrow-up", quoteJumpIcon(quote(isReply = true, postNumber = null), currentPostNumber = 7))
    }
}
