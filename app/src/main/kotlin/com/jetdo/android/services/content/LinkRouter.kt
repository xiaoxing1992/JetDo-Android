package com.jetdo.android.services.content

import com.jetdo.android.common.constants.AppConstants

// ==========================================================================
// 链接路由决策，1:1 对齐鸿蒙 services/content/LinkRouter.ets。
// 点击链接前的统一路由判断：同域链接按路径分流（帖子/下载/用户主页），
// 其余（含外站、站内非帖子页）交给通用 WebView / 系统浏览器。
// ==========================================================================

enum class LinkTargetKind {
    /** 站内帖子链接：提取 topicId 后跳原生详情页，不进通用 WebView。 */
    TOPIC,
    /** 站内附件下载链接（/uploads/）：应触发原生下载（带登录 Cookie），进 WebView 只会空白。 */
    DOWNLOAD,
    /** 站内用户主页链接（/u/<username>，正文里的 @提及 就是这个）：跳原生他人主页。 */
    USER,
    /** 其他一切链接（外站、站内非帖子页）：进通用 WebView。 */
    WEB
}

sealed interface LinkRoute {
    /** 站内帖子：开原生详情页（postNumber 可选，用于定位楼层）。 */
    data class Topic(val topicId: Long, val targetPostNumber: Int?) : LinkRoute

    /** 站内附件下载（/uploads/）。 */
    data class Download(val url: String) : LinkRoute

    /** 站内用户主页（/u/<username>，正文 @提及）。 */
    data class User(val url: String, val username: String) : LinkRoute

    /** 其余链接：通用 WebView / 系统浏览器。 */
    data class Web(val url: String) : LinkRoute
}

/** /u/ 下并非用户主页的保留段（注册激活、改密、邮箱确认等），这些仍走 WebView。 */
private val USER_PATH_RESERVED = listOf(
    "account-created", "activate-account", "password-reset", "confirm-email-token",
    "authorize-email", "admin-login", "email-login", "second-factor",
    "confirm-old-email", "confirm-new-email"
)

/** Discourse 用户名字符集：字母数字加 . _ -。保留段（激活/改密链接）不算用户名。 */
private fun isUsername(segment: String): Boolean {
    if (segment.isEmpty() || !Regex("^[A-Za-z0-9._-]+$").matches(segment)) {
        return false
    }
    return USER_PATH_RESERVED.contains(segment.lowercase()).not()
}

private fun decodeSegment(segment: String): String =
    runCatching { java.net.URLDecoder.decode(segment, "UTF-8") }.getOrDefault(segment)

private fun hostOf(url: String): String =
    Regex("^https?://([^/?#]+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)?.lowercase() ?: ""

private fun isSameHost(url: String): Boolean {
    val host = hostOf(url)
    if (host.isEmpty()) {
        return false
    }
    return host == hostOf(AppConstants.BASE_URL)
}

private fun pathOf(url: String): String {
    val withoutScheme = url.replace(Regex("^https?://[^/?#]+", RegexOption.IGNORE_CASE), "")
    val cut = withoutScheme.indexOfFirst { it == '?' || it == '#' }
    val path = if (cut >= 0) withoutScheme.substring(0, cut) else withoutScheme
    return path.ifEmpty { "/" }
}

/**
 * 点击链接前的统一路由判断：识别站内帖子/下载/用户主页链接走原生，
 * 其余交给通用 WebView。注意同帖定位（Jump）是页面层职责，不在本路由内。
 */
fun resolveLinkRoute(rawUrl: String): LinkRoute {
    val url = rawUrl.trim()
    if (url.isEmpty()) {
        return LinkRoute.Web(url)
    }
    if (!isSameHost(url)) {
        return LinkRoute.Web(url)
    }
    val path = pathOf(url)

    // 附件下载：/uploads/short-url/... 或 /uploads/default/...。服务端返回
    // Content-Disposition，用 WebView 打开只会空白，应触发原生下载（带登录 Cookie）。
    if (Regex("^/uploads/", RegexOption.IGNORE_CASE).containsMatchIn(path)) {
        return LinkRoute.Download(url)
    }

    // 用户主页：/u/<username>[/summary|/activity|...]。正文 @提及（<a class="mention">）走这条。
    val userMatch = Regex("^/u/([^/?#]+)", RegexOption.IGNORE_CASE).find(path)
    if (userMatch != null) {
        val username = decodeSegment(userMatch.groupValues[1])
        if (isUsername(username)) {
            return LinkRoute.User(url = url, username = username)
        }
    }

    // 站内帖子：/t/{slug 可选}/{topicId}[/{postNumber}]，slug 段可有可无（旧链接 /t/12345）。
    val topicMatch = Regex("^/t/(?:[^/]+/)?(\\d+)(?:/(\\d+))?").find(path)
    if (topicMatch != null) {
        // Kotlin groupValues 0 基：1 = topicId，2 = 楼号（鸿蒙 JS 是 1 基，直接照搬会取错组）。
        val topicId = topicMatch.groupValues[1].toLongOrNull() ?: 0L
        if (topicId > 0) {
            val postNumber = topicMatch.groupValues[2].toIntOrNull()?.takeIf { it > 0 }
            return LinkRoute.Topic(topicId = topicId, targetPostNumber = postNumber)
        }
    }

    return LinkRoute.Web(url)
}
