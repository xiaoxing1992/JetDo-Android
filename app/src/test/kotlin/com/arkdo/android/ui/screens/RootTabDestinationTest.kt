package com.arkdo.android.ui.screens

import com.arkdo.android.navigation.Routes
import org.junit.Assert.assertEquals
import org.junit.Test

class RootTabDestinationTest {
    @Test
    fun `首页 tab 顺序先旧首页再新首页`() {
        assertEquals(
            listOf(
                RootTabDestination.LEGACY_HOME to Routes.HOME,
                RootTabDestination.NEW_HOME to Routes.NEW_HOME,
                RootTabDestination.NOTIFICATION to Routes.NOTIFICATION,
                RootTabDestination.PROFILE to Routes.PROFILE
            ),
            rootTabDestinations.map { it to it.route }
        )
    }
}
