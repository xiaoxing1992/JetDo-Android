package com.arkdo.android.ui.topic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkdo.android.data.models.TopicDetail
import com.arkdo.android.common.constants.AppConstants
import com.arkdo.android.data.models.TopicCategory
import com.arkdo.android.data.models.TopicPost
import com.arkdo.android.services.topic.PostDataSource
import com.arkdo.android.services.topic.PostStreamPager
import com.arkdo.android.services.network.ArkWebNetworkBridge
import com.arkdo.android.services.site.CategoryIndex
import com.arkdo.android.services.topic.TopicService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 每页拉取楼层数，对齐鸿蒙 POSTS_PAGE_SIZE。 */
private const val POSTS_PAGE_SIZE = 20

/** 跳楼落地高亮时长，对齐鸿蒙 armTargetFlash 的 3s。 */
private const val TARGET_FLASH_MS = 3000L

/**
 * 话题详情页 ViewModel —— 窗口式加载，对齐鸿蒙 TopicDetailPage 的数据流。
 *
 * 首屏从一楼开窗；跳楼时：目标楼已加载 → 发一次性滚动请求；未加载 →
 * [rewindowToPost] 以目标楼为锚重拉一窗（详情接口本身支持楼号锚点）。
 * 双向分页沿 stream 由 [PostStreamPager] 取 id、[PostDataSource] 按楼号有序落位。
 *
 * 鸿蒙侧 loadEarlier 的 prefetch/pending 两段式（滚动停稳才插、避免列表跳动）
 * 在 Compose 由 LazyColumn 的 key 锚定行为承担，这里直接 prepend；
 * 滚动位置补偿若实测有跳动再在屏幕层补 —— 平台差异，行为语义不变。
 */
