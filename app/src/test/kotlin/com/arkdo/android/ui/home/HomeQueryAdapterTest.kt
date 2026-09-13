package com.arkdo.android.ui.home

import com.arkdo.android.core.model.HomeFeed
import com.arkdo.android.data.models.FeedKey
import com.arkdo.android.data.models.TopicCategory
import com.arkdo.android.data.models.TopicTag
import com.arkdo.android.services.topic.TopicListQuery
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeQueryAdapterTest {
    @Test
    fun maps_legacy_feed_scope_and_tag_identity() {
        val query = TopicListQuery(
            filter = FeedKey.CREATED.value,
            category = TopicCategory(id = 42, name = "Android", slug = "android"),
            tag = TopicTag(id = 7, name = "Kotlin", slug = "kotlin")
        )

        assertEquals(
            com.arkdo.android.core.model.HomeQuery(
                feed = HomeFeed.CREATED,
                categoryId = 42,
                categorySlug = "android",
                categoryName = "Android",
                tag = "Kotlin",
                tagId = 7,
                tagSlug = "kotlin"
            ),
            query.toHomeQuery()
        )
    }
}
