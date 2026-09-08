package com.arkdo.android.ui.user

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkdo.android.data.models.UserBadgeGroup
import com.arkdo.android.data.models.UserBadgeItem
import com.arkdo.android.services.user.UserProfileService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 全部徽章页 VM(对齐鸿蒙 BadgesPage:分组 + 单枚详情)。 */
@HiltViewModel
class BadgesViewModel @Inject constructor(
    private val userProfileService: UserProfileService
) : ViewModel() {

    data class UiState(
        val groups: List<UserBadgeGroup> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
        val selectedBadge: UserBadgeItem? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    private var loadedUsername: String? = null

    fun load(username: String) {
        if (username.isBlank() || loadedUsername == username) return
        loadedUsername = username
        _uiState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = userProfileService.getUserBadges(username)
            if (loadedUsername != username) return@launch
            _uiState.update {
                if (result.success && result.data != null) {
                    it.copy(loading = false, groups = result.data)
                } else {
                    it.copy(loading = false, error = result.error?.message ?: "加载失败,请重试")
                }
            }
        }
    }

    fun selectBadge(badge: UserBadgeItem) {
        _uiState.update { it.copy(selectedBadge = badge) }
    }

    fun clearSelected() {
        _uiState.update { it.copy(selectedBadge = null) }
    }
}
