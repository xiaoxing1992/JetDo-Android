package com.arkdo.android.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkdo.android.services.session.SessionChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 根容器的会话检查宿主（对应鸿蒙 RootTabPage aboutToAppear 里的 checkCurrentUser 触发点）。
 *
 * 启动时跑一次 /session/current.json：成功写 LoginGate 登录态与信任等级镜像，
 * 失败保留上次登录态。此后 requestLogin 监听/通知轮询等按各自切片接。
 */
@HiltViewModel
class SessionViewModel @Inject constructor(
    private val sessionChecker: SessionChecker
) : ViewModel() {

    private val _isChecking = MutableStateFlow(false)
    /**
     * 会话检查进行中。首页右上角账号入口据此转圈（对齐鸿蒙 HomePage.ets:1307 的
     * isCheckingSession 分支）。此前 RootTabScreen 恒传 false，那个分支是死的——
     * 启动瞬间未登录图标会闪一下再变头像。
     */
    val isChecking: StateFlow<Boolean> = _isChecking.asStateFlow()

    fun checkCurrentUser() {
        viewModelScope.launch {
            _isChecking.value = true
            try {
                sessionChecker.checkCurrentUser()
            } finally {
                _isChecking.value = false
            }
        }
    }
}
