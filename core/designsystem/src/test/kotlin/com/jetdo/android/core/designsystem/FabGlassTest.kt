package com.jetdo.android.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Test

class FabGlassTest {
    @Test
    fun `默认半径是改造前的硬编码值`() {
        assertEquals(28, FAB_GLASS_RADIUS_DEFAULT)
        assertEquals(4, FAB_GLASS_RADIUS_MIN)
        assertEquals(60, FAB_GLASS_RADIUS_MAX)
    }

    @Test
    fun `越界收到两端，非数字回默认`() {
        assertEquals(FAB_GLASS_RADIUS_MIN, clampFabGlassRadius(0f))
        assertEquals(FAB_GLASS_RADIUS_MIN, clampFabGlassRadius(-10f))
        assertEquals(FAB_GLASS_RADIUS_MAX, clampFabGlassRadius(999f))
        assertEquals(28, clampFabGlassRadius(28f))

        assertEquals(FAB_GLASS_RADIUS_DEFAULT, clampFabGlassRadius(Float.NaN))
        assertEquals(FAB_GLASS_RADIUS_DEFAULT, clampFabGlassRadius(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `小数四舍五入后再钳制`() {
        assertEquals(27, clampFabGlassRadius(27.4f))
        assertEquals(28, clampFabGlassRadius(27.6f))
    }
}
