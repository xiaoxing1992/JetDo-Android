package com.arkdo.android.ui.composer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.arkdo.android.services.network.NetworkResult
import com.arkdo.android.services.session.LoginGate
import com.arkdo.android.services.topic.TagSuggestion
import com.arkdo.android.services.topic.TopicService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import timber.log.Timber
import javax.inject.Inject

/** 发帖最多可选标签数(对齐鸿蒙 ComposerPage.MAX_TAGS,对应站点 max_tags_per_topic)。 */
const val COMPOSER_MAX_TAGS = 8
/** 标签联想防抖,对齐鸿蒙 ComposerPage 的 250ms。 */
const val TAG_QUERY_DEBOUNCE_MS = 250L

/**
 * 发新主题 ViewModel —— 首页发帖浮层的数据流(对齐鸿蒙 HomePage.openComposer →
 * ComposerPage(composeTopic) → CreateTopicFlow.submitNewTopic)。
 *
 * 本轮范围:标题/正文/分类/标签 + 提交(POST /posts)+ 图片上传 + 结果提示与回调。
 * 自动存草稿(DraftService)与视频/附件属后续切片。
 */
@HiltViewModel
class NewTopicViewModel @Inject constructor(
    private val topicService: TopicService,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(NewTopicUiState())
    val uiState: StateFlow<NewTopicUiState> = _uiState.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    private var tagDebounceJob: Job? = null
    private var tagQuerySeq = 0
    private var tagLoadingJob: Job? = null

    // 离屏 markdown 引擎:跟随首帧加载,页面销毁释放。仅当渲染真正需要时才懒建。
    private var renderEngine: MarkdownRenderEngine? = null

    /** 懒建渲染引擎(首次切预览时)。线程安全要求不高:UI 串行调用。 */
    fun ensureRenderEngine(context: Context): MarkdownRenderEngine {
        renderEngine?.let { return it }
        val engine = MarkdownRenderEngine(context)
        renderEngine = engine
        return engine
    }

    // 遥测:展开时刻;composerMs 结算用。
    private var appearTimeMs = 0L

    fun onOpened(initialCategoryId: Long?) {
        appearTimeMs = System.currentTimeMillis()
        if (initialCategoryId != null && _uiState.value.categoryId == null) {
            _uiState.update { it.copy(categoryId = initialCategoryId) }
        }
        if (_uiState.value.categoryId != null) {
            loadTags("")
        }
    }

    // ========== 字段编辑 ==========

    fun setTitle(title: String) {
        _uiState.update { it.copy(title = title) }
    }

    fun setBody(body: String) {
        _uiState.update { it.copy(body = body) }
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

    private data class UploadOutcome(val snippet: String?, val error: String?)

    /** 解析 /uploads.json 响应:成功取 short_url 组 markdown;失败给用户可读文案。 */
    private fun parseUploadOutcome(response: NetworkResult<String>): UploadOutcome {
        if (!response.success || response.data == null) {
            return UploadOutcome(null, response.error?.message ?: "图片上传失败,请稍后重试。")
        }
        return runCatching {
            val root = json.parseToJsonElement(response.data!!).jsonObject
            val shortUrl = root["short_url"]?.jsonPrimitive?.content
            if (shortUrl.isNullOrEmpty()) {
                // 2xx 但缺 short_url = Discourse 校验失败,服务端 errors 里有人话。
                val errors = root["errors"]?.jsonArray?.joinToString(" ") { it.jsonPrimitive.content }
                UploadOutcome(null, errors?.takeIf { it.isNotEmpty() } ?: "图片上传失败,请检查格式或大小。")
            } else {
                val width = root["width"]?.jsonPrimitive?.intOrNull ?: 0
                val height = root["height"]?.jsonPrimitive?.intOrNull ?: 0
                val name = root["original_filename"]?.jsonPrimitive?.content.orEmpty()
                UploadOutcome(UploadMarkdown.buildImage(name, width, height, shortUrl), null)
            }
        }.getOrElse {
            Timber.e(it, "[ArkDO][Composer] 上传响应解析失败")
            UploadOutcome(null, "图片上传失败,请稍后重试。")
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
    fun ensureLoggedIn(): Boolean = LoginGate.ensureLoggedIn(appContext)

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
        viewModelScope.launch {
            val result = topicService.createTopic(
                title = state.title.trim(),
                categoryId = state.categoryId ?: 0L,
                raw = state.body.trim(),
                tags = state.tags.map { it.name },
                composerOpenDurationMs = composerMs
            )
            _uiState.update { it.copy(isSubmitting = false) }
            if (result.success && result.data != null) {
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
