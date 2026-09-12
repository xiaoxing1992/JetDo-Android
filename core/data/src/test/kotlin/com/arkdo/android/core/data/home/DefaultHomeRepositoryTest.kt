package com.arkdo.android.core.data.home

import com.arkdo.android.core.model.HomeFeed
import com.arkdo.android.core.model.HomePageResult
import com.arkdo.android.core.model.HomeQuery
import com.arkdo.android.core.network.ChallengeState
import com.arkdo.android.core.network.JsonNetworkClient
import com.arkdo.android.core.network.JsonRequestOptions
import com.arkdo.android.core.network.NetworkError
import com.arkdo.android.core.network.NetworkErrorCode
import com.arkdo.android.core.network.NetworkResult
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
                    {"users":[{"id":7,"username":"ada","name":"Ada","avatar_template":"/u/ada/{size}/x.png"}],
                     "topic_list":{"more_topics_url":"/latest.json?page=1","topics":[
                       {"id":11,"title":"Hello","slug":"hello","category_id":3,"reply_count":4,
                        "last_posted_at":"2026-09-12T10:00:00.000Z","unread_posts":2,"posters":[{"user_id":7}],
                        "tags":["kotlin"]}]}}
                """.trimIndent()
            )
        )

        val result = DefaultHomeRepository(client).load(HomeQuery(), 0)

        assertTrue(result is HomePageResult.Success)
        val item = (result as HomePageResult.Success).page.items.single()
        assertEquals(11L, item.id)
        assertEquals("Ada", item.author?.name)
        assertEquals("kotlin", item.tags.single().name)
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
}

private class FakeHomeNetworkClient(
    private val response: NetworkResult<String>
) : JsonNetworkClient {
    var lastPath: String? = null

    override suspend fun getJson(path: String, options: JsonRequestOptions?): NetworkResult<String> {
        lastPath = path
        return response
    }

    override suspend fun getJsonWithHeaders(path: String, headers: List<com.arkdo.android.core.network.HttpHeaderEntry>, referrer: String, options: JsonRequestOptions?) = response
    override suspend fun postForm(path: String, body: String, options: JsonRequestOptions?) = response
    override suspend fun postJson(path: String, body: String, options: JsonRequestOptions?) = response
    override suspend fun putJson(path: String, options: JsonRequestOptions?) = response
    override suspend fun putForm(path: String, body: String, options: JsonRequestOptions?) = response
    override suspend fun deleteForm(path: String, body: String, options: JsonRequestOptions?) = response
    override suspend fun uploadImage(base64: String, fileName: String, mimeType: String, options: JsonRequestOptions?) = response
}
