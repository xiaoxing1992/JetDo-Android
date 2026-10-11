package com.arkdo.android.navigation

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 深链解析与事件通道。
 *
 * 解析用 java.net.URI 而不是 android.net.Uri，正是为了能在这里跑 —— 这段逻辑
 * 「看着简单、错起来全是静默失效」：host 写错、topicId 解析不出来，用户点通知
 * 就是没反应，且不报任何错。
 *
 * 事件通道测的是积压语义：冷启动时 intent 先于 NavHost 到达，丢了就白点一次。
 */
class DeepLinkEventBusTest {

    @Before
    fun setUp() {
        // 单例跨用例残留订阅者，每个用例开始前清干净。
        DeepLinkEventBus.resetForTest()
    }

    @After
    fun tearDown() {
        DeepLinkEventBus.resetForTest()
    }

    // ── 解析 ────────────────────────────────────────────────────────────

    @Test
    fun `无参数的 notification 深链落到通知中心`() {
        assertEquals(
            DeepLinkTarget.NotificationInbox,
            DeepLinkEventBus.parse("jetdo://notification")
        )
    }

    @Test
    fun `带话题与楼层的深链落到话题详情`() {
        assertEquals(
            DeepLinkTarget.Topic(topicId = 123L, postNumber = 4),
            DeepLinkEventBus.parse("jetdo://notification?topicId=123&postNumber=4")
        )
    }

    @Test
    fun `只带话题不带楼层`() {
        assertEquals(
            DeepLinkTarget.Topic(topicId = 123L, postNumber = null),
            DeepLinkEventBus.parse("jetdo://notification?topicId=123")
        )
    }

    @Test
    fun `话题 id 非法时退回通知中心而不是跳进一个必然报错的话题页`() {
        listOf("abc", "0", "-5", "").forEach { bad ->
            assertEquals(
                "topicId=$bad 应退回通知中心",
                DeepLinkTarget.NotificationInbox,
                DeepLinkEventBus.parse("jetdo://notification?topicId=$bad")
            )
        }
    }

    @Test
    fun `楼层号非法时忽略它但话题照跳`() {
        assertEquals(
            DeepLinkTarget.Topic(topicId = 7L, postNumber = null),
            DeepLinkEventBus.parse("jetdo://notification?topicId=7&postNumber=0")
        )
    }

    @Test
    fun `scheme 与 host 大小写不敏感`() {
        assertEquals(
            DeepLinkTarget.NotificationInbox,
            DeepLinkEventBus.parse("JETDO://Notification")
        )
    }

    @Test
    fun `不认识的 scheme host 与空值一律不解析`() {
        assertNull("http 链接不该被本应用的深链接走", DeepLinkEventBus.parse("https://linux.do/notification"))
        assertNull("host 不认识", DeepLinkEventBus.parse("jetdo://topic/123"))
        assertNull(DeepLinkEventBus.parse(null))
        assertNull(DeepLinkEventBus.parse(""))
        assertNull(DeepLinkEventBus.parse("   "))
        assertNull("不是 URL", DeepLinkEventBus.parse("不是个URL"))
        assertNull("带空格解析不出 scheme", DeepLinkEventBus.parse("not a url"))
    }

    // ── 事件通道 ────────────────────────────────────────────────────────

    @Test
    fun `有订阅者时直接派发`() {
        val received = mutableListOf<DeepLinkTarget>()
        DeepLinkEventBus.subscribe { received.add(it) }

        DeepLinkEventBus.dispatch(DeepLinkTarget.NotificationInbox)

        assertEquals(listOf(DeepLinkTarget.NotificationInbox), received)
    }

    @Test
    fun `没有订阅者时先积压 订阅时补发一次`() {
        // 冷启动：intent 在 onCreate 就拿到了，NavHost 要等首帧才订阅。
        DeepLinkEventBus.dispatch(DeepLinkTarget.Topic(topicId = 9L, postNumber = 2))

        val late = mutableListOf<DeepLinkTarget>()
        DeepLinkEventBus.subscribe { late.add(it) }

        assertEquals(listOf(DeepLinkTarget.Topic(topicId = 9L, postNumber = 2)), late)

        // 补发过就该清掉：Activity 重建时不该再导航一次。
        val second = mutableListOf<DeepLinkTarget>()
        DeepLinkEventBus.subscribe { second.add(it) }
        assertTrue("积压的目标只能被消费一次", second.isEmpty())
    }

    @Test
    fun `退订之后不再收到事件`() {
        val received = mutableListOf<DeepLinkTarget>()
        val unsubscribe = DeepLinkEventBus.subscribe { received.add(it) }
        unsubscribe()

        DeepLinkEventBus.dispatch(DeepLinkTarget.NotificationInbox)

        assertTrue(received.isEmpty())
    }

    @Test
    fun `一个订阅者抛异常不影响其余订阅者`() {
        val received = mutableListOf<DeepLinkTarget>()
        DeepLinkEventBus.subscribe { error("订阅者炸了") }
        DeepLinkEventBus.subscribe { received.add(it) }

        DeepLinkEventBus.dispatch(DeepLinkTarget.NotificationInbox)

        assertEquals("异常必须被隔离，否则后一个订阅者收不到", 1, received.size)
    }
}
