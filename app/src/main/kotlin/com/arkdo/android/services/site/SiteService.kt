package com.arkdo.android.services.site

import com.arkdo.android.data.models.DiscourseCategory
import com.arkdo.android.data.models.DiscourseSiteResponse
import com.arkdo.android.data.models.SiteMetadata
import com.arkdo.android.data.models.TopicCategory
import com.arkdo.android.services.network.JsonNetworkClient
import com.arkdo.android.services.network.NetworkResult
import com.arkdo.android.services.network.NetworkResults
import kotlinx.serialization.json.Json

/**
 * 站点服务
 *
 * 负责获取站点元数据（分类列表等）
 */
class SiteService(private val bridge: JsonNetworkClient) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 获取站点元数据（包含分类列表）
     */
    suspend fun getSiteMetadata(): NetworkResult<SiteMetadata> {
        val response = bridge.getJson("/site.json")

        if (!response.success) {
            return NetworkResults.forwardFailure(response)
        }

        val data = response.data
        if (data == null) {
            return NetworkResults.unexpectedResponse(
                response.statusCode,
                response.challengeState,
                "Linux.do 站点信息响应为空。"
            )
        }

        return try {
            val payload = json.decodeFromString<DiscourseSiteResponse>(data)
            val categories = payload.categories

            if (categories == null) {
                return NetworkResults.unexpectedResponse(
                    response.statusCode,
                    response.challengeState,
                    "Linux.do 站点信息响应缺少 categories。"
                )
            }

            NetworkResult(
                success = true,
                statusCode = response.statusCode,
                challengeState = response.challengeState,
                data = SiteMetadata(
                    categories = mapCategories(categories)
                )
            )
        } catch (e: Exception) {
            NetworkResults.unexpectedResponse(
                response.statusCode,
                response.challengeState,
                "Linux.do 站点信息响应不是有效 JSON。"
            )
        }
    }

    private fun mapCategories(sourceCategories: List<DiscourseCategory>): List<TopicCategory> {
        return sourceCategories.mapNotNull { mapCategory(it) }
    }

    private fun mapCategory(source: DiscourseCategory): TopicCategory? {
        val id = source.id ?: return null
        val name = source.name ?: return null

        return TopicCategory(
            id = id,
            name = name,
            color = normalizeHex(source.color, "6B7280"),
            textColor = normalizeHex(source.textColor, "FFFFFF"),
            slug = source.slug?.takeIf { it.isNotEmpty() },
            parentCategoryId = source.parentCategoryId,
            uploadedLogo = mapUploadedLogo(source.uploadedLogo),
            icon = source.icon?.takeIf { it.isNotEmpty() },
            readRestricted = source.readRestricted
        )
    }

    private fun mapUploadedLogo(uploadedLogo: String?): String? {
        // 简化处理：直接返回字符串
        // HarmonyOS 版本支持 string | object，这里先简化为 string
        return uploadedLogo?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun normalizeHex(value: String?, fallback: String): String {
        if (value == null) return fallback
        val normalized = value.replace("#", "").trim()
        return normalized.ifEmpty { fallback }
    }
}
