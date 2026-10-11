package com.jetdo.android.services.upload

import android.content.Context
import com.jetdo.android.core.data.AppContextHolder
import com.jetdo.android.R
import com.jetdo.android.services.session.LoginGate
import com.jetdo.android.services.session.TRUST_LEVEL_UNKNOWN
import com.jetdo.android.services.site.IMAGE_EXTENSIONS
import com.jetdo.android.services.site.SiteUploadSettings
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

// ==========================================================================
// 与 HarmonyOS 版 services/upload/UploadService.ets 的纯函数部分逐条对齐。
//
// 三条上传入口的差别只在「传之前对字节做什么」：
//   · 图片   —— 按策略择机重编码/缩放（见 ComposerCommon.prepareImageUpload / UploadMarkdown）
//   · 附件   —— 原样字节，**保留原始文件名**（扩展名决定白名单与限额，不能改）
//   · 音视频 —— 原样字节，改名成 .xz（见 [DISGUISE_EXTENSION]）
//
// 本文件只放与 Android 框架无关的判定与拼装：体积上限、白名单、markdown 片段。
// 这样单测可以直接覆盖，不必起 Robolectric。
// ==========================================================================

/**
 * linux.do 传音视频时伪装用的扩展名。
 *
 * 那个站的 authorized_extensions 里没有 mp4/mp3，却有 xz；而 Discourse 判白名单与限额
 * **只看扩展名**（lib/validators/upload_validator.rb 的 File.extname），从不嗅探文件内容。
 * 于是站方的主题组件就把视频改名成 .xz 传上去，再插一个写死 type="video/mp4" 的裸
 * `<video>` 标签 —— 播放器认 type 属性，不认后缀，所以照播。
 *
 * 这是那个站的私有约定，不是 Discourse 行为，故整条路径由 [mediaUploadEnabled] 关着。
 */
const val DISGUISE_EXTENSION = "xz"
const val DISGUISE_MIME = "application/x-xz"

/**
 * `<video>` 标签宽高属性的长边基准。取 640 是为了让横向 16:9 正好落到站方组件那个
 * 640x360，产物在网页端与主题组件插出来的完全一致。
 */
const val VIDEO_BOX_LONG_SIDE = 640

/**
 * 客户端硬性上传上限，与站点设置取小。
 *
 * 上传要把整个文件读进内存 → base64 字符串 → 再拼进注入脚本的源码字符串，Web 侧 atob
 * 还要再解一份，峰值内存大约是文件本身的 4 倍。站点上限是站长按服务器定的（Discourse
 * 允许配到 1 GB），照着它走会直接把 App 撑爆。
 *
 * 32 MB 能容下实测见过的最宽松配置（meta.discourse.org 的 30000 KB），又把峰值压在 ~128 MB。
 */
const val CLIENT_MAX_UPLOAD_BYTES = 32L * 1024 * 1024

/** 常见扩展名 → MIME。服务端会按文件名重新判定，这里给个像样的值即可。 */
private val MIME_BY_EXTENSION: Map<String, String> = mapOf(
    "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif",
    "webp" to "image/webp", "avif" to "image/avif", "heic" to "image/heic", "heif" to "image/heif",
    "svg" to "image/svg+xml", "bmp" to "image/bmp", "ico" to "image/x-icon", "jxl" to "image/jxl",
    "mp4" to "video/mp4", "m4v" to "video/mp4", "mov" to "video/quicktime", "webm" to "video/webm",
    "mkv" to "video/x-matroska", "3gp" to "video/3gpp", "avi" to "video/x-msvideo",
    "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac", "wav" to "audio/wav",
    "ogg" to "audio/ogg", "oga" to "audio/ogg", "opus" to "audio/opus", "flac" to "audio/flac",
    "pdf" to "application/pdf", "txt" to "text/plain", "md" to "text/markdown", "csv" to "text/csv",
    "json" to "application/json", "yml" to "text/yaml", "yaml" to "text/yaml",
    "zip" to "application/zip", "7z" to "application/x-7z-compressed", "gz" to "application/gzip",
    "xz" to DISGUISE_MIME, "tar" to "application/x-tar", "rar" to "application/vnd.rar",
    "doc" to "application/msword",
    "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "xls" to "application/vnd.ms-excel",
    "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
)

