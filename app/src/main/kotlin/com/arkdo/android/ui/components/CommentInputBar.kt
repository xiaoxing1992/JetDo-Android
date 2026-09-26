package com.arkdo.android.ui.components

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkdo.android.R
import com.arkdo.android.core.designsystem.FaIcon
import com.arkdo.android.services.content.isKnownEmoji
import com.arkdo.android.services.content.normalizeEmojiDeletion
import com.arkdo.android.ui.theme.ArkdoFixedColors
import com.arkdo.android.ui.theme.arkdoColors
import kotlinx.coroutines.delay

/** 提交按钮直径 / 图标尺寸（对齐鸿蒙 40 按钮 + 18 图标 / 22 loading）。 */
private val SUBMIT_BUTTON_SIZE = 40.dp

/** 表情面板高度下限（对齐鸿蒙 EMOJI_PANEL_MIN_HEIGHT）：键盘没弹过时给个能用的高度。 */
private val EMOJI_PANEL_MIN_HEIGHT = 260.dp

/**
 * 底部回复输入浮层（切片 7，对齐鸿蒙 CommentInputBar 的普通回复链路）。
 *
 * 结构：全屏遮罩（点击关闭，淡入淡出 220ms）+ 底部输入条（圆角输入框 + 提交按钮，
 * 进场从底部滑入 220ms）。键盘避让走 Compose 标准 imePadding/navigationBarsPadding，
 * 替代鸿蒙手动监听 avoidAreaChange；模糊背板因 Haze 1.6.1 单 HazeState 多效果节点
 * 在 API 31 不可靠，暂以 70% 半透明底色近似（鸿蒙 bar 底色本身即 #B3 半透明）。
 *
 * 本轮范围（普通回复）：占位文字、最短字数拦截（16，对齐 Discourse）、超限提示、
 * 提交 loading、失败保留内容供重试、草稿经 onTextChange 上抛页面暂存、表情面板
 * （分组选择 + 短码插入 + 整段退格）、展开进全屏编辑器。boost 与编辑模式由调用方
 * 传参切换，本组件不感知。
 */
