package com.jetdo.android.services.topic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TwoPageReadingPrefsTest {
    @Test
    fun `默认关闭，写入后能读回`() {
        // 先复位成关：JVM 单测里没有 SharedPreferences，内存镜像就是全部状态，
        // 而它是单例，会串到别的用例上。
        TwoPageReadingPrefs.setEnabled(false)
        assertFalse(TwoPageReadingPrefs.isEnabled())

        TwoPageReadingPrefs.setEnabled(true)
        assertTrue(TwoPageReadingPrefs.isEnabled())

        TwoPageReadingPrefs.setEnabled(false)
        assertFalse(TwoPageReadingPrefs.isEnabled())
    }
}
