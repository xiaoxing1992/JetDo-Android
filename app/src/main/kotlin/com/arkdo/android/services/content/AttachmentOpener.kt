package com.arkdo.android.services.content

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/**
 * 附件（及下到沙箱的媒体）「交给系统打开 / 分享」。
 *
 * 为什么必须走 FileProvider：附件落在**应用专属外部目录**（`getExternalFilesDir()/download`），
 * 系统「文件」App 看不到它，也更不能拿 `file://` 直接塞进 Intent —— Android 7 起
 * `FileUriExposedException` 会当场抛。必须换成 `content://` 并授临时读权限。
 *
 * 对齐鸿蒙 `services/content/DownloadService.ets`：那边下载由 host webview 的原生下载委托完成
 * （带会话 Cookie、自动跟随重定向），本类只负责「下完之后怎么交出去」以及扩展名 → MIME 的推断。
 */
object AttachmentOpener {

    private const val MIME_FALLBACK = "*/*"

    /**
     * 常见附件扩展名 → MIME，用于给「打开方式」选择器正确的类型提示。
     * 逐条对齐鸿蒙 `DownloadService.MIME_BY_EXT`，另补了几个 Android 侧常见的归档/媒体类型。
     */
    private val MIME_BY_EXT: Map<String, String> = mapOf(
        "txt" to "text/plain",
        "log" to "text/plain",
        "json" to "application/json",
        "md" to "text/markdown",
        "csv" to "text/csv",
        "pdf" to "application/pdf",
        "zip" to "application/zip",
        "rar" to "application/vnd.rar",
        "7z" to "application/x-7z-compressed",
        "gz" to "application/gzip",
        "xz" to "application/x-xz",
        "tar" to "application/x-tar",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "mp4" to "video/mp4",
        "mov" to "video/quicktime",
        "webm" to "video/webm",
        "mkv" to "video/x-matroska",
        "mp3" to "audio/mpeg",
        "m4a" to "audio/mp4",
        "wav" to "audio/wav",
        "flac" to "audio/flac",
        "apk" to "application/vnd.android.package-archive",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    )

    /** 扩展名 → MIME。认得就返回，认不得返回通配类型（对齐鸿蒙 mimeOf 的兜底）。 */
    fun mimeOf(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        if (dot < 0 || dot == fileName.length - 1) return MIME_FALLBACK
        val ext = fileName.substring(dot + 1).lowercase()
        return MIME_BY_EXT[ext] ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: MIME_FALLBACK
    }

    /**
     * 用系统「打开方式」面板打开沙箱里的文件。
     *
     * 先试 `ACTION_VIEW`（有能处理的 App 就直接进）；没有 App 认领时退到 `ACTION_SEND`
     * 分享面板 —— 这样「下载一个 .zip 但没装解压软件」不至于点了个寂寞，至少能发给别人或存进网盘。
     *
     * 返回 false 表示两条路都没人接（极罕见，系统自己也总有个「文件」）。
     */
    fun openWithSystem(context: Context, file: File, mimeType: String = mimeOf(file.name)): Boolean {
        val uri = uriFor(context, file) ?: return false
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (launch(context, Intent.createChooser(view, "打开方式"))) return true
        return share(context, file, mimeType)
    }

    /** 直接拉起分享面板。 */
    fun share(context: Context, file: File, mimeType: String = mimeOf(file.name)): Boolean {
        val uri = uriFor(context, file) ?: return false
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return launch(context, Intent.createChooser(send, "分享文件"))
    }

    /**
     * 沙箱文件 → 可对外授权的 `content://`。失败（FileProvider 配置没覆盖到该目录）返回 null。
     *
     * authority 与 Manifest 里 `${applicationId}.fileprovider` 一致 —— 用 `packageName` 拼，
     * 这样 debug/release 后缀不同也照样对得上。
     */
    private fun uriFor(context: Context, file: File): Uri? = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull()

    private fun launch(context: Context, intent: Intent): Boolean = runCatching {
        // 从非 Activity 上下文也能起面板；ACTION_VIEW/SEND 一律从新任务起，避免污染当前栈。
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}
