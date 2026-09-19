package com.arkdo.android.ui.components

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.arkdo.android.ui.components.liquidglass.DampedDragAnimation
import com.arkdo.android.ui.components.liquidglass.InteractiveHighlight
import com.arkdo.android.ui.theme.ArkdoFixedColors
import com.arkdo.android.ui.theme.arkdoColors
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.capsule.ContinuousCapsule
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.launch

// ==========================================================================
// 自定义底部标签栏(不使用 Material3 NavigationBar)。
//
// 形态是**悬浮液态玻璃胶囊**,实现对齐 Kyant0/AndroidLiquidGlass 的
// catalog 组件 LiquidBottomTabs(commit 65ab177),引擎为同作者的
// io.github.kyant0:backdrop:
//   1. 玻璃容器:drawBackdrop 对身后内容 vibrancy + blur + 边缘透镜折射,
//      表面只画 40% 的色调,玻璃感来自透出的内容;按压时整栏放大 16dp,
//      并叠一层跟手指走的径向高光(InteractiveHighlight);
//   2. 可见 tab 行画未选样式,但**选中槽位的图标不画**(避免与胶囊内图标重影),
//      角标照画;
//   3. 选中胶囊**静止时是平的**(只有 10% 压色),按下才"长成"玻璃透镜:
//      折射/色差/高光/阴影/内阴影全部随 pressProgress 从 0 生长,松手回平。
//      拖动时胶囊按速度沿 X 拉伸、Y 压扁(果冻),整栏也跟手偏移 ≤4dp。
//      胶囊内部直接画当前槽位 tab 的选中态(实心 accent 图标 + accent 文字)。
//      上游用"alpha=0 隐藏采样行 + combinedBackdrop 折射"实现同一效果,
//      采样捕获对层级时序敏感,这里改为直接绘制,观感一致且稳定。
// 页面内容侧由 RootTabScreen 标 layerBackdrop 提供 [backdrop]。
//
// 版本兼容(三档,对齐上游的降级策略并下探到本应用 minSdk 26):
//   - API 33+:完整液态玻璃(RuntimeShader 折射/色差/高光,库内自动启用);
//   - API 31/32:RenderEffect 可用,均匀模糊 + vibrancy,折射/高光由库降级;
//   - API 26-30:无 RenderEffect,退回实体材质(高不透明度表面 + 描边 +
//     实色滑动胶囊),不引入 backdrop 采样。
// ==========================================================================

/**
 * 按压进度 → tab 内容缩放。玻璃路径 provide,兜底路径读默认 1。
 */
internal val LocalBottomTabPressScale = staticCompositionLocalOf { { 1f } }

/** 标签栏几何与动效常量,集中在此便于对照调整。 */
internal object BottomBarDefaults {
    /** 胶囊容器高度,装得下 54dp 选中胶囊 + 上下各 4dp。 */
    val ContainerHeight = 62.dp

    /** 容器距屏幕左右留白。 */
    val ContainerHorizontalMargin = 14.dp

    /** 容器距系统手势区上方的间隙。 */
    val ContainerBottomGap = 10.dp

    /** tab 行内容与容器边缘的内缩(选中胶囊与容器边缘的间隙)。 */
    val ContentPadding = 4.dp

    /** 选中胶囊高度 = 容器高 - 上下内缩。 */
    val CapsuleHeight = 54.dp

    /** 图标槽位:图标在其内居中,有没有角标都不会顶动文字基线。 */
    val IconSlotSize = 26.dp

    /** 普通标签图标绘制尺寸。 */
    val IconSize = 23.dp

    /** 「我的」头像绘制尺寸,比普通图标略小,给选中描边留出半径。 */
    val AvatarSize = 22.dp

    /** 选中图标连着放大一点,和未选拉开重量差(doer:18pt bold → 19pt heavy)。 */
    const val IconScaleSelected = 1f
    const val IconScaleUnselected = 0.93f

    val LabelFontSize = 10.sp

