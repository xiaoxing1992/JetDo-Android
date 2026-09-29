package com.arkdo.android.ui.composer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import android.net.Uri
import com.arkdo.android.common.constants.AppConstants
import com.arkdo.android.services.content.EmojiPreviewHtml
import com.arkdo.android.services.content.getEmojiUrl
import com.arkdo.android.services.draft.DraftCountStore
import com.arkdo.android.services.draft.DraftService
import com.arkdo.android.services.draft.SaveTopicDraftInput
import com.arkdo.android.services.draft.draftHasContent
import com.arkdo.android.services.draft.resolveDraftKey
import com.arkdo.android.services.network.ArkWebNetworkBridge
import com.arkdo.android.services.network.JsonRequestOptions
import com.arkdo.android.services.network.NetworkResult
import com.arkdo.android.services.session.CurrentUserHolder
import com.arkdo.android.services.session.LoginGate
import com.arkdo.android.services.site.SiteSettingsService
import com.arkdo.android.services.site.SiteUploadSettings
import com.arkdo.android.services.site.defaultSiteUploadSettings
import com.arkdo.android.services.topic.TagSuggestion
import com.arkdo.android.services.topic.TopicService
import com.arkdo.android.services.upload.DISGUISE_MIME
import com.arkdo.android.services.upload.UploadService
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
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import javax.inject.Inject
import kotlin.coroutines.resume

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
 * 本轮范围:标题/正文/分类/标签 + 提交(POST /posts)+ 三类上传(图片/附件/音视频)
 * + 新主题自动存草稿。回帖草稿不在这条链路上。
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

    /** 最近一次拉到的站点上传设置（算体积上限与选择器后缀过滤）。加载完成前是出厂默认值。 */
    private var uploadSettings: SiteUploadSettings = defaultSiteUploadSettings()

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
        // 上传入口的显隐要等站点设置回来才定，先按保守态（只有图片钮）渲染。
        loadUploadCapabilities()
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
     * 上传一张图片（UI 直接调用的单选入口）：base64 → POST /uploads.json → 返回可插入正文的片段。
     * done(snippet, error):snippet 非空=成功;error 空串=用户取消/无需提示;否则失败文案。
     *
     * 重入闸与遮罩状态都留在这一层 —— 它面向用户点击，必须在开跑前挡住连点。执行层
     * [uploadFile] 会被批量流程内部复用，那时 isUploading 已被批量入口置位，守卫留在那里
     * 只会把自己挡回去（原因见 uploadFile 注释）。
     */
    fun uploadImage(
        base64: String,
        fileName: String,
        mimeType: String,
        done: (snippet: String?, error: String?) -> Unit
    ) {
        if (_uiState.value.isUploading) return
        _uiState.update {
            it.copy(
                isUploading = true,
                uploadProgressText = ComposerUploadKind.Image.progressText
            )
        }
        uploadFile(ComposerUploadKind.Image, base64, fileName, mimeType) { snippet, error ->
            _uiState.update { it.copy(isUploading = false, uploadProgressText = null) }
            done(snippet, error)
        }
    }

    /**
     * 执行一次上传（图片 / 附件 / 音视频共用）：发请求 → 按类型解析 → 回调 done。
     * 差别只在两处：
     *  · 音视频要把文件名换成 `.xz`、MIME 换成伪装值（字节不动，见 DISGUISE_EXTENSION）；
     *  · 结果拼装不同（图片 `![]()`、附件 `[名|attachment]()`、音视频裸 `<video>`）。
     *
     * **不设重入闸、不动 isUploading** —— 这两件事归调用入口（[uploadImage] / [uploadPickedFiles]）管。
     * 执行层再守一道会与批量流程打架：批量入口开跑前已把 isUploading 置 true，执行层一进门就被
     * 自己挡回、从不调用 done，[uploadOne] 的 suspendCancellableCoroutine 便永远挂住，遮罩不落。
     *
     * [localWidth] / [localHeight] 仅音视频用：服务端对 .xz 不回宽高，得靠本地量的
     * 播放方向尺寸去定 `<video>` 的框，否则竖屏视频会被摆歪。
     */
    private fun uploadFile(
        kind: ComposerUploadKind,
        base64: String,
        fileName: String,
        mimeType: String,
        localWidth: Int = 0,
        localHeight: Int = 0,
        done: (snippet: String?, error: String?) -> Unit
    ) {
        viewModelScope.launch {
            val disguised = kind == ComposerUploadKind.Media
            val response = ArkWebNetworkBridge.uploadImage(
                base64 = base64,
                fileName = if (disguised) UploadService.disguisedName(fileName) else fileName,
                mimeType = if (disguised) DISGUISE_MIME else mimeType,
                options = JsonRequestOptions(
                    notifyChallenge = true,
                    referrer = "${AppConstants.BASE_URL}/"
                )
            )
            val outcome = when (kind) {
                ComposerUploadKind.Image -> parseUploadOutcome(response)
                ComposerUploadKind.Attachment -> parseAttachmentOutcome(response)
                // mediaMimeType 用**原始**类型（video/mp4），不是 .xz 的那个 —— 播放器认 type 属性。
                ComposerUploadKind.Media -> parseMediaOutcome(response, localWidth, localHeight, mimeType)
            }
            // 只累积失败原因：遮罩/重入闸的收尾归入口层，批量中途在这里复位会让遮罩闪烁、闸门敞开。
            if (outcome.error != null && outcome.error.isNotEmpty()) {
                _uiState.update { it.copy(uploadFailures = it.uploadFailures + outcome.error) }
            }
            done(outcome.snippet, outcome.error)
        }
    }

    /**
     * 拉本站的上传设置，算出工具栏两个上传入口该不该露出。
     *
     * 附件入口的判据与鸿蒙一致（`UploadService.nonImageExtensions` 非空）再叠一道身份门控：
     * 站点白名单允许非图片扩展名 + 当前用户还能发附件。音视频入口是 linux.do 私有约定，
     * 走 [UploadService.mediaUploadEnabled]。
     */
    fun loadUploadCapabilities() {
        viewModelScope.launch {
            val settings = SiteSettingsService.load(ArkWebNetworkBridge)
            uploadSettings = settings
            val extensionMimes = UploadService.nonImageExtensions(settings)
                .map { UploadService.mimeFor(it) }
                .distinct()
            _uiState.update {
                it.copy(
                    showAttachment = extensionMimes.isNotEmpty() &&
                        LoginGate.canUploadAttachment(settings.newuserMaxAttachments),
                    showMedia = UploadService.mediaUploadEnabled(),
                    attachmentMimeTypes = extensionMimes
                )
            }
        }
    }

    /**
     * 选中一批文件后的完整处理：逐个「读字节 → 按类型上传 → 回传可插入的 markdown 片段」。
     * 选取之间的串行/失败汇总骨架见 [runUploadBatch]（与回帖页共用同一份）。
     */
    fun uploadPickedFiles(
        kind: ComposerUploadKind,
        uris: List<Uri>,
        onSnippet: (String) -> Unit
    ) {
        if (_uiState.value.isUploading || uris.isEmpty()) return
        _uiState.update { it.copy(isUploading = true, uploadProgressText = kind.progressText) }
        viewModelScope.launch {
            val settings = uploadSettings
            val limitKb = if (kind == ComposerUploadKind.Image) {
                settings.maxImageSizeKb
            } else {
                settings.maxAttachmentSizeKb
            }
            val firstError = try {
                runUploadBatch(
                    context = appContext,
                    kind = kind,
                    uris = uris,
                    limitKb = limitKb,
                    onProgress = { text -> _uiState.update { it.copy(uploadProgressText = text) } },
                    onSnippet = onSnippet,
                    uploadOne = { file, width, height -> uploadOne(kind, file, width, height) }
                )
            } finally {
                // 收尾必须在 finally：isUploading 既驱动遮罩，也是三个上传入口的重入闸，
                // 万一上面抛出来，不兜的话遮罩永远挂着、上传入口全部失效。
                _uiState.update { it.copy(isUploading = false, uploadProgressText = null) }
            }
            firstError?.let { message ->
                _uiState.update { it.copy(uploadFailures = it.uploadFailures + message) }
            }
        }
    }

    /** 等一次上传结束（把 done 回调适配成挂起函数），供串行批处理使用。 */
    private suspend fun uploadOne(
        kind: ComposerUploadKind,
        file: PreparedRawFile,
        localWidth: Int,
        localHeight: Int
    ): UploadOutcome = suspendCancellableCoroutine { cont ->
        uploadFile(
            kind = kind,
            base64 = file.base64,
            fileName = file.fileName,
            mimeType = file.mimeType,
            localWidth = localWidth,
            localHeight = localHeight
        ) { snippet, error ->
            cont.resume(UploadOutcome(snippet, error))
        }
    }

    /** UI 层展示完失败 toast 后清除累积失败。 */
    fun consumeUploadFailures() {
        _uiState.update { it.copy(uploadFailures = emptyList()) }
    }

    // ========== 预览 ==========

    /**
     * 把正文编译成可渲染的 HTML(本地 markdown-it 引擎),并把已知表情短码转成图片。
     *
     * 短码后处理只作用于预览:详情页正文拿的是服务端 cooked HTML,里面表情已经是
     * `<img class="emoji">`;本地 markdown-it 不会做这一步,不补的话预览里是一串裸短码。
     *
     * @param raw markdown 原文(当前光标所在候选,见调用侧说明)
     * @return 编译后的 HTML;失败/引擎未就绪返回 null,由 UI 退回原文。
     */
    suspend fun renderPreview(raw: String): String? =
        renderEngine?.render(raw)?.let { EmojiPreviewHtml.render(it, ::getEmojiUrl) }

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
    /** 工具栏「附件」入口是否露出(站点有非图片白名单 + 当前用户还能发附件)。 */
    val showAttachment: Boolean = false,
    /** 工具栏「音视频」入口是否露出(linux.do 私有约定)。 */
    val showMedia: Boolean = false,
    /** 附件选择器的 MIME 过滤（由站点白名单的非图片扩展名推出）。空 = 不限。 */
    val attachmentMimeTypes: List<String> = emptyList(),
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

