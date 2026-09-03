package com.arkdo.android.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.arkdo.android.data.models.FeedKey
import com.arkdo.android.data.models.TopicCategory
import com.arkdo.android.data.models.TopicTag
import com.arkdo.android.services.site.CategoryIndex
import com.arkdo.android.services.site.FeedPrefs
import com.arkdo.android.services.topic.TagSuggestion
import com.arkdo.android.services.topic.TopicListQuery
import com.arkdo.android.services.topic.describeTopicListQuery
import com.arkdo.android.services.topic.filterRequiresLogin
import com.arkdo.android.services.topic.isDefault
import com.arkdo.android.services.topic.sameTopicListQuery
import com.arkdo.android.ui.theme.arkdoColors

// ==========================================================================
// 对齐鸿蒙版 views/components/CategoryDrawer.ets。
//
// 这是个**完全受控**组件：draft / applied / expandedIds 全部由宿主持有。
// 原因是鸿蒙侧抽屉内容随开合挂载卸载，状态留在组件内会被一起丢掉；
// Compose 这边条件组合的行为一样，所以照搬这个分工。
//
// 只有 activeTab 与两个手风琴分组是内部状态 —— 它们本来就该随关闭复位。
// ==========================================================================

private val ROW_HEIGHT = 48.dp
private const val EXPAND_MS = 180
private const val COLLAPSE_MS = 140
private const val TAB_INDICATOR_MS = 180

private const val TAB_CATEGORY = 0
private const val TAB_TAG = 1

@Composable
fun CategoryDrawer(
    draft: TopicListQuery,
    applied: TopicListQuery,
    categories: List<TopicCategory>,
    myCategoryIds: List<Long>,
    myTags: List<TopicTag>,
    tagQuery: String,
    tagResults: List<TagSuggestion>,
    tagLoading: Boolean,
    isLoggedIn: Boolean,
    expandedIds: List<Long>,
    categoriesLoading: Boolean = false,
    categoriesError: String? = null,
    onRetryCategories: () -> Unit = {},
    onDraftChange: (TopicListQuery) -> Unit,
    onApply: () -> Unit,
    onClose: () -> Unit,
    onReset: () -> Unit,
    onToggleExpand: (Long) -> Unit,
    onTagQueryChange: (String) -> Unit,
    onTagTabShown: () -> Unit,
    onRequireLogin: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = arkdoColors
    var activeTab by remember { mutableIntStateOf(TAB_CATEGORY) }

    // 抽屉铺满整屏（含系统栏），底色因此能盖住状态栏与导航栏；
    // 内容再各自让开这两块，对齐鸿蒙的 padding({ top: sysTopInset }) 与
    // ConfirmBar 的 bottom: 12 + sysBottomInset。
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.pageBg)
            .padding(top = topInset)
    ) {
        DrawerHeader(
            showReset = !draft.isDefault(),
            onReset = onReset,
            onClose = onClose
        )

        FilterSection(
            draft = draft,
            isLoggedIn = isLoggedIn,
            onPick = { onDraftChange(draft.copy(filter = it)) },
            onBlocked = onRequireLogin
        )

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            thickness = 0.5.dp,
            color = colors.border
        )

        ScopeTabBar(
            activeTab = activeTab,
            categoryPicked = draft.category != null,
            tagPicked = draft.tag != null,
            onSelect = { activeTab = it }
        )

        LaunchedEffect(activeTab) {
            if (activeTab == TAB_TAG) onTagTabShown()
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            if (activeTab == TAB_CATEGORY) {
                CategoryTab(
                    draft = draft,
                    categories = categories,
                    myCategoryIds = myCategoryIds,
                    isLoggedIn = isLoggedIn,
                    expandedIds = expandedIds,
                    loading = categoriesLoading,
                    error = categoriesError,
                    onRetry = onRetryCategories,
                    onPick = { onDraftChange(draft.copy(category = it)) },
                    onToggleExpand = onToggleExpand,
                    onRequireLogin = onRequireLogin
                )
            } else {
                TagTab(
                    draft = draft,
                    myTags = myTags,
                    tagQuery = tagQuery,
                    tagResults = tagResults,
                    tagLoading = tagLoading,
                    onQueryChange = onTagQueryChange,
                    onPick = { onDraftChange(draft.copy(tag = it)) }
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        ConfirmBar(
            draft = draft,
            isDirty = !sameTopicListQuery(draft, applied),
            bottomInset = bottomInset,
            onApply = onApply,
            onClose = onClose
        )
    }
}

// ========== 头部 ==========

@Composable
private fun DrawerHeader(
    showReset: Boolean,
    onReset: () -> Unit,
    onClose: () -> Unit
) {
    val colors = arkdoColors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "筛选",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f)
        )

        // 草稿回到全默认时这个入口整个消失，不占位。
        if (showReset) {
            Text(
                text = "重置",
                fontSize = 13.sp,
                color = colors.brand,
                modifier = Modifier
                    .clickable(onClick = onReset)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }

        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center
        ) {
            FaIcon("xmark", colors.textSecondary, 15.dp)
        }
    }
}

