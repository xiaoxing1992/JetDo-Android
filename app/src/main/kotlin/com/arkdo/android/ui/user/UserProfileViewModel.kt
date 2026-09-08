package com.arkdo.android.ui.user

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkdo.android.data.models.TopicCategory
import com.arkdo.android.data.models.UserActivityItem
import com.arkdo.android.data.models.UserProfileDetail
import com.arkdo.android.data.models.UserSummary
import com.arkdo.android.services.network.ArkWebNetworkBridge
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

/** 分段下标（对齐鸿蒙 SEGMENT_*）。 */
const val SEGMENT_TOP_TOPICS = 0
const val SEGMENT_TOP_REPLIES = 1
const val SEGMENT_RECENT = 2

/**
 * 他人主页 ViewModel（对齐鸿蒙 UserProfilePage 的数据流）。
 *
 * 加载：资料 /u/{u}.json + 摘要 /u/{u}/summary.json + 最近活动 /user_actions.json。
 * 本地可变 state（isFollowing/selectedSegment）按 Compose 惯例用 UiState 承载，
 * 语义与鸿蒙 @State 一一对应。帖子里点作者/评论楼层/boost 头像进入本页。
 */
@HiltViewModel
class UserProfileViewModel @Inject constructor(
    private val userProfileService: UserProfileService
) : ViewModel() {

    data class UiState(
        val username: String = "",
        val displayName: String = "",
        val avatarTemplate: String = "",
        // 资料与摘要。
        val profile: UserProfileDetail? = null,
        val summary: UserSummary? = null,
        val loading: Boolean = true,
        val error: String? = null,
        // 最近动态。
        val recentActivity: List<UserActivityItem> = emptyList(),
        // 内容卡当前分段。
        val selectedSegment: Int = SEGMENT_TOP_TOPICS,
        // 关注按钮（discourse-follow）。
        val isFollowing: Boolean = false,
        val followPending: Boolean = false,
        val categories: List<TopicCategory> = emptyList()
    ) {
        val displayUsername: String get() = username
        val avatarUser: com.arkdo.android.data.models.User
            get() = profile?.toAvatarUser() ?: com.arkdo.android.data.models.User(
                id = 0,
                username = username,
                name = displayName.takeIf { it.isNotEmpty() },
                avatarTemplate = avatarTemplate.takeIf { it.isNotEmpty() }
            )
        fun categoryFor(id: Long?): TopicCategory? = CategoryIndex.find(categories, id)
        fun parentFor(id: Long?): TopicCategory? = CategoryIndex.findParent(categories, id)
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var loadedUsername: String? = null

    private fun loadCategoriesIfNeeded() {
        if (_uiState.value.categories.isNotEmpty()) {
            return
        }
        viewModelScope.launch {
            val loaded = CategoryIndex.loadIfNeeded(
                bridge = ArkWebNetworkBridge,
                ready = true,
                alreadyLoaded = _uiState.value.categories.isNotEmpty(),
                logTag = "[ArkDO][UserProfile]"
            ) ?: return@launch
            _uiState.update { it.copy(categories = loaded) }
        }
    }

    fun load(username: String, displayName: String?, avatarTemplate: String?) {
        if (username.isBlank()) return
        // 同一用户重复进入(子页返回):数据已在,直接跳过不重拉(对齐鸿蒙 loadedUsername 校验)。
        if (loadedUsername == username && _uiState.value.profile != null) {
            _uiState.update { it.copy(displayName = displayName ?: it.displayName, avatarTemplate = avatarTemplate ?: it.avatarTemplate) }
            return
        }
        loadCategoriesIfNeeded()
        _uiState.update {
            it.copy(
                username = username,
                displayName = displayName ?: "",
                avatarTemplate = avatarTemplate ?: "",
                loading = true,
                error = null,
                profile = null,
                summary = null,
                recentActivity = emptyList()
            )
        }
        loadedUsername = username
        loadDetail(username)
        loadSummary(username)
        loadActivity(username)
    }

    private fun loadDetail(username: String) {
        viewModelScope.launch {
            val result = userProfileService.getProfileDetail(username)
            if (loadedUsername != username) return@launch
            if (result.success && result.data != null) {
                _uiState.update {
                    it.copy(
                        profile = result.data,
                        isFollowing = result.data.isFollowed == true,
                        loading = false
                    )
                }
            } else {
                _uiState.update {
                    it.copy(loading = false, error = result.error?.message ?: "加载失败")
                }
            }
        }
    }

    private fun loadSummary(username: String) {
        viewModelScope.launch {
            val result = userProfileService.getSummary(username)
            Timber.i("[ArkDO][UserProfile] summary result user=%s success=%s status=%d error=%s",
                username, result.success, result.statusCode,
                result.error?.let { "${it.code}: ${it.message}" } ?: "-")
            if (loadedUsername != username) return@launch
            if (result.success && result.data != null) {
                val s = result.data
                Timber.i("[ArkDO][UserProfile] summary loaded user=%s topics=%d replies=%d circle=%d/%d/%d",
                    username, s.topTopics.size, s.topReplies.size,
                    s.mostRepliedToUsers.size, s.mostLikedUsers.size, s.mostLikedByUsers.size)
                _uiState.update { it.copy(summary = result.data) }
            } else {
                _uiState.update { it.copy(error = result.error?.message ?: "摘要加载失败") }
            }
        }
    }

    private fun loadActivity(username: String) {
        viewModelScope.launch {
            val result = userProfileService.getRecentActivity(username)
            if (loadedUsername != username) return@launch
            if (result.success && result.data != null) {
                _uiState.update { it.copy(recentActivity = result.data) }
            }
        }
    }

    fun selectSegment(index: Int) {
        _uiState.update { it.copy(selectedSegment = index) }
    }

    /** 关注 toggle（对齐鸿蒙 performFollow）。 */
    fun toggleFollow() {
        val state = _uiState.value
        if (state.followPending) return
        val target = !state.isFollowing
        _uiState.update { it.copy(followPending = true) }
        viewModelScope.launch {
            val result = userProfileService.toggleFollow(state.username, target)
            _uiState.update {
                it.copy(
                    followPending = false,
                    isFollowing = if (result.success) target else it.isFollowing
                )
            }
        }
    }

    fun setCategories(categories: List<TopicCategory>) {
        _uiState.update { it.copy(categories = categories) }
    }
}