@Composable
fun CommentInputOverlay(
    visible: Boolean,
    placeholder: String,
    initialText: String,
    isSubmitting: Boolean,
    onTextChange: (String) -> Unit,
    onSubmit: (text: String, typingMs: Long, composerMs: Long, done: (Boolean) -> Unit) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    minChars: Int = 16,
    maxChars: Int = 0,
    // 编辑态：提交按钮换对勾图标（回复态保持回复箭头，对齐鸿蒙共用一个圆钮、图标按场景区分）。
    editMode: Boolean = false,
    /** 点「展开」进全屏编辑器（对齐鸿蒙内联回复条的展开入口）。 */
    onExpand: () -> Unit = {}
) {
    // initialText 只在浮层从无到有时取一次；提交成功后 VM 清草稿，输入条随之回到空态。
    // 用 TextFieldValue 而非裸 String：插表情 / 整段删短码都要自己改光标位置。
    var fieldValue by remember { mutableStateOf(TextFieldValue(initialText)) }
    var localSubmitting by remember { mutableStateOf(false) }
    var showEmojiPanel by remember { mutableStateOf(false) }
    val submitting = isSubmitting || localSubmitting
    val appearTimeMs = remember { System.currentTimeMillis() }
    var firstTypeTimeMs by remember { mutableLongStateOf(0L) }
    var wasOverMaxChars by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val colors = arkdoColors

    // 表情面板高度对齐软键盘（对齐鸿蒙 emojiPanelHeight）：记录最近一次键盘高度，
    // 键盘从未弹过时退回下限。键盘收起后 ime 高度归 0，不能直接拿它当高度。
    val imeHeightPx = WindowInsets.ime.getBottom(density)
    var lastKeyboardHeightPx by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(imeHeightPx) {
        if (imeHeightPx > 0) lastKeyboardHeightPx = imeHeightPx.toFloat()
    }
    val emojiPanelHeight =
        if (lastKeyboardHeightPx > 0) with(density) { lastKeyboardHeightPx.toDp() } else EMOJI_PANEL_MIN_HEIGHT

    fun trimmedLength(): Int = fieldValue.text.trim().length
    fun overMaxChars(): Boolean = maxChars > 0 && trimmedLength() > maxChars

    // 超出上限提示。不硬顶输入，发送拦截交给 submit()；每次「由未超变超」只弹一次。
    fun warnIfOverMaxChars() {
        val over = overMaxChars()
        if (over && !wasOverMaxChars) {
            Toast.makeText(context, "最多 $maxChars 个字", Toast.LENGTH_SHORT).show()
        }
        wasOverMaxChars = over
    }

    fun submit() {
        if (submitting) return
        val trimmed = fieldValue.text.trim()
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
        val composerMs = System.currentTimeMillis() - appearTimeMs
        onSubmit(trimmed, typingMs, composerMs) { ok ->
            localSubmitting = false
            if (ok) {
                fieldValue = TextFieldValue("")
                showEmojiPanel = false
                onClose()
            }
        }
    }

    /** 面板里的 ⌫：先按普通退格算出结果，再让短码归一化决定是否整段删掉 `:name:`。 */
    fun backspace() {
        val old = fieldValue.text
        val selection = fieldValue.selection
        if (!selection.collapsed) {
            val next = old.removeRange(selection.min, selection.max)
            fieldValue = TextFieldValue(next, TextRange(selection.min))
            onTextChange(next)
            return
        }
        val caret = selection.start
        if (caret <= 0) return
        val single = old.removeRange(caret - 1, caret)
        val fixed = normalizeEmojiDeletion(old, single, ::isKnownEmoji)
        fieldValue = if (fixed != null) {
            TextFieldValue(fixed.text, TextRange(fixed.caret))
        } else {
            TextFieldValue(single, TextRange(caret - 1))
        }
        onTextChange(fieldValue.text)
    }

    /** 在光标处插入 `:name:`，光标落到短码之后。 */
    fun insertEmoji(name: String) {
        val snippet = ":$name:"
        val old = fieldValue.text
        val start = fieldValue.selection.min
        val end = fieldValue.selection.max
        val next = old.substring(0, start) + snippet + old.substring(end)
        fieldValue = TextFieldValue(next, TextRange(start + snippet.length))
        onTextChange(next)
        if (firstTypeTimeMs == 0L) firstTypeTimeMs = System.currentTimeMillis()
    }

    /** 表情键与系统键盘互斥切换（对齐鸿蒙 toggleEmojiKeyboard）。 */
    fun toggleEmojiPanel() {
        if (showEmojiPanel) {
            showEmojiPanel = false
            focusRequester.requestFocus()
            return
        }
        showEmojiPanel = true
        keyboardController?.hide()
    }

    // 挂载后短暂延迟再请求焦点弹键盘（对齐鸿蒙 focusInput 的 200ms 延迟）。
    LaunchedEffect(Unit) {
        delay(200)
        focusRequester.requestFocus()
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 遮罩层：点击空白处关闭浮层。
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(220, easing = EaseOut)),
            exit = fadeOut(tween(220, easing = EaseOut))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ArkdoFixedColors.Scrim)
                    .clickable(onClick = onClose)
            )
        }

        // 底部输入条：进场从底部滑入。背景先铺、再做导航栏/键盘避让——底色一直延伸到
        // 屏幕真实底部（对齐鸿蒙 padding bottom = 8 + bottomInset 的画法）。
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(tween(220, easing = EaseOut)) { it } +
                fadeIn(tween(220, easing = EaseOut)),
            exit = slideOutVertically(tween(220, easing = EaseOut)) { it } +
                fadeOut(tween(220, easing = EaseOut)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.immersiveInputBarBg)
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                // 顶部 0.5dp 高光：分清输入条与正文（对齐鸿蒙 immersive_input_bar_highlight 亮边）。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(colors.immersiveInputBarHighlight)
                )
                Row(
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    // 圆角输入容器：BasicTextField 多行，长文内部滚动（最高 120dp，对齐鸿蒙）。
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 40.dp, max = 120.dp)
                            .background(colors.immersiveInputFieldBg, RoundedCornerShape(8.dp))
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Box {
                            if (fieldValue.text.isEmpty()) {
                                Text(
                                    text = placeholder,
                                    fontSize = 16.sp,
                                    lineHeight = 24.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.height(24.dp)
                                )
                            }
                            BasicTextField(
                                value = fieldValue,
                                onValueChange = { value ->
                                    // 键盘退格删掉短码收尾冒号时，把整段 `:name:` 一并删掉。
                                    // 没有 onWillDeleteText 钩子，只能从删除前后的文本反推。
                                    val fixed = normalizeEmojiDeletion(
                                        fieldValue.text,
                                        value.text,
                                        ::isKnownEmoji
                                    )
                                    fieldValue = if (fixed != null) {
                                        TextFieldValue(fixed.text, TextRange(fixed.caret))
                                    } else {
                                        value
                                    }
                                    val next = fieldValue.text
                                    if (firstTypeTimeMs == 0L && next.isNotEmpty()) {
                                        firstTypeTimeMs = System.currentTimeMillis()
                                    }
                                    onTextChange(next)
                                    warnIfOverMaxChars()
                                },
                                textStyle = TextStyle(
                                    fontSize = 16.sp,
                                    lineHeight = 24.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 24.dp)
                                    .focusRequester(focusRequester)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // 表情：与系统键盘互斥切换（对齐鸿蒙输入条的表情切换钮）。
                    Box(
                        modifier = Modifier
                            .size(SUBMIT_BUTTON_SIZE)
                            .clickable(onClick = { toggleEmojiPanel() }),
                        contentAlignment = Alignment.Center
                    ) {
                        FaIcon(
                            "face-smile",
                            if (showEmojiPanel) colors.ldAccent else colors.textSecondary,
                            18.dp
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // 展开：进全屏编辑器（Markdown 工具栏 / 预览 / 图片）。单行条写长回复太憋屈。
                    Box(
                        modifier = Modifier
                            .size(SUBMIT_BUTTON_SIZE)
                            .clickable(onClick = onExpand),
                        contentAlignment = Alignment.Center
                    ) {
                        FaIcon("expand", colors.textSecondary, 18.dp)
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // 提交按钮：圆形蓝底，提交中转白圈；不可用降透明度提示，重复点击由 submit() 拦。
                    val canSubmit = !submitting && trimmedLength() >= minChars && !overMaxChars()
                    Box(
                        modifier = Modifier
                            .size(SUBMIT_BUTTON_SIZE)
                            .alpha(if (canSubmit || submitting) 1f else 0.5f)
                            .background(ArkdoFixedColors.BrandBlue, CircleShape)
                            .clickable(onClick = { submit() }),
                        contentAlignment = Alignment.Center
                    ) {
                        if (submitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = ArkdoFixedColors.OnDarkFg,
                                strokeWidth = 2.dp
                            )
                        } else if (editMode) {
                            // 编辑态提交 = 保存：对勾图标（FaIcon 字体图标，语义清晰）。
                            FaIcon(
                                "check",
                                ArkdoFixedColors.OnDarkFg,
                                18.dp,
                                Modifier.size(18.dp)
                            )
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.ic_fa_reply),
                                contentDescription = "发送回复",
                                tint = ArkdoFixedColors.OnDarkFg,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                // 表情面板：从底部滑入，高度对齐软键盘（对齐鸿蒙把 EmojiPicker 挂在
                // 输入条下方的做法）。挂在这块底板里，键盘收起后它会顶上原键盘的位置。
                AnimatedVisibility(
                    visible = showEmojiPanel,
                    enter = slideInVertically(tween(180, easing = EaseOut)) { it } +
                        fadeIn(tween(180, easing = EaseOut)),
                    exit = slideOutVertically(tween(180, easing = EaseOut)) { it } +
                        fadeOut(tween(180, easing = EaseOut))
                ) {
                    EmojiPicker(
                        onSelect = { emoji -> insertEmoji(emoji.name) },
                        onDelete = { backspace() },
                        modifier = Modifier.height(emojiPanelHeight)
                    )
                }
            }
        }
    }
}
