package com.arkdo.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arkdo.android.core.data.AppContextHolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 设备环境冒烟：验证插桩基建、包名、以及 AppContextHolder 在真实 Android 环境下
 * 能解析应用资源（对照 JVM 单测侧的 TestStrings resolver 钩子）。
 */
@RunWith(AndroidJUnit4::class)
class SmokeInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        AppContextHolder.init(context.applicationContext)
    }

    @Test
    fun packageName_matches() {
        assertEquals("com.arkdo.android", context.packageName)
    }

    @Test
    fun appContextHolder_resolvesResource() {
        assertEquals("JetDo", AppContextHolder.getString(R.string.app_name))
    }

    @Test
    fun localizedStrings_resolvable() {
        assertTrue(AppContextHolder.getString(R.string.tab_home).isNotEmpty())
        assertEquals(context.getString(R.string.tab_home), AppContextHolder.getString(R.string.tab_home))
    }
}
