package com.jetdo.android.services.topic

import com.jetdo.android.data.models.Poll
import com.jetdo.android.data.models.PollOption
import com.jetdo.android.data.models.PollVoteResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 投票乐观更新 / 回滚 / 服务端校正的纯逻辑测试。
 * 对齐鸿蒙 `services/topic/PollMutation.ets` 的语义。
 */
class PollMutationTest {

    private fun poll(
        chosen: List<String> = emptyList(),
        voters: Int = 0,
        status: String = "open",
        type: String = "regular",
        votes: List<Int> = listOf(0, 0, 0)
    ) = Poll(
        name = "poll",
        title = "投票",
        type = type,
        status = status,
        options = listOf(
            PollOption("a", "选项 A", votes[0]),
            PollOption("b", "选项 B", votes[1]),
            PollOption("c", "选项 C", votes[2])
        ),
        voters = voters,
        chosenOptionIds = chosen,
        canVote = chosen.isEmpty()
    )

    @Test
    fun `首次投票给选项加一票且投票人数加一`() {
        val before = poll(votes = listOf(3, 1, 0))

        val after = PollMutation.applyVote(before, listOf("a"))

        assertEquals(listOf(4, 1, 0), after.options.map { it.votes })
        assertEquals(1, after.voters)
        assertEquals(listOf("a"), after.chosenOptionIds)
        assertFalse(after.canVote)
    }

    @Test
    fun `改投时旧项减一新项加一且人数不变`() {
        val before = poll(chosen = listOf("a"), voters = 5, votes = listOf(4, 1, 0))

        val after = PollMutation.applyVote(before, listOf("b"))

        assertEquals(listOf(3, 2, 0), after.options.map { it.votes })
        assertEquals(5, after.voters)
        assertEquals(listOf("b"), after.chosenOptionIds)
    }

    @Test
    fun `多选投票同时对多个选项加票`() {
        val before = poll(type = "multiple", votes = listOf(3, 1, 0))

        val after = PollMutation.applyVote(before, listOf("a", "c"))

        assertEquals(listOf(4, 1, 1), after.options.map { it.votes })
        assertEquals(1, after.voters)
        assertEquals(listOf("a", "c"), after.chosenOptionIds)
    }

    @Test
    fun `多选减选只减对应项不减人数`() {
        val before = poll(
            chosen = listOf("a", "b"),
            voters = 3,
            type = "multiple",
            votes = listOf(4, 2, 0)
        )

        val after = PollMutation.applyVote(before, listOf("a"))

        assertEquals(listOf(4, 1, 0), after.options.map { it.votes })
        assertEquals(3, after.voters)
        assertEquals(listOf("a"), after.chosenOptionIds)
    }

    @Test
    fun `票数不会被减成负数`() {
        // 本地票数 0 但已投(他人撤销后的展示态):减票要夹到 0。
        val before = poll(chosen = listOf("a"), voters = 1, votes = listOf(0, 0, 0))

        val after = PollMutation.applyVote(before, listOf("b"))

        assertEquals(listOf(0, 1, 0), after.options.map { it.votes })
    }

    @Test
    fun `撤销投票各项减一并清空已投项`() {
        val before = poll(chosen = listOf("a", "b"), voters = 4, type = "multiple", votes = listOf(5, 3, 1))

        val after = PollMutation.applyRemove(before)

        assertEquals(listOf(4, 2, 1), after.options.map { it.votes })
        assertEquals(3, after.voters)
        assertTrue(after.chosenOptionIds.isEmpty())
        assertTrue(after.canVote)
    }

    @Test
    fun `未投票时撤销是幂等的`() {
        val before = poll(votes = listOf(2, 1, 0))

        val after = PollMutation.applyRemove(before)

        assertEquals(before.options.map { it.votes }, after.options.map { it.votes })
        assertEquals(before.voters, after.voters)
    }

    @Test
    fun `投票人数不会被减成负数`() {
        val before = poll(chosen = listOf("a"), voters = 0, votes = listOf(1, 0, 0))

        val after = PollMutation.applyRemove(before)

        assertEquals(0, after.voters)
    }

