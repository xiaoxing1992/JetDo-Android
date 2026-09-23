package com.arkdo.android.ui.composer

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
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
import com.arkdo.android.ui.components.PostRichContent
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
import java.io.ByteArrayOutputStream

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

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val prepared = withContext(Dispatchers.IO) { prepareImageUpload(context.contentResolver, uri) }
            if (prepared == null) {
                Toast.makeText(context, "图片读取失败", Toast.LENGTH_SHORT).show()
                return@launch
            }
            viewModel.uploadImage(prepared.base64, prepared.fileName, prepared.mimeType) { snippet, _ ->
                // 失败文案已计入 uploadFailures,由下方 LaunchedEffect 统一 toast。
                if (!snippet.isNullOrEmpty()) {
                    applyMdAction(MdAction.Insert(snippet))
                }
            }
        }
    }

    fun openImagePicker() {
        if (uiState.isUploading) return
        imagePicker.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
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
                    SegmentTab("编辑", selected = editMode) {
                        editMode = true
                        previewJob?.cancel()
                    }
                    SegmentTab("预览", selected = !editMode) {
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
                                Text("标题", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = colors.textTertiary)
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
                                Toast.makeText(context, "请先选择分类", Toast.LENGTH_SHORT).show()
                            } else if (uiState.tags.size >= COMPOSER_MAX_TAGS) {
                                Toast.makeText(context, "最多选 $COMPOSER_MAX_TAGS 个标签", Toast.LENGTH_SHORT).show()
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
                                        "正文(支持 Markdown)",
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
                    onUploadImage = { openImagePicker() },
                    onAction = { applyMdAction(it) }
                )
            } else {
                PreviewPane(
                    html = previewHtml,
                    rawText = bodyField.text,
                    loading = isPreviewLoading,
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
                        uiState.uploadProgressText ?: "图片上传中...",
                        fontSize = 15.sp,
                        color = colors.textPrimary
                    )
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
                    "选择分类",
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
                    "选择标签",
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
                            Toast.makeText(context, "最多选 $COMPOSER_MAX_TAGS 个标签", Toast.LENGTH_SHORT).show()
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
            selected?.name ?: "选择分类",
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
            if (tagCount > 0) "标签" else "添加标签",
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
                        if (query.isEmpty()) Text("搜索标签", fontSize = 15.sp, color = colors.textTertiary)
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
                        if (query.isNotEmpty()) "没有匹配的标签" else "该分类下暂无可选标签",
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

// ========== 预览 ==========

/** 预览内容区:编译好的 HTML 走 PostRichContent 富文本渲染;失败/空内容退回原文/占位。 */
@Composable
private fun PreviewPane(html: String?, rawText: String, loading: Boolean, modifier: Modifier = Modifier) {
    val colors = arkdoColors
    Box(modifier = modifier.fillMaxWidth()) {
        if (loading) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = colors.ldAccent,
                    strokeWidth = 2.dp
                )
                Text(
                    "正在生成预览...",
                    fontSize = 14.sp,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(start = 10.dp)
                )
            }
        } else if (!html.isNullOrEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                PostRichContent(
                    html = html,
                    // 预览里不上传图片解析,预览中的图(如已有 markdown 图链)原样显示链接/兜底。
                    onOpenImages = { _, _ -> },
                    onLinkClick = {}
                )
                Spacer(modifier = Modifier.height(32.dp))
            }
        } else if (rawText.isBlank()) {
            Text(
                "(无内容可预览)",
                fontSize = 15.sp,
                color = colors.textTertiary,
                modifier = Modifier.padding(16.dp)
            )
        } else {
            // 编译失败(引擎未就绪/超时):退回原文展示,与鸿蒙「(无内容可预览)」同层兜底。
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                Text(
                    rawText,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    color = colors.textBody
                )
            }
        }
    }
}

// ========== Markdown 工具栏 ==========

/**
 * Markdown 工具栏(对齐鸿蒙 MarkdownToolbar.ets 的常用子集)。
 * 图片按钮本页接入系统相册 → 上传;其余动作纯文本变换后回写编辑区。
 */
@Composable
fun MarkdownToolbar(
    uploading: Boolean,
    onUploadImage: () -> Unit,
    onAction: (MdAction) -> Unit
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
        DividerSpace()
        ToolIcon("bold", enabled = true) { onAction(MdAction.Wrap("**", "**", "加粗文字")) }
        ToolIcon("italic", enabled = true) { onAction(MdAction.Wrap("_", "_", "斜体文字")) }
        ToolIcon("code", enabled = true) { onAction(MdAction.Wrap("`", "`", "code")) }
        DividerSpace()
        ToolIcon("file-code", enabled = true) { onAction(MdAction.InsertCursor("```\n\n```", 4)) }
        ToolIcon("quote-left", enabled = true) { onAction(MdAction.LinePrefix("> ", ordered = false)) }
        ToolIcon("list-ul", enabled = true) { onAction(MdAction.LinePrefix("- ", ordered = false)) }
        ToolIcon("list-ol", enabled = true) { onAction(MdAction.LinePrefix("", ordered = true)) }
        ToolIcon("hashtag", enabled = true) { onAction(MdAction.LinePrefix("# ", ordered = false)) }
        DividerSpace()
        ToolIcon("link", enabled = true) { onAction(MdAction.Wrap("[", "](url)", "链接文字")) }
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

// ========== 图片预处理 ==========

/** 选中的一张图片 → base64 + 文件名 + MIME。超宽/过大时先缩放再 JPEG 压缩,避免撞站点上限。 */
private data class PreparedImage(val base64: String, val fileName: String, val mimeType: String)

private fun prepareImageUpload(resolver: ContentResolver, uri: Uri): PreparedImage? = runCatching {
    val fileName = queryDisplayName(resolver, uri)
        ?: uri.lastPathSegment?.substringAfterLast('/')
        ?: "image"
    val mimeType = resolver.getType(uri) ?: "image/jpeg"

    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    // 动图/无法解码的格式原样上传;大尺寸位图压到长边 ≤1920(对齐鸿蒙 1920 上限)。
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    val outBytes = if (bitmap != null && bitmap.width > 1920 && !mimeType.contains("gif")) {
        val scale = 1920f / maxOf(bitmap.width, bitmap.height)
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true
        )
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        out.toByteArray()
    } else {
        bytes
    }
    val mime = if (outBytes !== bytes) "image/jpeg" else mimeType
    PreparedImage(
        base64 = Base64.encodeToString(outBytes, Base64.NO_WRAP),
        fileName = if (outBytes !== bytes) fileName.substringBeforeLast('.', fileName) + ".jpg" else fileName,
        mimeType = mime
    )
}.getOrNull()

private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? =
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) cursor.getString(idx) else null
        } else {
            null
        }
    }
