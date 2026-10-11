package com.jetdo.android.services.topic

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `/latest.json` 的 users[] 解析。
 *
 * 这里之所以要有测试：列表页的用户信息不在 topic 对象里，而在同级的 users[] 数组中，
 * 靠 poster 的 user_id 关联。DiscourseUser 之前只声明了 id/username/name/avatar_template
 * 四个字段，flair_* 和 animated_avatar 在反序列化阶段就被丢掉了 ——
 * 领域模型 User 明明有这些字段，UI 也写好了角标渲染，但值永远是 null，
 * 表现成「头像右下角没有任何标识」，且没有任何报错。
 */
class DiscourseUserParsingTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** 取自 linux.do /latest.json 的 users[] 元素形态。 */
    private val payload = """
        {
          "users": [
            {
              "id": 1,
              "username": "neo",
              "name": "Neo",
              "avatar_template": "/user_avatar/linux.do/neo/{size}/1_2.png",
              "animated_avatar": "/uploads/animated/1.gif",
              "flair_url": "//linuxdo-uploads.s3.ldstatic.com/original/3X/f/l/flair.png",
              "flair_bg_color": "BE123C",
              "flair_color": "FFFFFF",
              "flair_name": "管理员",
              "trust_level": 4
            },
            {
              "id": 2,
              "username": "plain",
              "avatar_template": "/user_avatar/linux.do/plain/{size}/2_2.png"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `flair 四件套全部解析出来`() {
        val users = json.decodeFromString<DiscourseLatestResponse>(payload).users!!
        val neo = users.single { it.id == 1L }

        assertEquals(
            "//linuxdo-uploads.s3.ldstatic.com/original/3X/f/l/flair.png",
            neo.flair_url
        )
        assertEquals("BE123C", neo.flair_bg_color)
        assertEquals("FFFFFF", neo.flair_color)
        assertEquals("管理员", neo.flair_name)
    }

    @Test
    fun `动图头像也要接住`() {
        val users = json.decodeFromString<DiscourseLatestResponse>(payload).users!!
        assertEquals("/uploads/animated/1.gif", users.single { it.id == 1L }.animated_avatar)
    }

    @Test
    fun `没有 flair 的用户各字段为 null`() {
        val users = json.decodeFromString<DiscourseLatestResponse>(payload).users!!
        val plain = users.single { it.id == 2L }

        assertNull(plain.flairUrlOrNull())
        assertNull(plain.animated_avatar)
        assertNull(plain.name)
    }

    /** trust_level 等未声明字段不能让整份响应解析失败。 */
    @Test
    fun `未声明的字段被忽略而不是报错`() {
        val users = json.decodeFromString<DiscourseLatestResponse>(payload).users!!
        assertEquals(2, users.size)
    }

    private fun DiscourseUser.flairUrlOrNull(): String? = flair_url
}