    /** 选中/未选切换动效时长,与首页新话题提示条同一量级。 */
    const val SwitchDurationMs = 180

    // 玻璃参数(数值对齐 AndroidLiquidGlass catalog 的 LiquidBottomTabs)。
    /** 玻璃容器的模糊半径。 */
    val GlassBlurRadius = 8.dp

    /** 玻璃容器边缘透镜半径。 */
    val GlassLensRadius = 24.dp

    /** 容器表面色调的不透明度。 */
    const val GlassTintAlpha = 0.4f

    /** 按压时整栏沿宽度方向放大的量。 */
    val PressGrow = 16.dp

    /** 按压时 tab 内容放大倍率。 */
    const val PressContentScale = 1.2f

    /** 拖动时整栏跟手偏移的上限。 */
    val PanelOffsetMax = 4.dp

    /** 选中透镜按压态的反射/色差半径(静止时为 0,随按压进度生长)。 */
    val SelectionLensRadius = 10.dp
    val SelectionChromaticRadius = 14.dp

    /** 选中透镜按压态的内阴影半径。 */
    val SelectionInnerShadowRadius = 8.dp

    /** 按压态胶囊放大倍率:78dp 按压高 / 56dp 静止高(上游 demo 数值)。 */
    const val CapsulePressedScale = 78f / 56f

    /**
     * 选中胶囊宽度:固定尺寸、居中于槽位(上游是整槽宽,但 4 tab 时槽宽近 96dp,
     * 胶囊拉成 1.76:1 的横条不像胶囊;77×54 ≈ doer 实测比例 1.43,观感更"胶囊")。
     */
    val CapsuleMaxWidth = 77.dp
}

/**
 * 胶囊层的最终缩放 = 按压缩放 × 速度果冻(X 随速度拉伸、Y 压扁,±20% 钳制)。
 * 上游 LiquidBottomTabs 的 layerBlock 同一算法;抽出来是为了胶囊内容能按同一值反向抵消。
 */
private fun DampedDragAnimation.jellyScaleX(): Float {
    val v = velocity / 10f
    return scaleX / (1f - (v * 0.75f).fastCoerceIn(-0.2f, 0.2f))
}

private fun DampedDragAnimation.jellyScaleY(): Float {
    val v = velocity / 10f
    return scaleY * (1f - (v * 0.25f).fastCoerceIn(-0.2f, 0.2f))
}

/**
 * 底部标签栏容器:悬浮液态玻璃胶囊。
 *
 * [backdrop] 由调用方持有并标在页面内容上(`Modifier.layerBackdrop`),
 * 玻璃与选中透镜都从它取样身后滚过的内容。
 * [item] 按 (index, selected, iconVisible) 回调:玻璃路径的可见行以 selected=false、
 * 胶囊所在槽位 iconVisible=false 调用(图标文字在胶囊内画);兜底路径以真实选中态调用。
 * 无障碍的选中态请用 [ArkDoBottomBarItem.semanticSelected] 单独表达。
 */
