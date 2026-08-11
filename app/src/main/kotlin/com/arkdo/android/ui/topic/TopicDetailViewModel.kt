package com.arkdo.android.ui.topic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkdo.android.data.models.TopicDetail
import com.arkdo.android.data.models.TopicPost
import com.arkdo.android.services.topic.TopicService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 话题详情页 ViewModel
 *
 * 管理话题详情的加载、分页和互动状态。
 */
@HiltViewModel
class TopicDetailViewModel @Inject constructor(
    private val topicService: TopicService
) : ViewModel() {

    private val _uiState = MutableStateFlow(TopicDetailUiState())
    val uiState: StateFlow<TopicDetailUiState> = _uiState.asStateFlow()

    private var currentTopicId: Long? = null
    private var currentSlug: String? = null
    private var loadedPostIds = mutableSetOf<Long>()

    /**
     * 加载话题详情
     */
    fun loadTopic(topicId: Long, slug: String?, title: String?) {
        if (currentTopicId == topicId) return

        currentTopicId = topicId
        currentSlug = slug

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val result = topicService.getTopicDetail(topicId, slug)

            if (result.success && result.data != null) {
                val topic = result.data
                val posts = topic.postStream?.posts ?: emptyList()

                loadedPostIds.clear()
                loadedPostIds.addAll(posts.map { it.id })

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        topic = topic,
                        posts = posts,
                        hasMore = hasMorePosts(topic)
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = result.error?.message ?: "加载失败"
                    )
                }
            }
        }
    }

    /**
     * 加载更多回复
     */
    fun loadMorePosts() {
        val topic = _uiState.value.topic ?: return
        if (_uiState.value.isLoadingMore || !_uiState.value.hasMore) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }

            // 获取下一批未加载的 post IDs
            val allPostIds = topic.postStream?.stream ?: emptyList()
            val nextBatch = allPostIds
                .filter { it !in loadedPostIds }
                .take(20) // 每次加载 20 条

            if (nextBatch.isEmpty()) {
                _uiState.update { it.copy(isLoadingMore = false, hasMore = false) }
                return@launch
            }

            val topicId = currentTopicId ?: return@launch
            val result = topicService.loadMorePosts(topicId, nextBatch)

            if (result.success && result.data != null) {
                val newPosts = result.data
                loadedPostIds.addAll(newPosts.map { it.id })

                _uiState.update { state ->
                    state.copy(
                        isLoadingMore = false,
                        posts = state.posts + newPosts,
                        hasMore = hasMorePosts(topic)
                    )
                }
            } else {
                _uiState.update { it.copy(isLoadingMore = false) }
            }
        }
    }

    /**
     * 重试加载
     */
    fun retry() {
        val topicId = currentTopicId ?: return
        loadTopic(topicId, currentSlug, null)
    }

    /**
     * 判断是否还有更多回复
     */
    private fun hasMorePosts(topic: TopicDetail): Boolean {
        val allPostIds = topic.postStream?.stream ?: return false
        return loadedPostIds.size < allPostIds.size
    }
}

/**
 * 话题详情页 UI 状态
 */
data class TopicDetailUiState(
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
    val topic: TopicDetail? = null,
    val posts: List<TopicPost> = emptyList(),
    val hasMore: Boolean = false
)
