package com.arkdo.android.services.realtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeTopicsPrefsTest {
    @Test
    fun `默认开启，写入后能读回`() {
        RealtimeTopicsPrefs.setEnabled(true)
        assertTrue(RealtimeTopicsPrefs.isEnabled())

        RealtimeTopicsPrefs.setEnabled(false)
        assertFalse(RealtimeTopicsPrefs.isEnabled())
    }
}

