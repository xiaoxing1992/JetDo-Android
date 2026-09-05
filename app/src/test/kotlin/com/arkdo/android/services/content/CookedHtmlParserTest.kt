package com.arkdo.android.services.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * cooked HTML 块级解析，对齐鸿蒙 CookedHtmlParser.ets。
 * fixture 形态取自 linux.do 真实楼层正文（Discourse 服务端输出）。
 */
class CookedHtmlParserTest {

    @Test
    fun `段落 标题 分隔线`() {
        val blocks = CookedHtmlParser.parse(
            "<h2>标题</h2><p>第一段</p><hr><p>第二段</p>"
        )

        assertEquals(
            listOf(RichBlockType.HEADING, RichBlockType.PARAGRAPH, RichBlockType.DIVIDER, RichBlockType.PARAGRAPH),
            blocks.map { it.type }
        )
        assertEquals(2, blocks[0].level)
    }

    @Test
    fun `相邻纯文本段落合并且插入段落分隔`() {
        val blocks = CookedHtmlParser.parse("<p>一段</p><p>二段</p>")

        assertEquals(1, blocks.size)
        val inlines = blocks.single().inlines!!
        // 一段 + paragraphBreak + 二段
        assertEquals(3, inlines.size)
        assertTrue(inlines[1].paragraphBreak)
        assertEquals("一段\n二段", inlines.map { it.text }.joinToString(""))
    }

    @Test
    fun `纯引用与嵌套展开`() {
        val blocks = CookedHtmlParser.parse(
            """<blockquote><p>被引用的话</p></blockquote><p>回复</p>"""
        )

        assertEquals(listOf(RichBlockType.QUOTE, RichBlockType.PARAGRAPH), blocks.map { it.type })
        val quoteInner = blocks[0].blocks!!
        assertEquals(listOf(RichBlockType.PARAGRAPH), quoteInner.map { it.type })
    }

    @Test
    fun `跨帖话题引用解析成引用卡`() {
        val blocks = CookedHtmlParser.parse(
            """<aside class="quote" data-username="neo" data-topic="2860794" data-post="3">
                 <div class="title">
                   <img class="avatar" src="/user_avatar/linux.do/neo/{size}/1_2.png">
                   <a href="/t/some-topic/2860794">某个话题标题</a>
                   <span class="badge-category__wrapper"><span class="badge-category" data-category-id="44" style="--category-badge-color:#086ddd;"><span class="badge-category__name">开发调优</span></span></span>
                 </div>
                 <blockquote><p>引用预览</p></blockquote>
               </aside>"""
        )

        val card = blocks.single()
        assertEquals(RichBlockType.TOPIC_QUOTE, card.type)
        assertEquals(2860794L, card.quoteTopicId)
        assertEquals(3, card.quotePostNumber)
        assertEquals("neo", card.quoteAuthor)
        assertEquals("某个话题标题", card.quoteTitle)
        assertEquals("https://linux.do/t/some-topic/2860794", card.href)
        assertEquals("44", card.quoteCategory?.id.toString())
        assertEquals("086ddd", card.quoteCategory?.color)
        assertTrue(card.text.orEmpty().contains("引用预览"))
        assertNull(card.quoteIsReply.takeIf { it })
    }

    @Test
    fun `同帖回复引用走用户名卡片`() {
        val blocks = CookedHtmlParser.parse(
            """<aside class="quote" data-username="neo" data-display-name="尼奥" data-topic="2860794" data-post="5">
                 <div class="title"><div class="title">尼奥:</div></div>
                 <blockquote><p>前一楼的结论</p></blockquote>
               </aside>"""
        )

        val card = blocks.single()
        assertEquals(RichBlockType.TOPIC_QUOTE, card.type)
        assertEquals(true, card.quoteIsReply)
        assertEquals("尼奥", card.quoteTitle)
        assertEquals("https://linux.do/t/topic/2860794/5", card.href)
    }

    @Test
    fun `callout 升级 含折叠标记与别名归一`() {
        val blocks = CookedHtmlParser.parse(
            """<blockquote><p>[!tip]- 折叠提示<br>正文第一行</p><p>更多内容</p></blockquote>"""
        )

        val callout = blocks.single()
        assertEquals(RichBlockType.CALLOUT, callout.type)
        // hint/important → tip 别名归一
        assertEquals("tip", callout.calloutKind)
        assertEquals("#00bfbc", callout.calloutColor)
        assertTrue(callout.collapsible)
        assertFalse(callout.defaultExpanded!!) // "-" 默认折叠
        assertEquals("折叠提示", callout.titleInlines!!.first().text)
        assertTrue(callout.blocks!!.isNotEmpty())
    }

    @Test
    fun `代码块抽取语言与文本`() {
        val blocks = CookedHtmlParser.parse(
            """<pre><code class="language-kotlin">fun main() {
    println("hi")
}</code></pre>"""
        )

        val code = blocks.single()
        assertEquals(RichBlockType.CODE, code.type)
        assertEquals("kotlin", code.language)
        assertTrue(code.text.orEmpty().contains("println(\"hi\")"))
    }

