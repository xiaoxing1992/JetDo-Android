package com.jetdo.android.ui.components

import androidx.compose.ui.res.stringResource
import com.jetdo.android.core.data.AppContextHolder
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jetdo.android.R
import com.jetdo.android.core.designsystem.FaIcon
import com.jetdo.android.services.content.emojiCatalogRevision
import com.jetdo.android.services.content.getEmojiUrl
import com.jetdo.android.services.content.isKnownEmoji
import com.jetdo.android.services.content.normalizeEmojiDeletion
import com.jetdo.android.services.content.renderEmojiInput
import com.jetdo.android.ui.theme.JetDoFixedColors
import com.jetdo.android.ui.theme.jetdoColors
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

/** 键盘高度采样：实时值连续这么多帧不变，就认为键盘已经静止、可以记高度了。 */
private const val IME_STABLE_SAMPLE_MS = 16L
private const val IME_STABLE_FRAMES = 3

/**
 * 底部回复输入浮层（切片 7，对齐鸿蒙 CommentInputBar 的普通回复链路）。
 *
 * 结构：全屏遮罩（点击关闭，淡入淡出 220ms）+ 底部输入条（圆角输入框 + 提交按钮，
 * 进场从底部滑入 220ms）。键盘避让不用现成的 imePadding/navigationBarsPadding，而是自己按
 * insets 算一个「底部占位」（见 bottomPadding）—— 因为面板和键盘要抢同一块地方，两个独立的
 * padding 修饰符没法互斥。背板用 70% 半透明底色（鸿蒙 bar 底色本身即 #B3 半透明），
 * 不做模糊——Haze 已从工程移除（见 TopicDetailScreen 顶栏同款处理）。
 *
 * 本轮范围（普通回复）：占位文字、最短字数拦截（16，对齐 Discourse）、超限提示、
 * 提交 loading、失败保留内容供重试、草稿经 onTextChange 上抛页面暂存、表情面板
 * （分组选择 + 短码插入 + 整段退格 + 输入框内直接渲染成图片）、展开进全屏编辑器。
 * boost 与编辑模式由调用方传参切换，本组件不感知。
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
    val colors = jetdoColors

    // ── 输入框内的表情图文混排 ───────────────────────────────────────────────
    // 真值永远是短码原文（`:smile:`）：发帖、草稿、字数统计、服务端渲染都不变，只有**显示**
    // 变成图片。显示上把每个已知短码换成一个全角空格占位，再把表情图按量出来的盒子画上去 ——
    // 对齐鸿蒙 RichEditor 的 addImageSpan；Compose 的 BasicTextField 没有 inlineContent
    // 这个能力（详见 EmojiInputRender.kt 顶部的源码核对结论），只能这么曲线救国。
    //
    // 订阅目录版本号：表情表是异步拉来的，表到货时已输入的短码要跟着从裸文本变成图片。
    val catalogRevision by emojiCatalogRevision.collectAsState()
    val emojiRender = remember(fieldValue.text, catalogRevision) {
        renderEmojiInput(fieldValue.text, ::getEmojiUrl)
    }
    val emojiTransformation = remember(catalogRevision) {
        EmojiInputVisualTransformation(::getEmojiUrl)
    }
    // 输入框最近一次的排版结果：占位字符的位置/行高全靠它量，字体换行都不用自己算。
    var inputLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val onInputLayout: (TextLayoutResult) -> Unit = remember { { inputLayout = it } }

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
    val imeTargetPx = WindowInsets.imeAnimationTarget.getBottom(density)

    // 键盘「真实高度」。这个数必须**严格等于键盘静止时的高度**：面板态按它撑高、键盘态用实时
    // ime，两个数只要差 1~2dp，切回键盘时输入条就会轻轻跳一下（正是本轮修的现象）。
    var lastKeyboardSpacePx by remember { mutableFloatStateOf(0f) }

    // ① 打底：键盘升到一半时就要用高度（比如刚弹键盘就去点表情键），先用动画目标顶一下，
    //    免得面板退回 EMOJI_PANEL_MIN_HEIGHT。只在**还没有记录**时生效一次 —— 原来是无条件
    //    取单调最大值，于是每次切回键盘时这个「目标值」都可能把记录顶高，面板高度/交接 padding
    //    在动画中途被撑大，输入条就被推着往上跳。
    val imePeakPx = maxOf(imeTargetPx, imeNowPx)
    LaunchedEffect(imePeakPx) {
        if (lastKeyboardSpacePx == 0f && imePeakPx > 0) {
            lastKeyboardSpacePx = maxOf(imePeakPx, navBarPx).toFloat()
        }
    }

    // 实时 inset 镜像：下面的协程要读最新值，而 insets 只能在组合里读。
    // 用 SideEffect（本次提交后同步执行）而不是 LaunchedEffect —— 后者要等一次协程调度，
    // 镜像值会晚一帧，交接判定就会拿到陈旧的键盘高度。
    var liveImePx by remember { mutableFloatStateOf(0f) }
    var liveNavBarPx by remember { mutableFloatStateOf(0f) }
    SideEffect {
        liveImePx = imeNowPx.toFloat()
        liveNavBarPx = navBarPx.toFloat()
    }

    // ② 定稿：键盘静止后用实时值**覆盖**记录 —— 这才是键盘的真实高度。
    //    判据用「实时值连续若干帧不变」，不依赖 imeAnimationTarget 的语义：低版本（minSdk 26）
    //    没有 inset 动画框架，imeAnimationTarget 的值恒等于实时值，拿它当「已到位」的信号会在
    //    动画途中误判。覆盖是双向的，换成更矮的键盘（横屏/换输入法）也会跟着修正，两态始终等高。
    LaunchedEffect(Unit) {
        var prevPx = -1f
        var stableFrames = 0
        while (true) {
            delay(IME_STABLE_SAMPLE_MS)
            val nowPx = liveImePx
            if (nowPx > 0f && nowPx == prevPx) {
                if (++stableFrames >= IME_STABLE_FRAMES) {
                    val spacePx = maxOf(nowPx, liveNavBarPx)
                    // 值没变就不写，免得键盘静止期间每 16ms 白触发一次重组。
                    if (spacePx != lastKeyboardSpacePx) lastKeyboardSpacePx = spacePx
                }
            } else {
                stableFrames = 0
            }
            prevPx = nowPx
        }
    }

    val navBarDp = with(density) { navBarPx.toDp() }
    val keyboardSpaceDp = with(density) { lastKeyboardSpacePx.toDp() }
    // 面板内容高度 = 键盘内容区高度（键盘占位 − 导航栏），于是 navBar + 面板 == 键盘占位。
    // 这里刻意不再对下限取 max：一旦键盘高度比「下限 + 导航栏」矮，max 会把面板撑高，
    // 两个态就不等高了，切回键盘时输入条仍会掉一截。下限只用于「键盘从没弹过、还没有高度」——
    // 那种情况下面板顶替需要一个让位动作，属必要开销（只在首次打开时发生一次）。
    val emojiPanelHeight = if (lastKeyboardSpacePx > 0f) {
        (keyboardSpaceDp - navBarDp).coerceAtLeast(0.dp)
    } else {
        EMOJI_PANEL_MIN_HEIGHT
    }

    // 面板退场后它的布局高度就没了，但键盘要 200~300ms 才顶上来。这段空档继续把高度记在
    // 底部 padding 上（见 bottomPadding），否则输入条会先掉到导航栏、再被键盘顶回去。
    var handoverToKeyboard by remember { mutableStateOf(false) }
    LaunchedEffect(handoverToKeyboard) {
        if (!handoverToKeyboard) return@LaunchedEffect
        delay(PANEL_EXIT_MS)
        var waitedMs = 0L
        // 等键盘真的顶到位再交还。判据是「实时值已经到了记录高度」（差 1px 以内）：原先是 98%
        // 就放行，等于键盘还差 2%（270dp 键盘上约 5dp）时就把 padding 从记录高度换成实时值，
        // 输入条先掉一下、再随键盘升回来 —— 那一下就是切键盘时的轻微抖动。
        while (waitedMs < KEYBOARD_ARRIVE_TIMEOUT_MS &&
            liveImePx < lastKeyboardSpacePx - 1f
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
            Toast.makeText(context, context.getString(R.string.composer_max_chars, maxChars), Toast.LENGTH_SHORT).show()
        }
        wasOverMaxChars = over
    }

    fun submit() {
        if (submitting) return
        val trimmed = fieldValue.text.trim()
        val len = trimmedLength()
        if (len < minChars) {
            Toast.makeText(context, context.getString(R.string.composer_min_chars_left, minChars - len), Toast.LENGTH_SHORT).show()
            return
        }
        if (overMaxChars()) {
            Toast.makeText(context, context.getString(R.string.composer_over_chars, len - maxChars, maxChars), Toast.LENGTH_SHORT).show()
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
                    .background(JetDoFixedColors.Scrim)
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
                                // 显示层变换：短码 → 全角空格占位（原文不变，见 EmojiInputRender.kt）。
                                visualTransformation = emojiTransformation,
                                // 排版结果用来量占位字符的盒子，把表情图画在上面。
                                onTextLayout = onInputLayout,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 24.dp)
                                    .focusRequester(focusRequester)
                                    // 记录焦点：点输入框时用来收掉表情面板（见上面的 LaunchedEffect）。
                                    .onFocusChanged { editorFocused = it.isFocused }
                            )

                            // 表情图片：画在上面的占位字符上（不参与测量，所以不影响输入框高度）。
                            EmojiInputOverlay(render = emojiRender, layout = inputLayout)
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // 表情：与系统键盘互斥切换。按钮图标显示的是「点它会发生什么」——
                    // 键盘态是笑脸、面板态是键盘（对齐鸿蒙 commentKeyboardToggleIcon 的
                    // showEmojiKeyboard ? 'keyboard' : 'face-smile'，也是微信同一处的做法）。
                    // 颜色刻意保持恒定：状态已经由图标本身表达，鸿蒙侧同样固定 icon_secondary。
                    // （鸿蒙那边把两个分支写成 if/else 是为了让 ArkUI 重建图标节点；Compose 的
                    //  AsyncImage 在 model 变化时就会重新加载，直接换图标名即可。）
                    Box(
                        modifier = Modifier
                            .size(SUBMIT_BUTTON_SIZE)
                            .clickable(onClick = { toggleEmojiPanel() })
                            .semantics {
                                // 对齐鸿蒙 accessibilityText：读屏用户也该知道这个钮会切到哪去。
                                contentDescription = if (showEmojiPanel) AppContextHolder.getString(R.string.composer_switch_keyboard) else AppContextHolder.getString(R.string.detail_pick_emoji)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        FaIcon(
                            if (showEmojiPanel) "keyboard" else "face-smile",
                            colors.textSecondary,
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
                            .background(JetDoFixedColors.BrandBlue, CircleShape)
                            .clickable(onClick = { submit() }),
                        contentAlignment = Alignment.Center
                    ) {
                        if (submitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = JetDoFixedColors.OnDarkFg,
                                strokeWidth = 2.dp
                            )
                        } else if (editMode) {
                            // 编辑态提交 = 保存：对勾图标（FaIcon 字体图标，语义清晰）。
                            FaIcon(
                                "check",
                                JetDoFixedColors.OnDarkFg,
                                18.dp,
                                Modifier.size(18.dp)
                            )
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.ic_fa_reply),
                                contentDescription = stringResource(R.string.detail_send_reply),
                                tint = JetDoFixedColors.OnDarkFg,
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
