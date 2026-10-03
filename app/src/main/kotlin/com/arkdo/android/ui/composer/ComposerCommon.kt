package com.arkdo.android.ui.composer

import androidx.compose.ui.res.stringResource
import com.arkdo.android.core.data.AppContextHolder
import com.arkdo.android.R
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkdo.android.services.upload.UploadService
import com.arkdo.android.ui.components.PostRichContent
import com.arkdo.android.ui.theme.arkdoColors
import java.io.ByteArrayOutputStream

/**
 * composer 家族的共享件：发新主题（[NewTopicScreen]）与回帖/编辑全屏编辑器
 * （[ReplyComposerOverlay]）都用同一套预览与图片预处理。
 *
 * 抽出来的原因很实际：这两处如果各留一份，"预览的兜底文案"和"图片缩到 1920"
 * 这类细节会在两边慢慢漂移，而用户看到的同一个功能表现不一致。
 */

// ========== 预览 ==========

/**
 * 预览内容区：编译好的 HTML 走 PostRichContent 富文本渲染；失败/空内容退回原文/占位。
 *
 * [onResolveMedia] 必须由调用方接上（发帖页与回帖页都传 `ArkWebNetworkBridge.downloadMedia`）：
 * 预览里插进来的音视频是**站内 uploads 直链**，要过 Cloudflare 下载到沙箱才能播。
 * PostRichContent 的默认实现是 `error("媒体下载未接入")`，不传的话点播放只会得到
 * 「加载失败，点击重试」—— 这正是「上传的视频在预览里不能播」的根因。
 */
@Composable
internal fun PreviewPane(
    html: String?,
    rawText: String,
    loading: Boolean,
    onResolveMedia: suspend (String) -> String = { error(AppContextHolder.getString(R.string.common_media_download_not_ready)) },
    modifier: Modifier = Modifier
) {
    val colors = arkdoColors
    Box(modifier = modifier.fillMaxWidth()) {
        if (loading) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = colors.ldAccent,
                    strokeWidth = 2.dp
                )
                Text(
                    stringResource(R.string.composer_generating_preview),
                    fontSize = 14.sp,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(start = 10.dp)
                )
            }
        } else if (!html.isNullOrEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                PostRichContent(
                    html = html,
                    // 预览里点击图片不弹大图浏览器（编辑中没必要），图片本身照常内联显示。
                    onOpenImages = { _, _ -> },
                    onLinkClick = {},
                    onResolveMedia = onResolveMedia
                )
                Spacer(modifier = Modifier.height(32.dp))
            }
        } else if (rawText.isBlank()) {
            Text(
                stringResource(R.string.detail_empty_preview),
                fontSize = 15.sp,
                color = colors.textTertiary,
                modifier = Modifier.padding(16.dp)
            )
        } else {
            // 编译失败（引擎未就绪/超时）：退回原文展示，与鸿蒙「(无内容可预览)」同层兜底。
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                Text(
                    rawText,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    color = colors.textBody
                )
            }
        }
    }
}

// ========== 上传片段插入 ==========

/**
 * 上传件插入正文时的前后包裹 —— 必须是**空行**（`\n\n`），单个换行不够。
 *
 * 音视频在正文里是裸 `<video>` 块，而 CommonMark 的 HTML 块**只在空行处结束**：只补一个
 * 换行时，紧接着插入的 `![](…)` 会被当成该 HTML 块内部的原始文本 → 预览里直接显示成一串
 * 字面 markdown（`![xxx.jpg|864x1920](upload://…)`），也就是「图片不显示」的第二种成因。
 * 反方向同理：HTML 块不能打断段落，紧贴在一段文字/一张图后面的 `<video>` 会被吞进段落里。
 * 所以两侧都要空行。
 */
internal fun uploadSnippet(snippet: String): String = "\n\n$snippet\n\n"

// ========== 图片预处理 ==========

/** 选中的一张图片 → base64 + 文件名 + MIME。超宽/过大时先缩放再 JPEG 压缩，避免撞站点上限。 */
internal data class PreparedImage(val base64: String, val fileName: String, val mimeType: String)

internal fun prepareImageUpload(resolver: ContentResolver, uri: Uri): PreparedImage? = runCatching {
    val fileName = queryDisplayName(resolver, uri)
        ?: uri.lastPathSegment?.substringAfterLast('/')
        ?: "image"
    val mimeType = resolver.getType(uri) ?: "image/jpeg"

    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    // 动图/无法解码的格式原样上传；大尺寸位图压到长边 ≤1920（对齐鸿蒙 1920 上限）。
    // 注意：必须按「长边」判据，而不是只看 width —— 竖屏截图（1080×2400）宽度不超 1920 但长边严重超标，
    // 用 width>1920 会整张原样上传（320KB PNG），既慢又容易撞上传超时。
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    val longEdge = if (bitmap != null) maxOf(bitmap.width, bitmap.height) else 0
    val outBytes = if (bitmap != null && longEdge > 1920 && !mimeType.contains("gif")) {
        val scale = 1920f / longEdge
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true
        )
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        out.toByteArray()
    } else {
        bytes
    }
    val mime = if (outBytes !== bytes) "image/jpeg" else mimeType
    PreparedImage(
        base64 = Base64.encodeToString(outBytes, Base64.NO_WRAP),
        fileName = if (outBytes !== bytes) fileName.substringBeforeLast('.', fileName) + ".jpg" else fileName,
        mimeType = mime
    )
}.getOrNull()

