package com.arkdo.android.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

// ==========================================================================
// 全屏图片浏览器，1:1 对齐鸿蒙 ImageViewer.ets 的行为与细节。
//
// hero 转场自研（源注释：三方库只有缩放/平移，不提供转场）：打开时从源节点矩形
// 飞到整窗（280ms，Curve.Friction 近似），关闭时飞回「当前这张」的源节点矩形
// （260ms）——多图翻页后关闭，缩回去的是当前这张自己的原位。源节点滚出视野
// （可见面积 < 25%）或处于放大态时退化为淡入淡出（含 0.94 缩放起点）。
//
// 竖直拖动关闭：未放大（scaleGuess <= 1.05）时上/下拖均可，位移超 120 关闭、
// 否则回弹（200ms EaseOut）；黑底随位移渐透，最低留 25% 黑（iOS 风格拖拽消隐）。
// 放大后让位给图片平移。
//
// 渐进加载：底层优化图（与正文内联同源、多已缓存、秒显垫底），顶层高清原图
// 加载完成后覆盖；高清失败回退优化图。翻页后重置缩放估计。
//
// 系统返回键：非首图翻回上一张（带动画），首图才关闭预览。
// 保存/分享（源为下载到沙箱再交系统面板）为后续切片；分享按钮先分享链接文本。
// ==========================================================================

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 4f
private const val DOUBLE_TAP_SCALE = 2.5f

/** 拖动关闭：竖直位移绝对值超过该阈值即关闭，否则回弹（上/下拖同等）。 */
private const val DISMISS_THRESHOLD = 120f

/** 缩放估计值高于该值即视为「已放大」，此时禁用拖动关闭（让位给图片平移）。 */
private const val ZOOM_EPSILON = 1.05f

/** hero 飞入/飞回时长；无 hero 可用时退化成的淡入淡出时长。 */
private const val HERO_IN_MS = 280
private const val HERO_OUT_MS = 260
private const val FADE_MS = 220

/** 无 hero 时淡入淡出附带的轻微缩放起点，纯粹为了不那么生硬。 */
private const val FADE_SCALE = 0.94f

/** 源节点可见面积占自身面积低于此比例即视作「已滚出视野」：此时不飞回去，改为淡出。 */
private const val HERO_MIN_VISIBLE_RATIO = 0.25f

/** hero 曲线（Curve.Friction 近似）。 */
private val HERO_EASING = CubicBezierEasing(0.4f, 0f, 0.8f, 1f)

/** 一张待浏览的图片：url 为首选（原图 href，高清），fallbackUrl 为优化图兜底（内联同源）。 */
data class ViewerImage(
    val url: String,
    val fallbackUrl: String
)

/**
 * 楼层缩略图的窗口坐标登记表：ImageBlock 挂 onGloballyPositioned 持续更新，
 * 浏览器按图片 url 现查源节点矩形（等价鸿蒙 componentUtils.getRectangleById(heroId)）。
 */
object ImageViewerRectRegistry {
    val rects = mutableMapOf<String, Rect>()
}

