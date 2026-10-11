package com.jetdo.android.services.draft

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * DraftCountStore 是「我的草稿」角标的真源，三条写入路径(会话复查覆盖 / 登出归零 /
 * 删草稿 -1)都由它裁决。这里覆盖边界：未设置态、夹负、减到 0 不越界。
 */
class DraftCountStoreTest {

    @Before
    fun setUp() = DraftCountStore.resetForTest()

    @After
    fun tearDown() = DraftCountStore.resetForTest()

    @Test
    fun `initial state is unset so reader falls back to user snapshot`() {
        assertNull(DraftCountStore.count.value)
    }

    @Test
    fun `session check overwrites with authoritative value`() {
        DraftCountStore.set(7)
        assertEquals(7, DraftCountStore.count.value)
    }

    @Test
    fun `null authoritative value means logged out and collapses to zero`() {
        DraftCountStore.set(7)
        DraftCountStore.set(null)
        assertEquals(0, DraftCountStore.count.value)
    }

    @Test
    fun `negative authoritative value is clamped to zero`() {
        DraftCountStore.set(-5)
        assertEquals(0, DraftCountStore.count.value)
    }

    @Test
    fun `decrement subtracts exactly one`() {
        DraftCountStore.set(3)
        DraftCountStore.decrement()
        assertEquals(2, DraftCountStore.count.value)
    }

    @Test
    fun `decrement never goes below zero`() {
        DraftCountStore.set(0)
        DraftCountStore.decrement()
        assertEquals(0, DraftCountStore.count.value)
    }

    @Test
    fun `decrement from unset state stays at zero instead of negative`() {
        DraftCountStore.decrement()
        assertEquals(0, DraftCountStore.count.value)
    }

    @Test
    fun `reset clears to zero on logout`() {
        DraftCountStore.set(9)
        DraftCountStore.reset()
        assertEquals(0, DraftCountStore.count.value)
    }
}