    @Test
    fun `lightbox 上传图提升为图片块且取 srcset 高倍率`() {
        val blocks = CookedHtmlParser.parse(
            """<div class="lightbox-wrapper"><a class="lightbox" href="https://linux.do/uploads/default/original/1/x.png">
                 <img src="https://linux.do/uploads/default/optimized/1/x_2_690x388.png"
                      srcset="https://linux.do/uploads/default/optimized/1/x_2_690x388.png, https://linux.do/uploads/default/optimized/1/x_1380x776.png 2x"
                      width="690" height="388" alt="截图">
                 <div class="meta"><span class="filename">x.png</span><span>690×388</span></div>
               </a></div>"""
        )

        val image = blocks.single()
        assertEquals(RichBlockType.IMAGE, image.type)
        // srcset 2x 档优先
        assertEquals("https://linux.do/uploads/default/optimized/1/x_1380x776.png", image.src)
        assertEquals(690, image.width)
        assertEquals("截图", image.alt)
        assertEquals("https://linux.do/uploads/default/original/1/x.png", image.href)
    }

    @Test
    fun `图 文混排段落按图片单元切分`() {
        val blocks = CookedHtmlParser.parse(
            """<p>看这张图<br><img class="animated" src="https://linux.do/uploads/a.gif" width="500" height="300">配合说明</p>"""
        )

        assertEquals(
            listOf(RichBlockType.PARAGRAPH, RichBlockType.IMAGE, RichBlockType.PARAGRAPH),
            blocks.map { it.type }
        )
        assertEquals("看这张图", blocks[0].inlines!!.first().text)
        assertEquals("配合说明", blocks[2].inlines!!.first().text)
    }

    @Test
    fun `列表与表格结构化`() {
        val blocks = CookedHtmlParser.parse(
            """<ul><li>甲</li><li>乙</li></ul>
               <div class="md-table"><table><thead><tr><th>列A</th></tr></thead><tbody><tr><td>1</td></tr></tbody></table></div>"""
        )

        val list = blocks[0]
        assertEquals(RichBlockType.LIST, list.type)
        assertEquals(false, list.ordered)
        assertEquals(listOf("甲", "乙"), list.items!!.map { it.inlines.first().text })

        val table = blocks[1]
        assertEquals(RichBlockType.TABLE, table.type)
        assertEquals(true, table.tableRows!!.first().header)
        assertEquals("列A", table.tableRows!!.first().cells.single().inlines.single().text)
        assertEquals(false, table.tableRows!!.last().header)
    }

    @Test
    fun `剧透与折叠详情`() {
        val blocks = CookedHtmlParser.parse(
            """<div class="spoiler"><p>剧透内容</p></div>
               <details><summary>展开查看</summary><p>隐藏正文</p></details>"""
        )

        assertEquals(RichBlockType.SPOILER, blocks[0].type)
        assertTrue(blocks[0].blocks!!.single().inlines!!.single().text == "剧透内容")

        val disclosure = blocks[1]
        assertEquals(RichBlockType.DISCLOSURE, disclosure.type)
        assertEquals("展开查看", disclosure.title)
        assertTrue(disclosure.blocks!!.single().inlines!!.single().text == "隐藏正文")
    }

    @Test
    fun `onebox 与 poll 占位与数学公式`() {
        val blocks = CookedHtmlParser.parse(
            """<aside class="onebox">
                 <header class="source"><img class="site-icon" src="https://example.com/favicon.ico"><a href="https://example.com/post/1">example.com</a></header>
                 <article class="onebox-body"><h3><a href="https://example.com/post/1">文章标题</a></h3><p>文章摘要</p></article>
               </aside>
               <div class="poll" data-poll-name="poll1"></div>
               <div class="math">E = mc^2</div>"""
        )

        val onebox = blocks[0]
        assertEquals(RichBlockType.ONEBOX, onebox.type)
        assertEquals("example.com", onebox.domain)
        assertEquals("文章标题", onebox.title)
        assertEquals("https://example.com/post/1", onebox.href)
        assertEquals("文章摘要", onebox.description)

        assertEquals(RichBlockType.POLL, blocks[1].type)
        assertEquals("poll1", blocks[1].pollName)

        val math = blocks[2]
        assertEquals(RichBlockType.MATH, math.type)
        assertEquals("E = mc^2", math.text)
    }

    @Test
    fun `emoji 行保持行内不提升大图`() {
        val blocks = CookedHtmlParser.parse(
            """<p><img class="emoji" src="/images/emoji/twitter/smile.png" alt=":smile:"><img class="emoji" src="/images/emoji/twitter/plus1.png" alt=":+1:"></p>"""
        )

        assertEquals(listOf(RichBlockType.PARAGRAPH), blocks.map { it.type })
        assertEquals(2, blocks.single().inlines!!.count { it.type == RichInlineType.IMAGE })
    }
}
