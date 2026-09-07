package com.arkdo.android.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkdo.android.services.session.SessionChecker
import dagger.hilt.android.lifecycle.HiltViewModel
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

    fun checkCurrentUser() {
        viewModelScope.launch {
            sessionChecker.checkCurrentUser()
        }
    }
}
