package com.arkdo.android.core.data.session

import com.arkdo.android.core.data.notification.FakeJsonNetworkClient
import com.arkdo.android.core.model.HomeTag
import com.arkdo.android.core.network.ChallengeState
import com.arkdo.android.core.network.NetworkResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultSidebarPrefsSourceTest {

    private fun client(body: String) = FakeJsonNetworkClient().apply {
        getResponse = NetworkResult(true, 200, ChallengeState.NONE, body)
    }

    @Test
    fun load_parses_sidebar_categories_and_tags() = runTest {
        val source = DefaultSidebarPrefsSource(
            client(
                """
                {"current_user":{
                  "id":7,"username":"petter",
                  "sidebar_category_ids":[4,9],
                  "sidebar_tags":[{"id":1461,"name":"纯水","slug":"1461-tag"},
                                  {"name":"没有id"}
                                 ]
                }}
                """.trimIndent()
            )
        )

        val prefs = source.load()

        assertEquals(listOf(4L, 9L), prefs.categoryIds)
        assertEquals(2, prefs.tags.size)
        assertEquals(HomeTag(name = "纯水", id = 1461L, slug = "1461-tag"), prefs.tags[0])
        assertEquals(HomeTag(name = "没有id", id = null, slug = null), prefs.tags[1])
        assertFalse(prefs.isEmpty)
    }

    @Test
    fun load_returns_empty_when_logged_out() = runTest {
        // Discourse 对匿名请求回 404 + 空 body，不是 200 + current_user:null。
        val client = FakeJsonNetworkClient().apply {
            getResponse = NetworkResult(
                success = false,
                statusCode = 404,
                challengeState = ChallengeState.NONE,
                data = null
            )
        }

        val prefs = DefaultSidebarPrefsSource(client).load()

        assertTrue(prefs.isEmpty)
        assertEquals("/session/current.json", client.lastGetPath)
    }

    @Test
    fun load_returns_empty_when_user_never_configured_sidebar() = runTest {
        val prefs = DefaultSidebarPrefsSource(
            client("""{"current_user":{"id":7,"username":"petter"}}""")
        ).load()

        assertTrue(prefs.isEmpty)
    }

    @Test
    fun load_returns_empty_for_malformed_body() = runTest {
        val prefs = DefaultSidebarPrefsSource(client("not json")).load()

        assertTrue(prefs.isEmpty)
    }
}