@Composable
fun ArkDoBottomBar(
    tabCount: Int,
    selectedIndex: Int,
    backdrop: Backdrop,
    onTabSelected: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
    item: @Composable RowScope.(index: Int, selected: Boolean, iconVisible: Boolean) -> Unit
) {
    val colors = arkdoColors
    val useGlass = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    if (!useGlass) {
        // ---- API 26-30 兜底:无 RenderEffect,实体材质 + 实色滑动胶囊 ----
        SolidFallbackBottomBar(
            tabCount = tabCount,
            selectedIndex = selectedIndex,
            modifier = modifier,
            item = item
        )
        return
    }

    val isDark = colors.pageBg.luminance() < 0.5f
    // 玻璃表面色调:只画一层薄色,玻璃感主要来自透出的内容(上游同参数)。
    val glassTint = (if (isDark) Color(0xFF121212) else Color(0xFFFAFAFA))
        .copy(alpha = BottomBarDefaults.GlassTintAlpha)
    // 选中胶囊静止态的压色:平的一层 10%,按下时淡出、换成透镜本身的高光/阴影。
    val capsuleRestTint = if (isDark) Color.White.copy(alpha = 0.1f) else Color.Black.copy(alpha = 0.1f)

    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // 整栏跟手偏移:拖动时容器/可见行/胶囊一起随手指位移(EaseOut,≤4dp),松手弹回。
    val panelOffsetAnim = remember { Animatable(0f) }

    // 选中胶囊的拖拽/按压状态机(上游 DampedDragAnimation):槽位值、归一化速度、
    // 按压进度、X/Y 欠阻尼缩放。手势不用它自带的 modifier(那是挂在整槽宽胶囊上的),
    // 而是由下面容器层的 detectDragGestures 驱动,见那里的说明。
    val capsule = remember(scope) {
        DampedDragAnimation(
            animationScope = scope,
            initialValue = selectedIndex.toFloat(),
            valueRange = 0f..(tabCount - 1).toFloat(),
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = BottomBarDefaults.CapsulePressedScale,
            onDragStarted = {},
            onDragStopped = {},
            onDrag = { _, _ -> }
        )
    }
    // 以 selectedIndex 作 key:它是普通 Int 参数(按值捕获),不是 State ——
    // 用 snapshotFlow { selectedIndex } 只会读到首次组合的常量,点击切页时永不发射
    // (实测:拖拽正常、点击后胶囊不跟随)。改 key 后每次外部选中变化都补一段滑动动画;
    // 拖拽路径已自己 animateToValue,这里用目标值判等避免打架。
    LaunchedEffect(selectedIndex) {
        if (capsule.targetValue.fastRoundToInt() != selectedIndex) {
            capsule.animateToValue(selectedIndex.toFloat())
        }
    }

    // 按压时 tab 内容放大 1.2 倍(上游同参数)。
    CompositionLocalProvider(
        LocalBottomTabPressScale provides {
            lerp(1f, BottomBarDefaults.PressContentScale, capsule.pressProgress)
        }
    ) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            // 不铺通栏底色:四周留白透出页面,Scaffold 侧用 pageBg 兜住这块区域。
            // 手势区仍由这层垫掉,保证胶囊浮在手势条上方而不是被顶到屏幕外。
            .navigationBarsPadding()
            .padding(horizontal = BottomBarDefaults.ContainerHorizontalMargin)
            .padding(bottom = BottomBarDefaults.ContainerBottomGap)
    ) {
        val slotWidth = maxWidth / tabCount
        val capsuleWidth = minOf(BottomBarDefaults.CapsuleMaxWidth, slotWidth - BottomBarDefaults.ContentPadding * 2)
        val containerWidthPx = with(density) { maxWidth.toPx() }

        // 胶囊当前压着的槽位:可见行在这个槽位不画图标、胶囊内画它的选中态。
        // 跟胶囊位置而不是 selectedIndex 走,拖离原槽位时原图标才会立刻回填。
        // derivedStateOf 把逐帧的 value 收敛成槽位变化,避免拖动时每帧重组整行。
        val capsuleSlot by remember(tabCount) {
            derivedStateOf { capsule.value.fastRoundToInt().fastCoerceIn(0, tabCount - 1) }
        }

        // 拖动位移 → 整栏偏移:按容器宽归一化后 EaseOut,封顶 PanelOffsetMax(上游同算法)。
        val panelOffset by remember(density, containerWidthPx) {
            derivedStateOf {
                val fraction = (panelOffsetAnim.value / containerWidthPx).fastCoerceIn(-1f, 1f)
                with(density) {
                    BottomBarDefaults.PanelOffsetMax.toPx() * fraction.sign * EaseOut.transform(abs(fraction))
                }
            }
        }

        // 按压高光:光斑中心钉在胶囊中心(而不是手指落点),随胶囊一起滑。
        val highlight = remember(scope, tabCount) {
            InteractiveHighlight(
                animationScope = scope,
                position = { size, _ ->
                    Offset(
                        size.width / tabCount * (capsule.value + 0.5f) + panelOffset,
                        size.height / 2f
                    )
                }
            )
        }

        // 手势宿主:三层(容器 / 可见行 / 胶囊)的共同父节点。
        // 拖拽手势必须挂在父节点上:胶囊只有 77dp 宽,手势挂它自身会让"槽位边缘起拖"
        // 变成无效点击(实测踩过:落到新 tab 后再拖没反应);挂在容器层也不行 ——
        // 容器与 tab 行是兄弟,Compose 命中上层兄弟后不再测试下层兄弟。
        // 挂父节点后,槽内任意位置都能起拖;纯点击(未过 slop)仍落到 tab 的 onClick。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(BottomBarDefaults.ContainerHeight)
                .pointerInput(tabCount) {
                    // 槽位宽度直接由节点实测尺寸算(pointerInput 链上拿不到内容层的 slotWidth)。
                    val slotWidthPx = size.width.toFloat() / tabCount
                    detectDragGestures(
                        onDragStart = { capsule.press() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            capsule.updateValue(capsule.targetValue + dragAmount.x / slotWidthPx)
                            scope.launch { panelOffsetAnim.snapTo(panelOffsetAnim.value + dragAmount.x) }
                        },
                        onDragEnd = {
                            val target = capsule.targetValue.fastRoundToInt().fastCoerceIn(0, tabCount - 1)
                            if (target != selectedIndex) onTabSelected(target)
                            capsule.animateToValue(target.toFloat())
                            scope.launch { panelOffsetAnim.animateTo(0f, spring(1f, 300f, 0.5f)) }
                        },
                        onDragCancel = {
                            capsule.animateToValue(selectedIndex.toFloat())
                            scope.launch { panelOffsetAnim.animateTo(0f, spring(1f, 300f, 0.5f)) }
                        }
                    )
                }
                // 触底即按压放大(上游 press-on-down),松手回缩。
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        capsule.press()
                        while (true) {
                            val e = awaitPointerEvent()
                            if (e.changes.all { !it.pressed }) break
                        }
                        capsule.release()
                    }
                }
                // 高光的按压/位置跟踪(Initial pass 截获,不消费事件,不影响点击与拖拽)。
                .then(highlight.gestureModifier)
        ) {

        // 1) 玻璃容器
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(BottomBarDefaults.ContainerHeight)
                .graphicsLayer { translationX = panelOffset }
                .shadow(
                    elevation = 8.dp,
                    shape = ContinuousCapsule,
                    ambientColor = ArkdoFixedColors.ShadowSoft,
                    spotColor = ArkdoFixedColors.ShadowSoft
                )
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { ContinuousCapsule },
                    effects = {
                        vibrancy()
                        blur(BottomBarDefaults.GlassBlurRadius.toPx())
                        lens(
                            BottomBarDefaults.GlassLensRadius.toPx(),
                            BottomBarDefaults.GlassLensRadius.toPx()
                        )
                    },
                    // 按压时整条玻璃沿宽度方向放大 16dp(上游 layerBlock)。
                    layerBlock = {
                        val scale = lerp(
                            1f,
                            1f + BottomBarDefaults.PressGrow.toPx() / size.width,
                            capsule.pressProgress
                        )
                        scaleX = scale
                        scaleY = scale
                    },
                    onDrawSurface = { drawRect(glassTint) }
                )
                // 按压高光画在玻璃表面之上、tab 内容之下(BlendMode.Plus 提亮)。
                .then(highlight.modifier)
                // 白页上玻璃近乎透明,一条细描边保住栏形;深色下同样兜住边界。
                .border(1.dp, colors.border.copy(alpha = 0.5f), ContinuousCapsule)
        )

        // 2) 可见 tab 行:未选样式;胶囊压着的槽位不画图标与文字(胶囊内画),角标保留。
        //    高度必须钉死 62dp —— 用 fillMaxSize 会把 BoxWithConstraints 撑成全屏高,
        //    Scaffold 把整个全高节点当 bottomBar 贴底,容器就被顶到屏幕顶部去了。
        //    两行(可见行 / 胶囊内)都垂直居中:tab 列本身 wrap 高,各自以容器中线为准
        //    才能严丝合缝;顶对齐时胶囊内容会比可见行低 4dp(胶囊比容器矮的那 4dp)。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(BottomBarDefaults.ContainerHeight)
                .graphicsLayer { translationX = panelOffset }
                .padding(horizontal = BottomBarDefaults.ContentPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(tabCount) { index ->
                item(index, false, index != capsuleSlot)
            }
        }

        // 3) 选中玻璃胶囊:静止时是平的压色,按下才生长成透镜(折射/色差/高光/阴影/
        //    内阴影全部 × pressProgress),选中态直接画在胶囊内部,随胶囊滑动。
        //    胶囊可横向拖动换 tab:拖动时实时吸附经过的槽位(内容跟着切换),松手吸附最近 tab。
        //    注意必须 align(CenterStart):默认 TopStart 会让胶囊顶着容器上沿、内部内容
        //    与可见行错位 4dp(实测踩过,"胶囊位置偏上"就是这个)。
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .graphicsLayer {
                    translationX =
                        (slotWidth * capsule.value + (slotWidth - capsuleWidth) / 2).toPx() + panelOffset
                }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { ContinuousCapsule },
                    effects = {
                        val progress = capsule.pressProgress
                        // 与容器同一套磨砂:上游胶囊静止时不模糊(照片背景下是"清窗"),
                        // 但本应用页面是密集图文,清窗会把头像/标题原样透进胶囊压住 accent 图标。
                        vibrancy()
                        blur(BottomBarDefaults.GlassBlurRadius.toPx())
                        lens(
                            BottomBarDefaults.SelectionLensRadius.toPx() * progress,
                            BottomBarDefaults.SelectionChromaticRadius.toPx() * progress,
                            chromaticAberration = true
                        )
                    },
                    highlight = { Highlight.Default.copy(alpha = capsule.pressProgress) },
                    shadow = { Shadow(alpha = capsule.pressProgress) },
                    innerShadow = {
                        val progress = capsule.pressProgress
                        InnerShadow(
                            radius = BottomBarDefaults.SelectionInnerShadowRadius * progress,
                            alpha = progress
                        )
                    },
                    // 按压放大 + 速度果冻:X 随速度拉伸、Y 压扁(±20% 钳制)。
                    layerBlock = {
                        scaleX = capsule.jellyScaleX()
                        scaleY = capsule.jellyScaleY()
                    },
                    onDrawSurface = {
                        val progress = capsule.pressProgress
                        // 静止压色随按压淡出,换成一层极淡的暗调让透镜有"体"。
                        drawRect(capsuleRestTint, alpha = 1f - progress)
                        drawRect(Color.Black.copy(alpha = 0.03f * progress))
                    }
                )
                .width(capsuleWidth)
                .height(BottomBarDefaults.CapsuleHeight)
        ) {
            // 胶囊内容反向抵消 layerBlock 的放大/果冻:上游胶囊里没有子内容(靠折射隐藏行
            // 显示),玻璃怎么变形都不影响内容;这里内容直接画在胶囊里,不抵消会被连带
            // 放大到 1.39×1.2 撑出胶囊。抵消后内容只保留 tab 自身的 1.2 倍按压放大。
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1f / capsule.jellyScaleX()
                        scaleY = 1f / capsule.jellyScaleY()
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                item(capsuleSlot, true, true)
            }
        }
        } // 手势宿主
    }
    } // CompositionLocalProvider
}

