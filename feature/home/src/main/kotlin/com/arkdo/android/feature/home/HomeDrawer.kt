package com.arkdo.android.feature.home

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import com.arkdo.android.core.model.HomeCategory
import com.arkdo.android.core.model.HomeFeed
import com.arkdo.android.core.model.HomeQuery
import com.arkdo.android.core.model.HomeTag
import com.arkdo.android.core.model.TagSuggestion
import com.arkdo.android.core.designsystem.FaIcon
import com.arkdo.android.core.designsystem.parseTopicHexColor

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

// 两个 Tab 内部的手风琴分组：同时最多展开一组，点已展开的那组即两组全收。
// 只有「我的版块 / 我的标签」非空时手风琴才成立，否则整棵树/热门候选直接铺开。
private const val GROUP_NONE = -1
private const val GROUP_MINE = 0
private const val GROUP_ALL = 1
/** 未登录点受限入口的提示文案（对齐鸿蒙 CategoryDrawer.ets:257 的 onNotice 文案）。 */
private const val LOGIN_REQUIRED_HINT = "登录后可查看该入口"

@Composable
internal fun HomeDrawer(
    draft: HomeQuery,
    applied: HomeQuery,
    categories: List<HomeCategory>,
    tagQuery: String,
    tagResults: List<TagSuggestion>,
    tagLoading: Boolean,
    isLoggedIn: Boolean,
    expandedIds: List<Long>,
    /** 网页版侧栏自选的标签（「我的标签」组）。未登录或没配置过都是空。 */
    myTags: List<HomeTag> = emptyList(),
    /** 同上，自选版块的 id（「我的版块」组）。只有 id，名字/颜色去 categories 里查。 */
    myCategoryIds: List<Long> = emptyList(),
    categoriesLoading: Boolean = false,
    categoriesError: String? = null,
    onRetryCategories: () -> Unit = {},
    onDraftChange: (HomeQuery) -> Unit,
    onApply: () -> Unit,
    onClose: () -> Unit,
    onReset: () -> Unit,
    onToggleExpand: (Long) -> Unit,
    onTagQueryChange: (String) -> Unit,
    onTagTabShown: () -> Unit,
    colors: HomeVisualTokens,
    assetBaseUrl: String,
    modifier: Modifier = Modifier
) {
    var activeTab by remember { mutableIntStateOf(TAB_CATEGORY) }

    // 两个 Tab 各自的手风琴状态。默认展开「我的」：自选的那几个是高频入口，
    // 整棵树/热门候选收着，想翻再点。不提到宿主持有（与 draft/expandedIds 不同）：
    // 每次开抽屉回到这个默认正是想要的行为，而抽屉内容关闭即卸载，组件内状态天然复位。
    var categoryGroup by remember { mutableIntStateOf(GROUP_MINE) }
    var tagGroup by remember { mutableIntStateOf(GROUP_MINE) }

    // 标签页的手风琴只在「有自选标签且没在搜索」时成立：一旦打了字，就该只剩搜索结果，
    // 两组并排会分不清哪个是搜出来的。
    val tagAccordion = myTags.isNotEmpty() && tagQuery.trim().isEmpty()
    // 热门候选/搜索结果这一段此刻要不要显示。它同时是「该不该发请求」的判据：
    // 收在手风琴里没显示就不发，展开或进入搜索态时才发。
    val hotTagListVisible = !tagAccordion || tagGroup == GROUP_ALL

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
            showReset = !draft.isDefaultHomeQuery(),
            colors = colors,
            onReset = onReset,
            onClose = onClose
        )

        FilterSection(
            draft = draft,
            isLoggedIn = isLoggedIn,
            colors = colors,
            onPick = { onDraftChange(draft.copy(feed = it)) },
            onClose = onClose
        )

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            thickness = 0.5.dp,
            color = colors.border
        )

        ScopeTabBar(
            activeTab = activeTab,
            categoryPicked = draft.categoryId != null,
            tagPicked = !draft.tag.isNullOrBlank(),
            colors = colors,
            onSelect = { activeTab = it }
        )

        LaunchedEffect(activeTab, hotTagListVisible) {
            // 热门候选只在它真要显示时才取：手风琴默认收着热门组，
            // 一切过来就打一次请求是白打。展开热门组由下面的点按单独触发
            // （那时 hotTagListVisible 会变，这个 effect 也会重跑）。
            if (activeTab == TAB_TAG && hotTagListVisible) onTagTabShown()
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
                    isLoggedIn = isLoggedIn,
                    expandedIds = expandedIds,
                    myCategoryIds = myCategoryIds,
                    categoryGroup = categoryGroup,
                    onToggleGroup = { group ->
                        categoryGroup = if (categoryGroup == group) GROUP_NONE else group
                    },
                    colors = colors,
                    assetBaseUrl = assetBaseUrl,
                    loading = categoriesLoading,
                    error = categoriesError,
                    onRetry = onRetryCategories,
                    onPick = { category ->
                        onDraftChange(
                            draft.copy(
                                categoryId = category?.id,
                                categorySlug = category?.slug,
                                categoryName = category?.name
                            )
                        )
                    },
                    onToggleExpand = onToggleExpand
                )
            } else {
                TagTab(
                    draft = draft,
                    tagQuery = tagQuery,
                    tagResults = tagResults,
                    tagLoading = tagLoading,
                    myTags = myTags,
                    accordion = tagAccordion,
                    tagGroup = tagGroup,
                    onToggleGroup = { group ->
                        tagGroup = if (tagGroup == group) GROUP_NONE else group
                    },
                    colors = colors,
                    onQueryChange = onTagQueryChange,
                    onPick = { tag ->
                        onDraftChange(
                            draft.copy(
                                tag = tag?.name,
                                tagId = tag?.id,
                                tagSlug = tag?.slug
                            )
                        )
                    }
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        ConfirmBar(
            draft = draft,
            isDirty = !sameHomeQuery(draft, applied),
            bottomInset = bottomInset,
            colors = colors,
            onApply = onApply,
            onClose = onClose
        )
    }
}

