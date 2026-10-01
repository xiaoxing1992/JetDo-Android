package com.arkdo.android.services.realtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeTopicsPrefsTest {
    @Test
    fun `默认关闭，写入后能读回`() {
        // JVM 单测没有 SharedPreferences，内存镜像就是全部状态：单例默认值即代码默认值。
        // （本测试类是唯一触碰该单例的地方，前置断言不受其它用例串扰。）
        assertFalse(RealtimeTopicsPrefs.isEnabled())

        RealtimeTopicsPrefs.setEnabled(true)
        assertTrue(RealtimeTopicsPrefs.isEnabled())

        RealtimeTopicsPrefs.setEnabled(false)
        assertFalse(RealtimeTopicsPrefs.isEnabled())
    }
}
