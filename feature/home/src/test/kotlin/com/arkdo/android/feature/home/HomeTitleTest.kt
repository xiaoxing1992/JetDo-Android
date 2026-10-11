package com.arkdo.android.feature.home

import com.arkdo.android.core.model.HomeCategory
import com.arkdo.android.core.model.HomeFeed
import com.arkdo.android.core.model.HomeQuery
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeTitleTest {
    @Test
    fun default_query_uses_app_name() {
        assertEquals("JetDo", homeTitle(HomeQuery()))
    }

    @Test
    fun category_has_priority_over_feed_and_tag() {
        assertEquals(
            "开发",
            homeTitle(
                HomeQuery(
                    feed = HomeFeed.HOT,
                    categoryId = 3,
                    categorySlug = "dev",
                    tag = "kotlin",
                    tagSlug = "kotlin"
                ),
                categoryName = "开发"
            )
        )
    }

    @Test
    fun non_default_feed_uses_feed_label_without_scope() {
        assertEquals("热门", homeTitle(HomeQuery(feed = HomeFeed.HOT)))
    }
}
