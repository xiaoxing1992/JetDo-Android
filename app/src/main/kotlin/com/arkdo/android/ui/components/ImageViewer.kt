@file:OptIn(ExperimentalSharedTransitionApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.arkdo.android.ui.components

import androidx.compose.ui.res.stringResource
import com.arkdo.android.R
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import com.arkdo.android.core.designsystem.FaIcon

// ==========================================================================
// 全屏图片浏览器，1:1 对齐鸿蒙 ImageViewer.ets 的行为与细节。
//
// 共享元素转场：Compose 官方 SharedTransitionLayout + sharedElement（key 配对）。
//
// 手势（对齐鸿蒙 parallelGesture 组合的语义）：
// - 双指捏合：实时缩放 1..4（MIN/MAX 对齐源常量），两指平移同步跟手；
// - 双击：1 ↔ 2.5（DOUBLE_TAP_SCALE），带 220ms 动画；鸿蒙由 @rv/image-preview 承载，
//   Android 侧用 Animatable 直接实现同款数值与节奏；
// - 放大后（scale > 1.05 = ZOOM_EPSILON）：单指竖直拖动变为图片平移（水平方向仍让给
//   翻页，与鸿蒙 Horizontal direction 的取舍一致）；未放大时竖直拖动关闭，阈值 120；
// - 长按：菜单（保存/分享/拷贝链接）——鸿蒙浏览器没有长按菜单，这是 Android 侧按
//   用户要求的增补；分享与鸿蒙 ImageShareService 同语义：先下到沙箱再交系统面板。
//
// 渐进加载：底层优化图秒显垫底，顶层高清原图完成后覆盖；失败回退优化图。
// 系统返回键：非首图翻回上一张，首图才关闭预览。
// ==========================================================================

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 4f
private const val DOUBLE_TAP_SCALE = 2.5f

/** 拖动关闭：竖直位移绝对值超过该阈值即关闭，否则回弹（上/下拖同等）。 */
private const val DISMISS_THRESHOLD = 120f

/** 缩放高于该值视为「已放大」，竖直拖动从关闭让位给图片平移。 */
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
    // 真实缩放值：双击走动画（Animatable），捏合走 snapTo；不再需要鸿蒙的 scaleGuess
    // 旁路估计——Compose 侧变换由我们自己驱动，真值即状态。
    val scale = remember { Animatable(1f) }
    // 放大后的平移量（图片左上角相对中心的偏移，按视口一半 x (scale-1) 夹界）。
    var imageOffsetX by remember { mutableFloatStateOf(0f) }
    var imageOffsetY by remember { mutableFloatStateOf(0f) }
    // 竖直拖动关闭的实时位移（>0 向下、<0 向上）。
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    // 高清原图已加载 / 已失败的页下标（渐进加载）。
    var loadedPages by remember { mutableStateOf(setOf<Int>()) }
    var failedPages by remember { mutableStateOf(setOf<Int>()) }
    // 长按菜单与导出进行中标志。
    var showMenu by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }

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

    /** 按当前缩放把平移量夹回可视范围内（图片永远至少铺住视口）。视口由调用方传入。 */
    fun clampOffsets(viewportW: Float, viewportH: Float) {
        val s = scale.value
        val maxX = (s - 1f) * viewportW / 2f
        val maxY = (s - 1f) * viewportH / 2f
        imageOffsetX = imageOffsetX.coerceIn(-maxX, maxX)
        imageOffsetY = imageOffsetY.coerceIn(-maxY, maxY)
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
        if (showMenu) {
            showMenu = false
        } else if (pagerState.currentPage > 0) {
            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
        } else {
            requestClose()
        }
    }

    // 翻页后重置缩放与平移，让新页可下滑关闭；菜单一并收起。
    LaunchedEffect(pagerState.currentPage) {
        scale.snapTo(1f)
        imageOffsetX = 0f
        imageOffsetY = 0f
        showMenu = false
    }

    // 导航栏透明：enableEdgeToEdge 默认给浅色主题导航栏刷了 90% 白 scrim，
    // 会把浏览器的黑底盖成白色 bar。挂载期间染回透明（黑底由内容自身绘制），卸载恢复。
    val activityWindow = (LocalContext.current as? android.app.Activity)?.window
    DisposableEffect(Unit) {
        val saved = activityWindow?.navigationBarColor
        activityWindow?.navigationBarColor = android.graphics.Color.TRANSPARENT
        onDispose {
            saved?.let { activityWindow?.navigationBarColor = it }
        }
    }

    // 黑底（拖动消隐时随位移渐透，最低留 25% 黑）。
    val backdropColor = Color.Black.copy(alpha = (1f - minOf(abs(dragOffsetY) / 300f, 0.75f)).coerceIn(0f, 1f))

    /** 当前页高清图（未回退前）落成 Bitmap：保存/分享共用；失败返回 null。 */
    suspend fun currentBitmap(): Bitmap? = withContext(Dispatchers.IO) {
        val url = images.getOrNull(currentIndex)?.url ?: return@withContext null
        val request = ImageRequest.Builder(context).data(url).allowHardware(false).build()
        val image = (context.imageLoader.execute(request) as? SuccessResult)?.image
            ?: return@withContext null
        image.toBitmap()
    }

    /** 保存到系统相册 Pictures/JetDo（MediaStore），成功回 true。 */
    fun saveToGallery(bitmap: Bitmap, onDone: (Boolean) -> Unit) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "arkdo-${System.currentTimeMillis()}.jpg")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        if (Build.VERSION.SDK_INT >= 29) {
                            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/JetDo")
                        }
                    }
                    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        ?: return@runCatching false
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    } ?: return@runCatching false
                    true
                }.getOrDefault(false)
            }
            onDone(ok)
        }
    }

    /** 分享：下载到 cache/shared_images 再经 FileProvider 交系统面板（对齐鸿蒙 ImageShareService）。 */
    fun shareImage(bitmap: Bitmap, onDone: (Uri?) -> Unit) {
        scope.launch {
            val uri = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(context.cacheDir, "shared_images").apply { mkdirs() }
                    val file = File(dir, "arkdo-${System.currentTimeMillis()}.jpg")
                    file.outputStream().use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    }
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                }.getOrNull()
            }
            onDone(uri)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backdropColor)
            .pointerInput(Unit) {
                // 双指捏合 + 两指平移：Main pass 里父节点先于 Pager 收到事件，两指时全部
                // 消费，翻页与拖动关闭自然让位（对齐鸿蒙 PinchGesture 并行手势语义）。
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            val zoom = event.calculateZoom()
                            if (zoom != 1f) {
                                // awaitEachGesture 是受限挂起作用域，snapTo 须经常规协程调度。
                                scope.launch { scale.snapTo((scale.value * zoom).coerceIn(MIN_SCALE, MAX_SCALE)) }
                            }
                            if (scale.value > MIN_SCALE) {
                                val pan = event.calculatePan()
                                imageOffsetX += pan.x
                                imageOffsetY += pan.y
                                clampOffsets(size.width.toFloat(), size.height.toFloat())
                            }
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    // 缩回 1 时平移归零，恢复可拖动关闭状态。
                    if (scale.value <= ZOOM_EPSILON) {
                        imageOffsetX = 0f
                        imageOffsetY = 0f
                    }
                }
            }
            .pointerInput(Unit) {
                // 竖直 Pan：未放大时上/下拖关闭；放大后竖直位移归图片平移（水平仍给翻页）。
                detectVerticalDragGestures(
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        if (scale.value > ZOOM_EPSILON) {
                            imageOffsetY += dragAmount
                            clampOffsets(size.width.toFloat(), size.height.toFloat())
                        } else {
                            dragOffsetY += dragAmount
                        }
                    },
                    onDragEnd = {
                        if (scale.value > ZOOM_EPSILON) {
                            return@detectVerticalDragGestures
                        }
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
                        val target = if (scale.value > ZOOM_EPSILON) MIN_SCALE else DOUBLE_TAP_SCALE
                        scope.launch {
                            scale.animateTo(target, tween(220, easing = FastOutSlowInEasing))
                        }
                        if (target == MIN_SCALE) {
                            imageOffsetX = 0f
                            imageOffsetY = 0f
                        }
                    },
                    onLongPress = {
                        // 长按菜单：保存 / 分享 / 拷贝链接（Android 侧增补，鸿蒙无此交互）。
                        showMenu = true
                    }
                )
            }
    ) {
        // 内容层：拖动关闭时整页随手指上/下移；缩放/平移只作用于当前页图片。
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
                                    scaleX = scale.value
                                    scaleY = scale.value
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
                                    scaleX = scale.value
                                    scaleY = scale.value
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
            TopCircleButton(enabled = !exporting, onClick = {
                // 与鸿蒙 ImageShareService 同语义：先下到沙箱再交系统面板（面板认 uri 不认链接）。
                scope.launch {
                    exporting = true
                    val bitmap = currentBitmap()
                    if (bitmap == null) {
                        Toast.makeText(context, context.getString(R.string.common_share_failed), Toast.LENGTH_SHORT).show()
                        exporting = false
                        return@launch
                    }
                    shareImage(bitmap) { uri ->
                        exporting = false
                        if (uri == null) {
                            Toast.makeText(context, context.getString(R.string.common_share_failed), Toast.LENGTH_SHORT).show()
                            return@shareImage
                        }
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "image/jpeg"
                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(android.content.Intent.createChooser(intent, context.getString(R.string.common_share_image)))
                    }
                }
            }) {
                FaIcon("share-nodes", Color.White, 18.dp)
            }
        }

        // 长按菜单：底部弹出面板，保存/分享/拷贝链接。
        if (showMenu) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x66000000))
                    .clickable(onClick = { showMenu = false })
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0xF21C1E21), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .navigationBarsPadding()
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                ViewerMenuAction(iconId = "download", label = stringResource(R.string.common_save_image), enabled = !exporting) {
                    scope.launch {
                        exporting = true
                        val bitmap = currentBitmap()
                        if (bitmap == null) {
                            Toast.makeText(context, context.getString(R.string.common_save_failed), Toast.LENGTH_SHORT).show()
                            exporting = false
                            return@launch
                        }
                        saveToGallery(bitmap) { ok ->
                            exporting = false
                            Toast.makeText(
                                context,
                                if (ok) context.getString(R.string.common_saved_to_gallery) else context.getString(R.string.common_save_failed),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
                ViewerMenuAction(iconId = "share-nodes", label = stringResource(R.string.common_share_image), enabled = !exporting) {
                    scope.launch {
                        exporting = true
                        val bitmap = currentBitmap()
                        if (bitmap == null) {
                            Toast.makeText(context, context.getString(R.string.common_share_failed), Toast.LENGTH_SHORT).show()
                            exporting = false
                            return@launch
                        }
                        shareImage(bitmap) { uri ->
                            exporting = false
                            showMenu = false
                            if (uri == null) {
                                Toast.makeText(context, context.getString(R.string.common_share_failed), Toast.LENGTH_SHORT).show()
                                return@shareImage
                            }
                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "image/jpeg"
                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(android.content.Intent.createChooser(intent, context.getString(R.string.common_share_image)))
                        }
                    }
                }
                ViewerMenuAction(iconId = "link", label = stringResource(R.string.common_copy_link_alt), enabled = true) {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    clipboard.setPrimaryClip(
                        android.content.ClipData.newPlainText("arkdo-image", resolveUrl(currentIndex))
                    )
                    Toast.makeText(context, context.getString(R.string.common_link_copied_toast), Toast.LENGTH_SHORT).show()
                    showMenu = false
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

/** 长按菜单条目：图标 + 文案，整行可点；导出进行中降透明度并拒绝点击。 */
@Composable
private fun ViewerMenuAction(
    iconId: String,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp)
    ) {
        FaIcon(iconId, Color.White, 20.dp)
        Spacer(modifier = Modifier.width(14.dp))
        Text(text = label, fontSize = 16.sp, color = Color.White)
    }
}

/** 顶栏圆形按钮：40dp、#00000066 底、内容居中（对齐源 TOP_BUTTON_SIZE 规格）。 */
@Composable
private fun TopCircleButton(enabled: Boolean = true, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color(0x66000000))
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
