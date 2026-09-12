package com.arkdo.android.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeModelsTest {
    @Test
    fun query_preserves_selected_feed_and_scope() {
        val query = HomeQuery(feed = HomeFeed.HOT, categoryId = 42L, tag = "kotlin")

        assertEquals(HomeFeed.HOT, query.feed)
        assertEquals(42L, query.categoryId)
        assertEquals("kotlin", query.tag)
    }

    @Test
    fun page_reports_more_when_server_has_next_page() {
        val page = HomePage(items = listOf(topic(1)), page = 0, hasMore = true)

        assertTrue(page.hasMore)
        assertEquals(1L, page.items.single().id)
    }

    private fun topic(id: Long) = HomeTopic(id = id, title = "Topic $id")
}
