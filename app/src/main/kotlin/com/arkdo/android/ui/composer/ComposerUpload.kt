package com.arkdo.android.ui.composer

import android.content.Context
import android.net.Uri
import com.arkdo.android.services.network.NetworkResult
import com.arkdo.android.services.upload.UploadService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import timber.log.Timber

/**
 * 上传结果解析：发帖（[NewTopicViewModel]）与回帖（`TopicDetailViewModel`）共用一份。
 * 分出去的原因和 [ComposerCommon] 一样 —— 两个入口各留一份，"失败了给用户看什么话"
 * 会慢慢分叉。
 *
 * 对齐鸿蒙 UploadService.uploadEncoded 的解析段：图片取 short_url 拼 `![]()`；附件还要
 * `human_filesize`（服务端回什么就用什么）；音视频要 `short_path`（裸 `<video>` 里写
 * `upload://` 短码不会被解析，出来是死链）与真实宽高。
 */
internal data class UploadOutcome(val snippet: String?, val error: String?)

/**
 * 上传入口类型。决定遮罩文案、结果拼装方式，以及音视频那条要不要走 .xz 伪装
 * （[com.arkdo.android.services.upload.DISGUISE_EXTENSION]）。
 *
 * 公开而非 internal：两个 ViewModel 的公开上传方法都以它作参数。
 */
enum class ComposerUploadKind(val label: String, val progressText: String) {
    Image("图片", "图片上传中..."),
    Attachment("附件", "附件上传中..."),
    Media("视频", "视频上传中...")
}

/** 一次成功上传的服务端字段（三条入口共用）。 */
internal data class UploadedFileInfo(
    val shortUrl: String,
    val shortPath: String,
    val url: String,
    val fileName: String,
    val width: Int,
    val height: Int,
    val fileSize: Long,
    val humanFileSize: String,
)

internal data class ParsedUpload(val file: UploadedFileInfo?, val error: String?)

private val uploadJson = Json { ignoreUnknownKeys = true }

/**
 * 解析 /uploads.json 响应为字段集合；失败给用户可读文案。
 *
 * [kindLabel] 只影响失败文案（"图片"/"附件"/"视频"），不影响解析。
 */
internal fun parseUploadedFile(
    response: NetworkResult<String>,
    kindLabel: String = "文件",
    fallbackName: String = "",
    fallbackSize: Long = 0L,
): ParsedUpload {
    if (!response.success || response.data == null) {
        return ParsedUpload(null, response.error?.message ?: "${kindLabel}上传失败，请稍后重试。")
    }
    return runCatching {
        val root = uploadJson.parseToJsonElement(response.data).jsonObject
        val shortUrl = root["short_url"]?.jsonPrimitive?.content
        if (shortUrl.isNullOrEmpty()) {
            // 2xx 但缺 short_url = Discourse 校验失败，服务端 errors 里有人话。
            val errors = root["errors"]?.jsonArray?.joinToString(" ") { it.jsonPrimitive.content }
            ParsedUpload(null, errors?.takeIf { it.isNotEmpty() } ?: "${kindLabel}上传失败，请检查格式或大小。")
        } else {
            val size = root["filesize"]?.jsonPrimitive?.longOrNull ?: fallbackSize
            val human = root["human_filesize"]?.jsonPrimitive?.content.orEmpty()
            val file = UploadedFileInfo(
                shortUrl = shortUrl,
                shortPath = root["short_path"]?.jsonPrimitive?.content.orEmpty(),
                url = root["url"]?.jsonPrimitive?.content.orEmpty(),
                fileName = root["original_filename"]?.jsonPrimitive?.content.orEmpty().ifEmpty { fallbackName },
                width = root["width"]?.jsonPrimitive?.intOrNull ?: 0,
                height = root["height"]?.jsonPrimitive?.intOrNull ?: 0,
                fileSize = size,
                humanFileSize = human.ifEmpty { UploadService.humanSize(size) },
            )
            // 记下「短码/short_path → 真实直链」：本地预览解析 URL 时靠它换成能取到字节的
            // CDN 直链（linux.do 的 /uploads/short-url/ 对图片不回字节），详见该表注释。
            com.arkdo.android.services.content.UploadShortcodeRegistry.remember(
                file.shortUrl, file.shortPath, file.url
            )
            // 诊断日志：上传件在**本地预览**里能显示，靠的是上面那张映射表把
            // `upload://X.ext` / `/uploads/short-url/X.ext` 换成 CDN 直链；这一步是纯字符串
            // 替换做不到的，所以三个 URL 字段一起打出来，便于比对。
            Timber.i(
                "[ArkDO][Composer] 上传成功 name=%s shortUrl=%s shortPath=%s url=%s",
                file.fileName, file.shortUrl, file.shortPath, file.url
            )
            ParsedUpload(file = file, error = null)
        }
    }.getOrElse {
        Timber.e(it, "[ArkDO][Composer] 上传响应解析失败")
        ParsedUpload(null, "${kindLabel}上传失败，请稍后重试。")
    }
}

