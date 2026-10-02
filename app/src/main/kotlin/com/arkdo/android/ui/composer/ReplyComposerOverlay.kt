package com.arkdo.android.ui.composer

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkdo.android.core.designsystem.FaIcon
import com.arkdo.android.ui.composer.MarkdownActions.EditState
import com.arkdo.android.ui.composer.MarkdownActions.MdAction
import com.arkdo.android.ui.theme.ArkdoFixedColors
import com.arkdo.android.ui.theme.arkdoColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 回帖 / 编辑 / boost 的**全屏编辑器**（对齐鸿蒙 ComposerPage 的 `composeTopic = false` 模式）。
 *
 * 与底部紧凑态 [com.arkdo.android.ui.components.CommentInputOverlay] 的关系：两者共用同一份
 * 文本真源（`TopicDetailViewModel.pendingReplyText`），点「展开」进来时内容原样带过来、
 * 收起来也不丢 —— 用户不会因为切了一下窗口就白写。
 *
 * 与发帖页 [NewTopicScreen] 的关系：工具栏（[MarkdownToolbar]）、预览（[PreviewPane]）、
 * 图片预处理（[prepareImageUpload]）是同一套实现，回帖不再是一行纯文本。
 *
 * 本组件只管 UI：文本上抛 [onTextChange] 由 ViewModel 存草稿，提交走 [onSubmit]，
 * 上传与预览由调用方接（因为引擎与网络能力都挂在 ViewModel 上）。
 */
