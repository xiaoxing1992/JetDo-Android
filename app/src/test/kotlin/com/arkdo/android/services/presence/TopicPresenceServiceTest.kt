package com.arkdo.android.services.presence

import com.arkdo.android.data.models.User
import com.arkdo.android.services.message.MessageBusPresenceUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// 对齐鸿蒙 TopicPresenceService.ets 的三段纯逻辑：用户映射、增量合并、展示裁剪。
// 音视频那轮踩过的坑在这里同样成立 —— 服务整条挂在 WebView 桥和长轮询循环上，
// 只有把这三段抽成纯函数才测得动（见 skill：把可测逻辑从 IO 里剥出来）。
class TopicPresenceServiceTest {

    private fun raw(id: Long?, username: String?, name: String? = null, avatar: String? = null) =
        MessageBusPresenceUser(id = id, username = username, name = name, avatarTemplate = avatar)

    private fun user(id: Long, username: String) = User(id = id, username = username)

    // ---- mapPresenceUser ----

    @Test
    fun `id 与 username 齐全才映射`() {
        val mapped = mapPresenceUser(raw(7L, "alice", "Alice", "/user_avatar/x/{size}.png"))
        assertEquals(7L, mapped?.id)
        assertEquals("alice", mapped?.username)
        assertEquals("Alice", mapped?.name)
        assertEquals("/user_avatar/x/{size}.png", mapped?.avatarTemplate)
    }

    @Test
    fun `缺 id 或缺 username 一律丢弃`() {
        assertNull(mapPresenceUser(raw(null, "alice")))
        assertNull(mapPresenceUser(raw(7L, null)))
        // 空串与 id<=0 同样画不出头像，按丢弃处理。
        assertNull(mapPresenceUser(raw(7L, "")))
        assertNull(mapPresenceUser(raw(0L, "alice")))
    }

    // ---- mergePresenceUsers ----

    @Test
    fun `entering 追加到尾部并去重`() {
        val current = listOf(user(1L, "a"))
        val merged = mergePresenceUsers(
            current = current,
            entering = listOf(raw(2L, "b"), raw(1L, "a"))
        )
        assertEquals(listOf(1L, 2L), merged.map { it.id })
    }

    @Test
    fun `leaving 摘掉对应的人`() {
        val current = listOf(user(1L, "a"), user(2L, "b"), user(3L, "c"))
        val merged = mergePresenceUsers(current = current, leaving = listOf(2L))
        assertEquals(listOf(1L, 3L), merged.map { it.id })
    }

    @Test
    fun `同时进入与离开按先离后进处理`() {
        // 先摘再追加：同一条消息里既走又来的 id 不会既留下副本又追加一次。
        val current = listOf(user(1L, "a"), user(2L, "b"))
        val merged = mergePresenceUsers(
            current = current,
            entering = listOf(raw(2L, "b"), raw(4L, "d")),
            leaving = listOf(2L, 3L)
        )
        assertEquals(listOf(1L, 2L, 4L), merged.map { it.id })
    }

    @Test
    fun `非法 entering 项被跳过不影响其它项`() {
        val merged = mergePresenceUsers(
            current = emptyList(),
            entering = listOf(raw(null, "x"), raw(5L, null), raw(6L, "ok"))
        )
        assertEquals(listOf(6L), merged.map { it.id })
    }

    @Test
    fun `增量为空时原样返回`() {
        // 原样返回（同一实例）让调用方能靠引用比对直接判定"没变化、不必 emit"。
        val current = listOf(user(1L, "a"))
        val merged = mergePresenceUsers(current = current)
        assertTrue(merged === current)
    }

    // ---- visiblePresenceUsers ----

    @Test
    fun `展示时滤掉自己并截到 5 个`() {
        val all = listOf(user(1L, "me"), user(2L, "b"), user(3L, "c"), user(4L, "d"),
            user(5L, "e"), user(6L, "f"), user(7L, "g"))
        val shown = visiblePresenceUsers(all, me = 1L)
        assertEquals(5, shown.size)
        assertEquals(listOf(2L, 3L, 4L, 5L, 6L), shown.map { it.id })
    }

    @Test
    fun `自己不在场时不足 5 人按实际返回`() {
        val shown = visiblePresenceUsers(listOf(user(9L, "a"), user(8L, "b")), me = 1L)
        assertEquals(2, shown.size)
    }

    // ---- parsePresenceGet ----

    @Test
    fun `解析按频道取状态并读出游标`() {
        val body = """
            {"/discourse-presence/reply/123":{
              "users":[{"id":5,"username":"bob","name":"Bob"}],
              "last_message_id":987,
              "count":1
            }}
        """.trimIndent()
        val snapshot = parsePresenceGet(body, "/discourse-presence/reply/123")
        assertEquals(1, snapshot?.users?.size)
        assertEquals("bob", snapshot?.users?.first()?.username)
        assertEquals(987L, snapshot?.lastMessageId)
    }

    @Test
    fun `missing 频道或非法 JSON 返回 null`() {
        assertNull(parsePresenceGet("{}", "/discourse-presence/reply/123"))
        assertNull(parsePresenceGet("not json", "/discourse-presence/reply/123"))
        // 频道在但 last_message_id 缺失：仍要给出用户列表，游标回落 -1（只收新消息）。
        val body = """{"/c":{"users":[{"id":5,"username":"bob"}]}}"""
        assertEquals(-1L, parsePresenceGet(body, "/c")?.lastMessageId)
    }

    // ---- encodeRFC3986 ----

    @Test
    fun `频道路径参数只编码非 unreserved 字符`() {
        // 频道里的斜杠：URLEncoder 会编成 %2F，鸿蒙的 encodeURIComponent 不会。
        assertEquals(
            "%2Fdiscourse-presence%2Freply%2F123",
            encodeRFC3986("/discourse-presence/reply/123")
        )
        // unreserved 字符保持原样。
        assertEquals("aZ0-_.~", encodeRFC3986("aZ0-_.~"))
    }
}
