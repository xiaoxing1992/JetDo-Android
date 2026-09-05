package com.arkdo.android.services.topic

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `/t/{id}.json` 的 post_stream.posts 解析。
 *
 * 这里之所以要有测试：详情页帖子的作者头像同样是「动图候选 → 静态候选」的
 * 候选链（鸿蒙 TopicService.ets 的 mapPost 映射了 animated_avatar 字段）。
 * 之前 DiscoursePost 没声明这个字段，反序列化阶段就被丢掉 ——
 * 详情页作者的动图头像永远拿不到值，且没有任何报错。
 */
class TopicPostParsingTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** 取自鸿蒙 TopicService.test.ets 的详情响应形态。 */
    private val payload = """
        {
          "id": 7,
          "title": "动图头像",
          "post_stream": {
            "posts": [
              {
                "id": 70,
                "topic_id": 7,
                "post_number": 1,
                "username": "torvalds",
                "name": "Linus",
                "avatar_template": "/user_avatar/linux.do/torvalds/{size}/1_2.png",
                "animated_avatar": "//cdn.ldstatic.com/original/4X/a/b/c.gif",
                "cooked": "<p>hi</p>"
              }
            ]
          }
        }
    """.trimIndent()

    @Test
    fun `帖子的动图头像字段解析出来`() {
        val post = json.decodeFromString<DiscourseTopicDetail>(payload)
            .post_stream!!.posts!!.single()

        assertEquals("//cdn.ldstatic.com/original/4X/a/b/c.gif", post.animated_avatar)
    }

    @Test
    fun `没有动图字段时为 null`() {
        val payload = """
            {
              "id": 7,
              "post_stream": {
                "posts": [
                  {
                    "id": 70,
                    "post_number": 1,
                    "username": "plain",
                    "avatar_template": "/user_avatar/linux.do/plain/{size}/2_2.png",
                    "cooked": "<p>hi</p>"
                  }
                ]
              }
            }
        """.trimIndent()

        val post = json.decodeFromString<DiscourseTopicDetail>(payload)
            .post_stream!!.posts!!.single()

        assertNull(post.animated_avatar)
    }
}
