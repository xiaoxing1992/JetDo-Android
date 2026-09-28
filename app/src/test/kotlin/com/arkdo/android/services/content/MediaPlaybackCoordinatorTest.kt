package com.arkdo.android.services.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// 对齐鸿蒙 MediaPlaybackCoordinator.ets:同一时刻只有一个媒体在播;下载竞态时落败者不自动播。
class MediaPlaybackCoordinatorTest {

    @Test
    fun `notifyStarted 记活跃者并停其余播放器`() {
        val stoppedA = mutableListOf<Int>()
        val stoppedB = mutableListOf<Int>()
        val a = MediaPlaybackCoordinator.register { stoppedA.add(1) }
        val b = MediaPlaybackCoordinator.register { stoppedB.add(1) }

        MediaPlaybackCoordinator.notifyStarted(a)
        assertTrue(stoppedB.contains(1))
        assertFalse(stoppedA.contains(1))
        assertTrue(MediaPlaybackCoordinator.isActive(a))
        assertFalse(MediaPlaybackCoordinator.isActive(b))

        // 先播 A 再点 B:A 自动停,B 成活跃者。
        MediaPlaybackCoordinator.notifyStarted(b)
        assertTrue(stoppedA.contains(1))
        assertTrue(MediaPlaybackCoordinator.isActive(b))

        MediaPlaybackCoordinator.unregister(a)
        MediaPlaybackCoordinator.unregister(b)
    }

    @Test
    fun `注销活跃者后活跃位清空`() {
        val id = MediaPlaybackCoordinator.register { }
        MediaPlaybackCoordinator.notifyStarted(id)
        assertTrue(MediaPlaybackCoordinator.isActive(id))
        MediaPlaybackCoordinator.unregister(id)
        assertFalse(MediaPlaybackCoordinator.isActive(id))
    }

    @Test
    fun `注册 id 递增且不重复`() {
        val x = MediaPlaybackCoordinator.register { }
        val y = MediaPlaybackCoordinator.register { }
        assertTrue(x != y)
        MediaPlaybackCoordinator.unregister(x)
        MediaPlaybackCoordinator.unregister(y)
        // 顺序稳定递增(对齐鸿蒙 nextId 计数)。
        assertEquals(y, x + 1)
    }
}
