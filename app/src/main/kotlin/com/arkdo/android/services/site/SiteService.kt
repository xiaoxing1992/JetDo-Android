package com.arkdo.android.services.site

import android.content.Context
import com.arkdo.android.core.data.AppContextHolder
import com.arkdo.android.R
import com.arkdo.android.data.models.DiscourseCategory
import com.arkdo.android.data.models.DiscourseSiteResponse
import com.arkdo.android.data.models.SiteMetadata
import com.arkdo.android.data.models.TopicCategory
import com.arkdo.android.services.network.JsonNetworkClient
import com.arkdo.android.services.network.NetworkResult
import com.arkdo.android.services.network.NetworkResults
import kotlinx.serialization.json.Json
import timber.log.Timber

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
                AppContextHolder.getString(R.string.err_site_info_empty)
            )
        }

        return try {
            val payload = json.decodeFromString<DiscourseSiteResponse>(data)
            val categories = payload.categories

            if (categories == null) {
                return NetworkResults.unexpectedResponse(
                    response.statusCode,
                    response.challengeState,
                    AppContextHolder.getString(R.string.err_site_info_missing_categories)
                )
            }

            val mapped = mapCategories(categories)
            Timber.i("[JetDo][Site] /site.json 已解析 categories=%d", mapped.size)
            NetworkResult(
                success = true,
                statusCode = response.statusCode,
                challengeState = response.challengeState,
                data = SiteMetadata(categories = mapped)
            )
        } catch (e: Exception) {
            // 一定要打出来：/site.json 里任何一个字段的类型对不上都会整份解析失败，
            // 表现成「版块一个都没有」。之前这里静默吞掉，排查时完全没有线索。
            Timber.e(e, "[JetDo][Site] /site.json 解析失败 len=%d", data.length)
            NetworkResults.unexpectedResponse(
                response.statusCode,
                response.challengeState,
                AppContextHolder.getString(R.string.err_site_info_json)
            )
        }
    }

    private fun mapCategories(sourceCategories: List<DiscourseCategory>): List<TopicCategory> =
        Companion.mapCategories(sourceCategories)

    companion object {
        /**
         * DiscourseCategory -> TopicCategory。纯映射，不碰网络，
         * 所以单测可以直接拿真实 /site.json 的解析结果过一遍。
         *
         * uploaded_logo 的 string|object 两种形态在反序列化层就已经归一成 String?
         * （见 UploadedLogoSerializer），这里拿到的就是可用的地址或 null。
         */
        internal fun mapCategories(sourceCategories: List<DiscourseCategory>): List<TopicCategory> =
            sourceCategories.mapNotNull { mapCategory(it) }

        /** 单测入口，语义同 [mapCategories]。 */
        internal fun mapCategoriesForTest(sourceCategories: List<DiscourseCategory>): List<TopicCategory> =
            mapCategories(sourceCategories)

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
                uploadedLogo = source.uploadedLogo?.trim()?.takeIf { it.isNotEmpty() },
                icon = source.icon?.takeIf { it.isNotEmpty() },
                readRestricted = source.readRestricted
            )
        }

        private fun normalizeHex(value: String?, fallback: String): String {
            if (value == null) return fallback
            val normalized = value.replace("#", "").trim()
            return normalized.ifEmpty { fallback }
        }
    }
}
