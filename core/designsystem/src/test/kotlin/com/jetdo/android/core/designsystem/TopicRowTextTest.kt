package com.jetdo.android.core.designsystem

import com.jetdo.android.core.model.HomeTag
import com.jetdo.android.core.model.HomeTopic
import com.jetdo.android.core.model.HomeUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TopicRowTextTest {
    @Test
    fun unread_topic_uses_first_unread_post_as_row_target() {
        val topic = HomeTopic(
            id = 1,
            title = "Unread",
            unreadPosts = 3,
            lastReadPostNumber = 8,
            highestPostNumber = 10
        )

        assertEquals(9, topicUnreadTarget(topic))
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
            topicAvatarCandidates(user, "https://linux.do")
        )
    }

    @Test
    fun avatar_candidates_skip_animated_when_dynamic_avatar_disabled() {
        val user = HomeUser(
            id = 2,
            username = "alice",
            animatedAvatar = "/user_avatar/alice/96/animated.webp",
            avatarTemplate = "/user_avatar/alice/{size}/static.png"
        )

        assertEquals(
            listOf("https://linux.do/user_avatar/alice/96/static.png"),
            topicAvatarCandidates(user, "https://linux.do", includeAnimated = false)
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

        assertTrue(topicHasMetadata(topic, category = null, relativeTime = ""))
        assertTrue(topicHasMetadata(topic.copy(tags = emptyList()), category = null, relativeTime = "刚刚"))
    }

    @Test
    fun search_segments_keep_original_case_and_pick_the_earliest_term() {
        assertEquals(
            listOf(SearchTextSegment("还没命中", hit = false)),
            searchTextSegments("还没命中", emptyList())
        )
        assertEquals(
            listOf(
                SearchTextSegment("用 ", hit = false),
                SearchTextSegment("Kotlin", hit = true),
                SearchTextSegment(" 写 ", hit = false),
                SearchTextSegment("协程", hit = true)
            ),
            searchTextSegments("用 Kotlin 写 协程", listOf("kotlin", "", "协程"))
        )
        // 同一起点时保留列表里更靠前的词，不改成更长的那个。
        assertEquals(
            listOf(SearchTextSegment("kot", hit = true), SearchTextSegment("lin", hit = false)),
            searchTextSegments("kotlin", listOf("kot", "kotlin"))
        )
        assertEquals(emptyList<SearchTextSegment>(), searchTextSegments("", listOf("kotlin")))
    }

    @Test
    fun asset_paths_keep_absolute_protocol_relative_and_site_relative() {
        assertEquals("https://cdn.example/a.png", resolveAsset("https://cdn.example/a.png", "https://linux.do"))
        assertEquals("https://cdn.example/a.png", resolveAsset("//cdn.example/a.png", "https://linux.do"))
        assertEquals("https://linux.do/uploads/a.png", resolveAsset("/uploads/a.png", "https://linux.do/"))
        assertEquals("https://linux.do/uploads/a.png", resolveAsset("uploads/a.png", "https://linux.do"))
    }
}
