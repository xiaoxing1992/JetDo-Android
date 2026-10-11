package com.jetdo.android.services.topic

import com.jetdo.android.data.models.TopicPost
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 发表回复（POST /posts）的 payload 组装与响应解析，对齐鸿蒙 createReply 语义。
 */
class TopicCreateReplyTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Test
    fun `payload 含 composer 全量默认字段`() {
        val payload = buildCreateReplyPayload(
            topicId = 123L,
            categoryId = 5L,
            raw = "这是一段足够长的回复正文，用来通过最短字数校验。",
            typingDurationMs = 1200L,
            composerOpenDurationMs = 3000L
        )

        assertEquals("这是一段足够长的回复正文，用来通过最短字数校验。", payload["raw"]!!.jsonPrimitive.content)
        assertEquals(123L, payload["topic_id"]!!.jsonPrimitive.long)
        assertEquals(5L, payload["category"]!!.jsonPrimitive.long)
        assertEquals("regular", payload["archetype"]!!.jsonPrimitive.content)
        assertEquals(1200L, payload["typing_duration_msecs"]!!.jsonPrimitive.long)
        assertEquals(3000L, payload["composer_open_duration_msecs"]!!.jsonPrimitive.long)
        assertEquals("topic_123", payload["draft_key"]!!.jsonPrimitive.content)
        assertTrue(payload["nested_post"]!!.jsonPrimitive.content == "true")
        assertFalse(payload.containsKey("reply_to_post_number"))
    }

    @Test
    fun `定向回复时下发 reply_to_post_number`() {
        val payload = buildCreateReplyPayload(
            topicId = 1L,
            categoryId = null,
            raw = "x",
            replyToPostNumber = 7
        )
        assertEquals(7L, payload["reply_to_post_number"]!!.jsonPrimitive.long)
        // categoryId 为空时不下发 category（对齐鸿蒙条件赋值）。
        assertFalse(payload.containsKey("category"))
    }

    @Test
    fun `直接发布响应映射出新楼层`() {
        val body = """
            {"action":"create_post","post":{
                "id":991,"post_number":12,"cooked":"<p>新楼</p>",
                "created_at":"2026-09-07T00:00:00.000Z","username":"tester","name":"测试者"
            }}
        """.trimIndent()
        val outcome = parseCreateReplyResponse(body, json) { sources ->
            sources.map { TopicPost(id = it.id ?: 0, postNumber = it.post_number ?: 0, cookedHtml = it.cooked ?: "") }
        }
        assertTrue(outcome != null)
        assertFalse(outcome!!.pending)
        assertEquals(991L, outcome.post?.id)
        assertEquals(12, outcome.post?.postNumber)
        assertNull(outcome.pendingPost)
    }

    @Test
    fun `进审核队列响应返回 pending 快照`() {
        val body = """
            {"action":"enqueued","pending_post":{
                "id":4242,"raw":"待审内容","created_at":"2026-09-07T00:00:00.000Z"
            }}
        """.trimIndent()
        val outcome = parseCreateReplyResponse(body, json) { emptyList() }
        assertTrue(outcome != null)
        assertTrue(outcome!!.pending)
        assertNull(outcome.post)
        assertEquals(4242L, outcome.pendingPost?.id)
        assertEquals("待审内容", outcome.pendingPost?.raw)
    }

    @Test
    fun `无效响应返回 null`() {
        assertNull(parseCreateReplyResponse("not json", json) { emptyList() })
        assertNull(parseCreateReplyResponse("{\"action\":\"unknown\"}", json) { emptyList() })
    }
}
