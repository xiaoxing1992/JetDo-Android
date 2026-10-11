package com.jetdo.android.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeModelsTest {
    @Test
    fun query_preserves_selected_feed_and_scope() {
        val query = HomeQuery(
            feed = HomeFeed.HOT,
            categoryId = 42L,
            categorySlug = "android",
            tag = "kotlin",
            tagId = 7L,
            tagSlug = "kotlin"
        )

        assertEquals(HomeFeed.HOT, query.feed)
        assertEquals(42L, query.categoryId)
        assertEquals("kotlin", query.tag)
        assertEquals("android", query.categorySlug)
        assertEquals(7L, query.tagId)
        assertEquals("kotlin", query.tagSlug)
    }

    @Test
    fun feed_covers_all_legacy_home_filters() {
        assertEquals(
            listOf("latest", "created", "top", "hot", "unread", "new"),
            HomeFeed.entries.map(HomeFeed::apiValue)
        )
    }

    @Test
    fun page_reports_more_when_server_has_next_page() {
        val page = HomePage(items = listOf(topic(1)), page = 0, hasMore = true)

        assertTrue(page.hasMore)
        assertEquals(1L, page.items.single().id)
    }

    @Test
    fun topic_calculates_first_unread_post_and_caps_at_highest_post() {
        val topic = HomeTopic(
            id = 2,
            title = "Unread",
            lastReadPostNumber = 9,
            highestPostNumber = 10
        )

        assertEquals(10, topic.firstUnreadPostNumber())
    }

    @Test
    fun topic_without_read_history_opens_from_the_top() {
        val topic = HomeTopic(id = 3, title = "Unread", lastReadPostNumber = 0)

        assertEquals(null, topic.firstUnreadPostNumber())
    }

    private fun topic(id: Long) = HomeTopic(id = id, title = "Topic $id")
}
