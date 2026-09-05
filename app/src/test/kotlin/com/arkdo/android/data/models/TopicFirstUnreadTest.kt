package com.arkdo.android.data.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 未读话题的跳楼目标计算，对齐鸿蒙 HomePage.handleUnreadBadgeClick：
 * 有未读徽标的行整行点击 → 直跳第一条未读楼（lastRead+1，封顶最高楼）。
 * 之前 Android 把这个点击传成了和普通打开完全相同的 lambda，
 * 鸿蒙的跳楼语义整条丢失 —— 表现成「未读行也只会从顶部打开」。
 */
class TopicFirstUnreadTest {

    @Test
    fun `从未读楼的下一条开始`() {
        val topic = Topic(id = 1, lastReadPostNumber = 5, highestPostNumber = 20)

        assertEquals(6, topic.firstUnreadPostNumber())
    }

    @Test
    fun `没有阅读记录时不产生跳楼目标`() {
        // lastRead 缺省 → 鸿蒙侧走 onTopicClick 从第 1 楼打开。
        assertNull(Topic(id = 1, lastReadPostNumber = null, highestPostNumber = 20).firstUnreadPostNumber())
        assertNull(Topic(id = 1, lastReadPostNumber = 0, highestPostNumber = 20).firstUnreadPostNumber())
    }

    @Test
    fun `目标封顶到最高楼`() {
        // 已读完所有楼（lastRead == highest）时不能再指向 highest+1 的空气楼。
        val topic = Topic(id = 1, lastReadPostNumber = 20, highestPostNumber = 20)

        assertEquals(20, topic.firstUnreadPostNumber())
    }

    @Test
    fun `最高楼未知时不封顶`() {
        val topic = Topic(id = 1, lastReadPostNumber = 5, highestPostNumber = null)

        assertEquals(6, topic.firstUnreadPostNumber())
    }
}
