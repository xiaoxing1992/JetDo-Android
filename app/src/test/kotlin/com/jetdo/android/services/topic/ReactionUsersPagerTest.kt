package com.jetdo.android.services.topic

import com.jetdo.android.data.models.ReactionUser
import com.jetdo.android.data.models.ReactionUsersPage
import com.jetdo.android.data.models.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// 对齐鸿蒙 ReactionUsersPager.ets 的分页累积语义。
class ReactionUsersPagerTest {

    private fun user(id: Long, username: String = "u$id") =
        User(id = id, username = username)

    private fun reactionUser(id: Long, reaction: String) =
        ReactionUser(user = user(id), reaction = reaction)

    @Test
    fun `跨页按 user_id+reaction 去重累积`() {
        val first = ReactionUsersPager.merge(
            emptyList(),
            ReactionUsersPage(users = listOf(reactionUser(1, "heart"), reactionUser(2, "+1")), totalRows = 3)
        )
        assertEquals(2, first.users.size)
        assertTrue(first.hasMore)

        // 第二页带一个重复项(id=1 heart)与一个新项 → 只追加新项。
        val second = ReactionUsersPager.merge(
            first.users,
            ReactionUsersPage(users = listOf(reactionUser(1, "heart"), reactionUser(3, "clap")), totalRows = 3)
        )
        assertEquals(3, second.users.size)
        assertEquals(listOf(1L, 2L, 3L), second.users.map { it.user.id })
        assertFalse(second.hasMore)
    }

    @Test
    fun `同一用户不同 reaction 是两条独立记录`() {
        val result = ReactionUsersPager.merge(
            emptyList(),
            ReactionUsersPage(users = listOf(reactionUser(1, "heart"), reactionUser(1, "+1")), totalRows = 2)
        )
        assertEquals(2, result.users.size)
        assertFalse(result.hasMore)
    }

    @Test
    fun `空页强制停止防止 total_rows 不一致时无限触底`() {
        val existing = listOf(reactionUser(1, "heart"))
        val result = ReactionUsersPager.merge(
            existing,
            ReactionUsersPage(users = emptyList(), totalRows = 99)
        )
        assertFalse(result.hasMore)
    }

    @Test
    fun `totalRows 取跨页最大值`() {
        val first = ReactionUsersPager.merge(emptyList(), ReactionUsersPage(users = listOf(reactionUser(1, "heart")), totalRows = 10))
        assertEquals(10, first.totalRows)
        val second = ReactionUsersPager.merge(
            first.users,
            ReactionUsersPage(users = listOf(reactionUser(2, "+1")), totalRows = 5),
            existingTotalRows = first.totalRows
        )
        assertEquals(10, second.totalRows)
    }
}
