package com.arkdo.android.ui.composer

import android.content.Context
import com.arkdo.android.data.models.User
import com.arkdo.android.services.session.CurrentUserHolder
import com.arkdo.android.services.session.LoginGate

/**
 * 发帖必须同时满足当前会话存在和持久化登录镜像有效。
 *
 * 持久化标记只用于快速镜像，不能单独证明当前会话仍然有效；当前用户为空时，
 * 即使旧标记仍为 true，也必须拦截发帖并请求重新登录。
 */
internal fun canUseComposer(currentUser: User?, persistedLogin: Boolean): Boolean =
    currentUser != null && persistedLogin

/** 统一处理首页入口、直接路由和提交动作的发帖门禁。 */
internal fun ensureComposerAccess(context: Context): Boolean {
    val currentUser = CurrentUserHolder.currentUser.value
    if (!canUseComposer(currentUser, LoginGate.isLoggedIn(context))) {
        LoginGate.requestLogin()
        return false
    }
    return true
}