/** `<video>` 的宽高属性。 */
data class VideoBox(val width: Int, val height: Int)

object UploadService {

    // ==================== markdown 拼装 ====================

    /**
     * 附件 markdown：`[文件名.zip|attachment](upload://…) (38.3 KB)`。
     *
     * 格式抄自 frontend/discourse/app/lib/uploads.js 的 attachmentMarkdown —— 那个
     * `|attachment` 标记是 Discourse 服务端 cook 成 `<a class="attachment">` 的依据，
     * 少了它就只是个普通链接，也不会被 PostAnalyzer#attachment_count 数进去。
     */
    fun buildAttachmentMarkdown(fileName: String, humanFileSize: String, shortUrl: String): String {
        val name = markdownSafeName(fileName, "file")
        val size = if (humanFileSize.isNotEmpty()) " ($humanFileSize)" else ""
        return "[$name|attachment]($shortUrl)$size"
    }

    /**
     * 音视频的裸 `<video>` 标签（linux.do 专用）。
     *
     * 三个要点，都不能改：
     *  · 用 shortPath 而不是 upload:// —— 短码只在 markdown 的链接/图片 token 里被
     *    upload-protocol 规则改写，写进原始 HTML 不会被解析，出来就是个死链。
     *  · type 写真实媒体类型而非 .xz 的类型 —— 播放器认 type 属性，这正是伪装能生效的原因。
     *  · 宽高按**真实比例**算，不能像站方组件那样写死 640x360 —— 我们自己的渲染器会读这两个
     *    属性去定版面，写死横向框会把竖屏视频摆歪。
     */
    fun buildVideoHtml(
        shortPath: String,
        url: String,
        width: Int,
        height: Int,
        mediaMimeType: String,
    ): String {
        val src = shortPath.ifEmpty { url }
        val box = videoBox(width, height)
        return "<video width=\"${box.width}\" height=\"${box.height}\" controls>\n" +
            "  <source src=\"$src\" type=\"$mediaMimeType\">\n" +
            AppContextHolder.getString(R.string.video_tag_fallback) +
            "</video>"
    }

    /**
     * `<video>` 标签的宽高属性：等比缩到长边 [VIDEO_BOX_LONG_SIDE]。
     * 横向 16:9 会正好得到 640x360 —— 与站方主题组件的产物一致，不引入观感差异；
     * 竖屏则得到 360x640，这才是原先写死 640x360 时错掉的那一类。
     * 量不到尺寸时退回 640x360（总得给个值，横向是更常见的情形）。
     */
    fun videoBox(width: Int, height: Int): VideoBox {
        if (width <= 0 || height <= 0) {
            return VideoBox(VIDEO_BOX_LONG_SIDE, (VIDEO_BOX_LONG_SIDE * 9.0 / 16.0).roundToInt())
        }
        val longSide = max(width, height)
        if (longSide <= VIDEO_BOX_LONG_SIDE) return VideoBox(width, height)
        val factor = VIDEO_BOX_LONG_SIDE.toDouble() / longSide
        return VideoBox(
            width = max(1, (width * factor).roundToInt()),
            height = max(1, (height * factor).roundToInt()),
        )
    }

    /** markdown 链接文字里的 `[` `]` `|` 会破坏语法，与官方 uploads.js 一样直接剔除。 */
    fun markdownSafeName(fileName: String, fallback: String): String {
        val cleaned = fileName.replace(Regex("[\\[\\]|]"), "")
        return cleaned.ifEmpty { fallback }
    }

    // ==================== 白名单与限额 ====================