/**
 * API 26-30 的实体材质兜底:无 RenderEffect 时退回"实色容器 + 实色滑动胶囊",
 * 渲染语义与玻璃版一致(可见行即真实选中态),只是没有玻璃取样。
 */
@Composable
private fun SolidFallbackBottomBar(
    tabCount: Int,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    item: @Composable RowScope.(index: Int, selected: Boolean, iconVisible: Boolean) -> Unit
) {
    val colors = arkdoColors
    val capsuleShape = RoundedCornerShape(percent = 50)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = BottomBarDefaults.ContainerHorizontalMargin)
            .padding(bottom = BottomBarDefaults.ContainerBottomGap)
    ) {
        val slotWidth = maxWidth / tabCount
        val capsuleWidth = minOf(77.dp, slotWidth - BottomBarDefaults.ContentPadding * 2)
        val capsuleX by animateDpAsState(
            targetValue = slotWidth * selectedIndex.coerceIn(0, tabCount - 1) + (slotWidth - capsuleWidth) / 2,
            animationSpec = spring(dampingRatio = 1f, stiffness = 300f),
            label = "bottomBarCapsuleX"
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(BottomBarDefaults.ContainerHeight)
                .shadow(
                    elevation = 8.dp,
                    shape = capsuleShape,
                    ambientColor = ArkdoFixedColors.ShadowSoft,
                    spotColor = ArkdoFixedColors.ShadowSoft
                )
                .clip(capsuleShape)
                .background(colors.surfaceCard.copy(alpha = 0.92f))
                .border(1.dp, colors.border, capsuleShape)
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = capsuleX)
                    .width(capsuleWidth)
                    .height(BottomBarDefaults.CapsuleHeight)
                    .clip(capsuleShape)
                    .background(colors.surfaceMuted)
            )

            Row(modifier = Modifier.fillMaxSize()) {
                repeat(tabCount) { index ->
                    item(index, index == selectedIndex, true)
                }
            }
        }
    }
}

