package com.arkdo.android.core.data.bookmark

import com.arkdo.android.core.model.HomePageResult
import com.arkdo.android.core.network.ChallengeState
import com.arkdo.android.core.network.JsonNetworkClient
import com.arkdo.android.core.network.JsonRequestOptions
import com.arkdo.android.core.network.NetworkResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkRepositoryTest {
    @Test
    fun `第一页解析书签并带上下一页地址`() = runTest {
        val client = FakeClient(
            NetworkResult(
                true, 200, ChallengeState.NONE,
                """
                {"user_bookmark_list":{
                  "more_bookmarks_url":"/u/ada/bookmarks.json?page=1",
                  "bookmarks":[
                    {"id":9,"topic_id":30,"fancy_title":"A&amp;B","excerpt":"<p>摘要</p>",
                     "linked_post_number":3,"pinned":true,"category_id":4,
                     "highest_post_number":8,"created_at":"2026-09-01T00:00:00.000Z",
                     "tags":[{"id":2,"name":"kotlin","slug":"kotlin"}],
                     "user":{"id":7,"username":"ada","name":"Ada","avatar_template":"/a/{size}.png"}},
                    {"title":"缺 topic_id"}
                  ]
                }}
                """.trimIndent()
            )
        )

        val page = (DefaultBookmarkRepository(client).firstPage("ada") as BookmarkPageResult.Success).page

        assertEquals("/u/ada/bookmarks.json", client.path)
        assertEquals("/u/ada/bookmarks.json?page=1", page.moreUrl)
        val item = page.items.single()
        assertEquals(9L, item.id)
        assertEquals(30L, item.topicId)
        assertEquals("A&B", item.title)
        assertEquals("摘要", item.excerpt)
        assertEquals(3, item.linkedPostNumber)
        assertTrue(item.pinned)
        assertEquals("kotlin", item.tags.single().name)
        assertEquals("Ada", item.author?.name)
        assertEquals(7, item.replyCount)
    }

    @Test
    fun `缺少 user_bookmark_list 时失败`() = runTest {
        val client = FakeClient(NetworkResult(true, 200, ChallengeState.NONE, """{"ok":true}"""))
        val result = DefaultBookmarkRepository(client).firstPage("ada")
        assertTrue(result is BookmarkPageResult.Failure)
    }

    @Test
    fun `用户名会编码进路径`() = runTest {
        val client = FakeClient(NetworkResult(true, 200, ChallengeState.NONE, """{"user_bookmark_list":{"bookmarks":[]}}"""))
        DefaultBookmarkRepository(client).firstPage("a b")
        assertEquals("/u/a%20b/bookmarks.json", client.path)
    }

    @Test
    fun `下一页沿用服务端给的地址`() = runTest {
        val client = FakeClient(NetworkResult(true, 200, ChallengeState.NONE, """{"user_bookmark_list":{"bookmarks":[]}}"""))
        DefaultBookmarkRepository(client).nextPage("/u/ada/bookmarks.json?page=2")
        assertEquals("/u/ada/bookmarks.json?page=2", client.path)
    }

    @Test
    fun `删除和置顶打到对应路径`() = runTest {
        val client = FakeClient(NetworkResult(true, 200, ChallengeState.NONE, """{"success":"OK"}"""))
        val repo = DefaultBookmarkRepository(client)
        assertTrue(repo.delete(9))
        assertEquals("DELETE", client.method)
        assertEquals("/bookmarks/9", client.path)
        assertTrue(repo.togglePin(9))
        assertEquals("PUT", client.method)
        assertEquals("/bookmarks/9/toggle_pin", client.path)
    }

    @Test
    fun `请求失败时删除返回 false`() = runTest {
        val client = FakeClient(NetworkResult(false, 403, ChallengeState.NONE, null))
        assertFalse(DefaultBookmarkRepository(client).delete(1))
    }

    @Test
    fun `浏览历史按页请求并按 id 去重`() = runTest {
        val client = FakeClient(
            NetworkResult(
                true, 200, ChallengeState.NONE,
                """
                {"users":[{"id":7,"username":"ada"}],
                 "topic_list":{"more_topics_url":"/read.json?page=1","topics":[
                   {"id":11,"title":"已读","posters":[{"user_id":7}],"unread_posts":2,
                    "last_read_post_number":4,"highest_post_number":6}
                 ]}}
                """.trimIndent()
            )
        )
        val result = DefaultBookmarkRepository(client).readTopics(0)
        assertEquals("/read.json", client.path)
        val page = (result as HomePageResult.Success).page
        assertTrue(page.hasMore)
        assertEquals("ada", page.items.single().author?.username)
        assertEquals(5, page.items.single().firstUnreadPostNumber())

        DefaultBookmarkRepository(client).readTopics(2)
        assertEquals("/read.json?page=2", client.path)
    }

    @Test
    fun `翻页合并时丢掉已经出现过的帖子`() {
        val first = listOf(topic(1), topic(2))
        val next = listOf(topic(2), topic(3))
        assertEquals(listOf(1L, 2L, 3L), mergeReadTopics(first, next).map { it.id })
    }

    @Test
    fun `没有收藏时间标签`() {
        assertNull(bookmarkTimeLabel(" "))
    }

    private fun topic(id: Long) = com.arkdo.android.core.model.HomeTopic(id = id, title = "t$id")
}

private class FakeClient(private val response: NetworkResult<String>) : JsonNetworkClient {
    var path: String? = null
    var method: String? = null

    override suspend fun getJson(path: String, options: JsonRequestOptions?): NetworkResult<String> {
        this.path = path
        method = "GET"
        return response
    }

    override suspend fun getJsonWithHeaders(
        path: String,
        headers: List<com.arkdo.android.core.network.HttpHeaderEntry>,
        referrer: String,
        options: JsonRequestOptions?
    ) = response

    override suspend fun postForm(path: String, body: String, options: JsonRequestOptions?) = response
    override suspend fun postJson(path: String, body: String, options: JsonRequestOptions?) = response
    override suspend fun putJson(path: String, options: JsonRequestOptions?): NetworkResult<String> {
        this.path = path
        method = "PUT"
        return response
    }

    override suspend fun putForm(path: String, body: String, options: JsonRequestOptions?) = response
    override suspend fun deleteForm(path: String, body: String, options: JsonRequestOptions?): NetworkResult<String> {
        this.path = path
        method = "DELETE"
        return response
    }

    override suspend fun uploadImage(
        base64: String,
        fileName: String,
        mimeType: String,
        options: JsonRequestOptions?
    ) = response
}
