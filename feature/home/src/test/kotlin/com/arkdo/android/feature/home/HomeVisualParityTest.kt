package com.arkdo.android.feature.home

import com.arkdo.android.core.model.HomeTag
import com.arkdo.android.core.model.HomeTopic
import com.arkdo.android.core.model.HomeUser
import com.arkdo.android.core.model.HomeCategory
import com.arkdo.android.core.model.HomeQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeVisualParityTest {
    @Test
    fun unread_topic_uses_first_unread_post_as_row_target() {
        val topic = HomeTopic(
            id = 1,
            title = "Unread",
            unreadPosts = 3,
            lastReadPostNumber = 8,
            highestPostNumber = 10
        )

        assertEquals(9, homeUnreadTarget(topic))
    }

    @Test
    fun avatar_candidates_keep_animated_before_static_and_resolve_paths() {
        val user = HomeUser(
            id = 2,
            username = "alice",
            animatedAvatar = "/user_avatar/alice/96/animated.webp",
            avatarTemplate = "/user_avatar/alice/{size}/static.png"
        )

        assertEquals(
            listOf(
                "https://linux.do/user_avatar/alice/96/animated.webp",
                "https://linux.do/user_avatar/alice/96/static.png"
            ),
            homeAvatarCandidates(user, "https://linux.do")
        )
    }

    @Test
    fun metadata_is_kept_when_only_tag_or_time_exists() {
        val topic = HomeTopic(
            id = 3,
            title = "Topic",
            tags = listOf(HomeTag("android")),
            lastPostedAt = null
        )

        assertTrue(homeHasMetadata(topic, category = null, relativeTime = ""))
        assertTrue(homeHasMetadata(topic.copy(tags = emptyList()), category = null, relativeTime = "刚刚"))
    }

    @Test
    fun category_resolution_prefers_topic_then_index_then_selected_query() {
        val topic = HomeTopic(id = 4, title = "Topic", categoryId = 9)
        val indexed = HomeCategory(id = 9, name = "Android")
        val query = HomeQuery(categoryId = 9, categoryName = "Selected")

        assertEquals(indexed, homeCategoryForTopic(topic, listOf(indexed), query))
        assertEquals(
            "Selected",
            homeCategoryForTopic(topic, emptyList(), query)?.name
        )
        val embedded = HomeCategory(id = 9, name = "Embedded")
        assertEquals(
            embedded,
            homeCategoryForTopic(topic.copy(category = embedded), listOf(indexed), query)
        )
    }
}
