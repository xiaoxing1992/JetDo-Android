package com.arkdo.android.ui.composer

import com.arkdo.android.services.network.NetworkResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber

/**
 * 上传结果解析：发帖（[NewTopicViewModel]）与回帖（`TopicDetailViewModel`）共用一份。
 * 分出去的原因和 [ComposerCommon] 一样 —— 两个入口各留一份，"失败了给用户看什么话"
 * 会慢慢分叉。
 */
internal data class UploadOutcome(val snippet: String?, val error: String?)

private val uploadJson = Json { ignoreUnknownKeys = true }

/** 解析 /uploads.json 响应：成功取 short_url 拼 markdown 图；失败给用户可读文案。 */
internal fun parseUploadOutcome(response: NetworkResult<String>): UploadOutcome {
    if (!response.success || response.data == null) {
        return UploadOutcome(null, response.error?.message ?: "图片上传失败，请稍后重试。")
    }
    return runCatching {
        val root = uploadJson.parseToJsonElement(response.data).jsonObject
        val shortUrl = root["short_url"]?.jsonPrimitive?.content
        if (shortUrl.isNullOrEmpty()) {
            // 2xx 但缺 short_url = Discourse 校验失败，服务端 errors 里有人话。
            val errors = root["errors"]?.jsonArray?.joinToString(" ") { it.jsonPrimitive.content }
            UploadOutcome(null, errors?.takeIf { it.isNotEmpty() } ?: "图片上传失败，请检查格式或大小。")
        } else {
            val width = root["width"]?.jsonPrimitive?.intOrNull ?: 0
            val height = root["height"]?.jsonPrimitive?.intOrNull ?: 0
            val name = root["original_filename"]?.jsonPrimitive?.content.orEmpty()
            UploadOutcome(UploadMarkdown.buildImage(name, width, height, shortUrl), null)
        }
    }.getOrElse {
        Timber.e(it, "[ArkDO][Composer] 上传响应解析失败")
        UploadOutcome(null, "图片上传失败，请稍后重试。")
    }
}
