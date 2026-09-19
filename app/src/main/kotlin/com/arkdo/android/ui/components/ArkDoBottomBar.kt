package com.arkdo.android.ui.components

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.unit.sp
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

// ==========================================================================
// 自定义底部标签栏(不使用 Material3 NavigationBar)。
//
// 形态是**悬浮液态玻璃胶囊**(实现原理对齐 RiseDiary / Kyant0 AndroidLiquidGlass,
// 引擎为同作者的 io.github.kyant0:backdrop):
//   1. 玻璃容器:drawBackdrop 对身后内容 vibrancy + blur + 边缘透镜折射,
//      表面只画 ~14% 的色调,玻璃感来自透出的内容;
//   2. 可见 tab 行画未选样式,但**选中槽位的图标不画**(避免与胶囊内图标重影),
//      角标照画;
//   3. 选中胶囊是一枚独立的"透镜"(lens + 色差 + 高光/阴影),滑动到哪个 tab,
//      胶囊内部就直接画出那个 tab 的选中态(实心 accent 图标 + accent 文字)。
//      RiseDiary 原版用"alpha=0 隐藏采样行 + combinedBackdrop 折射"实现同一效果,
//      采样捕获对层级时序敏感,这里改为直接绘制,观感一致且稳定。
// 页面内容侧由 RootTabScreen 标 layerBackdrop 提供 [backdrop]。
//
// 版本兼容(三档,对齐 RiseDiary 的降级策略并下探到本应用 minSdk 26):
//   - API 33+:完整液态玻璃(RuntimeShader 折射/色差/高光,库内自动启用);
//   - API 31/32:RenderEffect 可用,均匀模糊 + vibrancy,折射/高光由库降级
//     (RiseDiary 称"部分实体降级效果");
//   - API 26-30:无 RenderEffect,退回实体材质(高不透明度表面 + 描边 +
//     实色滑动胶囊),不引入 backdrop 采样。
// ==========================================================================

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

    // 玻璃参数(观感对齐 RiseDiary 的底栏)。
    /** 玻璃容器的模糊半径。 */
    val GlassBlurRadius = 28.dp

    /** 玻璃容器边缘透镜半径。 */
    val GlassLensRadius = 24.dp

    /** 选中透镜的反射/色差半径。 */
    val SelectionLensRadius = 10.dp
    val SelectionChromaticRadius = 14.dp

    /**
     * 选中胶囊宽度:固定尺寸、居中于槽位(RiseDiary 是整槽宽,但 4 tab 时槽宽近 96dp,
     * 胶囊拉成 1.76:1 的横条不像胶囊;77×54 ≈ doer 实测比例 1.43,观感更"胶囊")。
     */
    val CapsuleMaxWidth = 77.dp
}

/**
 * 底部标签栏容器:悬浮液态玻璃胶囊。
 *
 * [backdrop] 由调用方持有并标在页面内容上(`Modifier.layerBackdrop`),
 * 玻璃与选中透镜都从它取样身后滚过的内容。
 * [item] 按 (index, selected, iconVisible) 回调:玻璃路径的可见行以 selected=false、
 * 选中槽位 iconVisible=false 调用(图标在胶囊内画);兜底路径以真实选中态调用。
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

    // 选中胶囊的滑动动画(spring 回弹,RiseDiary 同款观感;拖拽形变依赖其 GPL
    // 代码未引入,这里只做点击切换的滑动)。
    val capsulePosition = remember { Animatable(selectedIndex.toFloat()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(selectedIndex) {
        capsulePosition.animateTo(
            selectedIndex.coerceIn(0, tabCount - 1).toFloat(),
            spring(dampingRatio = 1f, stiffness = 300f)
        )
    }

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
    // 玻璃表面色调:只画一层薄色,玻璃感主要来自透出的内容(RiseDiary 同参数量级)。
    val glassTint = if (isDark) Color(0xFF121212).copy(alpha = 0.45f) else Color(0xFFFAFAFA).copy(alpha = 0.45f)
    // 选中胶囊表面的轻压色。
    // 选中胶囊表面偏实:压住底下透过的内容与灰字,选中态才跳得出来
    val capsuleSurface = colors.surfaceCard.copy(alpha = 0.78f)

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

        // 1) 玻璃容器
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(BottomBarDefaults.ContainerHeight)
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
                    onDrawSurface = { drawRect(glassTint) }
                )
                // 白页上玻璃近乎透明,一条细描边保住栏形;深色下同样兜住边界。
                .border(1.dp, colors.border.copy(alpha = 0.5f), ContinuousCapsule)
        )

        // 2) 可见 tab 行:未选样式;选中槽位不画图标(胶囊内画),角标保留。
        //    高度必须钉死 62dp —— 用 fillMaxSize 会把 BoxWithConstraints 撑成全屏高,
        //    Scaffold 把整个全高节点当 bottomBar 贴底,容器就被顶到屏幕顶部去了。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(BottomBarDefaults.ContainerHeight)
                .padding(horizontal = BottomBarDefaults.ContentPadding)
        ) {
            repeat(tabCount) { index ->
                item(index, false, index != selectedIndex)
            }
        }

        // 3) 选中玻璃胶囊:lens 折射身后的页面内容,选中态直接画在胶囊内部,随胶囊滑动。
        //    胶囊可横向拖动换 tab:拖动时实时吸附经过的槽位(内容跟着切换),松手吸附最近 tab。
        //    注意必须 align(CenterStart):默认 TopStart 会让胶囊顶着容器上沿、内部内容
        //    与可见行错位 4dp(实测踩过,"胶囊位置偏上"就是这个)。
        val barScope = rememberCoroutineScope()
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .graphicsLayer {
                    translationX = (slotWidth * capsulePosition.value + (slotWidth - capsuleWidth) / 2).toPx()
                }
                .pointerInput(tabCount) {
                    detectDragGestures(
                        onDrag = { change, dragAmount ->
                            change.consume()
                            barScope.launch {
                                capsulePosition.snapTo(
                                    (capsulePosition.value + dragAmount.x / slotWidth.toPx())
                                        .coerceIn(0f, (tabCount - 1).toFloat())
                                )
                            }
                        },
                        onDragEnd = {
                            barScope.launch {
                                val target = capsulePosition.value.fastRoundToInt()
                                    .coerceIn(0, tabCount - 1)
                                capsulePosition.animateTo(target.toFloat(), spring(1f, 300f))
                                if (target != selectedIndex) onTabSelected(target)
                            }
                        },
                        onDragCancel = {
                            barScope.launch {
                                capsulePosition.animateTo(selectedIndex.toFloat(), spring(1f, 300f))
                            }
                        }
                    )
                }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { ContinuousCapsule },
                    effects = {
                        lens(
                            BottomBarDefaults.SelectionLensRadius.toPx(),
                            BottomBarDefaults.SelectionChromaticRadius.toPx(),
                            chromaticAberration = true
                        )
                    },
                    highlight = { Highlight.Default },
                    shadow = { Shadow(alpha = 0.35f) },
                    innerShadow = { InnerShadow(radius = 6.dp, alpha = 0.6f) },
                    onDrawSurface = { drawRect(capsuleSurface) }
                )
                .width(capsuleWidth)
                .height(BottomBarDefaults.CapsuleHeight)
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                item(
                    capsulePosition.value.fastRoundToInt().coerceIn(0, tabCount - 1),
                    true,
                    true
                )
            }
        }
    }
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
            .padding(vertical = 4.dp),
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
            overflow = TextOverflow.Ellipsis
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