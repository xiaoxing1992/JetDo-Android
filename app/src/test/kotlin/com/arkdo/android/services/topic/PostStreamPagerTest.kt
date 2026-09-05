package com.arkdo.android.services.topic

import com.arkdo.android.data.models.TopicPost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 帖子流方向化分页纯逻辑，1:1 对齐鸿蒙 services/topic/PostStreamPager.ets。
 *
 * 窗口式加载（从任意楼号进入）下，已加载楼层是 stream 中的一段连续区间：
 * 向下取区间之后的 id、向上取区间之前的 id。全部为无状态函数。
 */
class PostStreamPagerTest {

    /** stream: 楼层 id 全量有序流；楼号 = 下标，方便造数据。 */
    private fun stream(size: Int) = (1L..size).toList()

    private fun post(id: Long, postNumber: Int = (id - 1).toInt()) =
        TopicPost(id = id, postNumber = postNumber)

    @Test
    fun `向下分页取已加载区间之后的前 pageSize 个`() {
        // 已加载楼 1..5（stream 前缀），下一页取 6..10。
        val posts = (1L..5L).map(::post)

        assertEquals((6L..10L).toList(), PostStreamPager.collectNextIds(stream(30), posts, 5))
    }

    @Test
    fun `窗口式进入时向下取区间之后的 id`() {
        // 从楼 11 进入，已加载 11..15（stream 下标 10..14），下一页取 16..20。
        val posts = (11L..15L).map(::post)

        assertEquals((16L..20L).toList(), PostStreamPager.collectNextIds(stream(30), posts, 5))
    }

    @Test
    fun `向下分页起点取已加载区间的最大下标`() {
        // 中段的已读楼不改变起点：区间 [0..7]（id 8 在下标 7），下一段从 id 9 开始。
        val posts = (1L..5L).map(::post) + listOf(post(8))

        assertEquals(listOf(9L, 10L, 11L, 12L, 13L), PostStreamPager.collectNextIds(stream(30), posts, 5))
    }

    @Test
    fun `向下分页不足 pageSize 时取到流末尾`() {
        val posts = (26L..30L).map(::post)

        assertEquals(emptyList<Long>(), PostStreamPager.collectNextIds(stream(30), posts, 5))
        assertEquals(listOf(29L, 30L), PostStreamPager.collectNextIds(stream(30), (26L..28L).map(::post), 5))
    }

    @Test
    fun `向上分页取区间之前紧邻的 pageSize 个且保持升序`() {
        val posts = (11L..15L).map(::post)

        assertEquals((6L..10L).toList(), PostStreamPager.collectPrevIds(stream(30), posts, 5))
        assertEquals(listOf(8L, 9L, 10L), PostStreamPager.collectPrevIds(stream(30), posts, 3))
    }

    @Test
    fun `已到顶部时向上分页为空`() {
        val posts = (1L..5L).map(::post)

        assertEquals(emptyList<Long>(), PostStreamPager.collectPrevIds(stream(30), posts, 5))
    }

    @Test
    fun `计数驱动加载更多可用性`() {
        val posts = (11L..15L).map(::post)

        assertEquals(10, PostStreamPager.countEarlier(stream(30), posts))
        assertEquals(15, PostStreamPager.countLater(stream(30), posts))
        assertEquals(0, PostStreamPager.countEarlier(stream(30), (1L..5L).map(::post)))
        assertEquals(0, PostStreamPager.countLater(stream(30), (26L..30L).map(::post)))
    }

    @Test
    fun `无已加载楼层时之后从头取之前为空`() {
        // 新回复刚发表时可能不在 stream 里 → 区间 [-1,-1]：之后取全部、之前为 0。
        val foreign = listOf(post(999L))

        assertEquals((1L..5L).toList(), PostStreamPager.collectNextIds(stream(5), foreign, 5))
        assertEquals(0, PostStreamPager.countEarlier(stream(5), foreign))
        assertEquals(5, PostStreamPager.countLater(stream(5), foreign))
        assertEquals(emptyList<Long>(), PostStreamPager.collectPrevIds(stream(5), foreign, 5))
    }

    @Test
    fun `按 id 找楼层与按楼号找下标`() {
        val posts = (1L..5L).map(::post)

        assertEquals(posts[2], PostStreamPager.findById(posts, 3L))
        assertNull(PostStreamPager.findById(posts, 99L))
        assertEquals(2, PostStreamPager.findIndexByPostNumber(posts, 2))
        assertEquals(-1, PostStreamPager.findIndexByPostNumber(posts, 42))
    }
}
