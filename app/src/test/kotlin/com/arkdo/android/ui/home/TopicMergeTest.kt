package com.arkdo.android.ui.home

import com.arkdo.android.data.models.Topic
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 话题列表按 id 去重合并。
 *
 * 这条测试存在的理由：真机「加载更多」闪退，堆栈是
 * IllegalArgumentException: Key "2857356" was already used —— LazyColumn 用
 * topic.id 做 key，而 Discourse 分页在话题被顶帖/移动时页与页会重叠（置顶帖
 * 每页都出现），裸拼接 current.topics + result.data.topics 就产生重复 id，
 * Compose 测量阶段直接抛异常。鸿蒙列表不强制唯一 key，同样的数据不炸，
 * 所以这个坑只在 Android 侧暴露。
 */
class TopicMergeTest {

    private fun topic(id: Long, title: String = "t$id") = Topic(id = id, title = title)

    @Test
    fun `翻页追加时丢弃已存在的 id`() {
        val existing = listOf(topic(1), topic(2), topic(3))
        // 第 2 页与第 1 页重叠：3 已存在，4、5 是新话题。
        val page2 = listOf(topic(3), topic(4), topic(5))

        val merged = appendTopicsUnique(existing, page2)

        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), merged.map { it.id })
    }

    @Test
    fun `同一页内的重复 id 也只保留第一个`() {
        // 置顶帖在单页响应里出现两次的场景。
        val page = listOf(topic(7, "first"), topic(8), topic(7, "second"))

        val merged = appendTopicsUnique(emptyList(), page)

        assertEquals(listOf(7L, 8L), merged.map { it.id })
        assertEquals("first", merged.first { it.id == 7L }.title)
    }

    @Test
    fun `追加新内容不改变原有部分的顺序`() {
        val existing = listOf(topic(2), topic(1))
        val page = listOf(topic(3), topic(1))

        val merged = appendTopicsUnique(existing, page)

        assertEquals(listOf(2L, 1L, 3L), merged.map { it.id })
    }

    @Test
    fun `空列表边界`() {
        assertEquals(emptyList<Topic>(), appendTopicsUnique(emptyList(), emptyList()))
        assertEquals(listOf(1L), appendTopicsUnique(emptyList(), listOf(topic(1))).map { it.id })
        assertEquals(listOf(1L), appendTopicsUnique(listOf(topic(1)), emptyList()).map { it.id })
    }
}
