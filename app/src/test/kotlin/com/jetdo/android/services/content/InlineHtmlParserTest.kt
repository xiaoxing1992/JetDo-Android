package com.jetdo.android.services.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行内解析层语义锁定，对齐鸿蒙 InlineHtmlParser.ets。
 * fixture 形态取自 linux.do 真实 cooked 正文。
 */
class InlineHtmlParserTest {

    @Test
    fun `文本 粗体 链接的基本序列`() {
        val inlines = parseInlines("""<p>你好 <strong>世界</strong> 去 <a href="/t/topic/1">这里</a></p>""")

        assertEquals(
            listOf(
                RichInlineType.TEXT to "你好 ",
                RichInlineType.TEXT to "世界",
                RichInlineType.TEXT to " 去 ",
                RichInlineType.LINK to "这里"
            ),
            inlines.map { it.type to it.text }
        )
        assertTrue(inlines[1].bold)
        assertEquals("https://linux.do/t/topic/1", inlines[3].href)
    }

    @Test
    fun `源码空白折叠且 br 后不留前导空格`() {
        val inlines = parseInlines("""<p>第一行<br>
            2.1 缩进行</p>""")

        assertEquals(3, inlines.size)
        assertEquals(RichInlineType.TEXT, inlines[0].type)
        assertEquals("第一行", inlines[0].text)
        assertEquals(RichInlineType.LINE_BREAK, inlines[1].type)
        assertEquals("2.1 缩进行", inlines[2].text) // 行首空白被去掉，没有 " 2.1"
    }

    @Test
    fun `相邻同型 token 合并`() {
        // <b>粗</b> 续 <strong>体</strong> → 同为 bold TEXT，合并成一个节点。
        val inlines = parseInlines("""<b>粗</b><strong>体</strong>""")

        assertEquals(1, inlines.size)
        assertEquals("粗体", inlines[0].text)
        assertTrue(inlines[0].bold)
    }

    @Test
    fun `实体解码不二次还原`() {
        // jsoup 解码 &amp;lt; → "&lt;"（用户手打的字面量），解析器不能再二次解码成 "<"。
        val inlines = parseInlines("""<p>a &amp; b &amp;lt; c</p>""")

        assertEquals("a & b &lt; c", inlines[0].text)
    }

    @Test
    fun `行内公式整体抽成 MATH`() {
        val inlines = parseInlines("""质能方程 <span class="math">\frac{E}{mc^2}</span> 如下""")

        assertEquals(3, inlines.size)
        assertEquals(RichInlineType.MATH, inlines[1].type)
        assertEquals("\\frac{E}{mc^2}", inlines[1].text)
    }

    @Test
    fun `附件链接标记 attachment`() {
        val inlines = parseInlines(
            """<a class="attachment" href="/uploads/short-url/abc.3g.zip">report.zip</a>"""
        )
        assertTrue(inlines.single().attachment)
        assertTrue(inlines.single().href!!.endsWith("/uploads/short-url/abc.3g.zip"))

        // 不带 class 但指向站内 /uploads/ 的链接同样算附件。
        val implicit = parseInlines("""<a href="/uploads/default/1/x.png">图</a>""")
        assertTrue(implicit.single().attachment)
    }

    @Test
    fun `链接内图片继承 href`() {
        val inlines = parseInlines(
            """<a href="https://example.com/post"><img src="//linuxdo-uploads.s3.ldstatic.com/p.png" alt="截图"></a>"""
        )

        val image = inlines.single()
        assertEquals(RichInlineType.IMAGE, image.type)
        assertEquals("https://linuxdo-uploads.s3.ldstatic.com/p.png", image.src)
        assertEquals("截图", image.alt)
        assertEquals("https://example.com/post", image.href)
    }

    @Test
    fun `spoiler 包裹的文字与图片都带标记`() {
        val inlines = parseInlines(
            """<span class="spoiler">结局是 <img src="/uploads/x.png"></span>"""
        )

        assertEquals(2, inlines.size)
        assertTrue(inlines.all { it.spoiler })
        assertEquals(RichInlineType.IMAGE, inlines[1].type)
    }

    @Test
    fun `连续 br 折叠且首尾空白修剪`() {
        val inlines = parseInlines("""<p>  <br><br>正文<br><br>  </p>""")

        assertEquals(listOf(RichInlineType.TEXT), inlines.map { it.type })
        assertEquals("正文", inlines.single().text)
    }

    @Test
    fun `nbsp 保留不被折叠`() {
        val inlines = parseInlines("""<p>前&nbsp;&nbsp;后</p>""")

        assertEquals("前\u00A0\u00A0后", inlines.single().text)
    }

    @Test
    fun `hasVisibleInlines 判空`() {
        assertFalse(hasVisibleInlines(parseInlines("""<p>   </p>""")))
        assertTrue(hasVisibleInlines(parseInlines("""<p><a href="/t/1">链接</a></p>""")))
    }
}