// ========== 头部 ==========

@Composable
private fun DrawerHeader(
    showReset: Boolean,
    colors: HomeVisualTokens,
    onReset: () -> Unit,
    onClose: () -> Unit
) {

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
    draft: HomeQuery,
    isLoggedIn: Boolean,
    colors: HomeVisualTokens,
    onPick: (HomeFeed) -> Unit,
    onClose: () -> Unit
) {
    // 顺序跟设置页一致：最新、最新发布、热门、排行榜、未读、未读新帖。
    // 枚举本身把排行榜排在热门前面，不能直接按 entries 铺。
    val feeds = remember {
        listOf(
            HomeFeed.LATEST,
            HomeFeed.CREATED,
            HomeFeed.HOT,
            HomeFeed.TOP,
            HomeFeed.UNREAD,
            HomeFeed.NEW
        )
    }

    val context = LocalContext.current

    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 2.dp)
    ) {
        feeds.forEach { feed ->
            val locked = feed.requiresLogin && !isLoggedIn
            DrawerChoiceChip(
                label = feed.label,
                selected = draft.feed == feed,
                disabled = locked,
                colors = colors,
                onTap = { onPick(feed) },
                // 未登录点受限排序：给一句提示，不要默默把抽屉关掉。
                // 此前是 onBlockedTap = onClose —— 用户点了没任何反馈，只看到抽屉收起来，
                // 完全不知道是「要登录」还是「点了没生效」。对齐鸿蒙 onNotice 语义。
                onBlockedTap = {
                    Toast.makeText(context, LOGIN_REQUIRED_HINT, Toast.LENGTH_SHORT).show()
                }
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
    colors: HomeVisualTokens,
    onSelect: (Int) -> Unit
) {
    Row(modifier = Modifier.padding(horizontal = 16.dp)) {
        ScopeTab(
            label = "版块",
            active = activeTab == TAB_CATEGORY,
            showDot = categoryPicked,
            colors = colors,
            onClick = { onSelect(TAB_CATEGORY) },
            modifier = Modifier.weight(1f)
        )
        ScopeTab(
            label = "标签",
            active = activeTab == TAB_TAG,
            showDot = tagPicked,
            colors = colors,
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
    colors: HomeVisualTokens,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {

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
    draft: HomeQuery,
    categories: List<HomeCategory>,
    isLoggedIn: Boolean,
    expandedIds: List<Long>,
    myCategoryIds: List<Long>,
    categoryGroup: Int,
    onToggleGroup: (Int) -> Unit,
    colors: HomeVisualTokens,
    assetBaseUrl: String,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onPick: (HomeCategory?) -> Unit,
    onToggleExpand: (Long) -> Unit
) {
    val topLevel = remember(categories) { categories.topLevel() }
    val mine = remember(categories, myCategoryIds) { categories.sidebarOrdered(myCategoryIds) }

    ScopeAllRow(
        iconId = "layer-group",
        label = "不限版块",
        selected = draft.categoryId == null,
        colors = colors,
        onClick = { onPick(null) }
    )

    // 版块一个都没有时，把原因显示出来 —— 留白会让人以为是抽屉坏了。
    if (categories.isEmpty()) {
        CategoryPlaceholder(loading = loading, error = error, colors = colors, onRetry = onRetry)
        return
    }

    // 没有自选版块就退回原样：整棵树直接铺开、不套分组标题，
    // 免得未登录的人对着两个空标题发愣。
    if (mine.isEmpty()) {
        CategoryTree(
            roots = topLevel,
            categories = categories,
            draft = draft,
            isLoggedIn = isLoggedIn,
            expandedIds = expandedIds,
            colors = colors,
            assetBaseUrl = assetBaseUrl,
            onPick = onPick,
            onToggleExpand = onToggleExpand
        )
        return
    }

    GroupHeader(
        label = "我的版块",
        count = mine.size,
        open = categoryGroup == GROUP_MINE,
        colors = colors,
        onClick = { onToggleGroup(GROUP_MINE) }
    )
    ExpandBlock(visible = categoryGroup == GROUP_MINE) {
        Column {
            MyCategoryList(
                mine = mine,
                categories = categories,
                draft = draft,
                isLoggedIn = isLoggedIn,
                expandedIds = expandedIds,
                colors = colors,
                assetBaseUrl = assetBaseUrl,
                onPick = onPick,
                onToggleExpand = onToggleExpand
            )
        }
    }

    GroupHeader(
        label = "所有版块",
        count = topLevel.size,
        open = categoryGroup == GROUP_ALL,
        colors = colors,
        onClick = { onToggleGroup(GROUP_ALL) }
    )
    ExpandBlock(visible = categoryGroup == GROUP_ALL) {
        CategoryTree(
            roots = topLevel,
            categories = categories,
            draft = draft,
            isLoggedIn = isLoggedIn,
            expandedIds = expandedIds,
            colors = colors,
            assetBaseUrl = assetBaseUrl,
            onPick = onPick,
            onToggleExpand = onToggleExpand
        )
    }
}

/**
 * 「我的版块」：网页版侧栏自选的那批，置顶一份。
 *
 * 同一个版块在「所有版块」那棵树里还会再出现一次，这是有意的 ——
 * 置顶组是快捷方式，不是把版块从树里搬走（与 Discourse 侧栏行为一致）。
 *
 * 展示逻辑与那棵树完全相同：主版块带展开箭头、子版块从箭头里出来。侧栏通常只勾主版块，
 * 子版块并不在 sidebar_category_ids 里，靠这里补齐，否则置顶组就成了「点不进子版块」的
 * 一串死链。子版块展开态与树共用 expandedIds，两处同步开合是有意的。
 */
@Composable
private fun MyCategoryList(
    mine: List<HomeCategory>,
    categories: List<HomeCategory>,
    draft: HomeQuery,
    isLoggedIn: Boolean,
    expandedIds: List<Long>,
    colors: HomeVisualTokens,
    assetBaseUrl: String,
    onPick: (HomeCategory?) -> Unit,
    onToggleExpand: (Long) -> Unit
) {
    mine.forEach { category ->
        val children = categories.childrenOf(category.id)
        CategoryRow(
            category = category,
            // 置顶组里的版块可能是子版块，图标兜底要拿它的父版块；取不到就用自身。
            parent = categories.parentOrSelf(category),
            isChild = false,
            selected = draft.categoryId == category.id,
            isLoggedIn = isLoggedIn,
            expandable = children.isNotEmpty(),
            expanded = category.id in expandedIds,
            colors = colors,
            assetBaseUrl = assetBaseUrl,
            onPick = onPick,
            onToggleExpand = { onToggleExpand(category.id) }
        )

        ExpandBlock(visible = category.id in expandedIds) {
            Column {
                children.forEach { child ->
                    CategoryRow(
                        category = child,
                        parent = category,
                        isChild = true,
                        selected = draft.categoryId == child.id,
                        isLoggedIn = isLoggedIn,
                        expandable = false,
                        expanded = false,
                        colors = colors,
                        assetBaseUrl = assetBaseUrl,
                        onPick = onPick,
                        onToggleExpand = {}
                    )
                }
            }
        }
    }
}

/** 版块为空时的状态位：加载中 / 失败可重试 / 站点确实没有版块。 */
@Composable
private fun CategoryPlaceholder(
    loading: Boolean,
    error: String?,
    colors: HomeVisualTokens,
    onRetry: () -> Unit
) {
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
    roots: List<HomeCategory>,
    categories: List<HomeCategory>,
    draft: HomeQuery,
    isLoggedIn: Boolean,
    expandedIds: List<Long>,
    colors: HomeVisualTokens,
    assetBaseUrl: String,
    onPick: (HomeCategory?) -> Unit,
    onToggleExpand: (Long) -> Unit
) {
    roots.forEach { root ->
        val children = categories.childrenOf(root.id)
        CategoryRow(
            category = root,
            parent = root,
            isChild = false,
            selected = draft.categoryId == root.id,
            isLoggedIn = isLoggedIn,
            expandable = children.isNotEmpty(),
            expanded = root.id in expandedIds,
            colors = colors,
            assetBaseUrl = assetBaseUrl,
            onPick = onPick,
            onToggleExpand = { onToggleExpand(root.id) }
        )

        ExpandBlock(visible = root.id in expandedIds) {
            Column {
                children.forEach { child ->
                    CategoryRow(
                        category = child,
                        parent = root,
                        isChild = true,
                        selected = draft.categoryId == child.id,
                        isLoggedIn = isLoggedIn,
                        expandable = false,
                        expanded = false,
                        colors = colors,
                        assetBaseUrl = assetBaseUrl,
                        onPick = onPick,
                        onToggleExpand = {}
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(
    category: HomeCategory,
    parent: HomeCategory?,
    isChild: Boolean,
    selected: Boolean,
    isLoggedIn: Boolean,
    expandable: Boolean,
    expanded: Boolean,
    colors: HomeVisualTokens,
    assetBaseUrl: String,
    onPick: (HomeCategory?) -> Unit,
    onToggleExpand: () -> Unit
) {
    val context = LocalContext.current

    val restricted = category.readRestricted && !isLoggedIn
    val accent = parseTopicHexColor(category.color) ?: Color(0xFF6B7280)
    val iconId = homeCategoryIcon(context, category, parent)
    val logo = category.uploadedLogo?.takeIf(String::isNotBlank)
        ?: parent?.uploadedLogo?.takeIf(String::isNotBlank)
        ?: ""

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .background(if (selected) colors.surfaceMuted else Color.Transparent)
            .alpha(if (restricted) 0.45f else 1f)
            // 受限版块整行仍然可点，点了给一句登录提示（对齐鸿蒙 handleCategoryTap → onRequireLogin）。
            // 此前是 clickable(enabled = !restricted)：注释写着「点了走登录提示」，实现却是完全不可点，
            // 用户看到半透明的版块点不动，不知道为什么。
            .clickable {
                if (restricted) {
                    Toast.makeText(context, LOGIN_REQUIRED_HINT, Toast.LENGTH_SHORT).show()
                } else {
                    onPick(category)
                }
            }
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
                    model = resolveHomeAsset(logo, assetBaseUrl),
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
            FaIcon("lock", colors.textTertiary, 12.dp, Modifier.padding(start = 6.dp, end = 4.dp))
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
                Chevron(expanded = expanded, size = 16.dp, colors = colors)
            }
        }
    }
}

// ========== 标签页 ==========

@Composable
private fun TagTab(
    draft: HomeQuery,
    tagQuery: String,
    tagResults: List<TagSuggestion>,
    tagLoading: Boolean,
    myTags: List<HomeTag>,
    accordion: Boolean,
    tagGroup: Int,
    onToggleGroup: (Int) -> Unit,
    colors: HomeVisualTokens,
    onQueryChange: (String) -> Unit,
    onPick: (HomeTag?) -> Unit
) {
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

            // 占位文字必须和输入框共用一个垂直基准：BasicTextField 带
            // padding(vertical = 8.dp) 把自己撑到 ~35dp，而 Box 默认 TopStart 会把
            // 不带 padding 的占位文字顶到框顶 —— 实测占位比左侧放大镜高 7dp，
            // 看上去就是「文字没在搜索框里居中」。库里另两处搜索框
            // （SearchScreen / SearchFilterPanel）都用 decorationBox + CenterStart，
            // 这里保持同样的对齐语义。
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
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
        selected = draft.tag.isNullOrBlank(),
        colors = colors,
        onClick = { onPick(null) }
    )

    val searching = tagQuery.trim().isNotEmpty()
    // 手风琴在场时，热门组里剔掉已经在「我的标签」里的那些，同一个标签不两组各出现一次。
    // 搜索态不剔：那时手风琴不在场，再剔就等于搜不到自己的标签了。
    val myTagNames = remember(myTags) { myTags.mapTo(HashSet()) { it.name } }
    val hotResults = remember(tagResults, myTagNames, accordion) {
        if (accordion) tagResults.filter { it.name !in myTagNames } else tagResults
    }

    if (accordion) {
        GroupHeader(
            label = "我的标签",
            count = myTags.size,
            open = tagGroup == GROUP_MINE,
            colors = colors,
            onClick = { onToggleGroup(GROUP_MINE) }
        )
        ExpandBlock(visible = tagGroup == GROUP_MINE) {
            // 「我的标签」不显示每个标签的计数：sidebar_tags 不回 count，
            // 硬凑一个还得为每个标签单独打一次请求，不值得。
            myTags.forEach { tag ->
                TagRow(name = tag.name, selected = draft.tag == tag.name, colors = colors) {
                    onPick(tag)
                }
            }
        }

        GroupHeader(
            label = "热门标签",
            count = hotResults.size,
            open = tagGroup == GROUP_ALL,
            colors = colors,
            onClick = { onToggleGroup(GROUP_ALL) }
        )
        ExpandBlock(visible = tagGroup == GROUP_ALL) {
            TagResultList(
                results = hotResults,
                selectedName = draft.tag,
                tagLoading = tagLoading,
                searching = searching,
                colors = colors,
                onPick = onPick
            )
        }
        return
    }

    // 搜索态 / 没有自选标签：热门候选就是唯一的列表，直接铺开，不套分组标题。
    TagResultList(
        results = hotResults,
        selectedName = draft.tag,
        tagLoading = tagLoading,
        searching = searching,
        colors = colors,
        onPick = onPick
    )
}

/**
 * 标签候选（热门 / 搜索结果）列表，「热门标签」与搜索态共用。
 *
 * 空且不在加载中时给一句占位 —— 展开着却取不到东西（请求失败、或该版块下没标签）
 * 才该说这句；收在手风琴里的时候调用方根本不会渲染到这里。
 */
@Composable
private fun TagResultList(
    results: List<TagSuggestion>,
    selectedName: String?,
    tagLoading: Boolean,
    searching: Boolean,
    colors: HomeVisualTokens,
    onPick: (HomeTag?) -> Unit
) {
    if (results.isNotEmpty()) {
        results.forEach { suggestion ->
            TagRow(
                name = suggestion.name,
                selected = selectedName == suggestion.name,
                colors = colors
            ) {
                onPick(HomeTag(name = suggestion.name, id = suggestion.id))
            }
        }
        return
    }
    if (tagLoading) return
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

@Composable
private fun TagRow(
    name: String,
    selected: Boolean,
    colors: HomeVisualTokens,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val styles = remember { loadHomeTagStyles(context) }
    val style = styles[name.trim().lowercase()]
    val iconId = style?.iconId ?: "tag"
    val iconColor = style?.color ?: Color(0xFF6B7280)

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
            FaIcon(iconId, iconColor, 14.dp)
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

/**
 * 分组标题（我的版块 / 所有版块 / 我的标签 / 热门标签）。
 * 左侧箭头指示展开态，整行都是热区。
 * 与版块行的箭头是两回事：那个开合子版块，这个开合整组。
 */
@Composable
private fun GroupHeader(
    label: String,
    count: Int,
    open: Boolean,
    colors: HomeVisualTokens,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
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
            Chevron(expanded = open, size = 15.dp, colors = colors)
        }

        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f)
        )

        // 组内条数：收起时它是「这组里有什么」的唯一线索；为 0 时不显示
        //（热门标签还没取回来时就是 0）。
        if (count > 0) {
            Text("$count", fontSize = 12.sp, color = colors.textTertiary)
        }
    }
}

/** 「不限版块」/「不限标签」行。 */
@Composable
private fun ScopeAllRow(
    iconId: String,
    label: String,
    selected: Boolean,
    colors: HomeVisualTokens,
    onClick: () -> Unit
) {
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

/** 右向箭头，展开时转 90°。 */
@Composable
private fun Chevron(expanded: Boolean, size: androidx.compose.ui.unit.Dp, colors: HomeVisualTokens) {
    val angle by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(EXPAND_MS),
        label = "chevron"
    )
    FaIcon("chevron-right", colors.textTertiary, size, Modifier.rotate(angle))
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
    draft: HomeQuery,
    isDirty: Boolean,
    bottomInset: androidx.compose.ui.unit.Dp,
    colors: HomeVisualTokens,
    onApply: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.pageBg)
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp + bottomInset),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        HorizontalDivider(thickness = 0.5.dp, color = colors.border)

        Text(
            text = describeHomeQuery(draft),
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

private fun List<HomeCategory>.topLevel(): List<HomeCategory> =
    filter { it.parentCategoryId == null && !it.isUncategorized() }

private fun List<HomeCategory>.childrenOf(parentId: Long): List<HomeCategory> =
    filter { it.parentCategoryId == parentId }

/** 取某版块的父版块；取不到（它本就是顶级、或父节点不在集合里）就用自身。 */
private fun List<HomeCategory>.parentOrSelf(category: HomeCategory): HomeCategory {
    val parentId = category.parentCategoryId ?: return category
    return find { it.id == parentId } ?: category
}

/**
 * 「我的版块」置顶组的取值与排序：对齐 Discourse 网页版侧栏。
 *
 * 官方实现（`sidebar/common/categories-section.gjs` 的 sortedCategories）分三步，顺序不能颠倒：
 *   ① 取**全站**版块，按 /site.json 的顺序（即 `site.rb` 的 `.order(:position)`，
 *      后台拖出来的那个顺序。linux.do 打开了 fixed_category_positions，实测侧栏是
 *      position 序而非名字序）
 *   ② `Category.sortCategories` 把子版块紧跟到各自父版块后面
 *   ③ 最后才按 sidebar 的 id 过滤
 * 先过滤再排会丢掉第 ② 步的父子归组，所以必须在全集上排完再筛。
 *
 * 与官方的一处有意偏差：父版块也被勾选时，这里丢掉它下面同样被勾选的子版块 ——
 * 置顶组是可展开的树（子版块从父版块的箭头里出来），两处都列会重复一次。
 */
private fun List<HomeCategory>.sidebarOrdered(sidebarIds: List<Long>): List<HomeCategory> {
    if (sidebarIds.isEmpty() || isEmpty()) return emptyList()

    val flattened = ArrayList<HomeCategory>(size)
    appendWithChildren(parentId = null, out = flattened)

    val result = ArrayList<HomeCategory>(sidebarIds.size)
    for (category in flattened) {
        if (category.id !in sidebarIds || category.isUncategorized()) continue
        // 父版块也在选中集里：该子版块交给父版块的展开箭头去显示。
        val parentId = category.parentCategoryId
        if (parentId != null && parentId in sidebarIds) continue
        result += category
    }
    return result
}

/** `Category.sortCategories` 的等价实现：深度优先展开，同层顺序保持传入顺序。 */
private fun List<HomeCategory>.appendWithChildren(
    parentId: Long?,
    out: MutableList<HomeCategory>
) {
    for (category in this) {
        if (category.parentCategoryId != parentId) continue
        out += category
        appendWithChildren(category.id, out)
    }
}

@Composable
private fun DrawerChoiceChip(
    label: String,
    selected: Boolean,
    disabled: Boolean,
    colors: HomeVisualTokens,
    onTap: () -> Unit,
    onBlockedTap: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val highlighted = selected && !disabled
    val textColor = when {
        disabled -> colors.textTertiary
        highlighted -> colors.brand
        else -> colors.textBody
    }
    Box(
        modifier = Modifier
            .padding(end = 8.dp, bottom = 8.dp)
            .height(32.dp)
            .alpha(if (pressed && !disabled) 0.7f else 1f)
            .clip(RoundedCornerShape(16.dp))
            .background(if (highlighted) colors.infoSoftBg else colors.surfaceMuted)
            .clickable(interactionSource = interactionSource, indication = null) {
                if (disabled) onBlockedTap() else onTap()
            }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = if (highlighted) FontWeight.Medium else FontWeight.Normal,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun HomeCategory.isUncategorized(): Boolean {
    val slug = slug?.trim()?.lowercase().orEmpty()
    if (slug == "uncategorized") return true
    val normalized = name.trim()
    return normalized == "未分类" || normalized.lowercase() == "uncategorized"
}
