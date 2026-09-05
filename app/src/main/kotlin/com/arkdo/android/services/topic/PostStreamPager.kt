package com.arkdo.android.services.topic

import com.arkdo.android.data.models.TopicPost

/**
 * 帖子流分页纯逻辑，1:1 对齐鸿蒙 services/topic/PostStreamPager.ets。
 *
 * 基于详情接口返回的全量楼层 id 流（stream）与已加载楼层做方向化分页。
 * 窗口式加载（从任意楼号进入）下，已加载楼层是 stream 中的一段连续区间：
 * 向下取区间之后的 id、向上取区间之前的 id；从头进入时区间是前缀，
 * 向下语义与旧版一致。全部为无状态函数；楼层数组由 [PostDataSource] 持有。
 */
object PostStreamPager {

    /** 下一页（向下）要加载的楼层 id：已加载区间最大 stream 下标之后的前 [pageSize] 个。 */
    fun collectNextIds(stream: List<Long>, posts: List<TopicPost>, pageSize: Int): List<Long> {
        val loadedIds = posts.mapTo(HashSet()) { it.id }
        val (minIndex, maxIndex) = loadedStreamRange(stream, loadedIds)
        val nextIds = ArrayList<Long>(pageSize)
        var index = maxIndex + 1
        while (index < stream.size && nextIds.size < pageSize) {
            if (stream[index] !in loadedIds) {
                nextIds.add(stream[index])
            }
            index++
        }
        return nextIds
    }

    /** 上一页（向上）要加载的楼层 id：已加载区间最小下标之前、紧邻的 [pageSize] 个，保持 stream 升序。 */
    fun collectPrevIds(stream: List<Long>, posts: List<TopicPost>, pageSize: Int): List<Long> {
        val loadedIds = posts.mapTo(HashSet()) { it.id }
        val (minIndex, _) = loadedStreamRange(stream, loadedIds)
        if (minIndex <= 0) {
            return emptyList()
        }
        val fromIndex = maxOf(0, minIndex - pageSize)
        val prevIds = ArrayList<Long>(minIndex - fromIndex)
        for (index in fromIndex until minIndex) {
            if (stream[index] !in loadedIds) {
                prevIds.add(stream[index])
            }
        }
        return prevIds
    }

    /** 已加载区间之前还有几楼（驱动"加载更早"是否可用）。 */
    fun countEarlier(stream: List<Long>, posts: List<TopicPost>): Int {
        val (minIndex, _) = loadedStreamRange(stream, posts.mapTo(HashSet()) { it.id })
        return maxOf(0, minIndex)
    }

    /** 已加载区间之后还有几楼（驱动"上拉加载更多/没有更多了"）。 */
    fun countLater(stream: List<Long>, posts: List<TopicPost>): Int {
        val (_, maxIndex) = loadedStreamRange(stream, posts.mapTo(HashSet()) { it.id })
        return maxOf(0, stream.size - 1 - maxIndex)
    }

    fun findById(posts: List<TopicPost>, postId: Long): TopicPost? =
        posts.firstOrNull { it.id == postId }

    /** 精确按楼号找已加载楼层下标；未加载返回 -1。 */
    fun findIndexByPostNumber(posts: List<TopicPost>, postNumber: Int): Int =
        posts.indexOfFirst { it.postNumber == postNumber }

    /**
     * 已加载楼层在 stream 中占据的下标区间 [minIndex, maxIndex]。
     * 无已加载楼层（或均不在 stream 中，如刚发表的新回复）时返回 [-1, -1]：
     * 此时"之后"从头取、"之前"为空，与从零加载的语义一致。
     */
    private fun loadedStreamRange(stream: List<Long>, loadedIds: Set<Long>): Pair<Int, Int> {
        var minIndex = -1
        var maxIndex = -1
        for ((index, id) in stream.withIndex()) {
            if (id in loadedIds) {
                if (minIndex < 0) {
                    minIndex = index
                }
                maxIndex = index
            }
        }
        return minIndex to maxIndex
    }
}
