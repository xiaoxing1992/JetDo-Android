package com.jetdo.android.services.topic

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `/t/{id}.json` 详情响应解析。
 *
 * 这条测试存在的理由：linux.do 详情接口的 tags 是**对象数组**（{id,name,slug}），
 * 不是字符串数组 —— DTO 之前声明成 List<String>，序列化在 $.tags[0] 直接抛
 * "Expected beginning of the string, but got {"，整个详情页打不开。
 * fixture 取自真实响应（topic 2860794）裁剪。
 */
class TopicDetailParsingTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val payload = """
        {
          "post_stream": {
            "posts": [
              {
                "id": 22398145,
                "post_number": 1,
                "username": "sayqz",
                "avatar_template": "/user_avatar/linux.do/sayqz/{size}/1_2.png",
                "animated_avatar": "//linuxdo-uploads.s3.ldstatic.com/original/4X/a/b/c.gif",
                "cooked": "<p>hi</p>",
                "user_id": 101010,
                "post_id": 1
              }
            ],
            "stream": [22398145, 22398200, 22398300]
          },
          "timeline_lookup": [],
          "suggested_topics": [],
          "tags": [
            {"id": 444, "name": "人工智能", "slug": "ai"},
            {"id": 445, "name": "开发调优", "slug": "dev"}
          ],
          "id": 2860794,
          "title": "一个真实形态的话题",
          "posts_count": 3,
          "user_id": 98765,
          "details": {
            "created_by": {
              "id": 101010,
              "username": "sayqz"
            }
          }
        }
    """.trimIndent()

    // TOPIC OWNER 数据链：楼主 id = details.created_by.id 优先、回退顶层 user_id（对齐鸿蒙
    // mapCreatedById）；楼层 user_id 与其相等才标。此前屏幕层传的是 topic.userId，且楼层
    // user_id 未进断言，标记从未渲染。
    @Test
    fun `楼主id解析与楼层userId匹配`() {
        val detail = json.decodeFromString<DiscourseTopicDetail>(payload)
        val createdById = detail.details?.createdBy?.id ?: detail.user_id
        org.junit.Assert.assertEquals(101010L, createdById)
        val post = detail.post_stream?.posts?.first()
        org.junit.Assert.assertEquals(101010L, post?.user_id)
        org.junit.Assert.assertEquals(createdById, post?.user_id)
    }

    @Test
    fun `对象数组形态的 tags 正常解析`() {
        val detail = json.decodeFromString<DiscourseTopicDetail>(payload)

        assertEquals(2860794L, detail.id)
        assertEquals(listOf("人工智能", "开发调优"), detail.tags?.map { it.name })
        assertEquals(444L, detail.tags?.first()?.id)
    }

    @Test
    fun `楼层数据与 stream 完整解析`() {
        val detail = json.decodeFromString<DiscourseTopicDetail>(payload)

        assertEquals(listOf(22398145L, 22398200L, 22398300L), detail.post_stream?.stream)
        val post = detail.post_stream?.posts?.single()
        assertEquals("//linuxdo-uploads.s3.ldstatic.com/original/4X/a/b/c.gif", post?.animated_avatar)
    }
}
