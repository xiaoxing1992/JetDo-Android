package com.arkdo.android.services.site

import com.arkdo.android.common.constants.AppConstants
import com.arkdo.android.services.network.JsonNetworkClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber

// ==========================================================================
// 与 HarmonyOS 版 services/site/SiteSettingsService.ets 逐字段对齐。
//
// 站点客户端设置（GET /site/settings.json）。Discourse 把标了 client: true 的设置
// 从这个端点原样吐出，匿名也能读，服务端还有 30 分钟缓存，拉一次很便宜。
//
// ## 为什么必须动态读
// 上传相关的每一项都是站长可改的，实测差得很远：
//
//     linux.do            : 图片 4096 KB / 附件 4096 KB，白名单含 zip/7z/xz 但无 mp4
//     meta.discourse.org  : 图片 10240 KB / 附件 30000 KB
//
// ArkDO 是通用 Discourse 客户端，任何写死的数字都会在某个站上是错的。
//
// ## 拿不到时的策略
// 回落到 Discourse 出厂默认值而不是「禁用上传」。设置拉失败多半是网络抖动，直接封掉
// 入口对用户来说是莫名其妙的功能消失；按默认值放行，真超限了服务端会拒，我们再把错误显示出来。
// ==========================================================================

private const val LOG_TAG = "[ArkDO][SiteSettings]"

/**
 * Discourse 认定为「图片」的扩展名，抄自 lib/file_helper.rb 的 supported_images。
 *
 * 这张表决定走 max_image_size_kb 还是 max_attachment_size_kb（见服务端
 * lib/validators/upload_validator.rb），所以必须与服务端逐字一致 —— 注意
 * mp4/mp3/mov/webm **不在**表里，音视频在 Discourse 眼里是附件。
 */
val IMAGE_EXTENSIONS: List<String> =
    listOf("jpg", "jpeg", "png", "gif", "svg", "ico", "webp", "avif", "heic", "heif", "jxl")

/** 站点上传设置（编辑器算按钮显隐、体积上限、选择器后缀过滤都用它）。 */
data class SiteUploadSettings(
    /** 图片体积上限（KB）。判据是扩展名属于 [IMAGE_EXTENSIONS]，不是 MIME。 */
    val maxImageSizeKb: Int,
    /** 非图片文件体积上限（KB）。mp4/mp3 这类也走这条，别被「媒体」二字带偏。 */
    val maxAttachmentSizeKb: Int,
    /** 允许上传的扩展名（小写、不带点）。空 = 站点没开任何上传。 */
    val authorizedExtensions: List<String>,
    /** 版主额外允许的扩展名。多数站为空。 */
    val authorizedExtensionsForStaff: List<String>,
    /** TL0 最多能发几个附件。默认 0，即新用户一个附件都发不出去。 */
    val newuserMaxAttachments: Int,
    /** TL0 最多能发几张图 / 内嵌媒体。 */
    val newuserMaxEmbeddedMedia: Int,
)

/**
 * Discourse 出厂默认值，拉取失败时的兜底。数字逐项对齐服务端 config/site_settings.yml。
 *
 * 白名单单列一份而不是复用 [IMAGE_EXTENSIONS]：那张表是「Discourse 认定为图片的扩展名」
 * （决定走哪条体积上限），与「默认允许上传哪些扩展名」（authorized_extensions）是两回事
 * —— 后者不含 ico。两者恰好高度重合，但语义不同，混用会在其中一张表改动时悄悄带歪另一处。
 */
private val DEFAULT_AUTHORIZED_EXTENSIONS: List<String> =
    listOf("jpg", "jpeg", "png", "gif", "heic", "heif", "webp", "avif", "svg", "jxl")

/** 出厂默认设置。 */
fun defaultSiteUploadSettings(): SiteUploadSettings = SiteUploadSettings(
    maxImageSizeKb = 10240,
    maxAttachmentSizeKb = 10240,
    authorizedExtensions = DEFAULT_AUTHORIZED_EXTENSIONS,
    authorizedExtensionsForStaff = emptyList(),
    newuserMaxAttachments = 0,
    newuserMaxEmbeddedMedia = 5,
)

object SiteSettingsService {

    private val json = Json { ignoreUnknownKeys = true }

    // 内存缓存 + 它属于哪个 host。切站后 host 对不上即视为未命中，不必订阅切站事件
    // —— 比对 host 是自证的，少一条要维护的订阅关系（与鸿蒙同一取舍）。
    private var cachedHost: String = ""
    private var cachedSettings: SiteUploadSettings? = null

    // 编辑器可能同时问附件上限与白名单，串行化即可做到「同一时刻只打一次」；
    // 后到者拿到锁时缓存已就绪，直接命中。
    private val lock = Mutex()

