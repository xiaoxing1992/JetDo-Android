package com.arkdo.android.services.content

import com.arkdo.android.common.constants.AppConstants

// ==========================================================================
// HTML 内容纯函数工具，1:1 对齐鸿蒙 services/content/HtmlContentUtils.ets。
// jsoup 已把文本/属性中的实体解码成原字符，因此 ArkTS 版的 decodeHtmlEntities
// 只在处理「原始 HTML 片段」（如引用标题、onebox 抽取）时才需要。
// ==========================================================================

/** 站内 emoji 图片的相对路径前缀，resolveUrl 据此把它们挂 CDN 而非主站。 */
private const val EMOJI_PATH_PREFIX = "/images/emoji/"

/** 站点相对路径（/uploads/... 等）挂主站。 */
fun resolveUrl(rawUrl: String): String {
    val url = rawUrl.trim()
    if (url.isEmpty() || url.startsWith("upload://")) {
        return url
    }
    if (url.startsWith("http://") || url.startsWith("https://")) {
        return url
    }
    if (url.startsWith("//")) {
        return "https:$url"
    }
    if (url.startsWith(EMOJI_PATH_PREFIX)) {
        // 正文 cooked 里的 emoji 服务端已改写成 CDN 绝对地址，但 discourse-boosts 的 cooked
        // 不走那套改写，吐的是站内相对路径。拼 BASE_URL 会得到必然加载不出的主站直链，
        // 故这里挂 CDN（同一份文件）。
        return "${AppConstants.STATIC_CDN_BASE}$url"
    }
    if (url.startsWith("/")) {
        return "${AppConstants.BASE_URL}$url"
    }
    return url
}

/** 附件下载链接：显式 class="attachment"，或指向 /uploads/ 的站内链接。 */
fun isAttachmentHref(hrefClass: String, resolvedHref: String): Boolean =
    hrefClass.split(Regex("\\s+")).contains("attachment") ||
        Regex("^https?://[^/]*linux\\.do/uploads/", RegexOption.IGNORE_CASE)
            .containsMatchIn(resolvedHref) ||
        resolvedHref.startsWith("/uploads/")

fun normalizeText(text: String): String = text
    .replace(Regex("[ \\t]+\\n"), "\n")
    .replace(Regex("\\n[ \\t]+"), "\n")
    .replace(Regex("\\n{3,}"), "\n\n")
    .trim()

/** 去标签、保留换行语义（br/p/div/li 折行，li 前缀 "- "），供引用预览/公式抽取等场景。 */
fun stripTagsKeepingLines(html: String): String {
    var text = html
    text = text.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
    text = text.replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n")
    text = text.replace(Regex("</div>", RegexOption.IGNORE_CASE), "\n")
    text = text.replace(Regex("</li>", RegexOption.IGNORE_CASE), "\n")
    text = text.replace(Regex("<li\\b[^>]*>", RegexOption.IGNORE_CASE), "- ")
    text = text.replace(Regex("<[^>]+>"), "")
    return text
}

/** 把 HTML 转为规范化纯文本（去标签、保留换行语义、折叠多余空白）。 */
fun htmlToPlainText(html: String): String = normalizeText(stripTagsKeepingLines(html))

fun hostFromUrl(url: String): String =
    Regex("^https?://([^/]+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1) ?: ""

/** 取 style 里某个 CSS 变量的值（如 --category-badge-color: #9cb6c4）。 */
fun cssVar(style: String, name: String): String =
    Regex("${Regex.escape(name)}\\s*:\\s*([^;]+)", RegexOption.IGNORE_CASE)
        .find(style)?.groupValues?.get(1)?.trim() ?: ""

/** <pre><code class="lang-xxx"> 的语言抽取。 */
fun extractCodeLanguage(className: String): String =
    Regex("(?:lang|language)-([a-zA-Z0-9_+-]+)").find(className)?.groupValues?.get(1) ?: ""
