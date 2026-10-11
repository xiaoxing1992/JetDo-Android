package com.jetdo.android.services.topic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 发新主题(POST /posts)的 payload 组装与响应解析,对齐鸿蒙 TopicService.createTopic。
 */
class TopicCreateTopicTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Test
    fun `发帖 payload 带标题分类标签且固定 new_topic 草稿键`() {
        val payload = buildCreateNewTopicPayload(
            title = "新主题",
            categoryId = 12L,
            raw = "正文",
            tags = listOf("jetdo", "android"),
            typingDurationMs = 800L,
            composerOpenDurationMs = 2000L
        )

        assertEquals("新主题", payload["title"]!!.jsonPrimitive.content)
        assertEquals(12L, payload["category"]!!.jsonPrimitive.long)
        assertEquals("正文", payload["raw"]!!.jsonPrimitive.content)
        assertEquals("new_topic", payload["draft_key"]!!.jsonPrimitive.content)
        assertEquals(800L, payload["typing_duration_msecs"]!!.jsonPrimitive.long)
        assertEquals(2000L, payload["composer_open_duration_msecs"]!!.jsonPrimitive.long)
        val tags = payload["tags"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("jetdo", "android"), tags)
        // 发新主题不带 topic_id(与回帖 payload 的分界)。
        assertFalse(payload.containsKey("topic_id"))
        assertTrue(payload["nested_post"]!!.jsonPrimitive.content == "true")
    }

    @Test
    fun `直接发布响应解析出新主题 id`() {
        val body = """
            {"action":"create_post","post":{
              "id": 880001,
              "topic_id": 1990001,
              "topic_slug": "new-topic",
              "post_number": 1,
              "username": "testuser"
            }}
        """.trimIndent()
        val outcome = parseCreateTopicOutcome(body, json)
        assertTrue(outcome != null)
        assertEquals(false, outcome!!.pending)
        assertEquals(1990001L, outcome.topicId)
    }

    @Test
    fun `进审核队列响应只有 pending`() {
        val body = """
            {"action":"enqueued","pending_post":{"id":990001,"raw":"正文","created_at":"2026-01-01T00:00:00.000Z"}}
        """.trimIndent()
        val outcome = parseCreateTopicOutcome(body, json)
        assertTrue(outcome != null)
        assertEquals(true, outcome!!.pending)
        assertNull(outcome.topicId)
    }

    @Test
    fun `缺少主题数据的响应解析为空`() {
        val body = """{"action":"create_post","post":{"id":1}}"""
        val outcome = parseCreateTopicOutcome(body, json)
        assertEquals(null, outcome)
    }
}
