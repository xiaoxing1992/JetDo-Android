package com.arkdo.android.data.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 站点元数据
 */
data class SiteMetadata(
    val categories: List<TopicCategory>
)

/**
 * 用户自定义站点定义
 *
 * @property id 站点唯一标识（时间戳+随机数）
 * @property name 站点名称
 * @property url 站点 URL
 * @property showInDrawer 是否在抽屉中显示
 * @property iconUri 本地缓存的 favicon URI（file://...）
 */
data class SiteDef(
    val id: String,
    val name: String,
    val url: String,
    val showInDrawer: Boolean,
    val iconUri: String = ""
)

/**
 * 内容入口类型
 */
enum class FeedKey(val value: String) {
    LATEST("latest"),
    // Discourse 没有按发布时间的 filter，走 latest 路由挂 order=created 换排序键。
    CREATED("created"),
    TOP("top"),
    HOT("hot"),
    UNREAD("unread"),
    NEW("new")
}

/**
 * 内容入口定义
 *
 * @property key 入口标识
 * @property label 显示标签
 * @property requiresLogin 是否需要登录
 */
data class FeedDef(
    val key: String,
    val label: String,
    val requiresLogin: Boolean
)

// ========== Discourse API 响应结构 ==========

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
 * `uploaded_logo` 在 /site.json 里有两种形态：字符串 URL，或
 * `{"url": ..., "width": ..., "height": ...}` 对象（Discourse 新版是后者）。
 *
 * 这里必须两种都收。之前把它简化成 String 直接接，遇到对象形态会抛
 * SerializationException —— 而那个异常被 SiteService 的 catch 吞掉，
 * 最终表现是「整站版块一个都加载不出来」，且没有任何错误提示。
 * ignoreUnknownKeys 对这种类型不匹配是无效的。
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

    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value)
    }
}
