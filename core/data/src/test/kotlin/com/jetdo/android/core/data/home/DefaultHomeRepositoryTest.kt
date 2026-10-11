package com.jetdo.android.core.data.home

import com.jetdo.android.core.model.HomeFeed
import com.jetdo.android.core.model.HomePageResult
import com.jetdo.android.core.model.HomeQuery
import com.jetdo.android.core.network.ChallengeState
import com.jetdo.android.core.network.JsonNetworkClient
import com.jetdo.android.core.network.JsonRequestOptions
import com.jetdo.android.core.network.NetworkError
import com.jetdo.android.core.network.NetworkErrorCode
import com.jetdo.android.core.network.NetworkResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultHomeRepositoryTest {
    @Test
    fun load_maps_topic_author_and_tags() = runTest {
        val client = FakeHomeNetworkClient(
            response = NetworkResult(
                success = true,
                statusCode = 200,
                challengeState = ChallengeState.NONE,
                data = """
                    {"users":[{"id":7,"username":"ada","name":"Ada","avatar_template":"/u/ada/{size}/x.png",
                        "animated_avatar":"/u/ada/animated.gif","flair_url":"award.svg","flair_bg_color":"112233"}],
                     "topic_list":{"more_topics_url":"/latest.json?page=1","topics":[
                       {"id":11,"title":"Hello","slug":"hello","category_id":3,"reply_count":4,
                        "last_posted_at":"2026-09-12T10:00:00.000Z","unread_posts":2,
                        "last_read_post_number":8,"highest_post_number":10,
                        "excerpt":"summary","created_at":"2026-09-11T10:00:00.000Z",
                        "category":{"id":3,"name":"开发","slug":"dev","color":"112233","text_color":"FFFFFF","icon":"code","read_restricted":true},
                        "posters":[{"user_id":7}],
                        "tags":["kotlin"]}]}}
                """.trimIndent()
            )
        )

        val result = DefaultHomeRepository(client).load(HomeQuery(), 0)

        assertTrue(result is HomePageResult.Success)
        val item = (result as HomePageResult.Success).page.items.single()
        assertEquals(11L, item.id)
        assertEquals("Ada", item.author?.name)
        assertEquals("/u/ada/animated.gif", item.author?.animatedAvatar)
        assertEquals("award.svg", item.author?.flairUrl)
        assertEquals("kotlin", item.tags.single().name)
        assertEquals("开发", item.category?.name)
        assertEquals("summary", item.searchBlurb)
        assertEquals("code", item.category?.icon)
        assertTrue(item.category?.readRestricted == true)
        assertEquals(9, item.firstUnreadPostNumber())
        assertTrue((result as HomePageResult.Success).page.hasMore)
        assertEquals("/latest.json", client.lastPath)
    }

    @Test
    fun load_uses_hot_page_path() = runTest {
        val client = FakeHomeNetworkClient(
            response = NetworkResult(true, 200, ChallengeState.NONE, data = "{\"topic_list\":{\"topics\":[]}}")
        )

        DefaultHomeRepository(client).load(HomeQuery(feed = HomeFeed.HOT), 2)

        assertEquals("/hot.json?page=2", client.lastPath)
    }

    @Test
    fun load_preserves_legacy_created_category_and_tag_path() = runTest {
        val client = FakeHomeNetworkClient(
            response = NetworkResult(true, 200, ChallengeState.NONE, data = "{\"topic_list\":{\"topics\":[]}}")
        )

        DefaultHomeRepository(client).load(
            HomeQuery(
                feed = HomeFeed.CREATED,
                categoryId = 42L,
                categorySlug = "android",
                tag = "kotlin",
                tagId = 7L,
                tagSlug = "kotlin"
            ),
            2
        )

        assertEquals("/tags/c/android/42/kotlin/7/l/latest.json?order=created&page=2", client.lastPath)
    }

    @Test
    fun load_returns_stable_failure_for_network_error() = runTest {
        val client = FakeHomeNetworkClient(
            response = NetworkResult(
                false, 503, ChallengeState.REQUIRED,
                error = NetworkError(NetworkErrorCode.CHALLENGE_REQUIRED, "challenge", true)
            )
        )

        val result = DefaultHomeRepository(client).load(HomeQuery(), 0)

        assertEquals(HomePageResult.Failure("challenge", true), result)
    }

    @Test
    fun loadByIds_requests_latest_topics_without_duplicate_ids() = runTest {
        val client = FakeHomeNetworkClient(
            response = NetworkResult(true, 200, ChallengeState.NONE, data = "{\"topic_list\":{\"topics\":[]}}")
        )

        DefaultHomeRepository(client).loadByIds(listOf(8L, 8L, 3L))

        assertEquals("/latest.json?topic_ids=8,3", client.lastPath)
    }
}

private class FakeHomeNetworkClient(
    private val response: NetworkResult<String>
) : JsonNetworkClient {
    var lastPath: String? = null

    override suspend fun getJson(path: String, options: JsonRequestOptions?): NetworkResult<String> {
        lastPath = path
        return response
    }

    override suspend fun getJsonWithHeaders(path: String, headers: List<com.jetdo.android.core.network.HttpHeaderEntry>, referrer: String, options: JsonRequestOptions?) = response
    override suspend fun postForm(path: String, body: String, options: JsonRequestOptions?) = response
    override suspend fun postJson(path: String, body: String, options: JsonRequestOptions?) = response
    override suspend fun putJson(path: String, options: JsonRequestOptions?) = response
    override suspend fun putForm(path: String, body: String, options: JsonRequestOptions?) = response
    override suspend fun deleteForm(path: String, body: String, options: JsonRequestOptions?) = response
    override suspend fun uploadImage(base64: String, fileName: String, mimeType: String, options: JsonRequestOptions?) = response
}
