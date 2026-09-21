package com.arkdo.android.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkdo.android.data.models.TopicCategory
import com.arkdo.android.data.models.UserActivityItem
import com.arkdo.android.data.models.UserBadgeItem
import com.arkdo.android.data.models.UserProfileDetail
import com.arkdo.android.data.models.UserSummaryStats
import com.arkdo.android.services.auth.AuthService
import com.arkdo.android.services.network.ArkWebNetworkBridge
import com.arkdo.android.services.session.CurrentUserHolder
import com.arkdo.android.services.session.LoginGate
import com.arkdo.android.services.session.SessionEventBus
import com.arkdo.android.services.site.CategoryIndex
import com.arkdo.android.services.user.UserProfileService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext

/**
 * 个人中心 ViewModel(对齐鸿蒙 ProfilePage 的数据流)。
 *
 * 已登录时用当前账号 username 拉 资料 + 摘要 + 最近活动(经 UserProfileService);
 * 未登录则整个内容走匿名卡(立即登录)。登出走 AuthService.logout + 本地清态。
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val userProfileService: UserProfileService,
    private val authService: AuthService,
    @ApplicationContext private val context: Context
) : ViewModel() {

    data class UiState(
        val profile: UserProfileDetail? = null,
        val stats: UserSummaryStats? = null,
        val topBadges: List<UserBadgeItem> = emptyList(),
        val recentActivity: List<UserActivityItem> = emptyList(),
        val categories: List<TopicCategory> = emptyList(),
        val loading: Boolean = false,
        val isLoggingOut: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var loadedUsername: String? = null

    private fun loadCategoriesIfNeeded() {
        if (_uiState.value.categories.isNotEmpty()) return
        viewModelScope.launch {
            val loaded = CategoryIndex.loadIfNeeded(
                bridge = ArkWebNetworkBridge,
                ready = true,
                alreadyLoaded = _uiState.value.categories.isNotEmpty(),
                logTag = "[ArkDO][Profile]"
            ) ?: return@launch
            _uiState.update { it.copy(categories = loaded) }
        }
    }

    /** 进入页/账号变化时重载(对齐鸿蒙 loadProfileData:同账号不重复拉)。 */
    fun load(username: String) {
        if (username.isBlank()) return
        loadCategoriesIfNeeded()
        if (loadedUsername == username && _uiState.value.profile != null) return
        loadedUsername = username
        _uiState.update { it.copy(loading = true) }
        viewModelScope.launch {
            val detailR = userProfileService.getProfileDetail(username)
            val summaryR = userProfileService.getSummary(username)
            val activityR = userProfileService.getRecentActivity(username)
            if (loadedUsername != username) return@launch
            _uiState.update {
                it.copy(
                    loading = false,
                    profile = detailR.data,
                    stats = summaryR.data?.stats,
                    topBadges = summaryR.data?.topBadges.orEmpty(),
                    recentActivity = activityR.data.orEmpty()
                )
            }
        }
    }

    /** 登出(对齐鸿蒙 performLogout:先退清理、再 DELETE session、成功后本地清态 + 广播)。 */
    fun logout(onResult: (Boolean) -> Unit) {
        val username = CurrentUserHolder.currentUser.value?.username.orEmpty()
        if (username.isEmpty() || _uiState.value.isLoggingOut) return
        _uiState.update { it.copy(isLoggingOut = true) }
        viewModelScope.launch {
            val result = authService.logout(username)
            _uiState.update { it.copy(isLoggingOut = false) }
            if (!result.success) {
                onResult(false)
                return@launch
            }
            // 先本地落地未登录,再广播(顺序不能反):清了镜像/角标,再触发会话复查与宿主重载。
            CurrentUserHolder.clear()
            LoginGate.setLoggedIn(context, false)
            LoginGate.clearUserPrivileges()
            ArkWebNetworkBridge.markHostReloading()
            SessionEventBus.notifySessionChanged()
            onResult(true)
        }
    }
}
