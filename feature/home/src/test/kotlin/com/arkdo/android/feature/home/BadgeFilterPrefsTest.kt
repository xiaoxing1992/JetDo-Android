package com.arkdo.android.feature.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BadgeFilterPrefsTest {
    @Test
    fun `默认关闭，写入后能读回`() {
        // JVM 单测没有 SharedPreferences，内存镜像就是全部状态：单例默认值即代码默认值。
        // （本测试类是唯一触碰该单例的地方，前置断言不受其它用例串扰。）
        assertFalse(BadgeFilterPrefs.isEnabled())

        BadgeFilterPrefs.setEnabled(true)
        assertTrue(BadgeFilterPrefs.isEnabled())

        BadgeFilterPrefs.setEnabled(false)
        assertFalse(BadgeFilterPrefs.isEnabled())
    }
}
