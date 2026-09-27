package com.arkdo.android.services.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 输入框表情图文混排的单测。
 *
 * 这套映射的正确性直接决定光标落在哪、图片画在哪：短码 7 个字符整体塌成 1 个占位字符，
 * 偏移映射一旦不单调，光标就会乱跳（OffsetMapping 的契约只有"单调不减"这一条硬要求，
 * 所以这里把两个方向都钉住）。纯函数，无 Compose 依赖。
 */
class EmojiInputRenderTest {

    private val urls = mapOf(
        "smile" to "https://cdn.example/smile.png",
        "tieba_087" to "https://cdn.example/tieba_087.png"
    )

    private fun render(model: String): EmojiInputRender =
        renderEmojiInput(model) { urls[it] }

    @Test
    fun `没有短码时显示文本与原文完全一致`() {
        val render = render("hello 世界")

        assertEquals("hello 世界", render.display)
        assertTrue(render.slots.isEmpty())
        assertEquals(0, render.originalToTransformed(0))
        assertEquals(8, render.originalToTransformed(8))
    }

    @Test
    fun `空串也不炸`() {
        val render = render("")

        assertEquals("", render.display)
        assertEquals(0, render.modelLength)
        assertEquals(0, render.displayLength)
        assertEquals(0, render.originalToTransformed(0))
        assertEquals(0, render.transformedToOriginal(0))
    }

    @Test
    fun `已知短码整体塌成一个全角空格占位`() {
        // "hi :smile:" → "hi " + 占位字符
        val render = render("hi :smile:")

        assertEquals("hi $EMOJI_INPUT_PLACEHOLDER", render.display)
        assertEquals(1, render.slots.size)
        val slot = render.slots.single()
        assertEquals("smile", slot.name)
        assertEquals("https://cdn.example/smile.png", slot.url)
        assertEquals(3, slot.displayIndex)
    }

    @Test
    fun `未收录的短码原样保留`() {
        // 目录里没有 :nope: —— 换成空白或图片都会比留着原文更糟，所以一动不动。
        val render = render("a :nope: b")

        assertEquals("a :nope: b", render.display)
        assertTrue(render.slots.isEmpty())
    }

    @Test
    fun `半截短码不会被当成表情`() {
        val render = render(":smi")

        assertEquals(":smi", render.display)
        assertTrue(render.slots.isEmpty())
    }

    @Test
    fun `连续两个短码各自占一位`() {
        val render = render(":smile::tieba_087:")

        assertEquals("$EMOJI_INPUT_PLACEHOLDER$EMOJI_INPUT_PLACEHOLDER", render.display)
        assertEquals(listOf(0, 1), render.slots.map { it.displayIndex })
        assertEquals(listOf("smile", "tieba_087"), render.slots.map { it.name })
    }

    @Test
    fun `短码夹在文字中间时两侧文字各归各位`() {
        val render = render("a:smile:b")

        assertEquals("a${EMOJI_INPUT_PLACEHOLDER}b", render.display)
        assertEquals("a", render.display.substring(0, 1))
        assertEquals("b", render.display.substring(2))
    }

    @Test
    fun `光标在短码之前落在图片之前 之后的任何位置都落在图片之后`() {
        // "hi :smile:" 的短码是 [3, 10)。
        val render = render("hi :smile:")

        assertEquals(3, render.originalToTransformed(3)) // 短码开头 = 图片前
        for (offset in 4..10) {
            // 短码内部与末尾一律吸附到图片之后：插表情后光标就该在图片右边
            assertEquals(4, render.originalToTransformed(offset))
        }
    }

    @Test
    fun `显示偏移能回到原文边界`() {
        val render = render("hi :smile:")

        assertEquals(0, render.transformedToOriginal(0))
        assertEquals(3, render.transformedToOriginal(3))  // 占位字符 = 短码开头
        assertEquals(10, render.transformedToOriginal(4)) // 占位之后 = 短码结尾
    }

    @Test
    fun `两个方向的映射都单调不减`() {
        val render = render("x :smile: y :tieba_087: z")

        var previous = -1
        for (offset in 0..render.modelLength) {
            val transformed = render.originalToTransformed(offset)
            assertTrue("原文 $offset 处映射回退", transformed >= previous)
            previous = transformed
        }

        previous = -1
        for (offset in 0..render.displayLength) {
            val original = render.transformedToOriginal(offset)
            assertTrue("显示 $offset 处映射回退", original >= previous)
            previous = original
        }
    }

    @Test
    fun `每个显示偏移都能原样映射回来`() {
        // 反向不变式：显示位置的映射必须自洽（否则光标会漂到别的字上）。
        val render = render("x :smile: y :tieba_087: z")

        for (displayOffset in 0..render.displayLength) {
            val original = render.transformedToOriginal(displayOffset)
            assertEquals(displayOffset, render.originalToTransformed(original))
        }
    }
}
