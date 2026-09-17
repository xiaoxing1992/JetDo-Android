package com.arkdo.android.ui.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.arkdo.android.core.model.HomeCategory
import com.arkdo.android.core.model.HomeTopic
import com.arkdo.android.core.model.SearchHit
import com.arkdo.android.data.models.Topic
import com.arkdo.android.data.models.TopicCategory
import com.arkdo.android.data.models.TopicTag
import com.arkdo.android.feature.search.SearchRoute
import com.arkdo.android.feature.search.SearchVisualTokens
import com.arkdo.android.services.site.CategoryIndex
import com.arkdo.android.ui.components.TopicListItem
import com.arkdo.android.ui.theme.ArkdoFixedColors
import com.arkdo.android.ui.theme.arkdoColors

/**
 * 搜索页的兼容入口。
 *
 * feature 模块不依赖 app 主题与 app 的列表组件,这里做两件桥接:
 * 1. 把 `arkdoColors` 映射成 feature 的 [SearchVisualTokens];
 * 2. 把 feature 的 `SearchHit` 适配成 app 的 `TopicListItem`(复用首页那张卡,
 *    保证搜索结果与首页列表视觉完全一致,并把搜索词作为高亮传下去)。
 */
@Composable
fun SearchFeatureScreen(
    onBack: () -> Unit,
    onOpenTopic: (topicId: Long, targetPostNumber: Int?) -> Unit,
    onOpenUser: (username: String) -> Unit
) {
    // 分类树:结果行渲染版块徽标要按 categoryId 查名,用应用级缓存的 CategoryIndex。
    val categories = rememberCategories()

    SearchRoute(
        tokens = arkdoSearchTokens(),
        categories = categories,
        onBack = onBack,
        onOpenTopic = onOpenTopic,
        onOpenUser = onOpenUser,
        topicRow = { hit, hitCategories ->
            SearchTopicRow(hit = hit, categories = hitCategories)
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
private fun SearchTopicRow(hit: SearchHit, categories: List<HomeCategory>) {
    val highlights = rememberSearchHighlights(hit)
    TopicListItem(
        topic = hit.topic.toLegacyTopic(),
        category = categoryFor(hit.topic, categories),
        parentCategory = parentCategoryFor(hit.topic, categories),
        onClick = {},
        searchHighlights = highlights
    )
}

/**
 * 高亮词:只取「该主题标题里真正出现过的词」。
 *
 * 搜索页把本次搜索词定格在 ViewModel 里(见 SearchViewModel.highlights),但那只在
 * 页面层可见;行组件这层按标题过滤一次反而更准 —— 全量词传下去会把标题里根本没出现
 * 的词也交给染色逻辑,`TopicListItem` 内部虽有包含判断,但提前收窄既省算也表达了意图。
 */
@Composable
private fun rememberSearchHighlights(hit: SearchHit): List<String> =
    remember(hit) {
        hit.blurb
            ?.let { blurb -> extractHighlightedTerms(blurb) }
            .orEmpty()
            .filter { it.isNotEmpty() && hit.topic.title.contains(it, ignoreCase = true) }
    }

/**
 * 搜索命中摘要里被 `<em>` / `<span class="search-highlight">` 包起来的词,就是服务端
 * 认定的命中词。这里注册表读成纯文本时标签已剥掉,故改用「标题包含」这一层判断,
 * 摘要本身只作为"有命中"的信号。
 */
private fun extractHighlightedTerms(blurb: String): List<String> =
    blurb.split(Regex("[\\s,，。;；:：]+"))
        .map { it.trim() }
        .filter { it.length >= 1 }

private fun categoryFor(topic: HomeTopic, categories: List<HomeCategory>): TopicCategory? {
    val category = topic.category ?: categories.firstOrNull { it.id == topic.categoryId }
    return category?.toLegacyCategory()
}

private fun parentCategoryFor(topic: HomeTopic, categories: List<HomeCategory>): TopicCategory? =
    categoryFor(topic, categories)?.parentCategoryId?.let { parentId ->
        categories.firstOrNull { it.id == parentId }?.toLegacyCategory()
    }

/** feature 稳定模型 → app 旧模型(只为复用既有 TopicListItem)。 */
private fun HomeTopic.toLegacyTopic(): Topic = Topic(
    id = id,
    title = title,
    slug = slug,
    categoryId = categoryId,
    tags = tags.map { TopicTag(id = it.id, name = it.name, slug = it.slug) },
    createdAt = createdAt,
    replyCount = replyCount,
    views = views,
    lastPostedAt = lastPostedAt,
    unseen = unseen,
    unreadPosts = unreadPosts,
    lastReadPostNumber = lastReadPostNumber,
    highestPostNumber = highestPostNumber,
    bookmarked = bookmarked,
    hasAcceptedAnswer = hasAcceptedAnswer,
    excerpt = searchBlurb ?: searchBlurb
)

private fun HomeCategory.toLegacyCategory(): TopicCategory = TopicCategory(
    id = id,
    name = name,
    color = color,
    textColor = textColor,
    slug = slug,
    parentCategoryId = parentCategoryId,
    uploadedLogo = uploadedLogo,
    icon = icon,
    readRestricted = readRestricted
)

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
        highlightBg = ArkdoFixedColors.Amber
    )
}