/** 全屏图片浏览器。浮层不进导航栈，需在页面根层渲染（与源注释一致）。 */
@Composable
fun ImageViewer(
    images: List<ViewerImage>,
    startIndex: Int,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    var currentIndex by remember { mutableIntStateOf(startIndex.coerceIn(0, (images.size - 1).coerceAtLeast(0))) }
    // hero 图层：挂载后常驻（只切透明度），使关闭时飞行的起点是早已提交过的矩形，
    // 不存在「同一帧刚赋值就动画」导致从 0,0 起飞的问题。
    var heroMounted by remember { mutableStateOf(false) }
    var heroOpacity by remember { mutableStateOf(0f) }
    var heroSourceRect by remember { mutableStateOf<Rect?>(null) }
    val heroProgress = remember { Animatable(0f) }
    // 内容层（翻页器 + 浮层）与黑底的整体透明度：hero 飞行期间藏起来，落位瞬间显形。
    var contentOpacity by remember { mutableFloatStateOf(1f) }
    var contentScale by remember { mutableFloatStateOf(1f) }
    // 黑底不透明度：打开时随 hero 飞行 0→1，关闭时 →0（拖动消隐另算，见 backdropColor）。
    val backdropAlpha = remember { Animatable(0f) }
    // 竖直拖动关闭的实时位移（>0 向下、<0 向上）。
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    // 缩放估计值：仅用于判断「是否已放大」；真实变换直接驱动当前页图片。
    var scaleGuess by remember { mutableFloatStateOf(1f) }
    var imageScale by remember { mutableFloatStateOf(1f) }
    var imageOffsetX by remember { mutableFloatStateOf(0f) }
    var imageOffsetY by remember { mutableFloatStateOf(0f) }
    var closing by remember { mutableStateOf(false) }
    // 高清原图已加载 / 已失败的页下标（渐进加载）。
    var loadedPages by remember { mutableStateOf(setOf<Int>()) }
    var failedPages by remember { mutableStateOf(setOf<Int>()) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    // 飞行目标尺寸快照：起飞时定格（系统栏恢复引起的视口变化不影响进行中的动画落点）。
    var flightTarget by remember { mutableStateOf(IntSize.Zero) }
    // 系统栏显隐：false=可见（打开时飞完前）；true=隐藏（落位黑底铺满后隐藏，重排被黑底盖住）。
    var systemBarsHidden by remember { mutableStateOf(false) }

    val pagerState = rememberPagerState(initialPage = currentIndex) { images.size }

    fun isLoaded(index: Int): Boolean = index in loadedPages

    /** hero 图层用哪张：高清已就位就用高清（关闭时与屏上画面一致，无画质回跳），否则用优化图。 */
    fun heroUrl(index: Int): String {
        val item = images.getOrNull(index) ?: return ""
        return if (isLoaded(index)) item.url else item.fallbackUrl.ifEmpty { item.url }
    }

    /** 占位图：优先用优化图（帖子内联同源、多已缓存，秒显）；没有则退回高清图本身。 */
    fun placeholderUrl(index: Int): String {
        val item = images.getOrNull(index) ?: return ""
        return item.fallbackUrl.ifEmpty { item.url }
    }

    /** 实际展示的图：高清失败过就回退优化图（与屏上所见一致）。 */
    fun resolveUrl(index: Int): String {
        val item = images.getOrNull(index) ?: return ""
        return if (index in failedPages && item.fallbackUrl.isNotEmpty()) {
            item.fallbackUrl
        } else {
            item.url.ifEmpty { item.fallbackUrl }
        }
    }

    /**
     * 取第 index 张图在底层页面里的源节点矩形（窗口坐标 → 转本浮层坐标）。
     * 返回 null：没登记 / 已滚出视野（可见面积 < 25%）。
     */
    fun sourceRect(index: Int): Rect? {
        val item = images.getOrNull(index) ?: return null
        val windowRect = ImageViewerRectRegistry.rects[item.fallbackUrl] ?: return null
        if (windowRect.width <= 0f || windowRect.height <= 0f) {
            return null
        }
        // 登记表存的就是根坐标，浮层与页面同根 → 直接用。
        val local = windowRect
        // 视口还没量到（入场首帧）时一律放行——那一刻的源节点就是用户刚点的那张。
        if (viewSize == IntSize.Zero) {
            return local
        }
        // 与视口的交叠面积是否够大：不够 = 已滚出视野，不飞回去（会看到图片甩向屏幕外）。
        val left = maxOf(local.left, 0f)
        val top = maxOf(local.top, 0f)
        val right = minOf(local.right, viewSize.width.toFloat())
        val bottom = minOf(local.bottom, viewSize.height.toFloat())
        if (right <= left || bottom <= top) {
            return null
        }
        val visible = (right - left) * (bottom - top)
        return if (visible / (local.width * local.height) >= HERO_MIN_VISIBLE_RATIO) local else null
    }

    /** 统一的关闭入口（✕ / 单击 / 首图返回键 / 拖动过阈值都走这里）。 */
    fun requestClose() {
        if (closing) {
            return
        }
        closing = true
        val zoomed = scaleGuess > ZOOM_EPSILON
        // onRevealing 时机：黑底还满着就先恢复系统栏，底层重排被黑底盖住；
        // 等一帧重排完成后再现查「当前这张」的源矩形（多图翻页后关闭缩回的是当前这张）。
        systemBarsHidden = false
        scope.launch {
            withFrameNanos { }
            val zoomedAfterLayout = scaleGuess > ZOOM_EPSILON
            val src: Rect? = if (zoomedAfterLayout) null else sourceRect(currentIndex)
            flightTarget = viewSize
            if (heroMounted && src != null) {
                heroOpacity = 1f
                kotlinx.coroutines.coroutineScope {
                    launch { heroProgress.snapTo(1f) }
                    launch { heroProgress.animateTo(0f, tween(HERO_OUT_MS, easing = HERO_EASING)) }
                    launch { backdropAlpha.animateTo(0f, tween(HERO_OUT_MS, easing = HERO_EASING)) }
                }
                onDismiss()
            } else {
                heroOpacity = 0f
                contentOpacity = 0f
                contentScale = FADE_SCALE
                backdropAlpha.animateTo(0f, tween(FADE_MS))
                onDismiss()
            }
        }
    }

    // 首帧前定位 hero 起点：源节点在底层页面里已完成布局，此刻查得到几何。
    remember {
        val src = sourceRect(startIndex)
        if (src != null) {
            heroSourceRect = src
            heroMounted = true
            heroOpacity = 1f
            contentOpacity = 0f
        } else {
            heroMounted = false
            contentOpacity = 0f
            contentScale = FADE_SCALE
        }
        true
    }

    // 入场：首帧已把 hero 渲染成源节点大小，这里才起飞到整窗。
    LaunchedEffect(viewSize) {
        if (viewSize == IntSize.Zero || closing) {
            return@LaunchedEffect
        }
        if (heroMounted) {
            kotlinx.coroutines.coroutineScope {
                launch { heroProgress.animateTo(1f, tween(HERO_IN_MS, easing = HERO_EASING)) }
                // 黑底随飞行渐显（对齐源 animateTo 里 backdropAlpha = 1 的收敛时机）。
                launch { backdropAlpha.animateTo(1f, tween(HERO_IN_MS, easing = HERO_EASING)) }
            }
            if (!closing) {
                // 落位矩形与内容里那张图逐像素重合，直接对调看不出接缝。
                contentOpacity = 1f
                heroOpacity = 0f
            }
        } else if (!heroMounted && contentOpacity < 1f) {
            // 没有 hero（或视口没量到）：退化为淡入
            backdropAlpha.animateTo(1f, tween(FADE_MS))
            contentOpacity = 1f
            contentScale = 1f
        }
    }

    // 全局沉浸式（对齐鸿蒙 expandSafeArea([SYSTEM]) 黑底铺满 + onCovered/onRevealing 的
    // 隐藏/恢复系统栏时机）：隐藏发生在「黑底已铺满」之后、恢复发生在「黑底还满着」的
    // 关闭开始时刻——系统栏显隐引发的底层重排都被不透明黑底盖住，不会当着用户的面跳。
    val activityWindow = (LocalContext.current as? android.app.Activity)?.window
    DisposableEffect(systemBarsHidden) {
        val controller = activityWindow?.let { WindowInsetsControllerCompat(it, view) }
        if (systemBarsHidden) {
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        activityWindow?.statusBarColor = android.graphics.Color.BLACK
        activityWindow?.navigationBarColor = android.graphics.Color.BLACK
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            activityWindow?.statusBarColor = android.graphics.Color.BLACK
            activityWindow?.navigationBarColor = android.graphics.Color.BLACK
        }
    }

    // 系统返回键：非首图翻回上一张（动画翻页），首图关闭预览。
    BackHandler {
        if (pagerState.currentPage > 0) {
            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
        } else {
            requestClose()
        }
    }

    // 翻页后库会重置该页缩放，同步重置估计值，让新页可下滑关闭。
    LaunchedEffect(pagerState.currentPage) {
        currentIndex = pagerState.currentPage
        scaleGuess = 1f
        imageScale = 1f
        imageOffsetX = 0f
        imageOffsetY = 0f
        dragOffsetY = 0f
    }

    // 黑底透明度随拖动位移（上/下同等）递减，最低留 25% 黑；再乘上开关场的整体不透明度。
    val backdropFade = minOf(abs(dragOffsetY) / 300f, 0.75f)
    val backdropColor = Color.Black.copy(alpha = ((1f - backdropFade) * backdropAlpha.value).coerceIn(0f, 1f))

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backdropColor)
            .onSizeChanged { viewSize = it }
            .pointerInput(Unit) {
                // 竖直 Pan：未放大时上/下拖均可关闭；已放大让位给图片平移。
                detectVerticalDragGestures(
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        if (scaleGuess > ZOOM_EPSILON) {
                            dragOffsetY = 0f
                        } else {
                            dragOffsetY += dragAmount
                        }
                    },
                    onDragEnd = {
                        if (abs(dragOffsetY) > DISMISS_THRESHOLD) {
                            requestClose()
                        } else {
                            // 回弹到原位（拖动未过阈值）。
                            dragOffsetY = 0f
                        }
                    },
                    onDragCancel = { dragOffsetY = 0f }
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { requestClose() },
                    onDoubleTap = {
                        scaleGuess = if (scaleGuess > ZOOM_EPSILON) MIN_SCALE else DOUBLE_TAP_SCALE
                        imageScale = scaleGuess
                        if (scaleGuess == MIN_SCALE) {
                            imageOffsetX = 0f
                            imageOffsetY = 0f
                        }
                    }
                )
            }
    ) {
        // 内容层：翻页器 + 浮层，hero 飞行期间整体藏起来；拖动关闭时整页随手指上/下移。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = contentScale
                    scaleY = contentScale
                    alpha = contentOpacity
                    translationY = dragOffsetY
                }
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val item = images.getOrNull(page) ?: return@HorizontalPager
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // 双指缩放/缩放后平移（对齐源 pinch 手势自维护 scaleGuess）：
                        // 只有两指才消费事件，单指穿透给拖动关闭与翻页。
                        .pointerInput(page) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                var multiTouch = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.filter { it.pressed }
                                    if (pressed.size >= 2) {
                                        multiTouch = true
                                        if (page == currentIndex) {
                                            imageScale = (imageScale * event.calculateZoom()).coerceIn(MIN_SCALE, MAX_SCALE)
                                            scaleGuess = imageScale
                                            val pan = event.calculatePan()
                                            if (imageScale > 1f) {
                                                imageOffsetX += pan.x
                                                imageOffsetY += pan.y
                                            }
                                        }
                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    } else if (imageScale > 1f) {
                                        // 放大后单指平移图片（此时拖动关闭已让位）。
                                        val pan = event.calculatePan()
                                        if (page == currentIndex) {
                                            imageOffsetX += pan.x
                                            imageOffsetY += pan.y
                                        }
                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    }
                                    if (event.changes.none { it.pressed }) break
                                }
                            }
                        }
                ) {
                    // 渐进加载：底层优化图（已缓存、秒显占位，避免高清图未回来时纯黑）。
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(placeholderUrl(page))
                            .crossfade(false)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                if (page == currentIndex) {
                                    scaleX = imageScale
                                    scaleY = imageScale
                                    translationX = imageOffsetX
                                    translationY = imageOffsetY
                                }
                            }
                    )
                    // 顶层高清原图：加载完成后覆盖；失败回退优化图（同时视为已就绪，隐藏 loading）。
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(item.url)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                if (page == currentIndex) {
                                    scaleX = imageScale
                                    scaleY = imageScale
                                    translationX = imageOffsetX
                                    translationY = imageOffsetY
                                }
                            },
                        onSuccess = { loadedPages = loadedPages + page },
                        onError = {
                            failedPages = failedPages + page
                            loadedPages = loadedPages + page
                        }
                    )
                }
            }
        }

        // 高清原图加载中的转圈（当前页未就绪且未失败时显示；优化图已垫底，仅提示高清在路上）。
        if (currentIndex !in loadedPages && currentIndex !in failedPages) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(44.dp),
                    color = Color.White,
                    strokeWidth = 3.dp
                )
            }
        }

        // 页码指示（多图时显示），置底居中胶囊，透传点击不拦截手势。
        if (images.size > 1) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Text(
                    text = "${currentIndex + 1} / ${images.size}",
                    fontSize = 14.sp,
                    color = Color.White,
                    modifier = Modifier
                        .padding(bottom = 44.dp)
                        .background(Color(0x80000000), RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 5.dp)
                )
            }
        }

        // 顶栏：✕（左）+ 分享（右），40dp 圆形按钮 #00000066，避让状态栏。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 12.dp, vertical = 12.dp)
        ) {
            TopCircleButton(onClick = { requestClose() }) {
                Text(text = "✕", fontSize = 18.sp, color = Color.White)
            }
            Spacer(modifier = Modifier.weight(1f))
            TopCircleButton(onClick = {
                // 待切片：下载到沙箱后交系统分享二进制（源 ImageShareService）。
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_TEXT, resolveUrl(currentIndex))
                }
                context.startActivity(android.content.Intent.createChooser(intent, "分享图片"))
            }) {
                FaIcon("share-nodes", Color.White, 18.dp)
            }
        }

        // hero 图层：坐标为浮层坐标；y 恒加上 dragOffsetY——关闭动画里 dragOffsetY 与
        // heroRect 一起收敛，拖到一半松手也能从手指所在处接着飞回原位。
        val src = heroSourceRect
        if (heroMounted && src != null) {
            val progress = heroProgress.value
            val left = src.left + (0f - src.left) * progress
            val top = src.top + (0f - src.top) * progress + dragOffsetY
            val targetW = flightTarget.width.takeIf { it > 0 } ?: viewSize.width
            val targetH = flightTarget.height.takeIf { it > 0 } ?: viewSize.height
            val width = src.width + (targetW.toFloat() - src.width) * progress
            val height = src.height + (targetH.toFloat() - src.height) * progress
            val widthDp = with(density) { width.toDp() }
            val heightDp = with(density) { height.toDp() }
            Box(
                modifier = Modifier
                    .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                    .size(widthDp, heightDp)
                    .alpha(heroOpacity)
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(heroUrl(currentIndex))
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/** 顶栏圆形按钮：40dp、#00000066 底、内容居中（对齐源 TOP_BUTTON_SIZE 规格）。 */
@Composable
private fun TopCircleButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color(0x66000000))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
