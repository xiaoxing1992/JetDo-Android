package com.jetdo.android.services.content

import com.jetdo.android.common.constants.AppConstants

// ==========================================================================
// HTML 内容纯函数工具，1:1 对齐鸿蒙 services/content/HtmlContentUtils.ets。
// jsoup 已把文本/属性中的实体解码成原字符，因此 ArkTS 版的 decodeHtmlEntities
// 只在处理「原始 HTML 片段」（如引用标题、onebox 抽取）时才需要。
// ==========================================================================

/** 站内 emoji 图片的相对路径前缀，resolveUrl 据此把它们挂 CDN 而非主站。 */
private const val EMOJI_PATH_PREFIX = "/images/emoji/"

/**
 * 上传短码前缀。`upload://<base62(sha1)><ext>` 只出现在**未经服务端 cook 的原始 markdown** 里
 * —— 也就是本地预览（详情页正文是服务端 cook 过的，服务端已把它换成真实直链，不会有它）。
 */
private const val UPLOAD_SHORTCODE_PREFIX = "upload://"

/**
 * 短码对应的**兜底**路径前缀。两处 hash 段同源：Discourse 的 `Upload#short_url` 与 `#short_path`
 * 都用 `UrlHelper.encode_base62_sha1(sha1)`，故 `upload://X.ext` → `/uploads/short-url/X.ext`
 * 是纯字符串替换（由上传响应的 short_url / short_path 字段实测确认）。
 *
 * **但它只是兜底，不是正解**：linux.do 是 S3 + CDN 部署，这条路由对图片稳定 404
 * （带完整浏览器头与会话 cookie 也一样），能取到图片的只有上传响应里的 `url`。
 * 所以先查 [UploadShortcodeRegistry]（上传成功时记下的短码 → 真实直链），查不到才落到这里。
 */
private const val UPLOAD_SHORT_PATH_PREFIX = "/uploads/short-url/"

/** 站点相对路径（/uploads/... 等）挂主站。 */
fun resolveUrl(rawUrl: String): String {
    val url = rawUrl.trim()
    if (url.isEmpty()) {
        return url
    }
    // 本地预览里的刚上传件：优先换成上传响应给的 CDN 直链（见 UploadShortcodeRegistry 的注释，
    // short-url 这条路由在 linux.do 上对图片不回字节）。
    UploadShortcodeRegistry.lookup(url)?.let { return it }
    if (url.startsWith(UPLOAD_SHORTCODE_PREFIX)) {
        // 本地预览里的上传件：短码不换成真实直链，<img>/<video> 拿到 upload:// 必然加载不出来
        // （浏览器/播放器不认这个私有协议）。表里没有时退到 short-url，至少对音视频有效。
        return "${AppConstants.BASE_URL}$UPLOAD_SHORT_PATH_PREFIX" +
            url.removePrefix(UPLOAD_SHORTCODE_PREFIX)
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
