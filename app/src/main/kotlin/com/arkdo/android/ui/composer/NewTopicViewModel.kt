package com.arkdo.android.ui.composer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.arkdo.android.services.draft.DraftCountStore
import com.arkdo.android.services.draft.DraftService
import com.arkdo.android.services.draft.SaveTopicDraftInput
import com.arkdo.android.services.draft.draftHasContent
import com.arkdo.android.services.draft.resolveDraftKey
import com.arkdo.android.services.network.NetworkResult
import com.arkdo.android.services.session.CurrentUserHolder
import com.arkdo.android.services.topic.TagSuggestion
import com.arkdo.android.services.topic.TopicService
import com.arkdo.android.data.models.DraftTagRef
import java.io.Serializable
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/** 发帖最多可选标签数(对齐鸿蒙 ComposerPage.MAX_TAGS,对应站点 max_tags_per_topic)。 */
const val COMPOSER_MAX_TAGS = 8
/** 标签联想防抖,对齐鸿蒙 ComposerPage 的 250ms。 */
const val TAG_QUERY_DEBOUNCE_MS = 250L
/** 自动存草稿防抖,对齐鸿蒙 ComposerPage 的 2 秒。 */
const val DRAFT_SAVE_DEBOUNCE_MS = 2000L

/**
 * 发新主题 ViewModel —— 首页发帖浮层的数据流(对齐鸿蒙 HomePage.openComposer →
 * ComposerPage(composeTopic) → CreateTopicFlow.submitNewTopic)。
 *
 * 本轮范围:标题/正文/分类/标签 + 提交(POST /posts)+ 图片上传 + 新主题自动存草稿。
 * 视频/附件属后续切片。回帖草稿不在这条链路上。
 */
