package com.jetdo.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.jetdo.android.data.models.TopicCategory
import com.jetdo.android.data.models.TopicTag
import com.jetdo.android.ui.theme.jetdoColors
import com.jetdo.android.core.designsystem.FaIcon
import com.jetdo.android.core.designsystem.FaIconSet

// ==========================================================================
// 对齐鸿蒙版 TopicBadges.ets / TopicCategoryBadge.ets / TopicTagBadge.ets。
//
// 几处容易被"想当然"改错的地方，均按鸿蒙原样保留：
//  · 分类徽章没有背景、没有圆角、没有内边距，只是「小图元 + 灰字」，
//    不是实心药丸；也不使用 category.textColor，文字恒为 text_secondary。
//  · parentCategory 不单独渲染成徽章，它只是图标 / logo 取不到时的回退数据源。
//  · 分类与标签的右间距不同（8 / 7），所以不能用统一的 Arrangement.spacedBy。
//  · 按下反馈是整体 opacity 0.6，不是 Material 的 ripple。
// ==========================================================================

private val BADGE_ROW_HEIGHT = 20.dp
private val CATEGORY_BADGE_HEIGHT = 18.dp
private val BADGE_ICON_SIZE = 10.dp
private val BADGE_TEXT_SIZE = 14.sp

// Android 默认 includeFontPadding 会在文本框顶部留一块音标区，
// 字形视觉中心整体下坠 —— 和垂直居中的 10dp 小图标一比就是歪的
// （鸿蒙 ArkUI 没这块留白，所以鸿蒙看着是正的）。关掉它，让文本框
// 收紧到字形本身，Row 的 CenterVertically 才真的居中。
private val BADGE_TEXT_STYLE = TextStyle(
    platformStyle = PlatformTextStyle(includeFontPadding = false)
)

@Composable
fun TopicMetadataBadges(
    category: TopicCategory? = null,
    parentCategory: TopicCategory? = null,
    tags: List<TopicTag> = emptyList(),
    badgesTappable: Boolean = false,
    onCategoryClick: ((TopicCategory) -> Unit)? = null,
    onTagClick: ((TopicTag) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    TagIconRegistry.ensureTagIconsLoaded(context)

    val scrollState = rememberScrollState()

    Row(
        // 只有真的装不下才认领横向手势，否则会把列表的横向滑动截胡。
        // maxValue 在首次布局后才有值，enabled 随之自我修正。
        modifier = modifier
            .height(BADGE_ROW_HEIGHT)
            .horizontalScroll(scrollState, enabled = scrollState.maxValue > 0),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (category != null) {
            CategoryBadge(
                category = category,
                parentCategory = parentCategory,
                tappable = badgesTappable,
                onClick = onCategoryClick
            )
        }

        tags.forEach { tag ->
            TagBadge(
                tag = tag,
                tappable = badgesTappable,
                onClick = onTagClick
            )
        }
    }
}

/**
 * 分类徽章。前导图元四选一，优先级：FA 图标 → uploaded_logo → 受限锁 → 分类色圆点。
 * 注意圆点分支的右间距是 5，其余三个是 3。
 */
@Composable
private fun CategoryBadge(
    category: TopicCategory,
    parentCategory: TopicCategory?,
    tappable: Boolean,
    onClick: ((TopicCategory) -> Unit)?
) {
    val context = LocalContext.current
    val colors = jetdoColors
    val accent = TopicBadgeUtils.categoryColor(category)

    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    val iconId = TopicBadgeUtils.categoryIcon(context, category, parentCategory)
    val logo = TopicBadgeUtils.categoryLogo(category, parentCategory)
    val lockIconId = if (category.readRestricted == true && FaIconSet.has(context, "lock")) "lock" else null

    Row(
        modifier = Modifier
            .height(CATEGORY_BADGE_HEIGHT)
            .alpha(if (tappable && pressed) 0.6f else 1f)
            .then(
                if (tappable && onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null
                    ) { onClick(category) }
                } else {
                    Modifier
                }
            )
            .padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when {
            iconId != null ->
                FaIcon(iconId, accent, BADGE_ICON_SIZE, Modifier.padding(end = 3.dp))

            logo.isNotEmpty() ->
                AsyncImage(
                    model = TopicBadgeUtils.resolveBadgeAssetUrl(logo),
                    contentDescription = null,
                    modifier = Modifier
                        .padding(end = 3.dp)
                        .size(BADGE_ICON_SIZE),
                    contentScale = ContentScale.Fit
                )

            lockIconId != null ->
                FaIcon(lockIconId, accent, BADGE_ICON_SIZE, Modifier.padding(end = 3.dp))

            else ->
                Row(
                    modifier = Modifier
                        .padding(end = 5.dp)
                        .size(BADGE_ICON_SIZE)
                        .clip(CircleShape)
                        .background(accent)
                ) {}
        }

        Text(
            text = category.name,
            fontSize = BADGE_TEXT_SIZE,
            fontWeight = FontWeight.Normal,
            color = colors.textSecondary,
            style = BADGE_TEXT_STYLE
        )
    }
}

/**
 * 标签徽章。灰底小方块，圆角 2，左右内边距 4，靠固定高 20 定形（上下不留 padding）。
 */
@Composable
private fun TagBadge(
    tag: TopicTag,
    tappable: Boolean,
    onClick: ((TopicTag) -> Unit)?
) {
    val colors = jetdoColors
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    val iconId = TopicBadgeUtils.tagIconId(tag.name)
    val iconColor = TopicBadgeUtils.tagColor(tag.name)

    Row(
        modifier = Modifier
            .padding(end = 7.dp)
            .height(BADGE_ROW_HEIGHT)
            .alpha(if (tappable && pressed) 0.6f else 1f)
            .clip(RoundedCornerShape(2.dp))
            .background(colors.surfaceMuted)
            .then(
                if (tappable && onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null
                    ) { onClick(tag) }
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (iconId != null) {
            FaIcon(iconId, iconColor, BADGE_ICON_SIZE, Modifier.padding(end = 4.dp))
        }

        Text(
            text = tag.name,
            fontSize = BADGE_TEXT_SIZE,
            fontWeight = FontWeight.Normal,
            color = colors.textSecondary,
            style = BADGE_TEXT_STYLE
        )
    }
}