/**
 * 单个标签:图标在上、文字在下。
 *
 * [selected] 驱动视觉(玻璃路径下可见行恒为 false,选中态由透镜呈现);
 * [semanticSelected] 驱动无障碍的选中语义,与视觉解耦。
 * [iconVisible] 为 false 时图标与文字都不画(只留角标),玻璃路径用它隐藏被胶囊压住的槽位。
 * [icon] 收到的是**已按选中态动画好的颜色**,调用方直接拿去染色即可。
 */
@Composable
fun RowScope.ArkDoBottomBarItem(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    semanticSelected: Boolean = selected,
    badgeCount: Int = 0,
    iconVisible: Boolean = true,
    icon: (@Composable (tint: Color) -> Unit)? = null
) {
    val colors = arkdoColors
    val accent = colors.ldAccent
    val idle = colors.textSecondary
    // 组合期只取出 lambda,真正读值放到 graphicsLayer 里:按压动画逐帧变化,
    // 在组合期调用会让每个 tab 每帧重组;在 layer 里读只触发重绘。
    val pressScale = LocalBottomTabPressScale.current

    val iconColor by animateColorAsState(
        targetValue = if (selected) accent else idle,
        animationSpec = tween(BottomBarDefaults.SwitchDurationMs),
        label = "bottomBarIconColor"
    )
    val labelColor by animateColorAsState(
        targetValue = if (selected) accent else idle,
        animationSpec = tween(BottomBarDefaults.SwitchDurationMs),
        label = "bottomBarLabelColor"
    )
    val iconScale by animateFloatAsState(
        targetValue = if (selected) {
            BottomBarDefaults.IconScaleSelected
        } else {
            BottomBarDefaults.IconScaleUnselected
        },
        animationSpec = tween(BottomBarDefaults.SwitchDurationMs),
        label = "bottomBarIconScale"
    )

    val interactionSource = remember { MutableInteractionSource() }

    Column(
        modifier = modifier
            .weight(1f)
            .defaultMinSize(minHeight = 48.dp)
            .selectable(
                selected = semanticSelected,
                role = Role.Tab,
                interactionSource = interactionSource,
                // 不加波纹:玻璃/胶囊材质上叠圆形 ripple 会破坏形制。
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 4.dp)
            // 按压时整个 tab(图标+文字)一起放大,与上游 LiquidBottomTab 一致。
            .graphicsLayer {
                val s = pressScale()
                scaleX = s
                scaleY = s
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier.size(BottomBarDefaults.IconSlotSize),
                contentAlignment = Alignment.Center
            ) {
                if (iconVisible) {
                    Box(
                        modifier = Modifier.graphicsLayer {
                            scaleX = iconScale
                            scaleY = iconScale
                        },
                        contentAlignment = Alignment.Center
                    ) {
                        icon(iconColor)
                    }
                }

                if (badgeCount > 0) {
                    UnreadBadge(
                        count = badgeCount,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 8.dp, y = (-4).dp)
                    )
                }
            }
        }

        Text(
            text = label,
            fontSize = BottomBarDefaults.LabelFontSize,
            // 选中加粗但不换字号:改字号会改变文字宽度,相邻标签跟着左右抖。
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // 被胶囊压住时文字也隐掉(只透明、不移除,布局不变):玻璃胶囊只有 10% 压色,
            // 取样层透明的页面(如通知页)上盖不住底下的灰字,会出现双份标签。
            modifier = Modifier.graphicsLayer { alpha = if (iconVisible) 1f else 0f }
        )
    }
}

