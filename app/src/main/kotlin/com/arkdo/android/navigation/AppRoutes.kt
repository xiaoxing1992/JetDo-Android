package com.arkdo.android.navigation

// ==========================================================================
// 与 HarmonyOS 版 navigation/AppRoutes.ets 逐字段对齐。
// 路由名 + 各路由参数 + 构造器/判定语。Android 用类型化 data class 承载参数，
// NavController 以 route 字符串索引，参数经 NavBackStackEntry 的 arguments 传递。
// ==========================================================================

object AppRouteName {
    const val TOPIC_DETAIL = "topicDetail"
    const val PROFILE = "profile"
    const val SETTINGS = "settings"
    const val DOH_SETTINGS = "dohSettings"
    const val PUSH_SETTINGS = "pushSettings"
    const val NETWORK_SETTINGS = "networkSettings"
    const val LAB_SETTINGS = "labSettings"
    const val DIAGNOSTICS_SETTINGS = "diagnosticsSettings"
    const val LEVEL_PROGRESS = "levelProgress"
    const val WEB = "web"
    const val NOTIFICATIONS = "notifications"
    const val BOOKMARKS = "bookmarks"
    const val DRAFTS = "drafts"
    const val ACTIVITY = "activity"
    const val BADGES = "badges"
    const val READ_TOPICS = "readTopics"
    const val SITES = "sites"
    // 他人主页：点任意头像进这里。PROFILE 是「我的」页，与本页无关。
    const val USER_PROFILE = "userProfile"
    // 他人被采纳为解决方案的回复列表。
    const val SOLVED = "solved"
    // 双栏(平板横屏)右详情栏的空占位。
    const val PLACEHOLDER = "placeholder"
    // LINUX DO Credit 页。
    const val LDC_CREDIT = "ldcCredit"
}

/** 话题详情路由参数。 */
data class TopicDetailRouteParam(
    val topicId: Int,
    val slug: String? = null,
    val title: String,
    val targetPostNumber: Int? = null,
    val highlightTarget: Boolean? = null,
    val draftReply: String? = null,
    val draftKey: String? = null,
    val draftSequence: Int? = null
)

data class ProfileRouteParam(val source: String)

data class SettingsRouteParam(val source: String)

data class DohSettingsRouteParam(val source: String)

data class PushSettingsRouteParam(val source: String)

data class NetworkSettingsRouteParam(val source: String)

data class LabSettingsRouteParam(val source: String)

data class DiagnosticsSettingsRouteParam(val source: String)

data class SitesRouteParam(val source: String)

data class LevelProgressRouteParam(val isLoggedIn: Boolean)

data class NotificationsRouteParam(val source: String)

data class ReadTopicsRouteParam(val source: String)

data class BookmarksRouteParam(val username: String)

data class DraftsRouteParam(val username: String)

data class BadgesRouteParam(val username: String, val badgeCount: Int)

data class UserProfileRouteParam(
    val username: String,
    val displayName: String? = null,
    val avatarTemplate: String? = null,
    val userId: Int? = null
)

data class SolvedRouteParam(val username: String, val solvedCount: Int)

data class ActivityRouteParam(val username: String, val filter: String, val title: String)

data class WebRouteParam(val url: String, val title: String? = null, val siteId: String? = null)

data class LdcCreditRouteParam(val startLogin: Boolean)

// ==================== 构造器 ====================

fun buildTopicDetailRouteParam(
    topicId: Int,
    slug: String?,
    title: String,
    targetPostNumber: Int? = null,
    highlightTarget: Boolean? = null
) = TopicDetailRouteParam(topicId, slug, title, targetPostNumber, highlightTarget)

fun buildTopicDetailDraftRouteParam(
    topicId: Int,
    title: String,
    draftReply: String,
    draftKey: String,
    draftSequence: Int
) = TopicDetailRouteParam(
    topicId = topicId,
    title = title,
    draftReply = draftReply,
    draftKey = draftKey,
    draftSequence = draftSequence
)

fun buildProfileRouteParam(source: String) = ProfileRouteParam(source)
fun buildSettingsRouteParam(source: String) = SettingsRouteParam(source)
fun buildDohSettingsRouteParam(source: String) = DohSettingsRouteParam(source)
fun buildPushSettingsRouteParam(source: String) = PushSettingsRouteParam(source)
fun buildNetworkSettingsRouteParam(source: String) = NetworkSettingsRouteParam(source)
fun buildLabSettingsRouteParam(source: String) = LabSettingsRouteParam(source)
fun buildDiagnosticsSettingsRouteParam(source: String) = DiagnosticsSettingsRouteParam(source)
fun buildSitesRouteParam(source: String) = SitesRouteParam(source)
fun buildLevelProgressRouteParam(isLoggedIn: Boolean) = LevelProgressRouteParam(isLoggedIn)
fun buildNotificationsRouteParam(source: String) = NotificationsRouteParam(source)
fun buildReadTopicsRouteParam(source: String) = ReadTopicsRouteParam(source)
fun buildBookmarksRouteParam(username: String) = BookmarksRouteParam(username)
fun buildDraftsRouteParam(username: String) = DraftsRouteParam(username)
fun buildBadgesRouteParam(username: String, badgeCount: Int) = BadgesRouteParam(username, badgeCount)
fun buildUserProfileRouteParam(username: String, displayName: String? = null, avatarTemplate: String? = null, userId: Int? = null) =
    UserProfileRouteParam(username, displayName, avatarTemplate, userId)
fun buildSolvedRouteParam(username: String, solvedCount: Int) = SolvedRouteParam(username, solvedCount)
fun buildActivityRouteParam(username: String, filter: String, title: String) = ActivityRouteParam(username, filter, title)
fun buildWebRouteParam(url: String, title: String? = null, siteId: String? = null) = WebRouteParam(url, title, siteId)
fun buildLdcCreditRouteParam(startLogin: Boolean) = LdcCreditRouteParam(startLogin)

// ==================== 判定语 ====================

fun isBookmarksRouteParam(p: Any?): Boolean = (p as? BookmarksRouteParam)?.username?.isNotEmpty() == true

fun isDraftsRouteParam(p: Any?): Boolean = (p as? DraftsRouteParam)?.username != null

fun isBadgesRouteParam(p: Any?): Boolean = (p as? BadgesRouteParam)?.username?.isNotEmpty() == true

fun isUserProfileRouteParam(p: Any?): Boolean = (p as? UserProfileRouteParam)?.username?.isNotEmpty() == true

fun isSolvedRouteParam(p: Any?): Boolean = (p as? SolvedRouteParam)?.username?.isNotEmpty() == true

fun isActivityRouteParam(p: Any?): Boolean {
    val a = p as? ActivityRouteParam ?: return false
    return a.username.isNotEmpty() && a.filter.isNotEmpty()
}

fun isWebRouteParam(p: Any?): Boolean {
    val w = p as? WebRouteParam ?: return false
    return w.url.isNotEmpty()
}

fun isLdcCreditRouteParam(p: Any?): Boolean = p is LdcCreditRouteParam

fun isTopicDetailRouteParam(p: Any?): Boolean {
    val t = p as? TopicDetailRouteParam ?: return false
    return t.title.isNotEmpty()
}