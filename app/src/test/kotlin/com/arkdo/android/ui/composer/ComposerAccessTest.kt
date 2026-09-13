package com.arkdo.android.ui.composer

import com.arkdo.android.data.models.User
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerAccessTest {
    @Test
    fun `未登录用户即使持久化登录标记为真也不能进入发帖流程`() {
        assertFalse(
            canUseComposer(
                currentUser = null,
                persistedLogin = true
            )
        )
    }

    @Test
    fun `当前用户存在且持久化登录标记为真才能进入发帖流程`() {
        assertTrue(
            canUseComposer(
                currentUser = User(id = 42L, username = "tester"),
                persistedLogin = true
            )
        )
    }

    @Test
    fun `当前用户存在但持久化登录标记失效时也不能进入发帖流程`() {
        assertFalse(
            canUseComposer(
                currentUser = User(id = 42L, username = "tester"),
                persistedLogin = false
            )
        )
    }
}
