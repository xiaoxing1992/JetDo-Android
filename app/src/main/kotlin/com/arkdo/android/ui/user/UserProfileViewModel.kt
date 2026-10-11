package com.arkdo.android.ui.user

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.arkdo.android.R
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
    private val userProfileService: UserProfileService,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    data class UiState(
        val username: String = "",
        val displayName: String = "",
        val avatarTemplate: String = "",
        // 导航种子里的用户 id。头像形状判据是 id==1 走方形(鸿蒙 UserAvatar.avatarBorderRadius
        // 同判据),种子不透传 id 的话兜底恒为 0=圆形,资料到达后真 id 是 1 就圆⇄方闪一下。
        val seedUserId: Long = 0L,
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
        // 认可(discourse-category-experts)。
        val endorseSheetVisible: Boolean = false,
        val endorseLoading: Boolean = false,
        val endorseSubmitting: Boolean = false,
        val endorsableCategories: List<com.arkdo.android.data.models.EndorsableCategory> = emptyList(),
        val remainingEndorsements: Int = 0,
        val endorseSelectedIds: List<Long> = emptyList(),
        val categories: List<TopicCategory> = emptyList()
    ) {
        val displayUsername: String get() = username

        /** 认可可用:detail 里插件字段存在即启用(对齐鸿蒙 endorsementAvailable)。 */
        val canEndorse: Boolean get() = profile?.endorsementAvailable == true

        /** 已认可分类数(按钮文案「已认可 · N」,对齐鸿蒙 endorsedCount)。 */
        val endorsedCount: Int get() = profile?.endorsedCategoryIds?.size ?: 0

        val avatarUser: com.arkdo.android.data.models.User
            get() = profile?.toAvatarUser() ?: com.arkdo.android.data.models.User(
                id = seedUserId,
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
                logTag = "[JetDo][UserProfile]"
            ) ?: return@launch
            _uiState.update { it.copy(categories = loaded) }
        }
    }

    fun load(username: String, displayName: String?, avatarTemplate: String?, userId: Long = 0L) {
        if (username.isBlank()) return
        // 同一用户重复进入(子页返回):数据已在,直接跳过不重拉(对齐鸿蒙 loadedUsername 校验)。
        if (loadedUsername == username && _uiState.value.profile != null) {
            _uiState.update {
                it.copy(
                    displayName = displayName ?: it.displayName,
                    avatarTemplate = avatarTemplate ?: it.avatarTemplate,
                    seedUserId = userId
                )
            }
            return
        }
        loadCategoriesIfNeeded()
        _uiState.update {
            it.copy(
                username = username,
                displayName = displayName ?: "",
                avatarTemplate = avatarTemplate ?: "",
                seedUserId = userId,
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
                    it.copy(loading = false, error = result.error?.message ?: appContext.getString(R.string.common_load_failed))
                }
            }
        }
    }

    private fun loadSummary(username: String) {
        viewModelScope.launch {
            val result = userProfileService.getSummary(username)
            Timber.i("[JetDo][UserProfile] summary result user=%s success=%s status=%d error=%s",
                username, result.success, result.statusCode,
                result.error?.let { "${it.code}: ${it.message}" } ?: "-")
            if (loadedUsername != username) return@launch
            if (result.success && result.data != null) {
                val s = result.data
                Timber.i("[JetDo][UserProfile] summary loaded user=%s topics=%d replies=%d circle=%d/%d/%d",
                    username, s.topTopics.size, s.topReplies.size,
                    s.mostRepliedToUsers.size, s.mostLikedUsers.size, s.mostLikedByUsers.size)
                _uiState.update { it.copy(summary = result.data) }
            } else {
                _uiState.update { it.copy(error = result.error?.message ?: appContext.getString(R.string.detail_summary_failed)) }
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
            val result = if (target) {
                userProfileService.followUser(state.username)
            } else {
                userProfileService.unfollowUser(state.username)
            }
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

    // ==================== 认可(discourse-category-experts)====================

    /** 打开认可弹窗:先复位在途状态,再拉可取分类(对齐鸿蒙 openEndorseSheet)。 */
    fun openEndorseSheet() {
        if (_uiState.value.endorseLoading || _uiState.value.endorseSubmitting) return
        _uiState.update { it.copy(endorseSheetVisible = true) }
        loadEndorsableCategories()
    }

    /** 加载可认可分类 + 今日剩余次数;预选当前已认可项(对齐鸿蒙 loadEndorsableCategories)。 */
    fun loadEndorsableCategories() {
        val state = _uiState.value
        if (state.endorseLoading) return
        // 编辑态预选:弹窗打开即反映当前认可状态。
        _uiState.update {
            it.copy(
                endorseLoading = true,
                endorseSelectedIds = it.profile?.endorsedCategoryIds ?: emptyList()
            )
        }
        viewModelScope.launch {
            val username = _uiState.value.username
            val result = userProfileService.getEndorsableCategories(username)
            _uiState.update {
                it.copy(
                    endorseLoading = false,
                    endorsableCategories = if (result.success && result.data != null) result.data.categories else emptyList(),
                    remainingEndorsements = if (result.success && result.data != null) result.data.remainingEndorsements else 0
                )
            }
        }
    }

    /** 切换一个分类的勾选(对齐鸿蒙 toggleEndorseSelection)。 */
    fun toggleEndorseSelection(categoryId: Long, checked: Boolean) {
        _uiState.update { s ->
            val current = s.endorseSelectedIds
            val next = when {
                checked && categoryId !in current -> current + categoryId
                !checked && categoryId in current -> current - categoryId
                else -> current
            }
            s.copy(endorseSelectedIds = next)
        }
    }

    /** 提交认可(对齐鸿蒙 submitEndorse:成功后更新计数并关弹窗)。 */
    fun submitEndorse() {
        val state = _uiState.value
        if (state.endorseSubmitting || state.endorseSelectedIds.isEmpty()) return
        _uiState.update { it.copy(endorseSubmitting = true) }
        viewModelScope.launch {
            val username = _uiState.value.username
            val selected = _uiState.value.endorseSelectedIds
            val result = userProfileService.endorseCategories(username, selected)
            _uiState.update { s ->
                if (result.success) {
                    val detail = s.profile?.also { it.endorsedCategoryIds = selected }
                    s.copy(
                        endorseSubmitting = false,
                        endorseSheetVisible = false,
                        profile = detail
                    )
                } else {
                    s.copy(endorseSubmitting = false)
                }
            }
        }
    }

    /** 关闭弹窗(拖拽收起 / ✕)。 */
    fun dismissEndorseSheet() {
        _uiState.update { it.copy(endorseSheetVisible = false) }
    }
}