    @Test
    fun `服务端结果校正票数人数与已投项`() {
        val before = poll(chosen = listOf("a"), voters = 1, votes = listOf(4, 1, 0))
        val result = PollVoteResult(
            options = listOf(
                PollOption("a", "选项 A", 6),
                PollOption("b", "选项 B", 3),
                PollOption("c", "选项 C", 1)
            ),
            chosenOptionIds = listOf("a"),
            voters = 7
        )

        val after = PollMutation.applyServerResult(before, result)

        assertEquals(listOf(6, 3, 1), after.options.map { it.votes })
        assertEquals(7, after.voters)
        assertEquals(listOf("a"), after.chosenOptionIds)
    }

    @Test
    fun `服务端撤销后已投项清空`() {
        val before = poll(chosen = listOf("a"), voters = 7, votes = listOf(6, 3, 1))
        val result = PollVoteResult(
            options = listOf(PollOption("a", "选项 A", 5)),
            chosenOptionIds = emptyList(),
            voters = 6
        )

        val after = PollMutation.applyServerResult(before, result)

        assertTrue(after.chosenOptionIds.isEmpty())
        assertTrue(after.canVote)
        assertEquals(6, after.voters)
    }

    @Test
    fun `服务端多出的选项被并入且保留本地 label`() {
        val before = poll(votes = listOf(1, 0, 0))
        val result = PollVoteResult(
            options = listOf(
                PollOption("a", "<p>选项 A</p>", 2),
                PollOption("d", "选项 D", 1)
            ),
            chosenOptionIds = emptyList(),
            voters = 3
        )

        val after = PollMutation.applyServerResult(before, result)

        assertEquals(4, after.options.size)
        assertEquals(listOf("a", "b", "c", "d"), after.options.map { it.id })
        // 已有选项保留本地 label(服务端 html 未剥标签),只覆盖票数。
        assertEquals("选项 A", after.options.first { it.id == "a" }.label)
        assertEquals(2, after.options.first { it.id == "a" }.votes)
    }

    @Test
    fun `快照与回滚恢复原状态`() {
        val before = poll(chosen = listOf("a"), voters = 3, votes = listOf(3, 1, 0))
        val snap = PollMutation.snapshot(before)

        val mutated = PollMutation.applyVote(before, listOf("b"))
        assertEquals(listOf(2, 2, 0), mutated.options.map { it.votes })

        val restored = PollMutation.restore(mutated, snap)

        assertEquals(listOf(3, 1, 0), restored.options.map { it.votes })
        assertEquals(3, restored.voters)
        assertEquals(listOf("a"), restored.chosenOptionIds)
    }

    @Test
    fun `百分比按总票数四舍五入`() {
        val p = poll(votes = listOf(6, 3, 1))

        assertEquals(10, PollMutation.totalVotes(p))
        assertEquals(60, PollMutation.percentOf(p, p.options[0]))
        assertEquals(30, PollMutation.percentOf(p, p.options[1]))
        assertEquals(10, PollMutation.percentOf(p, p.options[2]))
    }

    @Test
    fun `总票数为零时百分比为零`() {
        val p = poll(votes = listOf(0, 0, 0))

        assertEquals(0, PollMutation.totalVotes(p))
        assertEquals(0, PollMutation.percentOf(p, p.options[0]))
    }

    // 回归:Compose 按状态对象新旧值比对决定是否重组。若就地修改同一个 Poll 实例,
    // 引用不变 → 判定「没变化」→ 投票后界面不刷新(box 曾有同类 bug:退出重进才显示)。
    @Test
    fun `每次变更都返回新对象而非原地修改`() {
        val before = poll(votes = listOf(1, 0, 0))

        val voted = PollMutation.applyVote(before, listOf("a"))
        assertNotSame(before, voted)
        assertNotSame(before.options, voted.options)

        val removed = PollMutation.applyRemove(voted)
        assertNotSame(voted, removed)

        val corrected = PollMutation.applyServerResult(
            before,
            PollVoteResult(listOf(PollOption("a", "A", 9)), emptyList(), 9)
        )
        assertNotSame(before, corrected)
    }
}