// ========== 排序 chip 组 ==========

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterSection(
    draft: TopicListQuery,
    isLoggedIn: Boolean,
    onPick: (String) -> Unit,
    onBlocked: () -> Unit
) {
    // 顺序固定：最新 → 最新发布 → 热门 → 排行榜 → 未读 → 未读新帖
    val feeds = remember { listOf(FeedPrefs.latestFeed()) + FeedPrefs.optionalFeeds() }

    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 2.dp)
    ) {
        feeds.forEach { feed ->
            ChoiceChip(
                label = feed.label,
                selected = draft.filter == feed.key,
                // 未登录时 unread/new 置灰但仍可点，点了给提示。
                chipDisabled = filterRequiresLogin(feed.key) && !isLoggedIn,
                onTap = { onPick(feed.key) },
                onBlockedTap = onBlocked
            )
        }
    }
}

// ========== 版块 / 标签 页签 ==========

@Composable
private fun ScopeTabBar(
    activeTab: Int,
    categoryPicked: Boolean,
    tagPicked: Boolean,
    onSelect: (Int) -> Unit
) {
    Row(modifier = Modifier.padding(horizontal = 16.dp)) {
        ScopeTab(
            label = "版块",
            active = activeTab == TAB_CATEGORY,
            showDot = categoryPicked,
            onClick = { onSelect(TAB_CATEGORY) },
            modifier = Modifier.weight(1f)
        )
        ScopeTab(
            label = "标签",
            active = activeTab == TAB_TAG,
            showDot = tagPicked,
            onClick = { onSelect(TAB_TAG) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ScopeTab(
    label: String,
    active: Boolean,
    showDot: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = arkdoColors

    // 两个 tab 各有一条恒占位的指示条，切换时原地"长出来/缩回去"，
    // 不是一条滑块在左右平移 —— 别改成位移动画。
    val indicatorAlpha by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(TAB_INDICATOR_MS),
        label = "tabIndicatorAlpha"
    )
    val indicatorScale by animateFloatAsState(
        targetValue = if (active) 1f else 0.2f,
        animationSpec = tween(TAB_INDICATOR_MS),
        label = "tabIndicatorScale"
    )

    Column(
        modifier = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.height(38.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 14.sp,
                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                color = if (active) colors.brand else colors.textSecondary
            )
            if (showDot) {
                Box(
                    modifier = Modifier
                        .padding(start = 5.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(colors.brand)
                )
            }
        }

        Box(
            modifier = Modifier
                .width(24.dp)
                .height(2.dp)
                .alpha(indicatorAlpha)
                .scale(scaleX = indicatorScale, scaleY = 1f)
                .clip(RoundedCornerShape(1.dp))
                .background(colors.brand)
        )
    }
}

// ========== 版块页 ==========

@Composable
private fun CategoryTab(
    draft: TopicListQuery,
    categories: List<TopicCategory>,
    myCategoryIds: List<Long>,
    isLoggedIn: Boolean,
    expandedIds: List<Long>,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onPick: (TopicCategory?) -> Unit,
    onToggleExpand: (Long) -> Unit,
    onRequireLogin: () -> Unit
) {
    val myCategories = remember(categories, myCategoryIds) {
        categories.filter { it.id in myCategoryIds && !CategoryIndex.isUncategorized(it) }
    }
    val topLevel = remember(categories) { CategoryIndex.topLevelCategories(categories) }

    ScopeAllRow(
        iconId = "layer-group",
        label = "不限版块",
        selected = draft.category == null,
        onClick = { onPick(null) }
    )

    // 版块一个都没有时，把原因显示出来 —— 留白会让人以为是抽屉坏了。
    if (categories.isEmpty()) {
        CategoryPlaceholder(loading = loading, error = error, onRetry = onRetry)
        return
    }

    // 没有自选版块时整棵树直接铺开，不套分组标题。
    if (myCategories.isEmpty()) {
        CategoryTree(
            roots = topLevel,
            categories = categories,
            draft = draft,
            isLoggedIn = isLoggedIn,
            expandedIds = expandedIds,
            onPick = onPick,
            onToggleExpand = onToggleExpand,
            onRequireLogin = onRequireLogin
        )
        return
    }

    var openGroup by remember { mutableIntStateOf(0) }

    GroupHeader("我的版块", myCategories.size, openGroup == 0) {
        openGroup = if (openGroup == 0) -1 else 0
    }
    ExpandBlock(visible = openGroup == 0) {
        CategoryTree(
            roots = myCategories,
            categories = categories,
            draft = draft,
            isLoggedIn = isLoggedIn,
            expandedIds = expandedIds,
            onPick = onPick,
            onToggleExpand = onToggleExpand,
            onRequireLogin = onRequireLogin
        )
    }

    GroupHeader("所有版块", topLevel.size, openGroup == 1) {
        openGroup = if (openGroup == 1) -1 else 1
    }
    ExpandBlock(visible = openGroup == 1) {
        CategoryTree(
            roots = topLevel,
            categories = categories,
            draft = draft,
            isLoggedIn = isLoggedIn,
            expandedIds = expandedIds,
            onPick = onPick,
            onToggleExpand = onToggleExpand,
            onRequireLogin = onRequireLogin
        )
    }
}

/** 版块为空时的状态位：加载中 / 失败可重试 / 站点确实没有版块。 */
@Composable
private fun CategoryPlaceholder(
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit
) {
    val colors = arkdoColors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
                color = colors.brand
            )
            Text("正在加载版块…", fontSize = 13.sp, color = colors.textTertiary)
            return@Column
        }

        Text(
            text = error ?: "暂无版块",
            fontSize = 13.sp,
            color = colors.textTertiary,
            textAlign = TextAlign.Center
        )

        if (error != null) {
            Box(
                modifier = Modifier
                    .height(34.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(colors.surfaceMuted)
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("重试", fontSize = 14.sp, color = colors.brand)
            }
        }
    }
}

@Composable
private fun CategoryTree(
    roots: List<TopicCategory>,
    categories: List<TopicCategory>,
    draft: TopicListQuery,
    isLoggedIn: Boolean,
    expandedIds: List<Long>,
    onPick: (TopicCategory?) -> Unit,
    onToggleExpand: (Long) -> Unit,
    onRequireLogin: () -> Unit
) {
    roots.forEach { root ->
        val children = CategoryIndex.childrenOf(categories, root.id)
        CategoryRow(
            category = root,
            parent = root,
            isChild = false,
            selected = draft.category?.id == root.id,
            isLoggedIn = isLoggedIn,
            expandable = children.isNotEmpty(),
            expanded = root.id in expandedIds,
            onPick = onPick,
            onToggleExpand = { onToggleExpand(root.id) },
            onRequireLogin = onRequireLogin
        )

        ExpandBlock(visible = root.id in expandedIds) {
            Column {
                children.forEach { child ->
                    CategoryRow(
                        category = child,
                        parent = root,
                        isChild = true,
                        selected = draft.category?.id == child.id,
                        isLoggedIn = isLoggedIn,
                        expandable = false,
                        expanded = false,
                        onPick = onPick,
                        onToggleExpand = {},
                        onRequireLogin = onRequireLogin
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(
    category: TopicCategory,
    parent: TopicCategory?,
    isChild: Boolean,
    selected: Boolean,
    isLoggedIn: Boolean,
    expandable: Boolean,
    expanded: Boolean,
    onPick: (TopicCategory?) -> Unit,
    onToggleExpand: () -> Unit,
    onRequireLogin: () -> Unit
) {
    val context = LocalContext.current
    val colors = arkdoColors

    val restricted = category.readRestricted == true && !isLoggedIn
    val accent = TopicBadgeUtils.categoryColor(category)
    val iconId = TopicBadgeUtils.categoryIcon(context, category, parent)
    val logo = TopicBadgeUtils.categoryLogo(category, parent)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .background(if (selected) colors.surfaceMuted else Color.Transparent)
            .alpha(if (restricted) 0.45f else 1f)
            // 受限版块整行仍然可点，点了走登录提示。
            .clickable { if (restricted) onRequireLogin() else onPick(category) }
            .padding(start = if (isChild) 28.dp else 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(18.dp)
                .padding(end = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            when {
                iconId != null -> FaIcon(iconId, accent, 15.dp)
                logo.isNotEmpty() -> AsyncImage(
                    model = TopicBadgeUtils.resolveBadgeAssetUrl(logo),
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    contentScale = ContentScale.Fit
                )
                else -> Box(
                    modifier = Modifier
                        .size(11.dp)
                        .clip(CircleShape)
                        .background(accent)
                )
            }
        }

        Text(
            text = category.name,
            fontSize = if (isChild) 14.sp else 15.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) colors.brand else colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        if (restricted) {
            FaIcon("lock", colors.textFaint, 12.dp, Modifier.padding(start = 6.dp, end = 4.dp))
        } else if (selected) {
            FaIcon("check", colors.brand, 13.dp, Modifier.padding(start = 6.dp))
        }

        // 展开箭头是独立热区，点它只折叠不选中。
        // 这里用 offset 而不是负 padding：Compose 的 padding 要求非负值，负数会抛
        // IllegalArgumentException（鸿蒙侧是 margin({ right: -16 })，不能照抄）。
        // offset 只挪绘制与命中位置、不改测量结果，正好让 56 宽的热区探进父级那 16 的右内边距、贴到边。
        if (expandable) {
            Box(
                modifier = Modifier
                    .offset(x = 16.dp)
                    .width(56.dp)
                    .height(ROW_HEIGHT)
                    .clickable(onClick = onToggleExpand),
                contentAlignment = Alignment.Center
            ) {
                Chevron(expanded = expanded, size = 16.dp)
            }
        }
    }
}

// ========== 标签页 ==========

@Composable
private fun TagTab(
    draft: TopicListQuery,
    myTags: List<TopicTag>,
    tagQuery: String,
    tagResults: List<TagSuggestion>,
    tagLoading: Boolean,
    onQueryChange: (String) -> Unit,
    onPick: (TopicTag?) -> Unit
) {
    val colors = arkdoColors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.surfaceMuted)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FaIcon("magnifying-glass", colors.textTertiary, 14.dp, Modifier.padding(end = 8.dp))

            Box(modifier = Modifier.weight(1f)) {
                if (tagQuery.isEmpty()) {
                    Text("搜索标签", fontSize = 14.sp, color = colors.textTertiary)
                }
                BasicTextField(
                    value = tagQuery,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = LocalTextStyle.current.merge(
                        TextStyle(fontSize = 14.sp, color = colors.textPrimary)
                    ),
                    cursorBrush = SolidColor(colors.brand),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                )
            }

            if (tagLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = colors.brand
                )
            }
        }
    }

    ScopeAllRow(
        iconId = "tags",
        label = "不限标签",
        selected = draft.tag == null,
        onClick = { onPick(null) }
    )

    // 搜索态不做去重，否则搜不到自己的标签。
    val searching = tagQuery.trim().isNotEmpty()
    if (myTags.isNotEmpty() && !searching) {
        GroupHeader("我的标签", myTags.size, true) {}
        myTags.forEach { tag ->
            TagRow(name = tag.name, selected = draft.tag?.name == tag.name) {
                onPick(TopicTag(id = tag.id, name = tag.name, slug = tag.slug))
            }
        }
    }

    val hot = if (myTags.isNotEmpty() && !searching) {
        tagResults.filter { suggestion -> myTags.none { it.name == suggestion.name } }
    } else {
        tagResults
    }

    if (hot.isNotEmpty()) {
        if (myTags.isNotEmpty() && !searching) {
            GroupHeader("热门标签", hot.size, true) {}
        }
        hot.forEach { suggestion ->
            TagRow(name = suggestion.name, selected = draft.tag?.name == suggestion.name) {
                onPick(TopicTag(id = suggestion.id, name = suggestion.name, slug = null))
            }
        }
    } else if (!tagLoading) {
        Text(
            text = if (searching) "没有匹配的标签" else "暂无可选标签",
            fontSize = 13.sp,
            color = colors.textTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
        )
    }
}

@Composable
private fun TagRow(
    name: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = arkdoColors
    val iconId = TopicBadgeUtils.tagIconId(name) ?: "tag"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .background(if (selected) colors.surfaceMuted else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(18.dp)
                .padding(end = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            FaIcon(iconId, TopicBadgeUtils.tagColor(name), 14.dp)
        }

        Text(
            text = name,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) colors.brand else colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        if (selected) FaIcon("check", colors.brand, 13.dp)
    }
}

// ========== 公共行 ==========

/** 「不限版块」/「不限标签」行。 */
@Composable
private fun ScopeAllRow(
    iconId: String,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = arkdoColors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .background(if (selected) colors.surfaceMuted else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(18.dp)
                .padding(end = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            FaIcon(iconId, if (selected) colors.brand else colors.textSecondary, 14.dp)
        }

        Text(
            text = label,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) colors.brand else colors.textPrimary,
            modifier = Modifier.weight(1f)
        )

        if (selected) FaIcon("check", colors.brand, 13.dp)
    }
}

@Composable
private fun GroupHeader(
    label: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val colors = arkdoColors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .clickable(onClick = onToggle)
            .padding(start = 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(18.dp)
                .padding(end = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Chevron(expanded = expanded, size = 15.dp)
        }

        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f)
        )

        if (count > 0) {
            Text(text = "$count", fontSize = 12.sp, color = colors.textTertiary)
        }
    }
}

