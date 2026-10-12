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
    fun `底部 tab 顺序为 首页-消息-我的`() {
        assertEquals(
            listOf(
                RootTabDestination.HOME to Routes.HOME,
                // 枚举名与路由保持 NOTIFICATION（推送深链按这个字面量走），
                // 对用户可见的 label 已改为「消息」。
                RootTabDestination.NOTIFICATION to Routes.NOTIFICATION,
                RootTabDestination.PROFILE to Routes.PROFILE
            ),
            rootTabDestinations.map { it to it.route }
        )
    }

    @Test
    fun `消息 tab 的标签是「消息」而非「通知」`() {
        val messages = rootTabDestinations.first { it == RootTabDestination.NOTIFICATION }
        assertEquals("消息", messages.label)
    }
}