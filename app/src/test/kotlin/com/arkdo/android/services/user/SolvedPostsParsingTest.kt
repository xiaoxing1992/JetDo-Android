package com.arkdo.android.services.user

import com.arkdo.android.services.network.ChallengeState
import com.arkdo.android.services.network.JsonNetworkClient
import com.arkdo.android.services.network.JsonRequestOptions
import com.arkdo.android.services.network.NetworkResult
import com.arkdo.android.services.network.NetworkResults
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SolvedPostsParsingTest {

    @Test
    fun plainTextStripsTagsAndDecodesEntities() {
        assertEquals("开头&结尾", solvedPlainText("<p>开头&amp;结尾</p>"))
        assertEquals("", solvedPlainText(null))
        assertEquals("", solvedPlainText("   "))
    }

    @Test
    fun readsUserSolvedPostsAndKeepsHasMoreOnRawLength() = runBlocking {
        val body = """
            {"user_solved_posts":[
              {"post_id":9,"topic_id":11,"post_number":3,"topic_title":"怎么配","slug":"how","excerpt":"<p>先&amp;后</p>","category_id":4,"created_at":"2026-01-02T00:00:00.000Z"},
              {"topic_id":12,"topic_title":"","excerpt":"丢"},
              {"post_id":8,"topic_title":"没有话题号"}
            ]}
        """.trimIndent()
        val service = UserProfileService(FixedClient(body))

        val page = service.getSolvedPosts("neo", 0, limit = 3).data!!

        assertEquals(1, page.items.size)
        val item = page.items.single()
        assertEquals(9L, item.postId)
        assertEquals(11L, item.topicId)
        assertEquals(3, item.postNumber)
        assertEquals("怎么配", item.topicTitle)
        assertEquals("先&后", item.excerpt)
        assertEquals(4L, item.categoryId)
        assertTrue(page.hasMore)
    }

    @Test
    fun hasMoreIsFalseWhenRawPageIsShorterThanLimit() = runBlocking {
        val body = """{"user_solved_posts":[{"topic_id":1,"topic_title":"一条"}]}"""
        val service = UserProfileService(FixedClient(body))

        val page = service.getSolvedPosts("neo", 0).data!!

        assertEquals(1, page.items.size)
        assertEquals(0L, page.items.single().postId)
        assertFalse(page.hasMore)
    }
}

private class FixedClient(private val body: String) : JsonNetworkClient {
    override suspend fun getJson(path: String, options: JsonRequestOptions?) = NetworkResult(
        success = true, statusCode = 200, challengeState = ChallengeState.NONE, data = body
    )

    override suspend fun getJsonWithHeaders(
        path: String,
        headers: List<com.arkdo.android.services.network.HttpHeaderEntry>,
        referrer: String,
        options: JsonRequestOptions?
    ) = NetworkResults.networkFailure<String>(0, "unused")

    override suspend fun postForm(path: String, body: String, options: JsonRequestOptions?) =
        NetworkResults.networkFailure<String>(0, "unused")

    override suspend fun postJson(path: String, body: String, options: JsonRequestOptions?) =
        NetworkResults.networkFailure<String>(0, "unused")

    override suspend fun putJson(path: String, options: JsonRequestOptions?) =
        NetworkResults.networkFailure<String>(0, "unused")

    override suspend fun putForm(path: String, body: String, options: JsonRequestOptions?) =
        NetworkResults.networkFailure<String>(0, "unused")

    override suspend fun deleteForm(path: String, body: String, options: JsonRequestOptions?) =
        NetworkResults.networkFailure<String>(0, "unused")

    override suspend fun uploadImage(
        base64: String,
        fileName: String,
        mimeType: String,
        options: JsonRequestOptions?
    ) = NetworkResults.networkFailure<String>(0, "unused")
}
