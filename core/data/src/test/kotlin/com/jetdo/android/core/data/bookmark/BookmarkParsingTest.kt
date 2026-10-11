package com.jetdo.android.core.data.bookmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookmarkParsingTest {
    @Test
    fun `摘要去掉标签并解开实体，空白压成一格`() {
        val raw = "<p>开头&amp;结尾</p>  <a class=\"mention\">@ada</a>"
        assertEquals("开头&结尾 @ada", bookmarkPlainText(raw))
    }

    @Test
    fun `空摘要返回空串`() {
        assertEquals("", bookmarkPlainText(null))
        assertEquals("", bookmarkPlainText("   "))
    }

    @Test
    fun `标题优先 fancy_title 并解开实体`() {
        assertEquals("A&B", bookmarkTitle("A&amp;B", "plain"))
        assertEquals("plain", bookmarkTitle(null, "plain"))
        assertEquals("", bookmarkTitle(null, null))
    }

    @Test
    fun `楼号缺省或小于等于 0 时当成主楼`() {
        assertEquals(1, bookmarkPostNumber(null))
        assertEquals(1, bookmarkPostNumber(0))
        assertEquals(4, bookmarkPostNumber(4))
    }

    @Test
    fun `置顶的排在前面，组内保持原顺序`() {
        val items = listOf(
            sample(1, pinned = false),
            sample(2, pinned = true),
            sample(3, pinned = false),
            sample(4, pinned = true)
        )
        assertEquals(listOf(2L, 4L, 1L, 3L), sortPinnedFirst(items).map { it.id })
    }

    @Test
    fun `没有收藏时间时不显示`() {
        assertNull(bookmarkTimeLabel(null))
        assertNull(bookmarkTimeLabel(""))
    }

    private fun sample(id: Long, pinned: Boolean) = BookmarkItem(
        id = id,
        topicId = id,
        title = "t$id",
        excerpt = "",
        linkedPostNumber = 1,
        pinned = pinned,
        categoryId = null,
        tags = emptyList(),
        author = null,
        highestPostNumber = null,
        createdAt = null
    )
}
