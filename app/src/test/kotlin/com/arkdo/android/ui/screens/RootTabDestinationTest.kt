package com.arkdo.android.ui.screens

import com.arkdo.android.navigation.Routes
import org.junit.Assert.assertEquals
import org.junit.Test

class RootTabDestinationTest {
    @Test
    fun `底部 tab 顺序为 首页-新首页-通知-我的`() {
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