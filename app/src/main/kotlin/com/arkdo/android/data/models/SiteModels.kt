package com.arkdo.android.data.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    @SerialName("uploaded_logo") val uploadedLogo: UploadedLogoUnion? = null,
    @SerialName("icon") val icon: String? = null,
    @SerialName("read_restricted") val readRestricted: Boolean? = null
)

@Serializable
internal data class DiscourseUploadedLogo(
    @SerialName("url") val url: String? = null
)

// 处理 uploaded_logo 可能是 string 或 object
internal typealias UploadedLogoUnion = String // 简化处理，实际可能需要自定义序列化器
