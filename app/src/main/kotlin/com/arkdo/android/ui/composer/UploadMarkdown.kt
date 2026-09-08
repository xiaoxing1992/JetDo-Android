package com.arkdo.android.ui.composer

/**
 * 上传结果的 markdown 拼装,对齐鸿蒙 UploadService.buildImageMarkdown。
 *
 * 服务端 /uploads.json 回 short_url(形如 upload://<hash>.ext),Discourse 解析
 * `![name|WxH](upload://...)`:alt 尾部的 |宽x高 由 render.html 的 image 规则抽成
 * width/height 属性,宽高为 0 时不写尺寸段。|attachment 变体用于附件(后续切片)。
 */
object UploadMarkdown {

    /** ![文件名|宽x高](upload://xxx) —— 宽高为 0 时省略尺寸段。 */
    fun buildImage(fileName: String, width: Int, height: Int, shortUrl: String): String {
        val name = fileName.takeIf { it.isNotBlank() } ?: "image"
        val dims = if (width > 0 && height > 0) "|${width}x${height}" else ""
        return "![$name$dims]($shortUrl)"
    }
}
