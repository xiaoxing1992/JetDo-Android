package com.arkdo.android.services.session

import com.arkdo.android.data.models.User
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// ==========================================================================
// 当前登录用户内存持有者(可观察)。
//
// LoginGate 只持久化 id 与登录态布尔;头像/昵称/资料请求还需要 username /
// displayName / avatarTemplate。会话检查(SessionChecker)成功时把解析出的
// User 写到这里,未登录置 null。Compose 侧 collect 它即可在登录/登出/换号
// 后自动刷新首页右上角账号入口与「我的」页。
// ==========================================================================

object CurrentUserHolder {
    private val _currentUser = MutableStateFlow<User?>(null)
    val currentUser: StateFlow<User?> = _currentUser.asStateFlow()

    val isLoggedIn: Boolean get() = _currentUser.value != null

    fun update(user: User?) {
        _currentUser.value = user
    }

    fun clear() {
        _currentUser.value = null
    }
}
