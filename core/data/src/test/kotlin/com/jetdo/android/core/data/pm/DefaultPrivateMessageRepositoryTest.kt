package com.jetdo.android.core.data.pm

import com.jetdo.android.core.model.HomePageResult
import com.jetdo.android.core.network.ChallengeState
import com.jetdo.android.core.network.JsonNetworkClient
import com.jetdo.android.core.network.JsonRequestOptions
import com.jetdo.android.core.network.NetworkResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultPrivateMessageRepositoryTest {

    @Test
    fun `收件箱走 private-messages 路径并解析 topic_list`() = runTest {
        val client = FakeClient(
            NetworkResult(
                true, 200, ChallengeState.NONE,
                """
                {"users":[{"id":7,"username":"ada","name":"Ada","avatar_template":"/a/{size}.png"}],
                 "topic_list":{
                   "more_topics_url":"/topics/private-messages/ada.json?page=1",
                   "topics":[
                     {"id":30,"title":"头像真可爱","slug":"pm-30","posts_count":3,
                      "last_posted_at":"2026-09-08T00:00:00.000Z",
                      "posters":[{"user_id":7}]}
                   ]}}
                """.trimIndent()
            )
        )

        val page = (DefaultPrivateMessageRepository(client).inbox("ada", 0) as HomePageResult.Success).page

        assertEquals("/topics/private-messages/ada.json", client.path)
        assertEquals(1, page.items.size)
        assertEquals(30L, page.items.single().id)
        assertEquals("头像真可爱", page.items.single().title)
        assertTrue(page.hasMore)
    }

    @Test
    fun `第二页拼 page 查询参数`() = runTest {
        val client = FakeClient(NetworkResult(true, 200, ChallengeState.NONE, """{"topic_list":{"topics":[]}}"""))

        DefaultPrivateMessageRepository(client).inbox("ada", 2)

        assertEquals("/topics/private-messages/ada.json?page=2", client.path)
    }

    @Test
    fun `已发送与归档各有独立路径段`() = runTest {
        val client = FakeClient(NetworkResult(true, 200, ChallengeState.NONE, """{"topic_list":{"topics":[]}}"""))
        val repo = DefaultPrivateMessageRepository(client)

        repo.sent("ada", 0)
        assertEquals("/topics/private-messages-sent/ada.json", client.path)

        repo.archive("ada", 0)
        assertEquals("/topics/private-messages-archive/ada.json", client.path)
    }

    @Test
    fun `用户名含特殊字符时被转义`() = runTest {
        val client = FakeClient(NetworkResult(true, 200, ChallengeState.NONE, """{"topic_list":{"topics":[]}}"""))

        DefaultPrivateMessageRepository(client).inbox("a b", 0)

        assertEquals("/topics/private-messages/a+b.json", client.path)
    }

    @Test
    fun `响应缺少 topic_list 时给出失败而不是空列表`() = runTest {
        val client = FakeClient(NetworkResult(true, 200, ChallengeState.NONE, """{"users":[]}"""))

        val result = DefaultPrivateMessageRepository(client).inbox("ada", 0)

        assertTrue(result is HomePageResult.Failure)
    }
}

private class FakeClient(private val response: NetworkResult<String>) : JsonNetworkClient {
    var path: String? = null

    override suspend fun getJson(path: String, options: JsonRequestOptions?): NetworkResult<String> {
        this.path = path
        return response
    }

    override suspend fun getJsonWithHeaders(
        path: String,
        headers: List<com.jetdo.android.core.network.HttpHeaderEntry>,
        referrer: String,
        options: JsonRequestOptions?
    ) = response

    override suspend fun postForm(path: String, body: String, options: JsonRequestOptions?) = response
    override suspend fun postJson(path: String, body: String, options: JsonRequestOptions?) = response
    override suspend fun putJson(path: String, options: JsonRequestOptions?): NetworkResult<String> {
        this.path = path
        return response
    }
    override suspend fun putForm(path: String, body: String, options: JsonRequestOptions?) = response
    override suspend fun deleteForm(path: String, body: String, options: JsonRequestOptions?) = response
    override suspend fun uploadImage(
        base64: String,
        fileName: String,
        mimeType: String,
        options: JsonRequestOptions?
    ) = response
}