/** 右向箭头，展开时转 90°。 */
@Composable
private fun Chevron(expanded: Boolean, size: androidx.compose.ui.unit.Dp) {
    val colors = arkdoColors
    val angle by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(EXPAND_MS),
        label = "chevron"
    )
    FaIcon("chevron-right", colors.textFaint, size, Modifier.rotate(angle))
}

/** 展开内容只做淡入淡出，非对称时长；后续行的位移由布局本身完成。 */
@Composable
private fun ExpandBlock(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(EXPAND_MS)),
        exit = fadeOut(animationSpec = tween(COLLAPSE_MS))
    ) {
        Column { content() }
    }
}

// ========== 底部确认栏 ==========

@Composable
private fun ConfirmBar(
    draft: TopicListQuery,
    isDirty: Boolean,
    bottomInset: androidx.compose.ui.unit.Dp,
    onApply: () -> Unit,
    onClose: () -> Unit
) {
    val colors = arkdoColors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.pageBg)
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp + bottomInset),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        HorizontalDivider(thickness = 0.5.dp, color = colors.border)

        Text(
            text = describeTopicListQuery(draft),
            fontSize = 12.sp,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // 不做置灰死按钮：没改动时换文案换动作，直接当关闭用。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (isDirty) colors.brand else colors.surfaceMuted)
                .clickable { if (isDirty) onApply() else onClose() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isDirty) "查看结果" else "关闭",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = if (isDirty) Color.White else colors.textSecondary
            )
        }
    }
}
