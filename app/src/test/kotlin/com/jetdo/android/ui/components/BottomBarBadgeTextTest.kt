package com.jetdo.android.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 底栏未读角标的边界:负数/0 不画,超过 99 收敛成 99+。
 *
 * 99 这条尤其值得钉住 —— 论坛未读上千很常见,不收窄会把标签格撑变形。
 */
class BottomBarBadgeTextTest {

    @Test
    fun `未读为 0 不显示角标`() {
        assertNull(bottomBarBadgeText(0))
    }

    @Test
    fun `负数不显示角标`() {
        // 服务端字段缺失时调用方会传 0,但乐观递减路径理论上可能短暂为负,不能画出 "-1"。
        assertNull(bottomBarBadgeText(-3))
    }

    @Test
    fun `普通计数原样显示`() {
        assertEquals("1", bottomBarBadgeText(1))
        assertEquals("42", bottomBarBadgeText(42))
        assertEquals("99", bottomBarBadgeText(99))
    }

    @Test
    fun `超过 99 收敛成 99+`() {
        assertEquals("99+", bottomBarBadgeText(100))
        assertEquals("99+", bottomBarBadgeText(1234))
    }
}