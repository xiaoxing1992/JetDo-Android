package com.arkdo.android.ui.composer

import androidx.compose.ui.res.stringResource
import com.arkdo.android.core.data.AppContextHolder
import com.arkdo.android.R
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.arkdo.android.data.models.TopicCategory
import com.arkdo.android.services.site.CategoryIndex
import com.arkdo.android.services.topic.TagSuggestion
import com.arkdo.android.core.designsystem.FaIcon
import com.arkdo.android.ui.components.TagIconRegistry
import com.arkdo.android.ui.components.TopicBadgeUtils
import com.arkdo.android.ui.composer.MarkdownActions.EditState
import com.arkdo.android.ui.composer.MarkdownActions.MdAction
import com.arkdo.android.ui.theme.ArkdoFixedColors
import com.arkdo.android.ui.theme.arkdoColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 首页发帖浮层(对齐鸿蒙 ComposerPage 的 composeTopic 模式)。
 *
 * 全屏自绘层:顶栏(关闭/编辑·预览分段/发送)→ 标题 + 分类/标签 chips → 正文编辑区 + Markdown
 * 工具栏 / 预览。分类与标签选择器走底部弹窗,图片走系统相册→压缩→上传→光标处插 markdown。
 * 提交成功经 onPublished 跳新帖详情;进审核队列只提示;失败保留内容供重试。
 *
 * 预览:离屏 WebView 加载 assets/markdown/render.html(markdown-it)把 raw 编译成 HTML,
 * 交给 PostRichContent 与详情页同管线渲染;引擎未就绪/超时时退回原文。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NewTopicScreen(
    categories: List<TopicCategory> = emptyList(),
    seedCategoryId: Long? = null,
    resume: NewTopicResume? = null,
    onBack: () -> Unit,
    onPublished: (topicId: Long) -> Unit,
    viewModel: NewTopicViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val colors = arkdoColors
    val scope = rememberCoroutineScope()

    // 分类可能由调用方带入(首页筛选上下文);为空时按需拉全站版块(命中共用缓存)。
    var effectiveCategories by remember { mutableStateOf(categories) }
    LaunchedEffect(categories) {
        if (categories.isNotEmpty()) {
            effectiveCategories = categories
            return@LaunchedEffect
        }
        val loaded = com.arkdo.android.services.site.CategoryIndex.loadIfNeeded(
            bridge = com.arkdo.android.services.network.ArkWebNetworkBridge,
            ready = true,
            alreadyLoaded = false,
            logTag = "[ArkDO][Composer]"
        )
        if (loaded != null) effectiveCategories = loaded
    }

    var editMode by remember { mutableStateOf(true) }
    var title by remember { mutableStateOf(resume?.title.orEmpty()) }
    var bodyField by remember { mutableStateOf(TextFieldValue(resume?.body.orEmpty())) }
    var categoryPickerVisible by remember { mutableStateOf(false) }
    var tagPickerVisible by remember { mutableStateOf(false) }
    var expandedParentId by remember { mutableLongStateOf(-1L) }

    // 预览态:HTML + 加载中 + 本次编译 job(切走即取消)。
    var previewHtml by remember { mutableStateOf<String?>(null) }
    var isPreviewLoading by remember { mutableStateOf(false) }
    var previewJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    /** 光标/选区镜像:工具栏按它包裹/插入,操作后回写。 */
    fun applyMdAction(action: MdAction) {
        val state = EditState(bodyField.text, bodyField.selection.start, bodyField.selection.end)
        val next = MarkdownActions.applyAction(state, action)
        bodyField = TextFieldValue(next.text, TextRange(next.selectionStart, next.selectionEnd))
        viewModel.setBody(next.text)
    }

    // 图片：多选。逐张按长边 ≤1920 压缩后**串行**上传，插入顺序与选择顺序一致
    // （并发返回会乱序，对齐鸿蒙 ComposerPage.runUploadBatch）。
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        viewModel.uploadPickedFiles(ComposerUploadKind.Image, uris) { snippet ->
            applyMdAction(MdAction.Insert(uploadSnippet(snippet)))
        }
    }

    fun openImagePicker() {
        if (uiState.isUploading) return
        imagePicker.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    // 拍照：系统相机成图 → 与选图走同一条上传通道（压缩/串行上传/插 markdown 全复用）。
    // 无相机设备 rememberCameraCapture 返回 null，工具栏自动藏入口。
    val capturePhoto = rememberCameraCapture { uri ->
        viewModel.uploadPickedFiles(ComposerUploadKind.Image, listOf(uri)) { snippet ->
            applyMdAction(MdAction.Insert(uploadSnippet(snippet)))
        }
    }

    // 附件：多选。MIME 过滤由站点白名单的非图片扩展名推出（与鸿蒙 picker 的 fileSuffixFilters 同义）；
    // 白名单没拉到时空过滤 = 不限类型，交给服务端裁决。
    val attachmentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        viewModel.uploadPickedFiles(ComposerUploadKind.Attachment, uris) { snippet ->
            applyMdAction(MdAction.Insert(uploadSnippet(snippet)))
        }
    }

    fun openAttachmentPicker() {
        if (uiState.isUploading) return
        val mimes = uiState.attachmentMimeTypes.toTypedArray()
        attachmentPicker.launch(mimes.ifEmpty { arrayOf("*/*") })
    }

    // 音视频：仅 linux.do。上传时改名 .xz 绕过白名单，正文里插裸 <video> 标签
    // （详见 UploadService.DISGUISE_EXTENSION）。块级标签，前后留空行见 uploadSnippet。
    val mediaPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.uploadPickedFiles(ComposerUploadKind.Media, listOf(uri)) { snippet ->
            applyMdAction(MdAction.Insert(uploadSnippet(snippet)))
        }
    }

    fun openMediaPicker() {
        if (uiState.isUploading) return
        mediaPicker.launch(arrayOf("video/*", "audio/*"))
    }

    // 失败文案一次性提示(本批结束后)。
    LaunchedEffect(uiState.uploadFailures) {
        uiState.uploadFailures.firstOrNull()?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeUploadFailures()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.onOpened(seedCategoryId, resume)
    }

    BackHandler {
        viewModel.flushDraft()
        onBack()
    }

    // 离屏 markdown 编译 WebView(1×1、近透明,不可见;必须挂在视图树里才会加载完成)。
    val markdownEngine = remember(context) { viewModel.ensureRenderEngine(context.applicationContext) }
    DisposableEffect(markdownEngine) {
        onDispose {
            previewJob?.cancel()
            // 关掉时补存还没到防抖点的改动，再释放引擎。
            viewModel.flushDraft()
            viewModel.close()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { markdownEngine.webView },
            modifier = Modifier
                .size(1.dp)
                .alpha(0.01f),
            update = {}
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.pageBg)
        ) {
            // 顶栏:关闭 + 编辑/预览分段 + 发送。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(56.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CloseButton {
                    viewModel.flushDraft()
                    onBack()
                }
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.Center
                ) {
                    SegmentTab(stringResource(R.string.common_edit), selected = editMode) {
                        editMode = true
                        previewJob?.cancel()
                    }
                    SegmentTab(stringResource(R.string.composer_preview), selected = !editMode) {
                        editMode = false
                        previewHtml = null
                        isPreviewLoading = true
                        previewJob = scope.launch {
                            val html = viewModel.renderPreview(bodyField.text)
                            // 编辑期间切回/又切走会让 job 取消,取消后不再落地结果。
                            if (isActive) {
                                isPreviewLoading = false
                                previewHtml = html
                            }
                        }
                    }
                }
                SendButton(
                    enabled = !uiState.isSubmitting && !uiState.isUploading,
                    onClick = {
                        val blocked = viewModel.explainBlocked()
                        if (blocked != null) {
                            Toast.makeText(context, blocked, Toast.LENGTH_SHORT).show()
                            return@SendButton
                        }
                        viewModel.submit { ok, topicId, message ->
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            if (ok) {
                                if (topicId != null) onPublished(topicId) else onBack()
                            }
                        }
                    }
                )
            }

            HorizontalDivider(thickness = 0.5.dp, color = colors.border)

            if (editMode) {
                // 标题。
                BasicTextField(
                    value = title,
                    onValueChange = {
                        title = it
                        viewModel.setTitle(it)
                    },
                    textStyle = TextStyle(
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.textPrimary
                    ),
                    cursorBrush = SolidColor(colors.textPrimary),
                    singleLine = true,
                    decorationBox = { inner ->
                        Box {
                            if (title.isEmpty()) {
                                Text(stringResource(R.string.composer_title), fontSize = 18.sp, fontWeight = FontWeight.Medium, color = colors.textTertiary)
                            }
                            inner()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                )

                // 分类 chip + 已选标签 chips + 加标签按钮。一行放不下就换行，整颗 chip 点下去移除标签。
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CategoryChip(
                        categories = effectiveCategories,
                        selectedId = uiState.categoryId,
                        onClick = {
                            // 先按选中分类展开相关父级,方便改选同级子版块(对齐鸿蒙)。
                            val selected = effectiveCategories.find { it.id == uiState.categoryId }
                            expandedParentId = when {
                                selected?.parentCategoryId != null -> selected.parentCategoryId
                                selected != null && CategoryIndex.hasChildren(effectiveCategories, selected.id) -> selected.id
                                else -> -1L
                            }
                            categoryPickerVisible = true
                        }
                    )
                    uiState.tags.forEach { tag ->
                        SelectedTagChip(tag = tag, onRemove = { viewModel.toggleTag(tag) })
                    }
                    AddTagChip(
                        categorySelected = uiState.categoryId != null,
                        tagCount = uiState.tags.size,
                        onClick = {
                            if (uiState.categoryId == null) {
                                Toast.makeText(context, context.getString(R.string.composer_pick_category), Toast.LENGTH_SHORT).show()
                            } else if (uiState.tags.size >= COMPOSER_MAX_TAGS) {
                                Toast.makeText(context, context.getString(R.string.composer_tag_limit, COMPOSER_MAX_TAGS), Toast.LENGTH_SHORT).show()
                            } else {
                                viewModel.openTagPicker()
                                tagPickerVisible = true
                            }
                        }
                    )
                }
                HorizontalDivider(thickness = 0.5.dp, color = colors.border)

                // 正文编辑区。
                Box(modifier = Modifier.weight(1f)) {
                    BasicTextField(
                        value = bodyField,
                        onValueChange = { value ->
                            bodyField = value
                            viewModel.setBody(value.text)
                        },
                        textStyle = TextStyle(
                            fontSize = 16.sp,
                            lineHeight = 16.sp,
                            color = colors.textBody
                        ),
                        cursorBrush = SolidColor(colors.textBody),
                        decorationBox = { inner ->
                            Box {
                                if (bodyField.text.isEmpty()) {
                                    Text(
                                        stringResource(R.string.composer_body_hint),
                                        fontSize = 16.sp,
                                        lineHeight = 16.sp,
                                        color = colors.textTertiary
                                    )
                                }
                                inner()
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)
                    )
                }
                MarkdownToolbar(
                    uploading = uiState.isUploading,
                    showAttachment = uiState.showAttachment,
                    showMedia = uiState.showMedia,
                    onUploadImage = { openImagePicker() },
                    onUploadAttachment = { openAttachmentPicker() },
                    onUploadMedia = { openMediaPicker() },
                    onAction = { applyMdAction(it) },
                    onCapturePhoto = capturePhoto?.let { capture ->
                        { if (!uiState.isUploading) capture() }
                    }
                )
            } else {
                PreviewPane(
                    html = previewHtml,
                    rawText = bodyField.text,
                    loading = isPreviewLoading,
                    // 预览里的音视频要过 CF 下到沙箱才能播，接线方式与详情页一致。
                    onResolveMedia = { url -> com.arkdo.android.services.network.ArkWebNetworkBridge.downloadMedia(url) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 上传中遮罩:拦截交互,避免上传期间移动光标打乱插入位置。
        if (uiState.isUploading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ArkdoFixedColors.Scrim),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surfaceCard)
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = colors.ldAccent,
                        strokeWidth = 2.dp
                    )
                    Text(
                        uiState.uploadProgressText ?: stringResource(R.string.composer_uploading),
                        fontSize = 15.sp,
                        color = colors.textPrimary
                    )
                    // 压缩阶段才给取消：一段 1080p 压下来可能几十秒，不给口子只能干等。
                    // 上传阶段不给 —— 请求已经发出去了，取消也收不回来。
                    if (uiState.uploadCancelable) {
                        Text(
                            stringResource(R.string.common_cancel),
                            fontSize = 14.sp,
                            color = colors.ldAccent,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { viewModel.cancelUpload() }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }

    // 分类选择器。
    if (categoryPickerVisible) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { categoryPickerVisible = false },
            sheetState = sheetState,
            containerColor = colors.pageBg
        ) {
            Column(modifier = Modifier.fillMaxHeight(0.66f)) {
                Text(
                    stringResource(R.string.composer_select_category),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(16.dp)
                )
                LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    CategoryIndex.topLevelCategories(effectiveCategories).forEach { top ->
                        item(key = "p${top.id}") {
                            CategoryPickerRow(
                                categories = effectiveCategories,
                                category = top,
                                isChild = false,
                                selectedId = uiState.categoryId,
                                expanded = expandedParentId == top.id,
                                hasChildren = CategoryIndex.hasChildren(effectiveCategories, top.id),
                                onSelect = {
                                    viewModel.selectCategory(top.id)
                                    categoryPickerVisible = false
                                },
                                onToggleExpand = {
                                    expandedParentId = if (expandedParentId == top.id) -1L else top.id
                                }
                            )
                        }
                        if (expandedParentId == top.id) {
                            items(CategoryIndex.childrenOf(effectiveCategories, top.id), key = { "c${it.id}" }) { child ->
                                CategoryPickerRow(
                                    categories = effectiveCategories,
                                    category = child,
                                    isChild = true,
                                    selectedId = uiState.categoryId,
                                    expanded = false,
                                    hasChildren = false,
                                    onSelect = {
                                        viewModel.selectCategory(child.id)
                                        categoryPickerVisible = false
                                    },
                                    onToggleExpand = {}
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 标签选择器。
    if (tagPickerVisible) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { tagPickerVisible = false },
            sheetState = sheetState,
            containerColor = colors.pageBg
        ) {
            Column(modifier = Modifier.fillMaxHeight(0.8f)) {
                Text(
                    stringResource(R.string.composer_select_tags),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(16.dp)
                )
                TagPickerContent(
                    query = uiState.tagQuery,
                    loading = uiState.tagLoading,
                    results = uiState.tagResults,
                    selectedIds = uiState.tags.map { it.id }.toSet(),
                    onQueryChange = { q ->
                        viewModel.setTagQuery(q)
                        viewModel.onTagQueryChanged(q)
                    },
                    onToggle = { tag ->
                        if (!viewModel.toggleTag(tag)) {
                            Toast.makeText(context, context.getString(R.string.composer_tag_limit, COMPOSER_MAX_TAGS), Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        }
    }
}

// ========== 顶栏子件 ==========

@Composable
private fun CloseButton(onClick: () -> Unit) {
    val colors = arkdoColors
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        FaIcon("xmark", colors.textSecondary, 18.dp)
    }
}

@Composable
private fun SegmentTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = arkdoColors
    Text(
        text = label,
        fontSize = 15.sp,
        color = if (selected) colors.textPrimary else colors.textTertiary,
        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    )
}

@Composable
private fun SendButton(enabled: Boolean, onClick: () -> Unit) {
    val colors = arkdoColors
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        FaIcon("paper-plane", if (enabled) colors.ldAccent else colors.textFaint, 19.dp)
    }
}

// ========== 分类/标签 chips ==========

@Composable
private fun CategoryChip(
    categories: List<TopicCategory>,
    selectedId: Long?,
    onClick: () -> Unit
) {
    val colors = arkdoColors
    val context = LocalContext.current
    val selected = categories.find { it.id == selectedId }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surfaceMuted)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selected != null) {
            val parent = categories.find { it.id == selected.parentCategoryId }
            val iconId = TopicBadgeUtils.categoryIcon(context, selected, parent)
            if (iconId != null) {
                FaIcon(iconId, TopicBadgeUtils.categoryColor(selected), 13.dp, Modifier.padding(end = 6.dp))
            }
        }
        Text(
            selected?.name ?: stringResource(R.string.composer_select_category),
            fontSize = 13.sp,
            color = if (selectedId != null) colors.textBody else colors.textTertiary
        )
        FaIcon("chevron-down", colors.textTertiary, 11.dp, Modifier.padding(start = 5.dp))
    }
}

@Composable
private fun SelectedTagChip(tag: TagSuggestion, onRemove: () -> Unit) {
    val colors = arkdoColors
    val context = LocalContext.current
    val icon = remember(tag.name) {
        TagIconRegistry.ensureTagIconsLoaded(context)
        TagIconRegistry.getTagIconStyle(tag.name)
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(colors.infoSoftBg)
            .clickable(onClick = onRemove)
            .padding(start = 10.dp, end = 9.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            FaIcon(
                icon.iconId,
                TagIconRegistry.parseColor(icon.color) ?: colors.ldAccent,
                12.dp,
                Modifier.padding(end = 5.dp)
            )
        }
        Text(tag.name, fontSize = 13.sp, color = colors.ldAccent)
        FaIcon("xmark", colors.ldAccent, 11.dp, Modifier.padding(start = 5.dp))
    }
}

@Composable
private fun AddTagChip(categorySelected: Boolean, tagCount: Int, onClick: () -> Unit) {
    val colors = arkdoColors
    val fg = if (categorySelected) colors.textBody else colors.textTertiary
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surfaceMuted)
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FaIcon("plus", fg, 12.dp)
        Text(
            if (tagCount > 0) stringResource(R.string.search_tag) else stringResource(R.string.composer_add_tag),
            fontSize = 13.sp,
            color = fg,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

// ========== 分类选择器行 ==========

@Composable
private fun CategoryPickerRow(
    categories: List<TopicCategory>,
    category: TopicCategory,
    isChild: Boolean,
    selectedId: Long?,
    expanded: Boolean,
    hasChildren: Boolean,
    onSelect: () -> Unit,
    onToggleExpand: () -> Unit
) {
    val colors = arkdoColors
    val context = LocalContext.current
    val parent = if (category.parentCategoryId != null) {
        categories.find { it.id == category.parentCategoryId }
    } else {
        null
    }
    val iconId = TopicBadgeUtils.categoryIcon(context, category, parent)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(if (selectedId == category.id) colors.surfaceMuted else Color.Transparent)
            .clickable(onClick = onSelect)
            .padding(start = if (isChild) 30.dp else 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.width(22.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            if (iconId != null) {
                FaIcon(iconId, TopicBadgeUtils.categoryColor(category), 15.dp)
            } else {
                Box(
                    modifier = Modifier
                        .size(11.dp)
                        .clip(CircleShape)
                        .background(TopicBadgeUtils.categoryColor(category))
                )
            }
        }
        Text(
            category.name,
            fontSize = if (isChild) 14.sp else 15.sp,
            fontWeight = if (selectedId == category.id) FontWeight.Medium else FontWeight.Normal,
            color = if (selectedId == category.id) colors.ldAccent else colors.textPrimary,
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (selectedId == category.id) {
            FaIcon("check", colors.ldAccent, 16.dp, Modifier.padding(start = 6.dp))
        }
        // 有子版块的父行:箭头独立热区,点箭头只展开/收起,点名字才选中(对齐鸿蒙 PickerRow)。
        if (!isChild && hasChildren) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clickable(onClick = onToggleExpand),
                contentAlignment = Alignment.Center
            ) {
                FaIcon(
                    "chevron-right",
                    colors.textFaint,
                    16.dp,
                    Modifier.rotate(if (expanded) 90f else 0f)
                )
            }
        }
    }
}

// ========== 标签选择器内容 ==========

@Composable
private fun TagPickerContent(
    query: String,
    loading: Boolean,
    results: List<TagSuggestion>,
    selectedIds: Set<Long>,
    onQueryChange: (String) -> Unit,
    onToggle: (TagSuggestion) -> Unit
) {
    val colors = arkdoColors
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(colors.surfaceMuted)
                .padding(start = 12.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FaIcon("magnifying-glass", colors.textTertiary, 14.dp, Modifier.padding(end = 8.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 15.sp, color = colors.textPrimary),
                cursorBrush = SolidColor(colors.textPrimary),
                decorationBox = { inner ->
                    Box(modifier = Modifier.heightIn(min = 44.dp), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Text(stringResource(R.string.search_tags), fontSize = 15.sp, color = colors.textTertiary)
                        inner()
                    }
                },
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn(modifier = Modifier.weight(1f)) {
            if (loading && results.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(80.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = colors.ldAccent, strokeWidth = 2.dp)
                    }
                }
            } else if (results.isEmpty()) {
                item {
                    Text(
                        if (query.isNotEmpty()) stringResource(R.string.search_no_tags) else stringResource(R.string.search_no_tags_in_category),
                        fontSize = 14.sp,
                        color = colors.textTertiary,
                        modifier = Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 28.dp),
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                items(results, key = { "r${it.id}" }) { tag ->
                    val selected = tag.id in selectedIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggle(tag) }
                            .padding(horizontal = 4.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    tag.name,
                                    fontSize = 15.sp,
                                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                                    color = if (selected) colors.ldAccent else colors.textPrimary
                                )
                                if (tag.count > 0) {
                                    Text(
                                        "×${tag.count}",
                                        fontSize = 12.sp,
                                        color = colors.textTertiary,
                                        modifier = Modifier.padding(start = 8.dp)
                                    )
                                }
                            }
                            if (!tag.description.isNullOrEmpty()) {
                                Text(
                                    tag.description,
                                    fontSize = 12.sp,
                                    color = colors.textTertiary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                        if (selected) {
                            FaIcon("check", colors.ldAccent, 16.dp, Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

// 预览（PreviewPane）与图片预处理已抽到 ComposerCommon.kt：发帖与回帖全屏编辑器共用同一份，
// 免得"预览兜底文案""图片缩到 1920"这类细节在两边慢慢漂移。

// ========== Markdown 工具栏 ==========

/**
 * Markdown 工具栏(对齐鸿蒙 MarkdownToolbar.ets)。
 *
 * 三类上传按钮（图片 / 音视频 / 附件）由页面转成系统选择器，拍照按钮走 TakePicture
 * （见 [rememberCameraCapture]），其余动作纯文本变换后回写编辑区。
 * 按钮顺序与鸿蒙 TOOLBAR_BUTTONS 一致。
 *
 * @param showAttachment 站点一个非图片扩展名都不允许时藏掉 —— 留着只会让用户点开选择器
 *   发现没有任何文件类型可选（鸿蒙同判据：`UploadService.nonImageExtensions` 非空）。
 * @param showMedia 音视频入口，由站点能力决定（鸿蒙是 SiteCapabilities.mediaUpload）。
 */
@Composable
fun MarkdownToolbar(
    uploading: Boolean,
    showAttachment: Boolean,
    showMedia: Boolean,
    onUploadImage: () -> Unit,
    onUploadAttachment: () -> Unit,
    onUploadMedia: () -> Unit,
    onAction: (MdAction) -> Unit,
    // 「拍照」入口。null = 设备没有相机，藏掉（鸿蒙无此功能，属 Android 反超项）。
    onCapturePhoto: (() -> Unit)? = null
) {
    val colors = arkdoColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .background(colors.pageBg)
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToolIcon("image", enabled = !uploading, onClick = onUploadImage)
        onCapturePhoto?.let { capture ->
            ToolIcon("camera", enabled = !uploading, onClick = capture)
        }
        if (showMedia) {
            ToolIcon("video", enabled = !uploading, onClick = onUploadMedia)
        }
        if (showAttachment) {
            ToolIcon("file-arrow-up", enabled = !uploading, onClick = onUploadAttachment)
        }
        DividerSpace()
        ToolIcon("bold", enabled = true) { onAction(MdAction.Wrap("**", "**", AppContextHolder.getString(R.string.composer_bold_text))) }
        ToolIcon("italic", enabled = true) { onAction(MdAction.Wrap("_", "_", AppContextHolder.getString(R.string.composer_italic_text))) }
        ToolIcon("quote-left", enabled = true) { onAction(MdAction.LinePrefix("> ", ordered = false)) }
        ToolIcon("code", enabled = true) { onAction(MdAction.Wrap("`", "`", "code")) }
        ToolIcon("file-code", enabled = true) { onAction(MdAction.InsertCursor("```\n\n```", 4)) }
        ToolIcon("list-ul", enabled = true) { onAction(MdAction.LinePrefix("- ", ordered = false)) }
        ToolIcon("list-ol", enabled = true) { onAction(MdAction.LinePrefix("", ordered = true)) }
        ToolIcon("hashtag", enabled = true) { onAction(MdAction.LinePrefix("# ", ordered = false)) }
        DividerSpace()
        ToolIcon("link", enabled = true) { onAction(MdAction.Wrap("[", "](url)", AppContextHolder.getString(R.string.composer_link_text))) }
    }
}

@Composable
private fun ToolIcon(icon: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = arkdoColors
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        FaIcon(icon, if (enabled) colors.textSecondary else colors.textFaint, 18.dp)
    }
}

@Composable
private fun DividerSpace() {
    Spacer(modifier = Modifier.width(6.dp))
}

// 图片预处理（prepareImageUpload / PreparedImage）见 ComposerCommon.kt。