@HiltViewModel
class TopicDetailViewModel @Inject constructor(
    private val topicService: TopicService
) : ViewModel() {

    private val dataSource = PostDataSource()

    private val _uiState = MutableStateFlow(TopicDetailUiState())
    val uiState: StateFlow<TopicDetailUiState> = _uiState.asStateFlow()

    private var currentTopicId: Long? = null
    private var currentSlug: String? = null

    fun loadTopic(topicId: Long, slug: String?, title: String?, targetPostNumber: Int? = null) {
        if (currentTopicId == topicId) {
            // 同一话题再次进入（如引用跳楼）：走跳楼分流，不重复首屏加载。
            if (targetPostNumber != null) {
                jumpToPostNumber(targetPostNumber)
            }
            return
        }
        currentTopicId = topicId
        currentSlug = slug

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val result = topicService.getTopicDetail(topicId, slug)

            if (result.success && result.data != null) {
                applyDetail(result.data)
                _uiState.update { it.copy(isLoading = false) }
                loadCategoriesIfNeeded()
                // 对齐鸿蒙：带目标楼进入时首屏照常从一楼加载，随后走跳楼分流。
                if (targetPostNumber != null) {
                    jumpToPostNumber(targetPostNumber)
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
     * 跳到指定楼层：已加载 → 发一次性滚动请求并武装落地高亮；
     * 未加载 → 重开窗口。对齐鸿蒙 jumpToPostNumber。
     */
    fun jumpToPostNumber(postNumber: Int) {
        val index = dataSource.findIndexByPostNumber(postNumber)
        if (index >= 0) {
            _uiState.update {
                it.copy(
                    scrollRequestPostNumber = postNumber,
                    flashTargetPostId = dataSource.posts[index].id
                )
            }
            armTargetFlash(dataSource.posts[index].id)
        } else {
            rewindowToPost(postNumber)
        }
    }

    /**
     * 以目标楼为锚重拉一窗并整体替换（鸿蒙 rewindowToPost）。
     * 失败时旧列表保持原位，只发一条提示消息。
     */
    private fun rewindowToPost(postNumber: Int, asTop: Boolean = false) {
        val topic = _uiState.value.topic ?: return
        if (_uiState.value.isRewindowing) {
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isRewindowing = true) }
            val result = topicService.getTopicDetail(topic.id, currentSlug, postNumber)
            _uiState.update { it.copy(isRewindowing = false) }

            val detail = result.data
            if (result.success && detail != null && detail.postStream?.posts?.isNotEmpty() == true) {
                applyDetail(detail)
                _uiState.update {
                    it.copy(
                        scrollRequestPostNumber = if (asTop) null else postNumber,
                        flashTargetPostId = if (asTop) null else detail.postStream?.posts
                            ?.firstOrNull { it.postNumber >= postNumber }?.id
                    )
                }
                if (!asTop) {
                    _uiState.value.flashTargetPostId?.let(::armTargetFlash)
                }
            } else {
                _uiState.update { it.copy(actionMessage = "楼层定位失败，请稍后重试") }
            }
        }
    }

    /** 上拉加载更多（向下分页），对齐鸿蒙 loadMorePosts。 */
    fun loadMorePosts() {
        val state = _uiState.value
        val topic = state.topic ?: return
        if (state.isLoadingMore || !state.hasMore) {
            return
        }
        val stream = topic.postStream?.stream ?: emptyList()
        val nextIds = PostStreamPager.collectNextIds(stream, dataSource.posts, POSTS_PAGE_SIZE)
        if (nextIds.isEmpty()) {
            _uiState.update { it.copy(hasMore = false, loadMoreMessage = "没有更多了") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true, loadMoreMessage = "正在加载更多") }
            val result = topicService.loadMorePosts(topic.id, nextIds)

            if (result.success && result.data != null) {
                dataSource.appendPosts(result.data)
                _uiState.update {
                    it.copy(
                        isLoadingMore = false,
                        posts = dataSource.posts,
                        hasMore = PostStreamPager.countLater(stream, dataSource.posts) > 0,
                        loadMoreMessage = if (
                            PostStreamPager.countLater(stream, dataSource.posts) > 0
                        ) "上拉加载更多" else "没有更多了"
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isLoadingMore = false,
                        loadMoreMessage = "加载更多失败，上拉重试"
                    )
                }
            }
        }
    }

    /** 下拉补更早楼层（向上分页），对齐鸿蒙 prefetchEarlierPosts 的数据流部分。 */
    fun loadEarlierPosts() {
        val state = _uiState.value
        val topic = state.topic ?: return
        if (state.isLoadingEarlier || !state.hasEarlier) {
            return
        }
        val stream = topic.postStream?.stream ?: emptyList()
        val prevIds = PostStreamPager.collectPrevIds(stream, dataSource.posts, POSTS_PAGE_SIZE)
        if (prevIds.isEmpty()) {
            _uiState.update { it.copy(hasEarlier = false) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingEarlier = true) }
            val result = topicService.loadMorePosts(topic.id, prevIds)

            if (result.success && result.data != null) {
                dataSource.prependPosts(result.data)
                _uiState.update {
                    it.copy(
                        isLoadingEarlier = false,
                        posts = dataSource.posts,
                        hasEarlier = PostStreamPager.countEarlier(stream, dataSource.posts) > 0,
                        earlierCount = PostStreamPager.countEarlier(stream, dataSource.posts)
                    )
                }
            } else {
                _uiState.update { it.copy(isLoadingEarlier = false) }
            }
        }
    }

    /** 按需加载站点分类（应用级缓存），供摘要卡分类徽章显示。对齐鸿蒙 loadSiteMetadataIfNeeded。 */
    private fun loadCategoriesIfNeeded() {
        if (_uiState.value.categories.isNotEmpty()) {
            return
        }
        viewModelScope.launch {
            CategoryIndex.loadIfNeeded(
                bridge = ArkWebNetworkBridge,
                ready = true,
                alreadyLoaded = _uiState.value.categories.isNotEmpty(),
                logTag = "[ArkDO][TopicDetail]"
            )?.let { loaded ->
                _uiState.update { it.copy(categories = loaded) }
            }
        }
    }

    /**
     * 表情回应切换（点赞 = heart）：请求先行，结果经 done 回调（成功 true）。
     * 成功后的本地计数/已点态刷新依赖详情重拉，暂以回调通知屏幕。
     */
    fun toggleReaction(post: TopicPost, reaction: String, done: (Boolean) -> Unit) {
        val topicId = currentTopicId ?: return done(false)
        viewModelScope.launch {
            val result = topicService.toggleReaction(post.id, topicId, reaction)
            done(result.success)
        }
    }

    /** 书签 toggle：加书签直接加；移除需要 bookmarkId（详情接口暂未下发，暂不支持移除）。 */
    fun toggleBookmark(post: TopicPost, done: (Boolean) -> Unit) {
        val topicId = currentTopicId ?: return done(false)
        viewModelScope.launch {
            if (post.bookmarked == true) {
                val bookmarkId = post.bookmarkId
                if (bookmarkId == null) {
                    done(true)
                    return@launch
                }
                val result = topicService.removeBookmark(bookmarkId, topicId)
                done(result.success)
            } else {
                val result = topicService.addBookmark(post.id, topicId)
                done(result.success)
            }
        }
    }

    /** 楼层永久链接（拷贝链接用）。 */
    fun postLink(post: TopicPost): String? {
        val topic = _uiState.value.topic ?: return null
        val slug = topic.slug ?: return null
        return "${AppConstants.BASE_URL}/t/$slug/${topic.id}/${post.postNumber}"
    }

    /** 屏幕消费一次性滚动请求后调用，避免重组时重复触发。 */
    fun consumeScrollRequest() {
        _uiState.update { it.copy(scrollRequestPostNumber = null) }
    }

    /** 屏幕展示一次性提示消息后调用。 */
    fun consumeActionMessage() {
        _uiState.update { it.copy(actionMessage = null) }
    }

    /** 整体替换数据源并重算双向余量（首屏/换窗共用）。 */
    private fun applyDetail(detail: TopicDetail) {
        dataSource.reset(detail.postStream?.posts ?: emptyList())
        val stream = detail.postStream?.stream ?: emptyList()
        val earlierCount = PostStreamPager.countEarlier(stream, dataSource.posts)
        val laterCount = PostStreamPager.countLater(stream, dataSource.posts)
        _uiState.update {
            it.copy(
                topic = detail,
                posts = dataSource.posts,
                hasMore = laterCount > 0,
                hasEarlier = earlierCount > 0,
                earlierCount = earlierCount,
                loadMoreMessage = if (laterCount > 0) "上拉加载更多" else "没有更多了"
            )
        }
    }

    /** 武装目标楼落地高亮：3s 后自动清零，避免楼层重建时重播（对齐鸿蒙 armTargetFlash）。 */
    private fun armTargetFlash(postId: Long) {
        _uiState.update { it.copy(flashTargetPostId = postId) }
        viewModelScope.launch {
            delay(TARGET_FLASH_MS)
            _uiState.update { if (it.flashTargetPostId == postId) it.copy(flashTargetPostId = null) else it }
        }
    }

    fun retry() {
        val topicId = currentTopicId ?: return
        dataSource.reset(emptyList())
        _uiState.update { it.copy(topic = null) }
        currentTopicId = null
        loadTopic(topicId, currentSlug, null)
    }
}

/**
 * 话题详情页 UI 状态。
 *
 * [posts] 始终来自 [PostDataSource]（按楼号升序）；[scrollRequestPostNumber] 是
 * 一次性请求，屏幕滚动完成后必须调 [consumeScrollRequest] 清除。
 */
data class TopicDetailUiState(
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isLoadingEarlier: Boolean = false,
    val isRewindowing: Boolean = false,
    val error: String? = null,
    val actionMessage: String? = null,
    val topic: TopicDetail? = null,
    val posts: List<TopicPost> = emptyList(),
    val hasMore: Boolean = false,
    val hasEarlier: Boolean = false,
    val earlierCount: Int = 0,
    val loadMoreMessage: String = "",
    val scrollRequestPostNumber: Int? = null,
    val flashTargetPostId: Long? = null,
    val categories: List<TopicCategory> = emptyList()
)