internal fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? =
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) cursor.getString(idx) else null
        } else {
            null
        }
    }

// ========== 附件 / 音视频：原样字节 ==========

/** 一次「原样上传」的载荷：不做任何重编码，字节与文件内容逐字一致。 */
internal data class PreparedRawFile(
    val base64: String,
    val fileName: String,
    val mimeType: String,
    val byteLength: Long,
    /**
     * 音视频专用：本地量到的**播放方向**尺寸（0,0 = 没量到）。
     *
     * 服务端对 `.xz` 回的 width/height 是 null（它只认得图片），`<video>` 标签的宽高只能
     * 自己量，否则竖屏视频会被套进横向框摆歪。放在这里而不是读字节时再探一次：转码路径
     * 本来就要探测（时长/尺寸决定压到几档），顺带把结果显示带出来，省一遍解容器头。
     */
    val displayWidth: Int = 0,
    val displayHeight: Int = 0
)

/** 上传浮层的一行进度。 */
internal data class UploadProgress(
    val text: String,
    /**
     * 此刻能否取消。只有**压缩阶段**为真 —— 压缩可能要几十秒，不给退出口子只能干等；
     * 上传阶段不给，因为请求已经发出去了，取消也收不回来（对齐鸿蒙 ComposerPage 的浮层）。
     */
    val cancelable: Boolean = false
)

/** 读取结果：成功给载荷，失败给可直接展示的文案。 */
internal sealed interface RawFileResult {
    data class Ok(val file: PreparedRawFile) : RawFileResult
    data class Failed(val message: String) : RawFileResult
}

/**
 * 读一份文件用于上传（附件 / 音视频共用），**原样字节**。
 *
 * 与图片不同，这里绝不重编码：
 *  · 附件的扩展名决定白名单与体积上限，改名就会被服务端拒；
 *  · 音视频走 [prepareMediaForBatch]（在 ComposerUpload.kt）：达标原样传，超了先转码。
 *
 * 体积检查放在读进内存**之前**：超限的文件根本不该被读进来，否则一个 500 MB 的包会在
 * 「告诉用户传不了」之前先把 App 撑死（对齐鸿蒙 uploadRawFile 的顺序）。
 */
internal fun prepareRawUpload(
    resolver: ContentResolver,
    uri: Uri,
    siteLimitKb: Int,
    kindLabel: String
): RawFileResult = runCatching {
    val fileName = queryDisplayName(resolver, uri)
        ?: uri.lastPathSegment?.substringAfterLast('/')
        ?: "file"
    val declaredSize = queryFileSize(resolver, uri)
    if (declaredSize != null) {
        val overLimit = UploadService.checkSize(declaredSize, siteLimitKb, kindLabel)
        if (overLimit.isNotEmpty()) return RawFileResult.Failed(overLimit)
        if (declaredSize == 0L) return RawFileResult.Failed(AppContextHolder.getString(R.string.common_file_empty))
    }
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
        ?: return RawFileResult.Failed(AppContextHolder.getString(R.string.common_file_read_failed))
    if (bytes.isEmpty()) return RawFileResult.Failed(AppContextHolder.getString(R.string.common_file_empty))
    // provider 不给 SIZE 时（少见）上面那道闸门是空的，读完再兜一次。
    val afterRead = UploadService.checkSize(bytes.size.toLong(), siteLimitKb, kindLabel)
    if (afterRead.isNotEmpty()) return RawFileResult.Failed(afterRead)
    RawFileResult.Ok(
        PreparedRawFile(
            base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
            fileName = fileName,
            mimeType = resolver.getType(uri)
                ?: UploadService.mimeFor(UploadService.extensionOf(fileName)),
            byteLength = bytes.size.toLong()
        )
    )
}.getOrElse { RawFileResult.Failed(AppContextHolder.getString(R.string.common_file_read_failed)) }

internal fun queryFileSize(resolver: ContentResolver, uri: Uri): Long? =
    resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && !cursor.isNull(idx)) cursor.getLong(idx) else null
        } else {
            null
        }
    }

// 视频元数据探测已统一收口到 `VideoTranscoder.probeVideo` / `displaySize`：转码决策与
// `<video>` 标签的尺寸要的是同一次探测的产物，分成两处只会多解一遍容器头。
// 那条路**不能**用 Kotlin 的 `use`：`MediaMetadataRetriever` 到 API 29 才实现
// AutoCloseable，本工程 minSdk 26，只能手写 try/finally 释放。

