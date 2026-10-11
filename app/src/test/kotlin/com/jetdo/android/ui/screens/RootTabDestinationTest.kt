package com.jetdo.android.ui.screens

import com.jetdo.android.navigation.Routes
import org.junit.Assert.assertEquals
import org.junit.Test
import com.jetdo.android.common.TestStrings
import org.junit.Before

class RootTabDestinationTest {

    @Before
    fun installStrings() = TestStrings.install()

    @Test
    fun `底部 tab 顺序为 首页-通知-我的`() {
        assertEquals(
            listOf(
                RootTabDestination.HOME to Routes.HOME,
                RootTabDestination.NOTIFICATION to Routes.NOTIFICATION,
                RootTabDestination.PROFILE to Routes.PROFILE
            ),
            rootTabDestinations.map { it to it.route }
        )
    }
}