@HiltViewModel
class NewTopicViewModel @Inject constructor(
    private val topicService: TopicService,
    private val draftService: DraftService,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(NewTopicUiState())
    val uiState: StateFlow<NewTopicUiState> = _uiState.asStateFlow()

    private var tagDebounceJob: Job? = null
    private var tagQuerySeq = 0
    private var tagLoadingJob: Job? = null
    private var draftSaveJob: Job? = null

    // 草稿运行态不进 UI:key 首次保存才生成,续写沿用带进来的;序号必须用服务端回的下一次值。
    private var draftKey = ""
    private var draftSequence = 0L
    private var draftDirty = false
    private var draftSaving = false
    private var draftPublished = false
    private var draftPersisted = false

    // 离屏 markdown 引擎:跟随首帧加载,页面销毁释放。仅当渲染真正需要时才懒建。
    private var renderEngine: MarkdownRenderEngine? = null

    /** 懒建渲染引擎(首次切预览时)。线程安全要求不高:UI 串行调用。 */
    fun ensureRenderEngine(context: Context): MarkdownRenderEngine {
        renderEngine?.let { return it }
        val engine = MarkdownRenderEngine(context)
        renderEngine = engine
        return engine
    }

    // 遥测:展开时刻 / 首次输入时刻。composerMs、typingMs 结算用。
    private var appearTimeMs = 0L
    private var firstTypeTimeMs = 0L

    fun onOpened(initialCategoryId: Long?, resume: NewTopicResume? = null) {
        appearTimeMs = System.currentTimeMillis()
        if (resume != null) {
            draftKey = resume.draftKey
            draftSequence = resume.sequence
            draftPersisted = resume.draftKey.isNotEmpty()
            _uiState.update {
                it.copy(
                    title = resume.title,
                    body = resume.body,
                    categoryId = resume.categoryId,
                    tags = resume.tags
                )
            }
        } else if (initialCategoryId != null && _uiState.value.categoryId == null) {
            _uiState.update { it.copy(categoryId = initialCategoryId) }
        }
        if (_uiState.value.categoryId != null) {
            loadTags("")
        }
    }

    // ========== 字段编辑 ==========

    fun setTitle(title: String) {
        noteTyping(title)
        _uiState.update { it.copy(title = title) }
        scheduleDraftSave()
    }

    fun setBody(body: String) {
        noteTyping(body)
        _uiState.update { it.copy(body = body) }
        scheduleDraftSave()
    }

    fun selectCategory(categoryId: Long?) {
        _uiState.update {
            it.copy(
                categoryId = categoryId,
                // 标签隶属分类:换分类后清空重选(对齐鸿蒙 PickerRow 的 onClick)。
                tags = emptyList(),
                tagResults = emptyList()
            )
        }
        scheduleDraftSave()
        if (categoryId != null) loadTags("")
    }

    fun isTagSelected(tagId: Long): Boolean = _uiState.value.tags.any { it.id == tagId }

    /**
     * 选/取消一个标签。已达上限时拒绝新增并返回 false(UI 层 toast)。
     */
    fun toggleTag(tag: TagSuggestion): Boolean {
        val state = _uiState.value
        if (state.tags.any { it.id == tag.id }) {
            _uiState.update { it.copy(tags = it.tags.filterNot { t -> t.id == tag.id }) }
        } else {
            if (state.tags.size >= COMPOSER_MAX_TAGS) return false
            _uiState.update { it.copy(tags = it.tags + tag) }
        }
        scheduleDraftSave()
        return true
    }

    // ========== 标签联想 ==========

    /** 打开标签选择器:清空搜索词,立刻拉一遍热门标签(q 为空)。 */
    fun openTagPicker() {
        tagQuerySeq++ // 使在途联想过期,避免旧结果盖掉「空 q 热门」。
        _uiState.update { it.copy(tagQuery = "", tagResults = emptyList()) }
        loadTags("")
    }

    fun setTagQuery(query: String) {
        _uiState.update { it.copy(tagQuery = query) }
    }

    /** 打开标签选择器 / 清空搜索词时拉一次热门标签(q 为空)。 */
    fun loadTags(query: String) {
        val categoryId = _uiState.value.categoryId ?: return
        if (tagLoadingJob?.isActive == true) return
        val seq = ++tagQuerySeq
        _uiState.update { it.copy(tagLoading = true) }
        tagLoadingJob = viewModelScope.launch {
            val result = topicService.searchTags(categoryId, query.trim())
            if (seq != tagQuerySeq) return@launch
            _uiState.update {
                it.copy(
                    tagLoading = false,
                    tagResults = if (result.success && result.data != null) result.data else emptyList()
                )
            }
        }
    }

    /** 搜索词变化:防抖 250ms 再查(输入停顿才发一次,避免逐字狂发)。 */
    fun onTagQueryChanged(query: String) {
        tagDebounceJob?.cancel()
        tagDebounceJob = viewModelScope.launch {
            delay(TAG_QUERY_DEBOUNCE_MS)
            loadTags(query.trim())
        }
    }

    // ========== 上传 ==========

    /**
     * 上传一张图片:base64 → POST /uploads.json → 返回可插入正文的 markdown 片段。
     * done(snippet, error):snippet 非空=成功;error 空串=用户取消/无需提示;否则失败文案。
     * 请求期间置 isUploading,UI 据此盖遮罩、禁用重入。
     */
    fun uploadImage(
        base64: String,
        fileName: String,
        mimeType: String,
        done: (snippet: String?, error: String?) -> Unit
    ) {
        if (_uiState.value.isUploading) return
        _uiState.update { it.copy(isUploading = true, uploadProgressText = "图片上传中...") }
        viewModelScope.launch {
            val response = com.arkdo.android.services.network.ArkWebNetworkBridge.uploadImage(
                base64 = base64,
                fileName = fileName,
                mimeType = mimeType,
                options = com.arkdo.android.services.network.JsonRequestOptions(notifyChallenge = true, referrer = "${com.arkdo.android.common.constants.AppConstants.BASE_URL}/")
            )
            val outcome = parseUploadOutcome(response)
            _uiState.update {
                it.copy(
                    isUploading = false,
                    uploadProgressText = null,
                    uploadFailures = if (outcome.error != null && outcome.error.isNotEmpty()) {
                        it.uploadFailures + outcome.error
                    } else {
                        it.uploadFailures
                    }
                )
            }
            done(outcome.snippet, outcome.error)
        }
    }

    /** UI 层展示完失败 toast 后清除累积失败。 */
    fun consumeUploadFailures() {
        _uiState.update { it.copy(uploadFailures = emptyList()) }
    }

    // ========== 预览 ==========

    /**
     * 把正文编译成可渲染的 HTML(本地 markdown-it 引擎)。
     * @param raw markdown 原文(当前光标所在候选,见调用侧说明)
     * @return 编译后的 HTML;失败/引擎未就绪返回 null,由 UI 退回原文。
     */
    suspend fun renderPreview(raw: String): String? =
        renderEngine?.render(raw)

    // ========== 提交 ==========

    fun canSubmit(): Boolean {
        val s = _uiState.value
        return !s.isSubmitting && s.title.trim().isNotEmpty() && s.categoryId != null &&
            s.tags.isNotEmpty() && s.body.trim().isNotEmpty()
    }

    /** 发送按钮置灰时点它:说清楚缺什么,而不是毫无反应。 */
    fun explainBlocked(): String? = when {
        _uiState.value.title.trim().isEmpty() -> "还没写标题"
        _uiState.value.categoryId == null -> "还没选分类"
        _uiState.value.tags.isEmpty() -> "至少选一个标签"
        _uiState.value.body.trim().isEmpty() -> "正文不能为空"
        else -> null
    }

    /** 已登录检查(首页点发帖前已拦过一次;防浮层悬挂期间登出)。 */
    fun ensureLoggedIn(): Boolean = ensureComposerAccess(appContext)

    /**
     * 提交发帖。直接发布 → done(true, topicId, message) 由 UI 跳详情;
     * 进审核队列 → done(true, null, message) 只提示;失败 → done(false, null, message) 保留内容。
     */
    fun submit(done: (ok: Boolean, topicId: Long?, message: String) -> Unit) {
        val state = _uiState.value
        if (state.isSubmitting) return
        if (!ensureLoggedIn()) {
            done(false, null, "请先登录")
            return
        }
        _uiState.update { it.copy(isSubmitting = true) }
        val composerMs = if (appearTimeMs > 0) System.currentTimeMillis() - appearTimeMs else 0L
        val typingMs = if (firstTypeTimeMs > 0) System.currentTimeMillis() - firstTypeTimeMs else 0L
        viewModelScope.launch {
            val result = topicService.createTopic(
                title = state.title.trim(),
                categoryId = state.categoryId ?: 0L,
                raw = state.body.trim(),
                tags = state.tags.map { it.name },
                typingDurationMs = typingMs,
                composerOpenDurationMs = composerMs
            )
            _uiState.update { it.copy(isSubmitting = false) }
            if (result.success && result.data != null) {
                deleteDraftAfterPublish()
                val outcome = result.data
                if (outcome.pending) {
                    done(true, null, "帖子已提交,等待审核")
                } else {
                    done(true, outcome.topicId, "帖子已发布")
                }
            } else {
                val reason = result.error?.message ?: "发布失败,请稍后重试。"
                done(false, null, reason)
            }
        }
    }

    fun close() {
        tagDebounceJob?.cancel()
        tagLoadingJob?.cancel()
        renderEngine?.release()
        renderEngine = null
    }

    // ========== 自动存草稿 ==========

    private fun noteTyping(value: String) {
        if (firstTypeTimeMs == 0L && value.isNotEmpty()) {
            firstTypeTimeMs = System.currentTimeMillis()
        }
    }

    /** 标题、正文、分类、标签任一变化:标脏,停手 2 秒后再存。 */
    private fun scheduleDraftSave() {
        if (draftPublished) return
        draftDirty = true
        draftSaveJob?.cancel()
        draftSaveJob = viewModelScope.launch {
            delay(DRAFT_SAVE_DEBOUNCE_MS)
            saveDraftNow()
        }
    }

    /** 关闭页面时补存一次。已发出的不补,避免刚删掉的草稿又写回来。 */
    fun flushDraft() {
        if (draftPublished || !draftDirty) return
        draftSaveJob?.cancel()
        draftSaveJob = null
        viewModelScope.launch { saveDraftNow() }
    }

    private suspend fun saveDraftNow() {
        if (draftPublished || draftSaving) return
        val state = _uiState.value
        if (!draftHasContent(state.title, state.body)) return
        draftSaving = true
        draftDirty = false
        val now = System.currentTimeMillis()
        draftKey = resolveDraftKey(draftKey, now)
        val result = draftService.saveTopicDraft(
            SaveTopicDraftInput(
                draftKey = draftKey,
                sequence = draftSequence,
                title = state.title.trim(),
                categoryId = state.categoryId,
                tags = state.tags.map { DraftTagRef(it.id, it.name) },
                reply = state.body,
                composerTimeMs = if (appearTimeMs > 0) now - appearTimeMs else 0L,
                typingTimeMs = if (firstTypeTimeMs > 0) now - firstTypeTimeMs else 0L
            )
        )
        draftSaving = false
        if (result.success && result.data != null) {
            draftSequence = result.data
            draftPersisted = true
            Timber.i("[ArkDO][Composer] draft saved key=%s seq=%d", draftKey, draftSequence)
        } else {
            draftDirty = true
            Timber.i("[ArkDO][Composer] draft save failed status=%d", result.statusCode)
        }
    }

    private suspend fun deleteDraftAfterPublish() {
        draftPublished = true
        draftSaveJob?.cancel()
        draftSaveJob = null
        if (draftKey.isEmpty()) return
        val result = draftService.deleteDraft(draftKey, draftSequence)
        if (result.success && draftPersisted) {
            draftPersisted = false
            // 草稿确已删除 → 角标乐观 -1（对齐鸿蒙 ComposerPage.deleteDraftAfterPublish 的
            // DraftEventBus.notifyDeleted）。不重查会话：服务端 draftCount 可能还是删除前的缓存。
            DraftCountStore.decrement(CurrentUserHolder.currentUser.value?.draftCount)
            Timber.i("[ArkDO][Composer] draft deleted after publish key=%s", draftKey)
        }
    }
}

/**
 * 首页发帖浮层 UI 状态。
 */
data class NewTopicUiState(
    val title: String = "",
    val body: String = "",
    /** null=未选分类(发送置灰)。 */
    val categoryId: Long? = null,
    val tags: List<TagSuggestion> = emptyList(),
    val tagQuery: String = "",
    val tagResults: List<TagSuggestion> = emptyList(),
    val tagLoading: Boolean = false,
    val isUploading: Boolean = false,
    val uploadProgressText: String? = null,
    /** 本批上传的失败文案(逐条记,结束后一次性 toast)。 */
    val uploadFailures: List<String> = emptyList(),
    val isSubmitting: Boolean = false
)

/** 从草稿列表续写新主题时带进来的内容。空 key 表示新开一篇。 */
data class NewTopicResume(
    val draftKey: String,
    val sequence: Long,
    val title: String,
    val body: String,
    val categoryId: Long?,
    val tags: List<TagSuggestion>
) : Serializable

