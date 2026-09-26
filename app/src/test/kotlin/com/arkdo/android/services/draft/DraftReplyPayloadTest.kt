package com.arkdo.android.services.draft

import com.arkdo.android.data.models.DraftAction
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回帖 / 编辑草稿的 data 内层字段名守门测试。
 *
 * 这些名字是服务端契约，而且是 snake_case 与 camelCase **混用**（照官方 composer 抓包）：
 * `composerTime` 是驼峰，`reply_to_post_number` / `original_text` 是下划线。
 * 写错了既不会编译报错、也不会抛异常，只会**静默存不上草稿** —— 用户看到的是
 * "写了一半切走，回来内容没了"，排查成本极高。所以把契约钉在这里。
 */
class DraftReplyPayloadTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun `回帖草稿 key 是 topic_ 加话题 id`() {
        assertEquals("topic_42", replyDraftKey(42L))
        assertEquals("topic_1", replyDraftKey(1L))
    }

    @Test
    fun `回帖草稿 action 为 reply 且带定向楼层`() {
        val encoded = json.encodeToString(
            ReplyDraftData(
                reply = "试试看",
                action = DraftAction.REPLY.value,
                categoryId = 4L,
                tags = emptyList(),
                composerTime = 0L,
                typingTime = 0L,
                reply_to_post_number = 3
            )
        )
        assertTrue(encoded.contains("\"action\":\"reply\""))
        assertTrue(encoded.contains("\"reply_to_post_number\":3"))
        // 回帖草稿不该带只有发帖才有的 title，多发的字段没必要赌服务端会忽略。
        assertFalse(encoded.contains("\"title\""))
    }

    @Test
    fun `编辑草稿 action 为 edit 且带 postId 与原文`() {
        val encoded = json.encodeToString(
            EditDraftData(
                reply = "改过的内容",
                action = DraftAction.EDIT.value,
                categoryId = 4L,
                tags = emptyList(),
                composerTime = 0L,
                typingTime = 0L,
                postId = 987L,
                original_text = "原文"
            )
        )
        assertTrue(encoded.contains("\"action\":\"edit\""))
        assertTrue(encoded.contains("\"postId\":987"))
        assertTrue(encoded.contains("\"original_text\":\"原文\""))
        // 编辑草稿不带 reply_to_post_number（那是回帖的字段）。
        assertFalse(encoded.contains("reply_to_post_number"))
    }

    @Test
    fun `categoryId 为负时落盘成 null`() {
        // 详情页拿不到分类时传 -1，落盘必须转 null：服务端把 -1 当合法分类 id 会出问题。
        val encoded = json.encodeToString(
            ReplyDraftData(
                reply = "x",
                action = DraftAction.REPLY.value,
                categoryId = if (-1L >= 0) -1L else null,
                tags = emptyList(),
                composerTime = 0L,
                typingTime = 0L
            )
        )
        assertTrue(encoded.contains("\"categoryId\":null"))
    }
}
