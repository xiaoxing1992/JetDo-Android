@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.arkdo.android.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.launch
import kotlin.math.abs

// ==========================================================================
// 全屏图片浏览器，1:1 对齐鸿蒙 ImageViewer.ets 的行为与细节。
//
// 共享元素转场：采用 Compose 官方 SharedTransitionLayout + sharedElement（key 配对），
// 由外层 AnimatedContent 驱动开/关的飞入飞出——比手搓 hero 层的逐帧衔接可靠。
//
// 竖直拖动关闭：未放大（scaleGuess <= 1.05）时上/下拖均可，位移超 120 关闭、
// 否则回弹；放大后让位给图片平移。
//
// 渐进加载：底层优化图（与正文内联同源、多已缓存、秒显垫底），顶层高清原图
// 加载完成后覆盖；高清失败回退优化图。翻页后重置缩放。
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

/** 共享元素作用域（详情页根部提供，缩略图与全屏图按 key 配对）。 */
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

/** 浏览器覆盖层的 AnimatedVisibility 作用域（sharedElement 配对需要）。 */
val LocalAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** 缩略图/全屏图共用的共享元素 key（fallbackUrl 即正文优化图 src，两端同源）。 */
fun viewerImageKey(fallbackUrl: String): String = "post-image-$fallbackUrl"

/** 一张待浏览的图片：url 为首选（原图 href，高清），fallbackUrl 为优化图兜底（内联同源）。 */
data class ViewerImage(
    val url: String,
    val fallbackUrl: String
)

@Composable
fun ImageViewer(
    images: List<ViewerImage>,
    startIndex: Int,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentIndex by remember { mutableIntStateOf(startIndex.coerceIn(0, (images.size - 1).coerceAtLeast(0))) }
    var closing by remember { mutableStateOf(false) }
    // 缩放估计值：仅用于判断「是否已放大」；真实变换直接驱动当前页图片。
    var scaleGuess by remember { mutableStateOf(1f) }
    var imageScale by remember { mutableFloatStateOf(1f) }
    var imageOffsetX by remember { mutableFloatStateOf(0f) }
    var imageOffsetY by remember { mutableFloatStateOf(0f) }
    // 竖直拖动关闭的实时位移（>0 向下、<0 向上）。
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    // 高清原图已加载 / 已失败的页下标（渐进加载）。
    var loadedPages by remember { mutableStateOf(setOf<Int>()) }
    var failedPages by remember { mutableStateOf(setOf<Int>()) }

    val pagerState = rememberPagerState(initialPage = currentIndex) { images.size }

    /** 实际展示的图：高清失败过就回退优化图（与屏上所见一致）。 */
    fun resolveUrl(index: Int): String {
        val item = images.getOrNull(index) ?: return ""
        return if (index in failedPages && item.fallbackUrl.isNotEmpty()) {
            item.fallbackUrl
        } else {
            item.url.ifEmpty { item.fallbackUrl }
        }
    }

    /** 占位图：优先用优化图（帖子内联同源、多已缓存，秒显）；没有则退回高清图本身。 */
    fun placeholderUrl(index: Int): String {
        val item = images.getOrNull(index) ?: return ""
        return item.fallbackUrl.ifEmpty { item.url }
    }

    /** 统一的关闭入口（✕ / 单击 / 首图返回键 / 拖动过阈值都走这里）。转场由 AnimatedContent 接管。 */
    fun requestClose() {
        if (closing) {
            return
        }
        closing = true
        onDismiss()
    }

    // 系统返回键：非首图翻回上一张（动画翻页），首图关闭预览。
    BackHandler {
        if (pagerState.currentPage > 0) {
            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
        } else {
            requestClose()
        }
    }

    // 翻页后重置缩放，让新页可下滑关闭。
    LaunchedEffect(pagerState.currentPage) {
        scaleGuess = 1f
        imageScale = 1f
        imageOffsetX = 0f
        imageOffsetY = 0f
    }

    // 黑底（拖动消隐时随位移渐透，最低留 25% 黑）。
    val backdropColor = Color.Black.copy(alpha = (1f - minOf(abs(dragOffsetY) / 300f, 0.75f)).coerceIn(0f, 1f))

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backdropColor)
            .pointerInput(Unit) {
                // 竖直 Pan：未放大时上/下拖均可关闭；已放大让位给图片平移。
                detectVerticalDragGestures(
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        if (scaleGuess > ZOOM_EPSILON) {
                            // 放大后竖直位移归图片平移，这里不驱动关闭。
                        } else {
                            dragOffsetY += dragAmount
                        }
                    },
                    onDragEnd = {
                        if (abs(dragOffsetY) > DISMISS_THRESHOLD) {
                            requestClose()
                        } else {
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
        // 内容层：拖动关闭时整页随手指上/下移。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = dragOffsetY }
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val item = images.getOrNull(page) ?: return@HorizontalPager
                Box(modifier = Modifier.fillMaxSize()) {
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
                    // 当前页挂共享元素：开关时与楼层缩略图按 key 配对飞行。
                    val sts = LocalSharedTransitionScope.current
                    val avs = LocalAnimatedVisibilityScope.current
                    val imageModifier = if (page == startIndex && sts != null && avs != null) {
                        with(sts) {
                            Modifier.sharedElement(
                                rememberSharedContentState(key = viewerImageKey(item.fallbackUrl)),
                                animatedVisibilityScope = avs
                            )
                        }
                    } else {
                        Modifier
                    }
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(item.url)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = imageModifier
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
