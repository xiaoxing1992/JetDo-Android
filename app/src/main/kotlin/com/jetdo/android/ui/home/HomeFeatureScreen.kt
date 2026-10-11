package com.jetdo.android.ui.home

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.jetdo.android.services.realtime.RealtimeTopicsPrefs
import com.jetdo.android.common.constants.AppConstants
import com.jetdo.android.core.model.HomeFeed
import com.jetdo.android.core.model.HomeQuery
import com.jetdo.android.core.model.HomeUser
import com.jetdo.android.feature.home.HomeIncomingTopicEvent
import com.jetdo.android.feature.home.HomeRoute
import com.jetdo.android.feature.home.HomeVisualTokens
import com.jetdo.android.services.message.LATEST_TOPICS_CHANNEL
import com.jetdo.android.services.message.MessageBusEvent
import com.jetdo.android.services.message.MessageBusEventBus
import com.jetdo.android.services.message.NEW_TOPICS_CHANNEL
import com.jetdo.android.services.message.extractIncomingHomeTopicId
import com.jetdo.android.ui.theme.JetDoFixedColors
import com.jetdo.android.ui.theme.jetdoColors

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
    isWebViewReady: Boolean,
    isLoggedIn: Boolean,
    isCheckingSession: Boolean,
    accountUser: HomeUser?,
    // 通知未读数：顶栏铃铛角标（真源 NotificationEventBus，由 RootTabScreen 订阅后下发）。
    unreadCount: Int = 0,
    onTopicClick: (Long, Int?) -> Unit,
    onProfileClick: () -> Unit,
    onNotificationsClick: () -> Unit,
    onSearchClick: () -> Unit,
    onDebugClick: () -> Unit,
    onComposeClick: () -> Unit,
    onMenuClick: () -> Unit,
    viewModel: com.jetdo.android.feature.home.HomeViewModel
) {
    val incomingTopicEvent = remember {
        androidx.compose.runtime.mutableStateOf<HomeIncomingTopicEvent?>(null)
    }
    val eventSequence = remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    val currentQuery = rememberUpdatedState(viewModel.currentQuery)
    val realtimeTopicsEnabled by RealtimeTopicsPrefs.enabled.collectAsState()
    val acceptsRealtime = rememberUpdatedState(realtimeTopicsEnabled)
    LaunchedEffect(realtimeTopicsEnabled) {
        if (!realtimeTopicsEnabled) viewModel.dismissIncomingTopics()
    }
    DisposableEffect(Unit) {
        val main = Handler(Looper.getMainLooper())
        val unsubscribe = MessageBusEventBus.subscribe { event ->
            if (!acceptsRealtime.value) return@subscribe
            if (!acceptsIncomingTopic(currentQuery.value, event)) return@subscribe
            val topicId = extractIncomingHomeTopicId(event) ?: return@subscribe
            main.post {
                eventSequence.longValue += 1
                incomingTopicEvent.value = HomeIncomingTopicEvent(topicId, eventSequence.longValue)
            }
        }
        onDispose { unsubscribe() }
    }

    HomeRoute(
        isWebViewReady = isWebViewReady,
        isLoggedIn = isLoggedIn,
        isCheckingSession = isCheckingSession,
        accountUser = accountUser,
        unreadCount = unreadCount,
        colors = jetdoHomeVisualTokens(),
        avatarBaseUrl = AppConstants.BASE_URL,
        incomingTopicEvent = incomingTopicEvent.value,
        onTopicClick = onTopicClick,
        onProfileClick = onProfileClick,
        onNotificationsClick = onNotificationsClick,
        onSearchClick = onSearchClick,
        onDebugClick = onDebugClick,
        onComposeClick = onComposeClick,
        onMenuClick = onMenuClick,
        viewModel = viewModel
    )
}

/**
 * 「最新」听 /latest，「最新发布」听 /new。带标签或热门/未读时不提示，
 * 那些列表的排序和这两个频道对不上。选了版块时，payload 里的版块必须一致。
 */
private fun acceptsIncomingTopic(query: HomeQuery, event: MessageBusEvent): Boolean {
    val channel = when {
        !query.tag.isNullOrBlank() -> return false
        query.feed == HomeFeed.LATEST -> LATEST_TOPICS_CHANNEL
        query.feed == HomeFeed.CREATED -> NEW_TOPICS_CHANNEL
        else -> return false
    }
    if (event.channel != channel) return false
    val selectedCategory = query.categoryId ?: return true
    return event.data?.payload?.categoryId == selectedCategory
}

/** app 主题到首页列表色板的桥接。 */
@Composable
fun jetdoHomeVisualTokens(): HomeVisualTokens {
    val colors = jetdoColors
    return HomeVisualTokens(
        pageBg = colors.pageBg,
        textPrimary = colors.textPrimary,
        textSecondary = colors.textSecondary,
        textBody = colors.textBody,
        textTertiary = colors.textTertiary,
        brand = colors.brand,
        ldAccent = colors.ldAccent,
        border = colors.border,
        surfaceMuted = colors.surfaceMuted,
        fillSubtle = colors.fillSubtle,
        infoSoftBg = colors.infoSoftBg,
        avatarFg = colors.avatarFg,
        avatarBorder = JetDoFixedColors.AvatarBorder,
        fabComposeBg = colors.fabComposeBg,
        fabTopFg = colors.fabTopFg,
        fabGlassTint = colors.floatingActionBg,
        incomingHighlight = colors.incomingHighlight,
        unread = JetDoFixedColors.Unread,
        amber = JetDoFixedColors.Amber,
        scrim = JetDoFixedColors.Scrim
    )
}