@Composable
internal fun ReplyComposerOverlay(
    visible: Boolean,
    /** 顶栏副标题：回复某人 / 编辑这条回复 / 发一个 boost。 */
    title: String,
    placeholder: String,
    /** 浮层顶上的一行提醒(抽奖帖规则摘要,空串不渲染;对齐鸿蒙 composerHint)。 */
    hintText: String = "",
    initialText: String,
    isSubmitting: Boolean,
    isUploading: Boolean,
    uploadProgressText: String?,
    /** 上传提示行是否显示「取消」（仅视频压缩阶段为真）。 */
    uploadCancelable: Boolean = false,
    onCancelUpload: () -> Unit = {},
    minChars: Int,
    maxChars: Int,
    /** 编辑态：提交按钮换对勾（保存），与回复态的纸飞机区分。 */
    editMode: Boolean,
    onTextChange: (String) -> Unit,
    onSubmit: (text: String, typingMs: Long, composerMs: Long, done: (Boolean) -> Unit) -> Unit,
    /** 工具栏「附件」「音视频」入口是否露出（由调用方按站点设置算）。 */
    showAttachment: Boolean = false,
    showMedia: Boolean = false,
    /** 附件选择器的 MIME 过滤；空 = 不限类型。 */
    attachmentMimeTypes: List<String> = emptyList(),
    /** 选中一批文件后交给调用方上传；每成功一件回调一个可插入正文的 markdown 片段。 */
    onUploadPickedFiles: (ComposerUploadKind, List<Uri>, (String) -> Unit) -> Unit = { _, _, _ -> },
    renderPreview: suspend (String) -> String?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = arkdoColors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var editTab by remember { mutableStateOf(true) }
    var bodyField by remember { mutableStateOf(TextFieldValue(initialText)) }
    var localSubmitting by remember { mutableStateOf(false) }
    val submitting = isSubmitting || localSubmitting
    var appearTimeMs by remember { mutableLongStateOf(0L) }
    var firstTypeTimeMs by remember { mutableLongStateOf(0L) }
    var wasOverMaxChars by remember { mutableStateOf(false) }
    var previewHtml by remember { mutableStateOf<String?>(null) }
    var isPreviewLoading by remember { mutableStateOf(false) }
    var previewJob by remember { mutableStateOf<Job?>(null) }
    var disposed by remember { mutableStateOf(false) }

    // 每次由收起变展开时，用调用方的最新文本重置编辑区（紧凑态可能刚输入过）。
    LaunchedEffect(visible) {
        if (visible) {
            bodyField = TextFieldValue(initialText)
            editTab = true
            previewHtml = null
            previewJob?.cancel()
            appearTimeMs = System.currentTimeMillis()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            disposed = true
            previewJob?.cancel()
        }
    }

    fun trimmedLength(): Int = bodyField.text.trim().length
    fun overMaxChars(): Boolean = maxChars > 0 && trimmedLength() > maxChars

    fun warnIfOverMaxChars() {
        val over = overMaxChars()
        if (over && !wasOverMaxChars) {
            Toast.makeText(context, "最多 $maxChars 个字", Toast.LENGTH_SHORT).show()
        }
        wasOverMaxChars = over
    }

    /** 工具栏动作：按当前选区包裹/插入，再回写文本与光标。 */
    fun applyAction(action: MdAction) {
        val state = EditState(bodyField.text, bodyField.selection.start, bodyField.selection.end)
        val next = MarkdownActions.applyAction(state, action)
        bodyField = TextFieldValue(next.text, TextRange(next.selectionStart, next.selectionEnd))
        onTextChange(next.text)
    }

    // 图片：多选，与发帖页同一条批量通道（逐张压缩后串行上传，插入顺序 = 选择顺序）。
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        onUploadPickedFiles(ComposerUploadKind.Image, uris) { snippet ->
            applyAction(MdAction.Insert(uploadSnippet(snippet)))
        }
    }

    fun openImagePicker() {
        if (isUploading) return
        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    // 拍照：系统相机成图 → 与选图同一条上传通道。无相机设备返回 null，工具栏藏入口。
    val capturePhoto = rememberCameraCapture { uri ->
        onUploadPickedFiles(ComposerUploadKind.Image, listOf(uri)) { snippet ->
            applyAction(MdAction.Insert(uploadSnippet(snippet)))
        }
    }

    // 附件：多选；音视频：单选。两者都只负责选，读字节/上传交给调用方（站点上限在那边）。
    val attachmentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        onUploadPickedFiles(ComposerUploadKind.Attachment, uris) { snippet ->
            applyAction(MdAction.Insert(uploadSnippet(snippet)))
        }
    }

    val mediaPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        onUploadPickedFiles(ComposerUploadKind.Media, listOf(uri)) { snippet ->
            applyAction(MdAction.Insert(uploadSnippet(snippet)))
        }
    }

    fun openAttachmentPicker() {
        if (isUploading) return
        val mimes = attachmentMimeTypes.toTypedArray()
        attachmentPicker.launch(mimes.ifEmpty { arrayOf("*/*") })
    }

    fun openMediaPicker() {
        if (isUploading) return
        mediaPicker.launch(arrayOf("video/*", "audio/*"))
    }

    fun submit() {
        if (submitting) return
        val trimmed = bodyField.text.trim()
        val len = trimmedLength()
        if (len < minChars) {
            Toast.makeText(context, "至少还需 ${minChars - len} 个字", Toast.LENGTH_SHORT).show()
            return
        }
        if (overMaxChars()) {
            Toast.makeText(context, "超出 ${len - maxChars} 个字，最多 $maxChars 字", Toast.LENGTH_SHORT).show()
            return
        }
        localSubmitting = true
        val typingMs = if (firstTypeTimeMs > 0) System.currentTimeMillis() - firstTypeTimeMs else 0L
        val composerMs = if (appearTimeMs > 0) System.currentTimeMillis() - appearTimeMs else 0L
        onSubmit(trimmed, typingMs, composerMs) { ok ->
            localSubmitting = false
            if (ok) {
                bodyField = TextFieldValue("")
                onClose()
            }
        }
    }

    if (!visible) return

    BackHandler { onClose() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.pageBg)
    ) {
        // 顶栏：关闭 + 编辑/预览分段 + 发送。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(56.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center
            ) {
                FaIcon("xmark", colors.textSecondary, 18.dp)
            }
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SegmentTab(label = "编辑", selected = editTab) {
                    editTab = true
                    previewJob?.cancel()
                }
                SegmentTab(label = "预览", selected = !editTab) {
                    editTab = false
                    previewHtml = null
                    isPreviewLoading = true
                    previewJob = scope.launch {
                        val html = renderPreview(bodyField.text)
                        if (isActive && !disposed) {
                            isPreviewLoading = false
                            previewHtml = html
                        }
                    }
                }
            }
            SendButton(
                enabled = !submitting && !isUploading,
                editMode = editMode,
                onClick = { submit() }
            )
        }

        HorizontalDivider(thickness = 0.5.dp, color = colors.border)

        // 副标题：回给谁 / 在编辑哪条，一眼可见（发帖页没有这一行，因为标题就在下面）。
        if (title.isNotEmpty()) {
            Text(
                text = title,
                fontSize = 12.5.sp,
                color = colors.textTertiary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)
            )
            Spacer(modifier = Modifier.height(2.dp))
        }

        if (hintText.isNotEmpty()) {
            Text(
                text = hintText,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = colors.warning,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 6.dp)
            )
        }

        if (editTab) {
            Box(modifier = Modifier.weight(1f)) {
                if (bodyField.text.isEmpty()) {
                    Text(
                        text = placeholder,
                        fontSize = 16.sp,
                        lineHeight = 24.sp,
                        color = colors.textTertiary,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp)
                    )
                }
                BasicTextField(
                    value = bodyField,
                    onValueChange = { value ->
                        bodyField = value
                        if (firstTypeTimeMs == 0L && value.text.isNotEmpty()) {
                            firstTypeTimeMs = System.currentTimeMillis()
                        }
                        onTextChange(value.text)
                        warnIfOverMaxChars()
                    },
                    textStyle = TextStyle(
                        fontSize = 16.sp,
                        lineHeight = 24.sp,
                        color = colors.textPrimary
                    ),
                    cursorBrush = SolidColor(colors.textPrimary),
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
            MarkdownToolbar(
                uploading = isUploading,
                showAttachment = showAttachment,
                showMedia = showMedia,
                onUploadImage = { openImagePicker() },
                onUploadAttachment = { openAttachmentPicker() },
                onUploadMedia = { openMediaPicker() },
                onAction = { applyAction(it) },
                onCapturePhoto = capturePhoto?.let { capture ->
                    { if (!isUploading) capture() }
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

        // 上传中的进度提示（发帖页是盖遮罩，这里只在工具栏上方一行，避免全屏挡编辑）。
        if (isUploading && uploadProgressText != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text(
                    text = uploadProgressText,
                    fontSize = 12.5.sp,
                    color = colors.ldAccent
                )
                // 压缩阶段才给取消（一段 1080p 可能压几十秒）；上传阶段请求已发出，取消收不回来。
                if (uploadCancelable) {
                    Text(
                        text = "取消",
                        fontSize = 12.5.sp,
                        color = colors.textSecondary,
                        modifier = Modifier
                            .clickable { onCancelUpload() }
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        }

        if (submitting) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = colors.ldAccent,
                    strokeWidth = 2.dp
                )
            }
        }
    }
}

/** 编辑/预览分段（与发帖页同款，私有一份避免跨文件暴露内部样式）。 */
@Composable
private fun SegmentTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = arkdoColors
    Text(
        text = label,
        fontSize = 15.sp,
        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        color = if (selected) colors.textPrimary else colors.textTertiary,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    )
}

/** 发送钮：回复态纸飞机、编辑态对勾（对齐 CollapseButton/SendButton 的圆钮样式）。 */
@Composable
private fun SendButton(enabled: Boolean, editMode: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .background(
                if (enabled) ArkdoFixedColors.BrandBlue else ArkdoFixedColors.BrandBlue.copy(alpha = 0.4f),
                CircleShape
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        FaIcon(
            if (editMode) "check" else "paper-plane",
            ArkdoFixedColors.OnDarkFg,
            16.dp
        )
    }
}
