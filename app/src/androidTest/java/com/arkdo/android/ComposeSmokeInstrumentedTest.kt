package com.arkdo.android

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI 测试基建冒烟：验证 createComposeRule → setContent → 断言 的链路可用，
 * 后续插桩 UI 测试以此为骨架展开。
 */
@RunWith(AndroidJUnit4::class)
class ComposeSmokeInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compose_rendersAndAsserts() {
        composeRule.setContent { Text("ArkDO") }
        composeRule.onNodeWithText("ArkDO").assertIsDisplayed()
    }
}
