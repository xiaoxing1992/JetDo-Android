package com.arkdo.android.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import com.arkdo.android.ui.theme.ArkdoFixedColors
import com.arkdo.android.ui.theme.arkdoColors

// ==========================================================================
// 自定义底部标签栏(不使用 Material3 NavigationBar)。
//
// 形态是**悬浮胶囊容器**:左右留边、两端全圆、描边 + 软阴影,悬浮在手势区上方;
// 选中项外面套一枚固定尺寸的胶囊高亮(几何对齐 doer 截图实测:77×54、W/h≈1.43、
// 两端全圆,包住图标+文字,切换时平移过去)。
//
// 两处和 doer 的对应关系,免得再翻一遍:
// - 选中胶囊:doer 截图实测 #E2E2E2 on #F5F5F6,但**源码里没有画它的代码** —— 那是
//   iOS 26 系统 Liquid Glass UITabBar 自带的,所以必须自己画;
// - 外层容器:doer 是通栏贴底直角条(tabBarFrame 用 view.bounds.width,截图底色区
//   全宽到屏幕底),悬浮胶囊容器是我们自己的设计,别在 doer 源码里找出处。
//
// 之所以自己写而不是配 NavigationBar:NavigationBarItem 的固定 80dp 容器高度、内置
// ripple 和指示器形状都靠 indeterminate 的 token 覆盖去改,M3 换个版本就可能走样。
// ==========================================================================

/** 标签栏几何与动效常量,集中在此便于对照调整。 */
internal object BottomBarDefaults {
    /** 胶囊容器高度,装得下 54dp 选中胶囊 + 上下各 4dp。 */
    val ContainerHeight = 62.dp

    /** 容器距屏幕左右留白。 */
    val ContainerHorizontalMargin = 14.dp

    /** 容器距系统手势区上方的间隙。 */
    val ContainerBottomGap = 10.dp

    /** 选中胶囊高度,对齐 doer 实测 53.7pt。 */
    val CapsuleHeight = 54.dp

    /**
     * 选中胶囊最大宽度。doer 实测 76.7×53.7pt(W/h≈1.43),是**固定尺寸**、不随槽位宽度变
     * —— iOS 26 系统胶囊就是这样。若不设上限跟着槽位撑满,4 个 tab 时槽位宽,
     * 胶囊会被拉成 1.76:1 的胖横条,看着是圆角矩形而不是胶囊(实测踩过)。
     */
    val CapsuleMaxWidth = 77.dp

    /** 胶囊相对槽位的左右内缩;doer 的胶囊略宽于槽位,这里收 4dp 避免相邻相碰。 */
    val CapsuleInset = 4.dp

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
}

/**
 * 底部标签栏容器:悬浮玻璃胶囊。
 *
 * 外壳 = Haze 玻璃材质(对身后内容高斯模糊 + 半透明底色调子)+ 全圆描边 + 软阴影,
 * 悬浮在手势区上方;选中胶囊也在这里 —— 选中胶囊是**一枚**、按 [selectedIndex] 平移,
 * 而不是每个标签各画一块,这样切换时它才会滑过去。
 * [hazeState] 由调用方持有并标在页面内容上([androidx.compose.runtime.Composable] 层
 * `hazeSource`),玻璃才能取样到从栏下滚过的内容。
 * [item] 按 index 回调,调用方决定每个标签画什么。
 */
@Composable
fun ArkDoBottomBar(
    tabCount: Int,
    selectedIndex: Int,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    item: @Composable RowScope.(Int) -> Unit
) {
    val colors = arkdoColors
    val capsuleShape = RoundedCornerShape(percent = 50)

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
        // 胶囊固定尺寸、在选中槽位内居中(对齐 doer:胶囊大小不随槽位/标签数变)。
        val capsuleWidth = minOf(BottomBarDefaults.CapsuleMaxWidth, slotWidth - BottomBarDefaults.CapsuleInset * 2)
        // 选中胶囊横向位置:切换 tab 时跟着平移。doer 系统胶囊就是这个行为。
        val capsuleX by animateDpAsState(
            targetValue = slotWidth * selectedIndex.coerceIn(0, tabCount - 1) + (slotWidth - capsuleWidth) / 2,
            animationSpec = tween(BottomBarDefaults.SwitchDurationMs),
            label = "bottomBarCapsuleX"
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(BottomBarDefaults.ContainerHeight)
                // 阴影让胶囊从页面上"托起";ambient/spot 色 aligned 项目里 FAB 的软阴影。
                .shadow(
                    elevation = 8.dp,
                    shape = capsuleShape,
                    ambientColor = ArkdoFixedColors.ShadowSoft,
                    spotColor = ArkdoFixedColors.ShadowSoft
                )
                .clip(capsuleShape)
                .border(1.dp, colors.border, capsuleShape)
        ) {
            // 玻璃材质层:对身后滚过的内容做高斯模糊(Haze 从 hazeSource 取样)。
            // 半径对齐 TopicDetailScreen 顶栏(24dp,API 31 上 Haze 1.6.1 只支持均匀模糊,
            // 渐进模糊要 AGSL/API 33+,见那边注释);clip 已由外层 capsuleShape 完成,
            // 防止 RenderEffect 模糊输出溢出栏体。
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .hazeEffect(state = hazeState) {
                        blurRadius = 24.dp
                        noiseFactor = 0f
                    }
            )
            // 玻璃调子:半透明 surfaceCard 压一压透出的内容,保证图标/文字可读;
            // API<31 上 Haze 不模糊时,这一层就是兜底的可读底色。
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(colors.surfaceCard.copy(alpha = 0.6f))
            )

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
                    item(index)
                }
            }
        }
    }
}

/**
 * 单个标签:图标在上、文字在下,选中只改颜色与字重;胶囊背景由容器统一画,这里不再画。
 *
 * [icon] 收到的是**已按选中态动画好的颜色**,调用方直接拿去染色即可,不用自己再套一层动画
 * ——否则图标和文字会各自用一条时间线,切换时看着是两拍。
 */
@Composable
fun RowScope.ArkDoBottomBarItem(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badgeCount: Int = 0,
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
                selected = selected,
                role = Role.Tab,
                interactionSource = interactionSource,
                // 不加波纹:M3 的圆形 ripple 会盖在胶囊上面,和这套静态视觉冲突。
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
                Box(
                    modifier = Modifier.graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                    },
                    contentAlignment = Alignment.Center
                ) {
                    icon(iconColor)
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