    /**
     * 当前用户实际可用的扩展名白名单。
     *
     * `authorized_extensions_for_staff` 顾名思义只对版主/管理员生效（服务端在
     * lib/validators/upload_validator.rb 里也是按 user.staff? 才并进去的）。普通用户身上
     * 也算进来的话，会在「选文件」对话框里看到一堆自己传不了的后缀，选完传上去才被拒。
     */
    fun allowedExtensions(settings: SiteUploadSettings): List<String> {
        // 身份未知时**按 staff 放行**，与 LoginGate.canUploadAttachment 同一条约定：
        // 对版主来说首次会话检查回来之前 isStaff() 都是 false。此时若按"非 staff"处理，
        // 选择器会把他真能传的后缀过滤掉、上传还会被服务端收下后报错。
        // 宁可放行让服务端裁决（我们会把 422 的原因显示出来），也不要凭一个尚未就位的身份藏掉功能。
        val privileged = LoginGate.isStaff() || LoginGate.trustLevel() == TRUST_LEVEL_UNKNOWN
        return if (!privileged) {
            settings.authorizedExtensions
        } else {
            settings.authorizedExtensions + settings.authorizedExtensionsForStaff
        }
    }

    /** 当前用户能传的非图片扩展名。选文件对话框的后缀过滤用它；空 = 藏掉附件入口。 */
    fun nonImageExtensions(settings: SiteUploadSettings): List<String> =
        allowedExtensions(settings).filterNot { isImageExtension(it) }.distinct()

    /** 是否是 Discourse 认定的图片扩展名（决定走哪条体积上限）。 */
    fun isImageExtension(extension: String): Boolean =
        IMAGE_EXTENSIONS.contains(extension.lowercase())

    /** 给定站点上限（KB）下，本次实际生效的字节上限。站点上限与客户端硬上限取小。 */
    fun effectiveLimitBytes(siteLimitKb: Int): Long {
        val siteLimit = siteLimitKb.toLong() * 1024
        return minOf(siteLimit, CLIENT_MAX_UPLOAD_BYTES)
    }

    /** 超限返回可展示的文案，没超返回空串。 */
    fun checkSize(bytes: Long, siteLimitKb: Int, kindLabel: String): String {
        val siteLimitBytes = siteLimitKb.toLong() * 1024
        val effective = effectiveLimitBytes(siteLimitKb)
        if (bytes <= effective) return ""
        return if (siteLimitBytes <= CLIENT_MAX_UPLOAD_BYTES) {
            AppContextHolder.getString(R.string.composer_upload_too_large_site, kindLabel, humanSize(bytes), humanSize(siteLimitBytes))
        } else {
            AppContextHolder.getString(R.string.composer_upload_too_large_app, kindLabel, humanSize(bytes), humanSize(effective))
        }
    }

    /** 只用于我们自己生成的文案；服务端回了 human_filesize 时优先用它的。 */
    fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    // ==================== 文件名 ====================

    /** 小写扩展名，不带点；没有扩展名返回空串。 */
    fun extensionOf(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        if (dot < 0 || dot == fileName.length - 1) return ""
        return fileName.substring(dot + 1).lowercase()
    }

    /** 去掉扩展名的文件名；`.gitignore` 这类以点开头的名字原样返回。 */
    fun baseNameOf(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot > 0) fileName.substring(0, dot) else fileName
    }

    /**
     * 音视频上传时对外的文件名：原名 + `.xz`（见 [DISGUISE_EXTENSION]）。
     * 扩展名决定白名单与限额，所以这一步是整条 linux.do 路径的钥匙。
     */
    fun disguisedName(fileName: String): String = "${baseNameOf(fileName)}.$DISGUISE_EXTENSION"

    /** 按扩展名猜 MIME；认不出给 octet-stream。 */
    fun mimeFor(extension: String): String =
        MIME_BY_EXTENSION[extension.lowercase()] ?: "application/octet-stream"

    /**
     * 音视频上传入口是否露出。
     *
     * 鸿蒙侧是 `SiteCapabilities.mediaUpload`（按域名判 linux.do）。Android 目前只有
     * linux.do 一个站点（`AppConstants.BASE_URL` 硬编码），所以这里等价于常开；
     * 等多站点落地后改成走 CurrentSite 判定，判据只留这一处。
     */
    fun mediaUploadEnabled(): Boolean = true
}
