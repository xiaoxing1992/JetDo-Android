package com.jetdo.android.ui.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.jetdo.android.common.constants.AppConstants
import com.jetdo.android.core.model.HomeCategory
import com.jetdo.android.feature.search.SearchRoute
import com.jetdo.android.feature.search.SearchVisualTokens
import com.jetdo.android.services.site.CategoryIndex
import com.jetdo.android.ui.theme.JetDoFixedColors
import com.jetdo.android.ui.theme.jetdoColors

/**
 * 搜索页的兼容入口。
 *
 * feature 模块不依赖 app 主题,这里把 `jetdoColors` 映射成 [SearchVisualTokens]。
 * 帖子行由搜索模块自己画。
 */
@Composable
fun SearchFeatureScreen(
    isWebViewReady: Boolean,
    onBack: () -> Unit,
    onOpenTopic: (topicId: Long, targetPostNumber: Int?) -> Unit,
    onOpenUser: (username: String, userId: Long) -> Unit
) {
    // 分类树:结果行渲染版块徽标要按 categoryId 查名,用应用级缓存的 CategoryIndex。
    val categories = rememberCategories()

    SearchRoute(
        isWebViewReady = isWebViewReady,
        tokens = jetdoSearchTokens(),
        categories = categories,
        onBack = onBack,
        onOpenTopic = onOpenTopic,
        onOpenUser = onOpenUser,
        avatarBaseUrl = AppConstants.BASE_URL
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
            bridge = com.jetdo.android.services.network.ArkWebNetworkBridge,
            ready = true,
            alreadyLoaded = false,
            logTag = "[JetDo][Search]"
        ) ?: return@LaunchedEffect
        state.value = loaded.map { it.toHomeCategory() }
    }
    return state.value
}

private fun com.jetdo.android.data.models.TopicCategory.toHomeCategory(): HomeCategory = HomeCategory(
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
private fun jetdoSearchTokens(): SearchVisualTokens {
    val colors = jetdoColors
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
        highlightBg = JetDoFixedColors.Amber,
        infoSoftBg = colors.infoSoftBg,
        warning = colors.warning
    )
}
