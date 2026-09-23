package com.arkdo.android.ui.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.arkdo.android.common.constants.AppConstants
import com.arkdo.android.core.model.HomeCategory
import com.arkdo.android.core.model.SearchHit
import com.arkdo.android.feature.home.HomeTopicRow
import com.arkdo.android.feature.search.SearchRoute
import com.arkdo.android.feature.search.SearchVisualTokens
import com.arkdo.android.services.site.CategoryIndex
import com.arkdo.android.ui.home.arkdoHomeVisualTokens
import com.arkdo.android.ui.theme.ArkdoFixedColors
import com.arkdo.android.ui.theme.arkdoColors

/**
 * 搜索页的兼容入口。
 *
 * feature 模块不依赖 app 主题,这里做两件桥接:
 * 1. 把 `arkdoColors` 映射成 feature 的 [SearchVisualTokens];
 * 2. 结果行复用新首页的 [HomeTopicRow],搜索词按鸿蒙规则染到标题和摘要上。
 */
@Composable
fun SearchFeatureScreen(
    isWebViewReady: Boolean,
    onBack: () -> Unit,
    onOpenTopic: (topicId: Long, targetPostNumber: Int?) -> Unit,
    onOpenUser: (username: String) -> Unit
) {
    // 分类树:结果行渲染版块徽标要按 categoryId 查名,用应用级缓存的 CategoryIndex。
    val categories = rememberCategories()

    SearchRoute(
        isWebViewReady = isWebViewReady,
        tokens = arkdoSearchTokens(),
        categories = categories,
        onBack = onBack,
        onOpenTopic = onOpenTopic,
        onOpenUser = onOpenUser,
        topicRow = { hit, hitCategories, highlights ->
            SearchTopicRow(
                hit = hit,
                categories = hitCategories,
                highlights = highlights,
                onOpen = { onOpenTopic(hit.topic.id, null) }
            )
        }
    )
}

/** 分类树走 CategoryIndex 的应用级缓存(首页/个人中心同源,只请求一次)。 */
@Composable
private fun rememberCategories(): List<HomeCategory> {
    val state = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<List<HomeCategory>>(emptyList())
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val loaded = CategoryIndex.loadIfNeeded(
            bridge = com.arkdo.android.services.network.ArkWebNetworkBridge,
            ready = true,
            alreadyLoaded = false,
            logTag = "[ArkDO][Search]"
        ) ?: return@LaunchedEffect
        state.value = loaded.map { it.toHomeCategory() }
    }
    return state.value
}

@Composable
private fun SearchTopicRow(
    hit: SearchHit,
    categories: List<HomeCategory>,
    highlights: List<String>,
    onOpen: () -> Unit
) {
    val category = hit.topic.category ?: categories.firstOrNull { it.id == hit.topic.categoryId }
    val parent = category?.parentCategoryId?.let { parentId ->
        categories.firstOrNull { it.id == parentId }
    }
    HomeTopicRow(
        topic = hit.topic,
        category = category,
        parentCategory = parent,
        // 未读行和普通行都进同一个 handler,对齐鸿蒙 handleResultClick。
        onClick = onOpen,
        onUnreadClick = onOpen,
        colors = arkdoHomeVisualTokens(),
        avatarBaseUrl = AppConstants.BASE_URL,
        // 高亮词用本次搜索词的解析结果(对齐鸿蒙:highlights 定格在搜索发起时)。
        searchHighlights = highlights
    )
}

private fun com.arkdo.android.data.models.TopicCategory.toHomeCategory(): HomeCategory = HomeCategory(
    id = id,
    name = name,
    color = color,
    textColor = textColor,
    parentCategoryId = parentCategoryId,
    slug = slug,
    uploadedLogo = uploadedLogo,
    icon = icon,
    readRestricted = readRestricted == true
)

@Composable
private fun arkdoSearchTokens(): SearchVisualTokens {
    val colors = arkdoColors
    return SearchVisualTokens(
        pageBg = colors.pageBg,
        textPrimary = colors.textPrimary,
        textSecondary = colors.textSecondary,
        textFaint = colors.textFaint,
        ldAccent = colors.ldAccent,
        border = colors.border,
        fillSubtle = colors.fillSubtle,
        surfaceMuted = colors.surfaceMuted,
        danger = colors.ldDanger,
        highlightBg = ArkdoFixedColors.Amber,
        infoSoftBg = colors.infoSoftBg,
        warning = colors.warning
    )
}
