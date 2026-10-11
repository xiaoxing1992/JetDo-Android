package com.jetdo.android.feature.home

import com.jetdo.android.core.model.HomeTag
import com.jetdo.android.core.model.HomeTopic
import com.jetdo.android.core.model.HomeUser
import com.jetdo.android.core.model.HomeCategory
import com.jetdo.android.core.model.HomeQuery
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

    @Test
    fun search_segments_keep_original_case_and_pick_the_earliest_term() {
        assertEquals(
            listOf(HomeTextSegment("还没命中", hit = false)),
            homeSearchSegments("还没命中", emptyList())
        )
        assertEquals(
            listOf(
                HomeTextSegment("用 ", hit = false),
                HomeTextSegment("Kotlin", hit = true),
                HomeTextSegment(" 写 ", hit = false),
                HomeTextSegment("协程", hit = true)
            ),
            homeSearchSegments("用 Kotlin 写 协程", listOf("kotlin", "", "协程"))
        )
        // 同一起点时保留列表里更靠前的词，不改成更长的那个。
        assertEquals(
            listOf(HomeTextSegment("kot", hit = true), HomeTextSegment("lin", hit = false)),
            homeSearchSegments("kotlin", listOf("kot", "kotlin"))
        )
        assertEquals(emptyList<HomeTextSegment>(), homeSearchSegments("", listOf("kotlin")))
    }

    @Test
    fun incoming_insert_slides_old_rows_down_and_fades_new_rows_in() {
        assertEquals(0f, incomingInsertOffset(progress = 1f, distance = 180f))
        assertEquals(-180f, incomingInsertOffset(progress = 0f, distance = 180f))
        assertEquals(-90f, incomingInsertOffset(progress = 0.5f, distance = 180f))
        assertEquals(0f, incomingInsertOffset(progress = 0f, distance = 0f))
        assertEquals(0f, incomingInsertAlpha(progress = 0f, fadeIn = true))
        assertEquals(1f, incomingInsertAlpha(progress = 1f, fadeIn = true))
        assertEquals(1f, incomingInsertAlpha(progress = 0f, fadeIn = false))
        assertEquals(900L, incomingHighlightHoldMs(1500L))
        assertEquals(0L, incomingHighlightHoldMs(400L))
    }

    @Test
    fun incoming_prompt_digits_roll_enter_and_exit() {
        val roll = incomingDigitColumns(375, 376)
        assertEquals(listOf("3", "7", "5"), roll.map { it.previous })
        assertEquals(listOf("3", "7", "6"), roll.map { it.current })
        assertEquals(
            listOf(IncomingDigitKind.STABLE, IncomingDigitKind.STABLE, IncomingDigitKind.ROLL),
            roll.map { it.kind }
        )

        val enter = incomingDigitColumns(99, 100)
        assertEquals("", enter[0].previous)
        assertEquals("1", enter[0].current)
        assertEquals(IncomingDigitKind.ENTER, enter[0].kind)
        assertEquals(IncomingDigitKind.ROLL, enter[1].kind)
        assertEquals("9", enter[1].previous)
        assertEquals("0", enter[1].current)

        val exit = incomingDigitColumns(10, 1)
        assertEquals("1", exit[0].previous)
        assertEquals("", exit[0].current)
        assertEquals(IncomingDigitKind.EXIT, exit[0].kind)
        assertEquals("0", exit[1].previous)
        assertEquals("1", exit[1].current)

        assertEquals(0f, incomingDigitColumnWidth(IncomingDigitKind.ENTER, 0f, 9f))
        assertEquals(9f, incomingDigitColumnWidth(IncomingDigitKind.ENTER, 1f, 9f))
        assertEquals(9f, incomingDigitColumnWidth(IncomingDigitKind.EXIT, 0f, 9f))
        assertEquals(0f, incomingDigitColumnWidth(IncomingDigitKind.EXIT, 1f, 9f))
        assertEquals(9f, incomingDigitColumnWidth(IncomingDigitKind.STABLE, 0.5f, 9f))
        assertEquals(9f, incomingDigitColumnWidth(IncomingDigitKind.ROLL, 0.5f, 9f))
    }
}
