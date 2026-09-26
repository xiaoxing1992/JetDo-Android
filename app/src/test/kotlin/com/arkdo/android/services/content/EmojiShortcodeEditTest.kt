package com.arkdo.android.services.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 短码整段删除单测。
 *
 * 背景：Compose 的 BasicTextField 没有鸿蒙 TextArea 那个 `onWillDeleteText` 钩子，
 * 所以这里改成**比对删除前后的文本**反推"用户删了哪个字符"。纯函数，边界多，值得钉住。
 *
 * 只识别一组已知短码，模拟真实目录。
 */
class EmojiShortcodeEditTest {

    private val known = setOf("smile", "tieba_087")
    private val isKnown: (String) -> Boolean = { it in known }

    @Test
    fun `退格删掉收尾冒号时整段短码一起删`() {
        // "hello :smile:" → 用户退格删掉最后的 ':' → 应该把 ":smile" 也带走
        val result = normalizeEmojiDeletion("hello :smile:", "hello :smile", isKnown)

        assertEquals("hello ", result?.text)
        assertEquals(6, result?.caret)
    }

    @Test
    fun `整条就是短码时删完剩空串`() {
        val result = normalizeEmojiDeletion(":smile:", ":smile", isKnown)

        assertEquals("", result?.text)
        assertEquals(0, result?.caret)
    }

    @Test
    fun `删除键删掉开头冒号时整段短码一起删`() {
        // ":smile:x" → 用户在开头按删除键 → 应该连 ":smile:" 一起去掉
        val result = normalizeEmojiDeletion(":smile:x", "smile:x", isKnown)

        assertEquals("x", result?.text)
        assertEquals(0, result?.caret)
    }

    @Test
    fun `下划线短码同样适用`() {
        val result = normalizeEmojiDeletion("a :tieba_087:", "a :tieba_087", isKnown)

        assertEquals("a ", result?.text)
        assertEquals(2, result?.caret)
    }

    @Test
    fun `未知短码不干预`() {
        assertNull(normalizeEmojiDeletion("hello :nope:", "hello :nope", isKnown))
        assertNull(normalizeEmojiDeletion(":nope:x", "nope:x", isKnown))
    }

    @Test
    fun `删掉的不是冒号则不干预`() {
        assertNull(normalizeEmojiDeletion("hello", "hell", isKnown))
        assertNull(normalizeEmojiDeletion("smile:", "smile", isKnown)) // 删的是 ':'，但前面没有开冒号
    }

    @Test
    fun `一次删多个字符不干预`() {
        // 选中一段删掉 / 整段替换：用户意图已经足够明确，不该再扩大删除范围。
        assertNull(normalizeEmojiDeletion(":smile:", "", isKnown))
        assertNull(normalizeEmojiDeletion("abc", "a", isKnown))
    }

    @Test
    fun `不是删除(新增或等长替换)一律不干预`() {
        assertNull(normalizeEmojiDeletion(":smile", ":smile:", isKnown))
        assertNull(normalizeEmojiDeletion("ab", "ab", isKnown))
        assertNull(normalizeEmojiDeletion("bc", "ac", isKnown))
    }

    @Test
    fun `光标处的冒号不是任何短码边界时不干预`() {
        // "x:y:" 删掉最后的 ':'，往前最近的 ':' 之间是 "y"，不是已知表情。
        assertNull(normalizeEmojiDeletion("x:y:", "x:y", isKnown))
    }

    @Test
    fun `相邻短码时只删紧邻的那一个`() {
        // ":smile::nope:" 退格删最后一个 ':' → 往前是 "nope"（未知）→ 不干预，
        // 不能误伤前面那个已知的 ":smile:"。
        assertNull(normalizeEmojiDeletion(":smile::nope:", ":smile::nope", isKnown))
    }
}
