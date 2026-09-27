package com.arkdo.android.ui.components

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
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

/** 表情面板退场动画时长（与下面 AnimatedVisibility 的 180ms 一致）。 */
private const val PANEL_EXIT_MS = 180L

/** 面板让位给键盘时，轮询「键盘是否顶到位」的间隔 / 最长等待。 */
private const val HANDOVER_POLL_MS = 20L
private const val KEYBOARD_ARRIVE_TIMEOUT_MS = 600L

/**
 * 底部回复输入浮层（切片 7，对齐鸿蒙 CommentInputBar 的普通回复链路）。
 *
 * 结构：全屏遮罩（点击关闭，淡入淡出 220ms）+ 底部输入条（圆角输入框 + 提交按钮，
 * 进场从底部滑入 220ms）。键盘避让不用现成的 imePadding/navigationBarsPadding，而是自己按
 * insets 算一个「底部占位」（见 bottomPadding）—— 因为面板和键盘要抢同一块地方，两个独立的
 * padding 修饰符没法互斥。模糊背板因 Haze 1.6.1 单 HazeState 多效果节点
 * 在 API 31 不可靠，暂以 70% 半透明底色近似（鸿蒙 bar 底色本身即 #B3 半透明）。
 *
 * 本轮范围（普通回复）：占位文字、最短字数拦截（16，对齐 Discourse）、超限提示、
 * 提交 loading、失败保留内容供重试、草稿经 onTextChange 上抛页面暂存、表情面板
 * （分组选择 + 短码插入 + 整段退格）、展开进全屏编辑器。boost 与编辑模式由调用方
 * 传参切换，本组件不感知。
 *
 * 键盘与表情面板**互斥且等高**：两者抢同一块底部空间，任意时刻只算一份，而且相加相等
 * （见 bottomPadding），所以来回切换时输入条不会上下跳。
 * 打开面板走「编辑器失焦」（[androidx.compose.ui.focus.FocusManager.clearFocus]）由系统收键盘，
 * 关面板走「重新请求焦点」弹回键盘；点输入框重新获焦时也会自动收掉面板。详见 hideKeyboard 的注释。
 */
