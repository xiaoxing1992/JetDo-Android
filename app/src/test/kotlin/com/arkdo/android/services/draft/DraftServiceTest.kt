package com.arkdo.android.services.draft

import com.arkdo.android.data.models.cleanDraftExcerpt
import com.arkdo.android.services.network.ChallengeState
import com.arkdo.android.services.network.JsonNetworkClient
import com.arkdo.android.services.network.JsonRequestOptions
import com.arkdo.android.services.network.NetworkResult
import com.arkdo.android.services.network.NetworkResults
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

/**
 * 新主题草稿的表单、序号回写和空内容判定。对齐鸿蒙 DraftService.saveTopicDraft。
 */
class DraftServiceTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `保存草稿用表单编码且 data 带标题分类和标签`() {
        val client = RecordingClient(
            NetworkResult(true, 200, ChallengeState.NONE, """{"success":"OK","draft_sequence":7}""")
        )
        val service = DraftService(client)

        val result = kotlinx.coroutines.runBlocking {
            service.saveTopicDraft(
                SaveTopicDraftInput(
                    draftKey = "new_topic_100",
                    sequence = 3,
                    title = "标题",
                    categoryId = 12,
                    tags = listOf(com.arkdo.android.data.models.DraftTagRef(4, "教程")),
                    reply = "正文",
                    composerTimeMs = 2000,
                    typingTimeMs = 800
                )
            )
        }

        assertTrue(result.success)
        assertEquals(7L, result.data)
        assertEquals("/drafts.json", client.path)
        val fields = formFields(client.body.orEmpty())
        assertEquals("new_topic_100", fields["draft_key"])
        assertEquals("3", fields["sequence"])
        assertEquals("false", fields["force_save"])
        assertEquals(32, fields["owner"]?.length)
        val data = json.parseToJsonElement(fields["data"].orEmpty()).jsonObject
        assertEquals("createTopic", data["action"]!!.jsonPrimitive.content)
        assertEquals("标题", data["title"]!!.jsonPrimitive.content)
        assertEquals(12L, data["categoryId"]!!.jsonPrimitive.long)
        assertEquals("正文", data["reply"]!!.jsonPrimitive.content)
        assertEquals(2000L, data["composerTime"]!!.jsonPrimitive.long)
        assertEquals(800L, data["typingTime"]!!.jsonPrimitive.long)
        assertTrue(data["categoryId"] !is JsonNull)
        val tag = data["tags"]!!.jsonArray.single().jsonObject
        assertEquals(4L, tag["id"]!!.jsonPrimitive.long)
        assertEquals("教程", tag["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `未选分类时 categoryId 写成 null`() {
        val client = RecordingClient(NetworkResult(true, 200, ChallengeState.NONE, """{"success":"OK"}"""))
        val result = kotlinx.coroutines.runBlocking {
            DraftService(client).saveTopicDraft(
                SaveTopicDraftInput("new_topic_1", 0, "只有标题", null, emptyList(), "", 0, 0)
            )
        }
        assertTrue(result.success)
        assertEquals(0L, result.data)
        val data = json.parseToJsonElement(formFields(client.body.orEmpty())["data"].orEmpty()).jsonObject
        assertTrue(data["categoryId"] is JsonNull)
    }

    @Test
    fun `删除草稿把序号放进表单`() {
        val client = RecordingClient(NetworkResult(true, 200, ChallengeState.NONE, """{"success":"OK"}"""))
        val result = kotlinx.coroutines.runBlocking {
            DraftService(client).deleteDraft("new_topic_100", 7)
        }
        assertTrue(result.success)
        assertEquals("/drafts/new_topic_100.json", client.path)
        assertEquals("DELETE", client.method)
        val fields = formFields(client.body.orEmpty())
        assertEquals("new_topic_100", fields["draft_key"])
        assertEquals("7", fields["sequence"])
    }

    @Test
    fun `标题和正文都空才不算有内容`() {
        assertFalse(draftHasContent("", "  "))
        assertTrue(draftHasContent(" 标题 ", ""))
        assertTrue(draftHasContent("", "正文"))
    }

    @Test
    fun `续写沿用原 key 新开才生成`() {
        assertEquals("new_topic_9", resolveDraftKey("new_topic_9", 100))
        assertEquals("new_topic_100", resolveDraftKey("", 100))
    }

    @Test
    fun `列表按满页判断还有没有下一页`() {
        val one = """{"drafts":[{"draft_key":"new_topic_1","sequence":1,"data":"{\"action\":\"createTopic\",\"title\":\"a\",\"reply\":\"b\"}"}]}"""
        val client = RecordingClient(NetworkResult(true, 200, ChallengeState.NONE, one))
        val page = kotlinx.coroutines.runBlocking { DraftService(client).getPage(0) }
        assertTrue(page.success)
        assertFalse(page.data!!.hasMore)
        assertEquals("a", page.data!!.drafts.single().title)
        assertEquals("/drafts.json?offset=0&limit=30", client.path)
    }

    @Test
    fun `列表预览清掉图片和引用并压空白`() {
        val raw = "开头\n\n![图](https://linux.do/a.png)\n[quote=\"a, post:1, topic:2\"]引用[/quote]结尾"
        assertEquals("开头 [图片] 结尾", cleanDraftExcerpt(null, raw))
        assertEquals("服务端摘要", cleanDraftExcerpt("服务端摘要", raw))
    }

    private fun formFields(body: String): Map<String, String> =
        body.split("&").associate { part ->
            val (key, value) = part.split("=", limit = 2)
            key to URLDecoder.decode(value, "UTF-8")
        }
}

private class RecordingClient(private val response: NetworkResult<String>) : JsonNetworkClient {
    var path: String? = null
    var body: String? = null
    var method: String? = null

    override suspend fun getJson(path: String, options: JsonRequestOptions?): NetworkResult<String> {
        this.path = path
        method = "GET"
        return response
    }

    override suspend fun getJsonWithHeaders(
        path: String,
        headers: List<com.arkdo.android.services.network.HttpHeaderEntry>,
        referrer: String,
        options: JsonRequestOptions?
    ) = NetworkResults.networkFailure<String>(0, "unused")

    override suspend fun postForm(path: String, body: String, options: JsonRequestOptions?): NetworkResult<String> {
        this.path = path
        this.body = body
        method = "POST"
        return response
    }

    override suspend fun postJson(path: String, body: String, options: JsonRequestOptions?) =
        NetworkResults.networkFailure<String>(0, "unused")

    override suspend fun putJson(path: String, options: JsonRequestOptions?) =
        NetworkResults.networkFailure<String>(0, "unused")

    override suspend fun putForm(path: String, body: String, options: JsonRequestOptions?) =
        NetworkResults.networkFailure<String>(0, "unused")

    override suspend fun deleteForm(path: String, body: String, options: JsonRequestOptions?): NetworkResult<String> {
        this.path = path
        this.body = body
        method = "DELETE"
        return response
    }

    override suspend fun uploadImage(
        base64: String,
        fileName: String,
        mimeType: String,
        options: JsonRequestOptions?
    ) = NetworkResults.networkFailure<String>(0, "unused")
}
