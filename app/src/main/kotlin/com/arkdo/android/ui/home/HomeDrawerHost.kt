package com.arkdo.android.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.arkdo.android.ui.components.CategoryDrawer
import com.arkdo.android.ui.theme.ArkdoFixedColors
import com.arkdo.android.ui.theme.arkdoColors
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 侧边筛选抽屉的宿主。
 *
 * 必须挂在**底部标签栏之外**：抽屉要盖住标签栏和上下系统栏，
 * 而 RootTabScreen 的 Scaffold 会把内容区从标签栏和状态栏之间切出来，
 * 放在里面的抽屉永远够不到那几块。所以这里包住整个 RootTabScreen。
 *
 * 抽屉自身的上下系统栏留白在 CategoryDrawer 内部处理（对齐鸿蒙的
 * `padding({ top: sysTopInset })` + ConfirmBar `bottom: 12 + sysBottomInset`），
 * 这样抽屉的底色能铺满整屏，内容又不会被系统栏压住。
 */
@Composable
fun HomeDrawerHost(
    viewModel: HomeViewModel,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val colors = arkdoColors
    val coroutineScope = rememberCoroutineScope()

    val density = LocalDensity.current
    val drawerWidthPx = with(density) { HomeConstants.DRAWER_WIDTH.dp.toPx() }
    val edgeWidthPx = with(density) { HomeConstants.DRAWER_EDGE_WIDTH.dp.toPx() }
    // 0 = 完全在屏外，1 = 贴左边缘。跟手时直接 snapTo，松手再补完。
    val drawerProgress = remember { Animatable(0f) }

    LaunchedEffect(uiState.drawerOpen) {
        drawerProgress.animateTo(
            targetValue = if (uiState.drawerOpen) 1f else 0f,
            animationSpec = tween(220, easing = EaseOut)
        )
    }

    /** 松手补完：过阈值就补到底，否则回弹。 */
    fun settleDrawer() {
        val open = drawerProgress.value >= HomeConstants.DRAWER_COMMIT_RATIO
        if (open == uiState.drawerOpen) {
            // 状态没变，LaunchedEffect 不会触发，得自己收尾。
            coroutineScope.launch {
                drawerProgress.animateTo(
                    if (open) 1f else 0f,
                    tween(
                        if (open) HomeConstants.DRAWER_COMMIT_MS.toInt()
                        else HomeConstants.DRAWER_REBOUND_MS.toInt()
                    )
                )
            }
        }
        if (open) viewModel.openDrawer() else viewModel.closeDrawer()
    }

    BackHandler(enabled = uiState.drawerOpen) { viewModel.closeDrawer() }

    val progress = drawerProgress.value

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 边缘起手手势挂在内容的**祖先**上，不是覆盖层：
                // 子节点先拿到事件，列表照常滚动，只有确认是边缘横向右滑时才 consume 抢过来。
                .edgeSwipeToOpenDrawer(
                    enabled = progress == 0f,
                    edgeWidthPx = edgeWidthPx,
                    drawerWidthPx = drawerWidthPx,
                    onDrag = { fraction ->
                        coroutineScope.launch {
                            drawerProgress.snapTo((drawerProgress.value + fraction).coerceIn(0f, 1f))
                        }
                    },
                    onEnd = { settleDrawer() }
                )
        ) {
            content()
        }

        if (progress > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(progress)
                    .background(ArkdoFixedColors.Scrim)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { viewModel.closeDrawer() }
            )

            Box(
                modifier = Modifier
                    .width(HomeConstants.DRAWER_WIDTH.dp)
                    .fillMaxHeight()
                    .offset { IntOffset(((progress - 1f) * drawerWidthPx).toInt(), 0) }
                    .background(colors.pageBg)
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { delta ->
                            coroutineScope.launch {
                                drawerProgress.snapTo(
                                    (drawerProgress.value + delta / drawerWidthPx).coerceIn(0f, 1f)
                                )
                            }
                        },
                        onDragStopped = { settleDrawer() }
                    )
            ) {
                CategoryDrawer(
                    draft = uiState.draftQuery,
                    applied = uiState.listQuery,
                    categories = uiState.categories,
                    myCategoryIds = uiState.myCategoryIds,
                    myTags = uiState.myTags,
                    tagQuery = uiState.tagQuery,
                    tagResults = uiState.tagResults,
                    tagLoading = uiState.tagLoading,
                    isLoggedIn = uiState.isLoggedIn,
                    expandedIds = uiState.drawerExpandedIds,
                    categoriesLoading = uiState.categoriesLoading,
                    categoriesError = uiState.categoriesError,
                    onRetryCategories = { viewModel.retryLoadCategories() },
                    onDraftChange = { viewModel.updateDraft(it) },
                    onApply = { viewModel.applyDraft() },
                    onClose = { viewModel.closeDrawer() },
                    onReset = { viewModel.resetDraft() },
                    onToggleExpand = { viewModel.toggleDrawerExpand(it) },
                    onTagQueryChange = { viewModel.onTagQueryChanged(it) },
                    onTagTabShown = { viewModel.loadHotTagsIfNeeded() },
                    onRequireLogin = { viewModel.closeDrawer() }
                )
            }
        }
    }
}

/**
 * 左边缘右滑拉出抽屉。
 *
 * 必须挂在内容的**祖先**节点上，不能做成覆盖在上面的透明 Box ——
 * Compose 的命中测试只把指针交给最上层的兄弟节点，覆盖层会把落在该区域内的
 * 所有点击（包括顶栏的汉堡按钮）和滚动全部吞掉，即便它没有真的 consume 事件。
 *
 * 分工：
 *  · Initial pass 上只"旁听" down 事件，不消费，子节点照常收到；
 *  · 子节点已经消费了移动（比如卡片上可横滑的徽章行）→ 不抢；
 *  · 纵向先越过 touchSlop → 判定为列表滚动，彻底让开；
 *  · 横向向右越过 touchSlop 且起手点在边缘内 → 才 consume，把手势抢过来。
 *
 * 松手只按位移比例判定，不看甩动速度（鸿蒙侧还会看 800vp/s 的速度阈值）。
 */
private fun Modifier.edgeSwipeToOpenDrawer(
    enabled: Boolean,
    edgeWidthPx: Float,
    drawerWidthPx: Float,
    onDrag: (Float) -> Unit,
    onEnd: () -> Unit
): Modifier = if (!enabled) this else this.pointerInput(edgeWidthPx, drawerWidthPx) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (down.position.x > edgeWidthPx) return@awaitEachGesture

        var claimed = false
        var totalX = 0f
        var totalY = 0f

        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break

            val delta = change.positionChange()
            totalX += delta.x
            totalY += delta.y

            if (!claimed) {
                if (change.isConsumed) break
                if (abs(totalY) > viewConfiguration.touchSlop) break
                if (totalX > viewConfiguration.touchSlop) claimed = true
            }

            if (claimed) {
                change.consume()
                onDrag(delta.x / drawerWidthPx)
            }
        }

        if (claimed) onEnd()
    }
}