@OptIn(ExperimentalLayoutApi::class) // WindowInsets.imeAnimationTarget
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
    // 编辑器当前是否持有焦点。面板打开时是失焦的，所以「重新获焦」= 用户要去打字 = 面板该让位。
    var editorFocused by remember { mutableStateOf(false) }
    val submitting = isSubmitting || localSubmitting
    val appearTimeMs = remember { System.currentTimeMillis() }
    var firstTypeTimeMs by remember { mutableLongStateOf(0L) }
    var wasOverMaxChars by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val colors = arkdoColors

    // ── 底部占位：键盘与表情面板共用同一块空间 ───────────────────────────────
    // 两者抢的是同一块地方，所以必须「等高」、而且任意时刻只算一份。
    //
    // 原写法是 Column 上叠「navigationBarsPadding + imePadding」，再把面板追加在最下面：
    // 面板出现的那一帧，底部位移 = 键盘 + 面板（≈两个键盘高），输入条先猛地弹上去、再随键盘
    // 收起滑下来 —— 就是「切一次跳一下」的来源。
    //
    // 现在只算一个数：输入条底边到屏幕底边的距离。
    //   · 键盘在场 → 实时 ime。键盘本来就压在导航栏上，所以这个值已经含导航栏区域
    //     （Compose 的 WindowInsets.ime 不做导航栏扣除，navigationBarsPadding + imePadding
    //      的合成值恰好也是它）。
    //   · 面板在场 → 只留导航栏，剩下的高度由面板自己占（面板高度 = 键盘占位 − 导航栏）。
    //   两侧相加相等：navBar + (键盘占位 − navBar) == 键盘占位 ⇒ 切换时输入条**完全不动**。
    //   （顺带修掉面板顶边比键盘顶边高出一个导航栏的老问题。）
    val navBarPx = WindowInsets.navigationBars.getBottom(density)
    val imeNowPx = WindowInsets.ime.getBottom(density)
    // imeAnimationTarget 弹出时立刻就是全高、收起时是 0，拿它记键盘高度不会被逐帧中间态污染
    // （实时值收起时是单调递减的，最后一次非零采样可能只剩零头）。但它只参与「记录」、不参与
    // 布局：低版本（minSdk 26）没有 inset 动画框架，目标值可能恒为 0，拿它布局会把输入条卡在半空。
    val imePeakPx = maxOf(WindowInsets.imeAnimationTarget.getBottom(density), imeNowPx)
    var lastKeyboardSpacePx by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(imePeakPx) {
        if (imePeakPx > 0) {
            val space = maxOf(imePeakPx, navBarPx)
            if (space > lastKeyboardSpacePx) lastKeyboardSpacePx = space.toFloat()
        }
    }
    // 键盘实时占用镜像成 State：下面交接用的协程里要读到最新值，而 insets 只能在组合里读。
    var liveKeyboardPx by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(imeNowPx) { liveKeyboardPx = imeNowPx.toFloat() }

    val navBarDp = with(density) { navBarPx.toDp() }
    val keyboardSpaceDp = with(density) { lastKeyboardSpacePx.toDp() }
    // 面板内容高度 = 键盘内容区高度（键盘占位 − 导航栏），于是 navBar + 面板 == 键盘占位。
    // 只有「键盘从没弹过、拿不到真高度」时才退回下限；那种情况下面板顶替需要一个让位动作，
    // 属必要开销（只在首次打开时发生一次）。
    val emojiPanelHeight = maxOf(EMOJI_PANEL_MIN_HEIGHT, keyboardSpaceDp - navBarDp)

    // 面板退场后它的布局高度就没了，但键盘要 200~300ms 才顶上来。这段空档继续把高度记在
    // 底部 padding 上（见 bottomPadding），否则输入条会先掉到导航栏、再被键盘顶回去。
    var handoverToKeyboard by remember { mutableStateOf(false) }
    LaunchedEffect(handoverToKeyboard) {
        if (!handoverToKeyboard) return@LaunchedEffect
        delay(PANEL_EXIT_MS)
        var waitedMs = 0L
        while (waitedMs < KEYBOARD_ARRIVE_TIMEOUT_MS &&
            liveKeyboardPx < lastKeyboardSpacePx * 0.98f
        ) {
            delay(HANDOVER_POLL_MS)
            waitedMs += HANDOVER_POLL_MS
        }
        handoverToKeyboard = false
    }

    // 面板的「在场」含退场动画（currentState 在退场播完才翻 false）：这段期间高度由面板自己占，
    // padding 必须留空，否则面板高度和 padding 会叠成两份 —— 这正是原来那个上跳。
    val panelState = remember { MutableTransitionState(false) }
    panelState.targetState = showEmojiPanel
    val panelInLayout = panelState.currentState || panelState.targetState

    val bottomPadding = when {
        panelInLayout -> navBarDp
        handoverToKeyboard -> maxOf(keyboardSpaceDp, navBarDp)
        imeNowPx > 0 -> maxOf(with(density) { imeNowPx.toDp() }, navBarDp)
        else -> navBarDp
    }

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

    /**
     * 收起系统键盘：让编辑器**失焦**，由系统自己把软键盘收掉（对齐鸿蒙 hideKeyboard 的 clearFocus）。
     *
     * 这里刻意不用 [keyboardController.hide]（原实现就是它，也是「面板和键盘同时在场」的根因）：
     * hide() 只收键盘、编辑器仍持有焦点，于是 ① 用户点一下输入框键盘就又被顶回来，表情面板和
     * 键盘叠在同屏；② 之后想切回键盘时 requestFocus 会变成空操作，得点两下才回来。鸿蒙侧在
     * hideKeyboard 上同样注了这个坑（「保留焦点会导致后续 requestFocus 成为空操作」）。
     *
     * 不用像鸿蒙那样手动存取选区：光标位置在我们自己的 [TextFieldValue] 里，失焦再获焦不会丢。
     */
    fun hideKeyboard() {
        focusManager.clearFocus()
    }

    /** 弹回系统键盘：重新请求焦点。补一次显式 show —— 个别输入法对「失焦后重新聚焦」不敏感。 */
    fun showSystemKeyboard() {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    /** 面板让位给系统键盘：高度交给 handoverToKeyboard 衔接，输入条原地不动。 */
    fun closePanelForKeyboard() {
        showEmojiPanel = false
        handoverToKeyboard = true
    }

    /** 表情键与系统键盘互斥切换（对齐鸿蒙 toggleEmojiKeyboard）：两者占同一块底部空间，只留一个。 */
    fun toggleEmojiPanel() {
        if (showEmojiPanel) {
            closePanelForKeyboard()
            showSystemKeyboard()
            return
        }
        showEmojiPanel = true
        // 面板重新接管高度，撤掉上一次可能的交接（值不变的话 LaunchedEffect 不会重启）。
        handoverToKeyboard = false
        hideKeyboard()
    }

    // 挂载后短暂延迟再请求焦点弹键盘（对齐鸿蒙 focusInput 的 200ms 延迟）。
    LaunchedEffect(Unit) {
        delay(200)
        focusRequester.requestFocus()
    }

    // 编辑器重新拿到焦点 = 用户点了输入框要打字：先把表情面板收掉。这条是「点输入框切回键盘」
    // 的兜底（表情键本身也会主动切），否则键盘会盖在面板上、两条底部栏同屏。
    // 反方向安全：打开面板走的是 clearFocus（焦点变 false），不会误触发这里。
    LaunchedEffect(editorFocused) {
        if (editorFocused && showEmojiPanel) closePanelForKeyboard()
    }

    // 浮层收起时复位面板：下次打开是干净的输入态，不会还挂着上次没关的表情面板，
    // 也不会留着上一次没交完的高度（否则重开时输入条会先悬在半空等键盘）。
    LaunchedEffect(visible) {
        if (!visible) {
            showEmojiPanel = false
            handoverToKeyboard = false
        }
    }

    // 返回键分两级（对齐鸿蒙 onComposerBackTick）：面板开着先收面板，再按才关浮层。
    // 这条路径刻意不补 showSystemKeyboard()（鸿蒙同样只收面板）：没有键盘接班，面板就收掉自己的
    // 高度、输入条顺势滑到底（见下面退场动画的 shrinkVertically 分支）。
    BackHandler(enabled = showEmojiPanel) { showEmojiPanel = false }

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
                    // 键盘 / 面板 / 都没有，三种情况的底部占位都由 bottomPadding 一个数决定
                    // （原先是 navigationBarsPadding + imePadding 叠着，面板再追加在最下面）。
                    .padding(bottom = bottomPadding)
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
                                    // 记录焦点：点输入框时用来收掉表情面板（见上面的 LaunchedEffect）。
                                    .onFocusChanged { editorFocused = it.isFocused }
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

                // 表情面板：高度按键盘占位算（见 emojiPanelHeight），所以它一进场就顶上原键盘
                // 的位置，输入条不用动。
                //
                // 进场只做位移、不改高度：高度一旦是渐变的，Column 总高就会跟着变，输入条会被
                // 拽着上下动。退场分两种：
                //   · 键盘在下面接班 → 同样只做位移，高度等面板退场完再交给 padding
                //     （handoverToKeyboard），中间那段空档输入条一动不动；
                //   · 没人接班（返回键收面板）→ 收掉高度，输入条顺势滑到底，而不是一格一格地跳。
                AnimatedVisibility(
                    visibleState = panelState,
                    enter = slideInVertically(tween(PANEL_EXIT_MS.toInt(), easing = EaseOut)) { it } +
                        fadeIn(tween(PANEL_EXIT_MS.toInt(), easing = EaseOut)),
                    exit = if (handoverToKeyboard) {
                        slideOutVertically(tween(PANEL_EXIT_MS.toInt(), easing = EaseOut)) { it } +
                            fadeOut(tween(PANEL_EXIT_MS.toInt(), easing = EaseOut))
                    } else {
                        shrinkVertically(
                            tween(PANEL_EXIT_MS.toInt(), easing = EaseOut),
                            shrinkTowards = Alignment.Top
                        ) + fadeOut(tween(PANEL_EXIT_MS.toInt(), easing = EaseOut))
                    }
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
