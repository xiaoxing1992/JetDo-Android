package com.arkdo.android.core.data.site

import com.arkdo.android.core.data.json.DiscourseJson
import com.arkdo.android.core.model.HomeCategory
import com.arkdo.android.core.model.TagSuggestion
import com.arkdo.android.core.network.JsonNetworkClient
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.net.URLEncoder
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Loads the Discourse site category index from `/site.json`.
 *
 * The legacy app implementation lives at `app/.../services/site/SiteService.kt` and keeps
 * a process-wide static cache; this repository is the modular slice that serves
 * `feature:home` (and later other features) without reaching back into the legacy services.
 */
class DefaultCategoryRepository(
    private val client: JsonNetworkClient
) : CategoryRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun load(): CategoryLoadResult {
        val response = client.getJson("/site.json")
        if (!response.success) {
            return CategoryLoadResult.Failure(
                message = response.error?.message ?: "站点信息加载失败",
                requiresChallenge = response.error?.requiresChallenge == true
            )
        }
        val data = response.data
            ?: return CategoryLoadResult.Failure("站点信息响应为空")
        return runCatching {
            val payload = json.decodeFromString<DiscourseSiteResponse>(data)
            val categories = payload.categories ?: return CategoryLoadResult.Failure("站点信息响应缺少 categories")
            CategoryLoadResult.Success(categories.mapNotNull(::mapCategory))
        }.getOrElse {
            CategoryLoadResult.Failure("站点信息响应不是有效 JSON")
        }
    }

    override suspend fun suggestTags(query: String, limit: Int): List<TagSuggestion> {
        val path = "/tags/filter/search?q=${encode(query.trim())}&limit=$limit&filterForInput=true"
        val response = client.getJson(path)
        if (!response.success) return emptyList()
        val data = response.data ?: return emptyList()
        return runCatching {
            val root = json.parseToJsonElement(data).jsonObject
            root["results"]?.jsonArray.orEmpty().mapNotNull { element ->
                runCatching {
                    val obj = element.jsonObject
                    if (obj["pm_only"]?.jsonPrimitive?.contentOrNull == "true") return@runCatching null
                    val id = DiscourseJson.long(obj, "id") ?: return@runCatching null
                    val name = DiscourseJson.string(obj, "name") ?: return@runCatching null
                    TagSuggestion(
                        id = id,
                        name = name,
                        text = DiscourseJson.string(obj, "text") ?: name,
                        count = DiscourseJson.int(obj, "count") ?: 0
                    )
                }.getOrNull()
            }
        }.getOrElse { emptyList() }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun mapCategory(source: DiscourseCategory): HomeCategory? {
        val id = source.id ?: return null
        val name = source.name ?: return null
        return HomeCategory(
            id = id,
            name = name,
            color = source.color ?: "6B7280",
            textColor = source.textColor ?: "FFFFFF",
            parentCategoryId = source.parentCategoryId,
            slug = source.slug,
            uploadedLogo = source.uploadedLogo,
            icon = source.icon,
            readRestricted = source.readRestricted == true
        )
    }
}

@Serializable
internal data class DiscourseSiteResponse(
    @SerialName("categories") val categories: List<DiscourseCategory>? = null
)

@Serializable
internal data class DiscourseCategory(
    @SerialName("id") val id: Long? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("color") val color: String? = null,
    @SerialName("text_color") val textColor: String? = null,
    @SerialName("slug") val slug: String? = null,
    @SerialName("parent_category_id") val parentCategoryId: Long? = null,
    @SerialName("uploaded_logo")
    @Serializable(with = UploadedLogoSerializer::class)
    val uploadedLogo: String? = null,
    @SerialName("icon") val icon: String? = null,
    @SerialName("read_restricted") val readRestricted: Boolean? = null
)

/**
 * `uploaded_logo` 在 /site.json 里有两种形态:字符串 URL,或
 * `{"url": ..., "width": ..., "height": ...}` 对象(Discourse 新版是后者)。
 * 两种都必须收,与 legacy SiteModels.kt 的 UploadedLogoSerializer 保持一致。
 */
internal object UploadedLogoSerializer : KSerializer<String?> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("UploadedLogo", PrimitiveKind.STRING).nullable

    override fun deserialize(decoder: Decoder): String? {
        val input = decoder as? JsonDecoder ?: return null
        return when (val element = input.decodeJsonElement()) {
            is JsonNull -> null
            is JsonPrimitive -> element.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            is JsonObject -> element["url"]?.jsonPrimitive?.contentOrNull
                ?.trim()?.takeIf { it.isNotEmpty() }
            else -> null
        }
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value)
    }
}