/**
 * 未读角标。超过 99 收成 99+,避免宽度把图标挤变形。
 */
@Composable
private fun UnreadBadge(count: Int, modifier: Modifier = Modifier) {
    val text = bottomBarBadgeText(count) ?: return

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
            .clip(CircleShape)
            .background(ArkdoFixedColors.BadgeRed)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            color = ArkdoFixedColors.OnDarkFg,
            maxLines = 1
        )
    }
}

/**
 * 角标文案。返回 null 表示不画角标(未读数 <= 0,或计数缺失)。
 * 抽成纯函数是为了能在 JVM 上直接测边界,不必起 Compose 环境。
 */
internal fun bottomBarBadgeText(count: Int): String? = when {
    count <= 0 -> null
    count > 99 -> "99+"
    else -> count.toString()
}

/**
 * 普通标签图标。选中用实心、未选用线框,颜色由调用方传进来。
 */
@Composable
fun TabVectorIcon(selected: Boolean, tint: Color, selectedIcon: ImageVector, unselectedIcon: ImageVector) {
    Icon(
        imageVector = if (selected) selectedIcon else unselectedIcon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(BottomBarDefaults.IconSize)
    )
}

/**
 * 头像版标签图标:「我的」用它,登录后显示真实头像,未登录退回人形图标。
 *
 * 描边跟随选中态变主题色(对齐 doer 的 avatarImage:选中加粗 accent 环、未选细灰环),
 * 而不是给整个头像染色 —— 头像染色会把它糊成一块单色块。
 */
@Composable
fun AvatarTabIcon(
    selected: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
    fallbackIcon: ImageVector? = null,
    avatar: (@Composable (Dp) -> Unit)? = null
) {
    val colors = arkdoColors
    val ringWidth by animateDpAsState(
        targetValue = if (selected) 2.dp else 1.dp,
        animationSpec = tween(BottomBarDefaults.SwitchDurationMs),
        label = "bottomBarAvatarRingWidth"
    )

    Box(
        modifier = modifier.size(BottomBarDefaults.AvatarSize),
        contentAlignment = Alignment.Center
    ) {
        when {
            avatar != null -> Box(
                modifier = Modifier
                    .size(BottomBarDefaults.AvatarSize)
                    .clip(CircleShape)
                    .border(width = ringWidth, color = tint, shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                avatar(BottomBarDefaults.AvatarSize)
            }

            fallbackIcon != null -> Icon(
                imageVector = fallbackIcon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(BottomBarDefaults.IconSize)
            )

            else -> Box(
                modifier = Modifier
                    .size(BottomBarDefaults.AvatarSize)
                    .clip(CircleShape)
                    .background(colors.surfaceMuted)
            )
        }
    }
}