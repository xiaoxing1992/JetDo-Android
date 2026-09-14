package com.arkdo.android.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.arkdo.android.common.constants.AppConstants
import com.arkdo.android.core.model.HomeQuery
import com.arkdo.android.core.model.HomeUser
import com.arkdo.android.feature.home.HomeIncomingTopicEvent
import com.arkdo.android.feature.home.HomeRoute
import com.arkdo.android.feature.home.HomeVisualTokens
import com.arkdo.android.services.message.MessageBusEventBus
import com.arkdo.android.services.message.extractMessageBusTopicId
import com.arkdo.android.ui.theme.ArkdoFixedColors
import com.arkdo.android.ui.theme.arkdoColors

/**
 * 模块化首页(feature:home)的兼容入口。
 *
 * 新首页作为底部导航的独立 tab 使用(future 替换 app/ui/home/HomeScreen 的过渡入口):
 * - 版块索引由 feature 内部 `CategoryRepository` 自行加载,无需再从这里传 categories。
 * - colors / avatarBaseUrl 是 app 主题到 feature `HomeVisualTokens` 的桥接,
 *   保持 feature 层不依赖 app 主题。
 * - MessageBus 实时新话题订阅在这里做,subscribe 生命周期跟随组合。
 */
@Composable
fun HomeFeatureScreen(
    query: HomeQuery,
    isWebViewReady: Boolean,
    isLoggedIn: Boolean,
    isCheckingSession: Boolean,
    accountUser: HomeUser?,
    onTopicClick: (Long, Int?) -> Unit,
    onProfileClick: () -> Unit,
    onNotificationsClick: () -> Unit,
    onSearchClick: () -> Unit,
    onDebugClick: () -> Unit,
    onComposeClick: () -> Unit,
    onMenuClick: () -> Unit
) {
    val incomingTopicEvent = remember {
        androidx.compose.runtime.mutableStateOf<HomeIncomingTopicEvent?>(null)
    }
    val eventSequence = remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    DisposableEffect(Unit) {
        val unsubscribe = MessageBusEventBus.subscribe { event ->
            extractMessageBusTopicId(event)?.let { topicId ->
                eventSequence.longValue += 1
                incomingTopicEvent.value = HomeIncomingTopicEvent(topicId, eventSequence.longValue)
            }
        }
        onDispose { unsubscribe() }
    }

    HomeRoute(
        query = query,
        isWebViewReady = isWebViewReady,
        isLoggedIn = isLoggedIn,
        isCheckingSession = isCheckingSession,
        accountUser = accountUser,
        colors = HomeVisualTokens(
            pageBg = arkdoColors.pageBg,
            textPrimary = arkdoColors.textPrimary,
            textSecondary = arkdoColors.textSecondary,
            textBody = arkdoColors.textBody,
            textTertiary = arkdoColors.textTertiary,
            brand = arkdoColors.brand,
            ldAccent = arkdoColors.ldAccent,
            border = arkdoColors.border,
            surfaceMuted = arkdoColors.surfaceMuted,
            fillSubtle = arkdoColors.fillSubtle,
            infoSoftBg = arkdoColors.infoSoftBg,
            avatarFg = arkdoColors.avatarFg,
            avatarBorder = ArkdoFixedColors.AvatarBorder,
            fabComposeBg = arkdoColors.fabComposeBg,
            fabTopFg = arkdoColors.fabTopFg,
            incomingHighlight = arkdoColors.incomingHighlight,
            unread = ArkdoFixedColors.Unread,
            amber = ArkdoFixedColors.Amber
        ),
        avatarBaseUrl = AppConstants.BASE_URL,
        incomingTopicEvent = incomingTopicEvent.value,
        onTopicClick = onTopicClick,
        onProfileClick = onProfileClick,
        onNotificationsClick = onNotificationsClick,
        onSearchClick = onSearchClick,
        onDebugClick = onDebugClick,
        onComposeClick = onComposeClick,
        onMenuClick = onMenuClick
    )
}