package com.arkdo.android.ui.composer

import androidx.compose.ui.res.stringResource
import com.arkdo.android.core.data.AppContextHolder
import com.arkdo.android.R
import android.content.Context
import android.net.Uri
import android.util.Base64
import com.arkdo.android.services.network.NetworkResult
import com.arkdo.android.services.upload.UploadService
import com.arkdo.android.services.upload.VideoTranscoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import timber.log.Timber
import java.io.File

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
    Image(AppContextHolder.getString(R.string.composer_kind_image), AppContextHolder.getString(R.string.composer_uploading_image)),
    Attachment(AppContextHolder.getString(R.string.composer_kind_file), AppContextHolder.getString(R.string.composer_uploading_file)),
    Media(AppContextHolder.getString(R.string.composer_kind_video), AppContextHolder.getString(R.string.composer_uploading_video))
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
    kindLabel: String = AppContextHolder.getString(R.string.composer_file),
    fallbackName: String = "",
    fallbackSize: Long = 0L,
): ParsedUpload {
    if (!response.success || response.data == null) {
        return ParsedUpload(null, response.error?.message ?: AppContextHolder.getString(R.string.composer_upload_failed, kindLabel))
    }
    return runCatching {
        val root = uploadJson.parseToJsonElement(response.data).jsonObject
        val shortUrl = root["short_url"]?.jsonPrimitive?.content
        if (shortUrl.isNullOrEmpty()) {
            // 2xx 但缺 short_url = Discourse 校验失败，服务端 errors 里有人话。
            val errors = root["errors"]?.jsonArray?.joinToString(" ") { it.jsonPrimitive.content }
            ParsedUpload(null, errors?.takeIf { it.isNotEmpty() } ?: AppContextHolder.getString(R.string.composer_upload_failed_format, kindLabel))
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
        ParsedUpload(null, AppContextHolder.getString(R.string.composer_upload_failed, kindLabel))
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
    onProgress: (UploadProgress) -> Unit,
    onSnippet: (String) -> Unit,
    uploadOne: suspend (PreparedRawFile, Int, Int) -> UploadOutcome,
): String? {
    if (uris.isEmpty()) return null
    var firstError: String? = null
    val resolver = context.contentResolver

    /** 除「压缩进度」外的常态文案。压缩阶段由 [prepareMediaForBatch] 自己往外发。 */
    fun phaseText(index: Int) = UploadProgress(
        if (uris.size > 1) AppContextHolder.getString(R.string.composer_uploading_n, kind.label, index + 1, uris.size) else AppContextHolder.getString(R.string.composer_uploading_kind, kind.label)
    )

    uris.forEachIndexed { index, uri ->
        onProgress(phaseText(index))
        when (val prepared = withContext(Dispatchers.IO) {
            // 三条入口的「读字节」各不相同：
            //  · 图片先缩到长边 ≤1920 再编码，否则一张 1080×2400 的截图会以 320 KB PNG
            //    原样上传 —— 既慢又容易撞上传超时；
            //  · 音视频在超站点上限时**先转码**再读字节（见 VideoTranscoder），转码那几十秒
            //    的百分比进度与取消口子经 onProgress 传出去；
            //  · 附件原样读，绝不重编码（扩展名决定白名单与限额，改名就会被服务端拒）。
            when (kind) {
                ComposerUploadKind.Image -> prepareImageForBatch(resolver, uri, limitKb, kind.label)
                ComposerUploadKind.Attachment -> prepareRawUpload(resolver, uri, limitKb, kind.label)
                ComposerUploadKind.Media -> prepareMediaForBatch(context, uri, limitKb, onProgress)
            }
        }) {
            is RawFileResult.Failed -> {
                // 空文案 = 用户主动取消（见 prepareMediaForBatch），不当失败上报 ——
                // 对齐鸿蒙「空 error = 用户主动取消，上层不该再弹提示」。
                if (prepared.message.isNotEmpty() && firstError == null) firstError = prepared.message
            }
            is RawFileResult.Ok -> {
                // 转码结束后把浮层切回「上传中」—— 否则会一直停在「压缩中 100%」，而真正
                // 在跑的是上传。
                onProgress(phaseText(index))
                // 视频的播放方向尺寸由 prepareMediaForBatch 顺带探出来（转码路径本来就要探）。
                val outcome = uploadOne(
                    prepared.file,
                    prepared.file.displayWidth,
                    prepared.file.displayHeight
                )
                outcome.snippet?.let(onSnippet)
                val error = outcome.error
                if (!error.isNullOrEmpty() && firstError == null) firstError = error
            }
        }
    }
    return firstError
}

/**
 * 音视频版的「读字节」：超站点上限时先转码到目标体积以内，再读字节。
 *
 * 已经达标就**不再编一遍** —— 重编码是纯画质净赔。不达标才落到 [VideoTranscoder]：
 * 手机实拍 1080p 约 17 Mbps，随便一段都远超 4 MB，不压等于这个入口没法用。
 *
 * 转码产物落 cacheDir（前缀 `arkdo_transcode_`），读完字节**一定**删掉；冷启动还会
 * 由 `VideoTranscoder.clearStaleOutputs` 兜一次（崩溃/被回收时 finally 不会跑）。
 */
private suspend fun prepareMediaForBatch(
    context: Context,
    uri: Uri,
    siteLimitKb: Int,
    onProgress: (UploadProgress) -> Unit
): RawFileResult {
    val resolver = context.contentResolver
    val fileName = queryDisplayName(resolver, uri)
        ?: uri.lastPathSegment?.substringAfterLast('/')
        ?: "video"

    // 探一次，两件事一起拿：播放方向尺寸（给 <video> 标签）与时长/分辨率（转码决策要用）。
    val probe = runCatching { VideoTranscoder.probeVideo(context, uri) }.getOrNull()
    val display = probe?.let { VideoTranscoder.displaySize(it) }
    val displayWidth = display?.width ?: 0
    val displayHeight = display?.height ?: 0

    val declaredSize = queryFileSize(resolver, uri) ?: -1L
    val limitBytes = UploadService.effectiveLimitBytes(siteLimitKb)

    /** 把一份源（原始 uri 或转码产物文件）读成载荷。 */
    fun readBytesOf(target: Uri): RawFileResult = runCatching {
        val bytes = resolver.openInputStream(target)?.use { it.readBytes() }
            ?: return RawFileResult.Failed(AppContextHolder.getString(R.string.common_video_read_failed))
        if (bytes.isEmpty()) return RawFileResult.Failed(AppContextHolder.getString(R.string.common_file_empty))
        RawFileResult.Ok(
            PreparedRawFile(
                base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                fileName = fileName,
                mimeType = resolver.getType(uri)
                    ?: UploadService.mimeFor(UploadService.extensionOf(fileName)),
                byteLength = bytes.size.toLong(),
                displayWidth = displayWidth,
                displayHeight = displayHeight
            )
        )
    }.getOrElse { RawFileResult.Failed(AppContextHolder.getString(R.string.common_video_read_failed)) }

    // 体积未知（provider 不给 SIZE）时不敢断言「已达标」，走转码那条路更安全 ——
    // 但探不出元数据时转码也无从下手，此时只能原样传，由服务端去拒。
    if (declaredSize in 0..limitBytes) {
        return readBytesOf(uri)
    }
    if (probe == null) {
        return readBytesOf(uri)
    }

    val transcoder = VideoTranscoder(context)
    VideoTranscoder.markActive(transcoder)
    val result = try {
        transcoder.fit(
            sourceUri = uri,
            targetBytes = limitBytes,
            known = probe,
            onPlan = { plan ->
                if (plan.willDegrade) {
                    onProgress(UploadProgress(AppContextHolder.getString(R.string.composer_video_long_warning), cancelable = true))
                }
            },
            onProgress = { percent, pass ->
                val round = if (pass > 1) AppContextHolder.getString(R.string.composer_compress_pass, pass) else ""
                onProgress(UploadProgress(AppContextHolder.getString(R.string.composer_compressing, round, percent), cancelable = true))
            }
        )
    } finally {
        VideoTranscoder.markActive(null)
    }

    val producedPath = result.path
    if (producedPath.isNullOrEmpty()) {
        // 空文案 = 用户主动取消，浮层正在被入口层收掉，不必再报错。
        return RawFileResult.Failed(result.error.orEmpty())
    }
    return try {
        readBytesOf(Uri.fromFile(File(producedPath)))
    } finally {
        VideoTranscoder.discard(producedPath)
    }
}

/**
 * 图片版的「读字节」：复用 [prepareImageUpload]（长边 ≤1920 + JPEG 重编码），
 * 再把产物适配成批量通道统一的 [RawFileResult]。
 *
 * 体积闸门用 base64 长度反推（`len / 4 * 3`，误差来自 padding），不再解一次码 ——
 * 这里只是拦下「压缩完仍超站点上限」的极端情况，精度够用。
 */
private fun prepareImageForBatch(
    resolver: android.content.ContentResolver,
    uri: android.net.Uri,
    limitKb: Int,
    kindLabel: String
): RawFileResult {
    val prepared = prepareImageUpload(resolver, uri) ?: return RawFileResult.Failed(AppContextHolder.getString(R.string.common_image_read_failed))
    val approxBytes = prepared.base64.length.toLong() / 4L * 3L
    val overLimit = UploadService.checkSize(approxBytes, limitKb, kindLabel)
    if (overLimit.isNotEmpty()) return RawFileResult.Failed(overLimit)
    return RawFileResult.Ok(
        PreparedRawFile(prepared.base64, prepared.fileName, prepared.mimeType, approxBytes)
    )
}

/** 解析 /uploads.json 响应：成功取 short_url 拼 markdown 图；失败给用户可读文案。 */
internal fun parseUploadOutcome(response: NetworkResult<String>): UploadOutcome {
    val parsed = parseUploadedFile(response, kindLabel = AppContextHolder.getString(R.string.composer_kind_image))
    val file = parsed.file ?: return UploadOutcome(null, parsed.error)
    return UploadOutcome(UploadMarkdown.buildImage(file.fileName, file.width, file.height, file.shortUrl), null)
}

/** 附件：`[名.zip|attachment](upload://…) (38.3 KB)`。 */
internal fun parseAttachmentOutcome(response: NetworkResult<String>): UploadOutcome {
    val parsed = parseUploadedFile(response, kindLabel = AppContextHolder.getString(R.string.composer_kind_file))
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
    val parsed = parseUploadedFile(response, kindLabel = AppContextHolder.getString(R.string.composer_kind_video))
    val file = parsed.file ?: return UploadOutcome(null, parsed.error)
    val width = if (file.width > 0) file.width else localWidth
    val height = if (file.height > 0) file.height else localHeight
    return UploadOutcome(
        UploadService.buildVideoHtml(file.shortPath, file.url, width, height, mediaMimeType),
        null,
    )
}