/**
 * 一批文件的完整处理：逐个「读字节 → 按类型上传 → 回传可插入的 markdown 片段」。
 *
 * **串行而非并发**：站点有 `max_uploads_per_minute` 限流，并发发出去反而更容易被拒；
 * 而且插入位置按当前光标算，乱序返回会让正文顺序与用户选的顺序对不上
 * （对齐鸿蒙 ComposerPage.runUploadBatch）。
 *
 * 一批里可能只坏一个（比如其中一个包超了 4 MB）。逐条记原因，由调用方在批量结束后
 * 一次性提示，避免连弹好几个 toast 互相盖掉。
 *
 * @param uploadOne 实际发请求的那一步，由各 ViewModel 注入（发帖页与回帖页各有一份实现）。
 * @return 第一条失败文案；全部成功返回 null。
 */
internal suspend fun runUploadBatch(
    context: Context,
    kind: ComposerUploadKind,
    uris: List<Uri>,
    limitKb: Int,
    onProgress: (String) -> Unit,
    onSnippet: (String) -> Unit,
    uploadOne: suspend (PreparedRawFile, Int, Int) -> UploadOutcome,
): String? {
    if (uris.isEmpty()) return null
    var firstError: String? = null
    val resolver = context.contentResolver
    uris.forEachIndexed { index, uri ->
        onProgress(
            if (uris.size > 1) "${kind.label}上传中 (${index + 1}/${uris.size})" else "${kind.label}上传中..."
        )
        when (val prepared = withContext(Dispatchers.IO) {
            prepareRawUpload(resolver, uri, limitKb, kind.label)
        }) {
            is RawFileResult.Failed -> if (firstError == null) firstError = prepared.message
            is RawFileResult.Ok -> {
                // 视频要自己量播放方向尺寸：服务端对 .xz 不回宽高，不量的话竖屏视频会被摆歪。
                val size = if (kind == ComposerUploadKind.Media) {
                    withContext(Dispatchers.IO) { probeVideoDisplaySize(context, uri) }
                } else {
                    0 to 0
                }
                val outcome = uploadOne(prepared.file, size.first, size.second)
                outcome.snippet?.let(onSnippet)
                val error = outcome.error
                if (!error.isNullOrEmpty() && firstError == null) firstError = error
            }
        }
    }
    return firstError
}

/** 解析 /uploads.json 响应：成功取 short_url 拼 markdown 图；失败给用户可读文案。 */
internal fun parseUploadOutcome(response: NetworkResult<String>): UploadOutcome {
    val parsed = parseUploadedFile(response, kindLabel = "图片")
    val file = parsed.file ?: return UploadOutcome(null, parsed.error)
    return UploadOutcome(UploadMarkdown.buildImage(file.fileName, file.width, file.height, file.shortUrl), null)
}

/** 附件：`[名.zip|attachment](upload://…) (38.3 KB)`。 */
internal fun parseAttachmentOutcome(response: NetworkResult<String>): UploadOutcome {
    val parsed = parseUploadedFile(response, kindLabel = "附件")
    val file = parsed.file ?: return UploadOutcome(null, parsed.error)
    return UploadOutcome(
        UploadService.buildAttachmentMarkdown(file.fileName, file.humanFileSize, file.shortUrl),
        null,
    )
}

/**
 * 音视频：拼裸 `<video>` 标签。
 *
 * [localWidth] / [localHeight] 是本地量到的播放方向尺寸 —— 服务端对 .xz 回的
 * width/height 是 null（它只认得图片），不自己量就只能写死一个横向框，竖屏视频会被摆歪。
 * [mediaMimeType] 必须是真实媒体类型（如 video/mp4），不是 .xz 的类型。
 */
internal fun parseMediaOutcome(
    response: NetworkResult<String>,
    localWidth: Int,
    localHeight: Int,
    mediaMimeType: String,
): UploadOutcome {
    val parsed = parseUploadedFile(response, kindLabel = "视频")
    val file = parsed.file ?: return UploadOutcome(null, parsed.error)
    val width = if (file.width > 0) file.width else localWidth
    val height = if (file.height > 0) file.height else localHeight
    return UploadOutcome(
        UploadService.buildVideoHtml(file.shortPath, file.url, width, height, mediaMimeType),
        null,
    )
}
