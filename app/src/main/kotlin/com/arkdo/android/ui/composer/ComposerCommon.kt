package com.arkdo.android.ui.composer

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

/** 预览内容区：编译好的 HTML 走 PostRichContent 富文本渲染；失败/空内容退回原文/占位。 */
@Composable
internal fun PreviewPane(
    html: String?,
    rawText: String,
    loading: Boolean,
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
                    "正在生成预览...",
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
                    // 预览里不上传图片解析，预览中的图（如已有 markdown 图链）原样显示链接/兜底。
                    onOpenImages = { _, _ -> },
                    onLinkClick = {}
                )
                Spacer(modifier = Modifier.height(32.dp))
            }
        } else if (rawText.isBlank()) {
            Text(
                "(无内容可预览)",
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
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    val outBytes = if (bitmap != null && bitmap.width > 1920 && !mimeType.contains("gif")) {
        val scale = 1920f / maxOf(bitmap.width, bitmap.height)
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

private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? =
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) cursor.getString(idx) else null
        } else {
            null
        }
    }