    /**
     * 取当前站点的上传设置。命中缓存直接返回，否则拉一次。
     * **永远返回可用的值**（失败时是出厂默认值），调用方不必处理 null。
     */
    suspend fun load(bridge: JsonNetworkClient): SiteUploadSettings {
        val host = AppConstants.BASE_URL
        return lock.withLock {
            cachedSettings?.takeIf { cachedHost == host }?.let { return@withLock it }
            val fetched = fetch(bridge, host)
            // 只在拿到真数据后才写缓存；失败态不缓存，下次进编辑器还会再试。
            if (fetched != null) {
                cachedHost = host
                cachedSettings = fetched
            }
            fetched ?: defaultSiteUploadSettings()
        }
    }

    /**
     * 同步取已缓存的设置；没拉过返回 null。
     * 给「渲染时要立刻判断按钮显不显示」这类场合用：拿不到就先按保守态渲染，等 load 回来再刷。
     */
    fun snapshot(): SiteUploadSettings? =
        cachedSettings?.takeIf { cachedHost == AppConstants.BASE_URL }

    /** 测试用：清掉缓存，让每个用例从干净状态开始。 */
    internal fun resetForTest() {
        cachedHost = ""
        cachedSettings = null
    }

    /** 拉取并解析；失败返回 null（调用方兜底成默认值，且不写缓存）。 */
    private suspend fun fetch(bridge: JsonNetworkClient, host: String): SiteUploadSettings? {
        val response = runCatching { bridge.getJson("/site/settings.json") }.getOrElse {
            Timber.i(it, "%s fetch threw, using defaults", LOG_TAG)
            return null
        }
        val data = response.data
        if (!response.success || data.isNullOrEmpty()) {
            Timber.i("%s fetch failed status=%s, using defaults", LOG_TAG, response.statusCode)
            return null
        }
        val parsed = runCatching { json.parseToJsonElement(data).jsonObject }.getOrElse {
            Timber.i(it, "%s response not valid JSON object, using defaults", LOG_TAG)
            return null
        }

        // 逐字段取而不是整份 @Serializable：服务端把某一项的类型改掉（比如给成字符串）时，
        // 只丢那一项，不至于整份设置退化成默认值。
        val fallback = defaultSiteUploadSettings()
        val settings = SiteUploadSettings(
            maxImageSizeKb = parsed.positiveInt("max_image_size_kb") ?: fallback.maxImageSizeKb,
            maxAttachmentSizeKb = parsed.positiveInt("max_attachment_size_kb") ?: fallback.maxAttachmentSizeKb,
            authorizedExtensions = splitExtensions(parsed["authorized_extensions"]?.jsonPrimitive?.contentOrNullSafe()),
            authorizedExtensionsForStaff =
                splitExtensions(parsed["authorized_extensions_for_staff"]?.jsonPrimitive?.contentOrNullSafe()),
            // 这两项 0 是合法值（且是默认值），不能用 positiveInt 兜。
            newuserMaxAttachments =
                parsed["newuser_max_attachments"]?.jsonPrimitive?.intOrNull ?: fallback.newuserMaxAttachments,
            newuserMaxEmbeddedMedia =
                parsed["newuser_max_embedded_media"]?.jsonPrimitive?.intOrNull ?: fallback.newuserMaxEmbeddedMedia,
        )
        // 拉的时候是哪个站、现在还是不是：切站后旧站响应不该覆盖缓存（Android 目前只有一个站，
        // 与鸿蒙同一处判断保留，等多站点落地即生效）。
        if (AppConstants.BASE_URL != host) {
            Timber.i("%s site switched while loading (%s), not caching", LOG_TAG, host)
            return settings
        }
        Timber.i(
            "%s loaded host=%s image=%dKB attachment=%dKB exts=%d",
            LOG_TAG, host, settings.maxImageSizeKb, settings.maxAttachmentSizeKb, settings.authorizedExtensions.size
        )
        return settings
    }

    private fun kotlinx.serialization.json.JsonObject.positiveInt(key: String): Int? {
        val value = this[key]?.jsonPrimitive?.intOrNull ?: return null
        return value.takeIf { it > 0 }
    }

    /**
     * `'jpg|jpeg|png'` → `["jpg","jpeg","png"]`。对齐 frontend/discourse 的 extensionsToArray：
     * 小写、去空白与点、丢掉带 `*` 的通配项。
     */
    internal fun splitExtensions(raw: String?): List<String> {
        if (raw.isNullOrEmpty()) return emptyList()
        return raw.lowercase()
            .replace(Regex("[\\s.]+"), "")
            .split('|')
            .filter { it.isNotEmpty() && !it.contains('*') }
    }
}

/** jsonPrimitive 对 JsonNull / 非字符串会抛，这里统一吞成 null。 */
private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    runCatching { content }.getOrNull()?.takeIf { it.isNotEmpty() && it != "null" }
