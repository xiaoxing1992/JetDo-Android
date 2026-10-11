package com.jetdo.android.services.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预览里的短码 → 图片 单测。
 *
 * 只服务预览：详情页正文拿的是服务端 cooked HTML，里面表情已经是 `<img class="emoji">`；
 * 本地 markdown-it 不做这一步，所以要在编译产物上补一刀。
 *
 * 用注入的 resolver 而不是真表：这个类的职责是"在 HTML 里找短码并换掉"，与目录无关。
 */
class EmojiPreviewHtmlTest {

    private val resolved = mapOf(
        "smile" to "https://cdn.example.com/smile.png",
        "tieba_087" to "https://cdn.example.com/tieba.png"
    )

    private fun resolve(name: String): String? = resolved[name]

    @Test
    fun `已知短码转成行内图片`() {
        val html = EmojiPreviewHtml.render("<p>:smile:</p>", ::resolve)

        assertTrue(html.contains("class=\"emoji\""))
        assertTrue(html.contains("src=\"https://cdn.example.com/smile.png\""))
        assertTrue(html.contains("alt=\":smile:\""))
        // 短码不再作为**正文文字**出现。alt / title 里的 ":smile:" 是无障碍标签，属预期。
        assertFalse(html.contains("<p>:smile:</p>"))
    }

    @Test
    fun `未知短码原样保留,不当成表情`() {
        val html = EmojiPreviewHtml.render("<p>:definitely_not_an_emoji:</p>", ::resolve)

        assertFalse(html.contains("<img"))
        assertTrue(html.contains(":definitely_not_an_emoji:"))
    }

    @Test
    fun `code 与 pre 里的短码不替换`() {
        val code = EmojiPreviewHtml.render("<p><code>:smile:</code></p>", ::resolve)
        val pre = EmojiPreviewHtml.render("<pre>:smile:</pre>", ::resolve)

        assertFalse(code.contains("<img"))
        assertTrue(code.contains(":smile:"))
        assertFalse(pre.contains("<img"))
        assertTrue(pre.contains(":smile:"))
    }

    @Test
    fun `同一段文本里的多个短码都替换,周边文字保留`() {
        val html = EmojiPreviewHtml.render("<p>hi :smile: and :tieba_087: done</p>", ::resolve)

        assertEquals(2, Regex("<img").findAll(html).count())
        assertTrue(html.contains("hi "))
        assertTrue(html.contains(" and "))
        assertTrue(html.contains(" done"))
    }

    @Test
    fun `相邻短码不会被合并成一个`() {
        val html = EmojiPreviewHtml.render("<p>:smile::tieba_087:</p>", ::resolve)

        assertEquals(2, Regex("<img").findAll(html).count())
        assertTrue(html.contains("smile.png"))
        assertTrue(html.contains("tieba.png"))
    }

    @Test
    fun `未知短码夹在已知短码之间时,已知的仍被替换`() {
        val html = EmojiPreviewHtml.render("<p>:nope::smile:</p>", ::resolve)

        assertEquals(1, Regex("<img").findAll(html).count())
        assertTrue(html.contains(":nope:"))
    }

    @Test
    fun `空输入与无冒号输入直接原样返回`() {
        assertEquals("", EmojiPreviewHtml.render("", ::resolve))
        assertEquals("<p>plain</p>", EmojiPreviewHtml.render("<p>plain</p>", ::resolve))
    }

    @Test
    fun `只有半个冒号的短码不替换`() {
        val html = EmojiPreviewHtml.render("<p>:smile</p>", ::resolve)

        assertFalse(html.contains("<img"))
        assertTrue(html.contains(":smile"))
    }
}
