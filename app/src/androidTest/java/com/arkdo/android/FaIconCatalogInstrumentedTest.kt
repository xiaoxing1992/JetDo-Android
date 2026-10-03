package com.arkdo.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arkdo.android.core.designsystem.FaIconSet
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FaIcon 资产目录守卫：验证 fa_icons 资产与符号表在真机/模拟器环境下能加载，
 * has() 的存在判定语义（运行时静默不画的依据）与资产目录一致。
 */
@RunWith(AndroidJUnit4::class)
class FaIconCatalogInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun catalog_loadsAndIsNotEmpty() {
        assertTrue("符号表不应为空（加载失败会静默回落 emptySet）", FaIconSet.ids(context).isNotEmpty())
    }

    @Test
    fun catalog_containsKnownIcons() {
        // 与鸿蒙侧同一份 FA 符号表的首批稳定条目
        for (iconId in listOf("a", "address-book", "anchor")) {
            assertTrue("图标 $iconId 应存在", FaIconSet.has(context, iconId))
        }
    }

    @Test
    fun catalog_rejectsUnknownIcons() {
        assertFalse(FaIconSet.has(context, ""))
        assertFalse(FaIconSet.has(context, "__no_such_icon__"))
    }
}
