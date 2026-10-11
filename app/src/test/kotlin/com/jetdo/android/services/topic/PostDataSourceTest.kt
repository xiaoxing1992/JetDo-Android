package com.jetdo.android.services.topic

import com.jetdo.android.data.models.TopicPost
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 楼层懒加载数据源，1:1 对齐鸿蒙 services/topic/PostDataSource.ets 的插入语义。
 *
 * 列表始终按楼号升序：向下分页取到的旧楼要插到已发表新回复**之前**
 * （否则"只加载首页时回帖、再上拉加载更多，新回帖被夹在旧楼中间"的时间错乱）；
 * 向上补更早楼层头插。Android 侧无需鸿蒙 IDataSource 的精准 ADD 通知
 * （LazyColumn 由状态驱动重组），插入算法与去重语义 1:1 保留。
 */
class PostDataSourceTest {

    private fun post(id: Long, postNumber: Int) = TopicPost(id = id, postNumber = postNumber)

    @Test
    fun `reset 整体替换`() {
        val ds = PostDataSource()
        ds.reset(listOf(post(1, 0), post(2, 1)))

        assertEquals(2, ds.totalCount)
        assertEquals(listOf(1L, 2L), ds.posts.map { it.id })
    }

    // 回归:详情页「上拉加载更多」拉回的新楼不显示。appendPosts 的快路径
    // (整批落在同一插入点)直接 return,漏了 republish() —— snapshotPosts 仍是旧引用,
    // UiState.copy(posts = dataSource.snapshotPosts) 前后引用相同,Compose 按状态对象
    // 比对判定"没变",新楼整批不渲染。每次真实插入都必须刷新快照。
    @Test
    fun `append 快路径插入后快照引用必须刷新`() {
        val ds = PostDataSource()
        ds.reset((1L..5L).map { post(it, (it - 1).toInt()) })
        val before = ds.snapshotPosts

        ds.appendPosts((6L..10L).map { post(it, (it - 1).toInt()) })

        val after = ds.snapshotPosts
        org.junit.Assert.assertNotSame(before, after)
        assertEquals(10, after.size)
        assertEquals(10L, after.last().id)
    }

    @Test
    fun `append 按 id 去重并落到尾部`() {
        val ds = PostDataSource()
        ds.reset(listOf(post(1, 0), post(2, 1)))

        assertEquals(2, ds.appendPosts(listOf(post(2, 1), post(3, 2), post(4, 3))))
        assertEquals(listOf(1L, 2L, 3L, 4L), ds.posts.map { it.id })
    }

    @Test
    fun `补拉的旧楼插到新回复之前`() {
        val ds = PostDataSource()
        ds.reset((1L..5L).map { post(it, (it - 1).toInt()) })

        // 用户回帖（楼 30，id 6），随后上拉加载更多取到旧楼 5..9。
        ds.appendPosts(listOf(post(6, 30)))
        assertEquals(5, ds.appendPosts(listOf(post(7, 5), post(8, 6), post(9, 7), post(10, 8), post(11, 9))))

        // 旧楼按楼号升序插在楼 30 之前，新回复仍钉在尾部。
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 30), ds.posts.map { it.postNumber })
        assertEquals(6L, ds.posts.last().id)
    }

    @Test
    fun `一批跨过已加载楼时分段插入保持楼号有序`() {
        val ds = PostDataSource()
        ds.reset(listOf(post(1, 0), post(5, 4)))

        // 同时来一条补拉旧楼（楼 1..2）和一条新楼（楼 9）—— 跨过已加载的楼 5。
        assertEquals(3, ds.appendPosts(listOf(post(2, 1), post(3, 9), post(4, 2))))

        assertEquals(listOf(0, 1, 2, 4, 9), ds.posts.map { it.postNumber })
    }

    @Test
    fun `prepend 头插且按楼号升序`() {
        val ds = PostDataSource()
        ds.reset((6L..10L).map { post(it, (it + 4).toInt()) })

        assertEquals(3, ds.prependPosts(listOf(post(3, 2), post(1, 0), post(2, 1), post(9, 8))))

        assertEquals(listOf(0, 1, 2, 10, 11, 12, 13, 14), ds.posts.map { it.postNumber })
        assertEquals(1L, ds.posts.first().id)
    }

    @Test
    fun `按楼号找下标与容忍空洞的定位`() {
        val ds = PostDataSource()
        ds.reset(listOf(post(1, 0), post(2, 2), post(3, 4)))

        assertEquals(1, ds.findIndexByPostNumber(2))
        assertEquals(-1, ds.findIndexByPostNumber(1))
        // 楼 3 被删形成空洞：定位到首个楼号 >= 3 的楼层（即楼 4，下标 2）。
        assertEquals(2, ds.findIndexAtOrAfterPostNumber(3))
        assertEquals(1, ds.findIndexAtOrAfterPostNumber(2))
        assertEquals(-1, ds.findIndexAtOrAfterPostNumber(9))
    }

    @Test
    fun `全部已存在时 append 返回零`() {
        val ds = PostDataSource()
        ds.reset(listOf(post(1, 0)))

        assertEquals(0, ds.appendPosts(listOf(post(1, 0))))
        assertEquals(1, ds.totalCount)
    }

    // 回归:boost 发出后 UI 不刷新(退出重进才出现)的根因是 UiState.posts 引用了同一个
    // mutable 列表 —— replacePost 后列表内容变了但引用没变,Compose 只按状态对象比对新旧,
    // 内容级变化不被感知。现在每次变更都重建快照,replacePost 前后 snapshotPosts 必须是
    // 不同引用且内容含新楼。
    @Test
    fun `replacePost 后快照引用刷新`() {
        val ds = PostDataSource()
        ds.reset(listOf(post(1L, 1), post(2L, 2)))
        val before = ds.snapshotPosts
        val target = ds.findById(2L)!!
        ds.replacePost(target.copy(boosts = emptyList()))
        val after = ds.snapshotPosts
        org.junit.Assert.assertNotSame(before, after)
        assertEquals(2, after.size)
        assertEquals(2L, after[1].id)
    }
}
