package com.arkdo.android.services.topic

import com.arkdo.android.data.models.TopicTag
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// 对齐鸿蒙 LotteryTopicRule.ets 的判定语义:排除优先、包含匹配、双状态豁免、本人豁免。
class LotteryTopicRuleTest {

    private fun tag(id: Long? = null, name: String) = TopicTag(id = id, name = name)

    private fun context(
        tags: List<TopicTag>,
        closed: Boolean = false,
        archived: Boolean = false,
        createdById: Long = 100L,
        currentUserId: Long = 200L
    ) = LotteryNoticeContext(tags, closed, archived, createdById, currentUserId)

    @Test
    fun `带抽奖标签且非高级推广显示提醒`() {
        assertTrue(shouldShowLotteryNotice(context(listOf(tag(name = "抽奖")))))
        // 「包含」匹配覆盖「抽奖活动」这类变体。
        assertTrue(shouldShowLotteryNotice(context(listOf(tag(name = "抽奖活动")))))
    }

    @Test
    fun `高级推广排除优先于抽奖命中`() {
        // 两个标签同时挂着时高级推广赢;id 命中与 name 命中都能排除。
        assertFalse(shouldShowLotteryNotice(context(listOf(tag(name = "抽奖"), tag(id = 1514L, name = "x")))))
        assertFalse(shouldShowLotteryNotice(context(listOf(tag(name = "抽奖"), tag(name = "高级推广")))))
    }

    @Test
    fun `无抽奖标签不显示`() {
        assertFalse(shouldShowLotteryNotice(context(listOf(tag(name = "开发调优")))))
    }

    @Test
    fun `已关或已归档不显示`() {
        assertTrue(shouldShowLotteryNotice(context(listOf(tag(name = "抽奖")))))
        assertFalse(shouldShowLotteryNotice(context(listOf(tag(name = "抽奖")), closed = true)))
        assertFalse(shouldShowLotteryNotice(context(listOf(tag(name = "抽奖")), archived = true)))
    }

    @Test
    fun `自己办的抽奖不显示`() {
        assertFalse(shouldShowLotteryNotice(context(listOf(tag(name = "抽奖")), createdById = 200L, currentUserId = 200L)))
        // 未登录(0)撞上未知楼主(0)不豁免。
        assertTrue(shouldShowLotteryNotice(context(listOf(tag(name = "抽奖")), createdById = 0L, currentUserId = 0L)))
    }
}
