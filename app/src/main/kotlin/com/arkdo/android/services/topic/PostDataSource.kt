package com.arkdo.android.services.topic

import com.arkdo.android.data.models.TopicPost

/**
 * 楼层懒加载数据源，1:1 对齐鸿蒙 services/topic/PostDataSource.ets 的插入语义。
 *
 * 楼层列表始终按楼号升序：
 * · 向下分页取到的是已加载窗口之后的一段，其楼号小于尾部新回复 → 插到新回复之前，
 *   避免"只加载首页时回帖、再上拉加载更多，新回帖被夹在旧楼中间"的时间错乱。
 * · 向上补更早楼层头插。
 *
 * 鸿蒙侧经 IDataSource 发精准 ADD 通知（LazyForEach 头插不跳动的补偿前提）；
 * Android 的 LazyColumn 由状态驱动按 key 重组，无需该机制，故只保留
 * 数组维护与查询语义，通知层省略 —— 这是平台差异，不是行为差异。
 */
class PostDataSource {

    private val mutablePosts = mutableListOf<TopicPost>()

    /** 只读视图：供 ReadController / PostStreamPager 线性扫描，不得直接增删。 */
    val posts: List<TopicPost> get() = mutablePosts

    val totalCount: Int get() = mutablePosts.size

    /** 整体替换（首屏加载/按楼号重开窗口）。 */
    fun reset(posts: List<TopicPost>) {
        mutablePosts.clear()
        mutablePosts.addAll(posts)
    }

    /**
     * 按 id 去重、按楼号升序插到正确位置（向下分页/发表新回复），返回实际新增条数。
     * 新回复楼号恒为当前最高 → 落到尾部；一批跨过已加载楼时拆成若干段各自落位，
     * 保持全程楼号有序。
     */
    fun appendPosts(newPosts: List<TopicPost>): Int {
        val fresh = collectFresh(newPosts)
        if (fresh.isEmpty()) {
            return 0
        }
        fresh.sortBy { it.postNumber }

        // 快路径：整批落在同一个插入点（fresh 是 stream 上一段连续区间时几乎恒成立）。
        val insertAt = insertIndexForPostNumber(fresh.last().postNumber)
        if (insertIndexForPostNumber(fresh.first().postNumber) == insertAt) {
            mutablePosts.addAll(insertAt, fresh)
            return fresh.size
        }

        // 慢路径：这一批跨过了已加载的楼（例如实时新楼的去抖窗口里同时来了一条
        // 补拉的旧楼和一条新楼）。按最大楼号整块插会让数组失序，拆成若干段，
        // 每段各自落到自己的插入点。
        var added = 0
        var runStart = 0
        while (runStart < fresh.size) {
            val target = insertIndexForPostNumber(fresh[runStart].postNumber)
            var runEnd = runStart + 1
            while (runEnd < fresh.size && insertIndexForPostNumber(fresh[runEnd].postNumber) == target) {
                runEnd++
            }
            mutablePosts.addAll(target, fresh.subList(runStart, runEnd))
            added += runEnd - runStart
            runStart = runEnd
        }
        return added
    }

    /** 有序数组中首个楼号 > 目标的下标（该连续块的落点）；全部 <= 目标时插到末尾。 */
    private fun insertIndexForPostNumber(postNumber: Int): Int {
        for ((index, post) in mutablePosts.withIndex()) {
            if (post.postNumber > postNumber) {
                return index
            }
        }
        return mutablePosts.size
    }

    /** 按 id 去重、按楼号升序头插（向上补更早楼层），返回实际新增条数。 */
    fun prependPosts(newPosts: List<TopicPost>): Int {
        val fresh = collectFresh(newPosts)
        if (fresh.isEmpty()) {
            return 0
        }
        fresh.sortBy { it.postNumber }
        mutablePosts.addAll(0, fresh)
        return fresh.size
    }

    fun findIndexByPostNumber(postNumber: Int): Int =
        PostStreamPager.findIndexByPostNumber(mutablePosts, postNumber)

    /** 首个楼号 >= 目标的下标（容忍目标楼被删形成的空洞）；全部小于目标时返回 -1。 */
    fun findIndexAtOrAfterPostNumber(postNumber: Int): Int =
        mutablePosts.indexOfFirst { it.postNumber >= postNumber }

    private fun collectFresh(newPosts: List<TopicPost>): MutableList<TopicPost> {
        val loadedIds = mutablePosts.mapTo(HashSet()) { it.id }
        val fresh = mutableListOf<TopicPost>()
        for (post in newPosts) {
            if (post.id !in loadedIds) {
                loadedIds.add(post.id)
                fresh.add(post)
            }
        }
        return fresh
    }
}
