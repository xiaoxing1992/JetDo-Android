package com.jetdo.android.services.session

import android.content.Context
import com.jetdo.android.services.auth.AuthService
import com.jetdo.android.services.draft.DraftCountStore
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 会话检查（对齐鸿蒙 RootTabPage.checkCurrentUser 的 LoginGate 数据流部分）：
 *
 * - 成功：LoginGate.setLoggedIn(currentUser != null) + 同帧写入信任等级/管理身份
 *   （登出时 clearUserPrivileges 复位成「未知」，免得下一个账号沿用上一个的等级）。
 * - 失败（CF 盾/断网）：不改 LoginGate —— 保留上一次「检查成功」的登录态，
 *   避免网络抖动把已登录用户误判成游客，写操作被静默拦截强弹登录页。
 *
 * 通知未读广播与轮询启动属通知切片，这里只负责登录态真源。
 */
@Singleton
class SessionChecker @Inject constructor(
    private val authService: AuthService,
    @ApplicationContext private val context: Context
) {
    suspend fun checkCurrentUser() {
        val result = authService.getCurrentUser()
        if (!result.success) {
            Timber.i("%s session check failed, keep previous login state", LOGIN_GATE_LOG_TAG)
            return
        }
        val user = result.data
        LoginGate.setLoggedIn(context, user != null, user?.id)
        CurrentUserHolder.update(user)
        // 草稿角标同源同帧更新：会话结果是这个数的唯一权威来源（对齐鸿蒙
        // RootTabPage.checkCurrentUser 里的 AppStorage.setOrCreate(PROFILE_DRAFT_COUNT_KEY)）。
        // 未登录写成 0，免得下一个账号沿用上一个账号的草稿数。
        DraftCountStore.set(user?.draftCount)
        if (user != null) {
            LoginGate.setUserPrivileges(
                user.trustLevel ?: 0,
                user.admin == true || user.moderator == true
            )
        } else {
            LoginGate.clearUserPrivileges()
        }
        Timber.i(
            "%s current user checked loggedIn=%b username=%s",
            LOGIN_GATE_LOG_TAG, user != null, user?.username.orEmpty()
        )
    }
}
