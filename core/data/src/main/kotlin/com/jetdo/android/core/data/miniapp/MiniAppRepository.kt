package com.jetdo.android.core.data.miniapp

import com.jetdo.android.core.model.MiniAppLaunch
import com.jetdo.android.core.model.MiniAppListPage
import com.jetdo.android.core.model.MiniAppProfile
import com.jetdo.android.core.model.MiniAppSummary
import com.jetdo.android.core.network.JsonNetworkClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import timber.log.Timber

/**
 * 微应用数据源。
 *
 * 站点协议（2026-11-20 真机实测确认）：
 *  - 广场列表：`GET /mini-app-api/apps.json` → 200，`{"apps":[{app_id,name,description,
 *    tags,icon_url,version,published_at,rating_*,usage_count,is_favorited,author{...}}]}`
 *  - 启动：`GET /mini-app-api/production/<app_id>/launch` → `{release, runtime_token, runtime_context}`；
 *    **需要登录**，未登录返回 403 `{"error_type":"not_logged_in"}`。
 *  - 包：release.package_url 指向 CDN，下载到的是**自定义 DMA 容器**（非 ZIP），
 *    解密与验签交给论坛下发的 `/mini-app-api/workers/package-worker-<sha256>.js`（同源 Worker）完成，
 *    宿主不自行实现解密。
 */
interface MiniAppRepository {
    /** 拉取指定微应用的启动信息。 */
    suspend fun launch(appId: String): MiniAppResult<MiniAppLaunch>

    /** 拉取微应用广场列表的一页（0-based）。 */
    suspend fun listApps(page: Int = 0): MiniAppResult<MiniAppListPage>

    /** 查询论坛用户资料（sdk.user.getById 用）。 */
    suspend fun userProfile(userId: Long): MiniAppResult<MiniAppProfile>
}

sealed interface MiniAppResult<out T> {
    data class Success<T>(val data: T) : MiniAppResult<T>
    data class Failure(
        val message: String,
        val requiresChallenge: Boolean = false
    ) : MiniAppResult<Nothing>
}

class DefaultMiniAppRepository(
    private val client: JsonNetworkClient
) : MiniAppRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun launch(appId: String): MiniAppResult<MiniAppLaunch> {
        val cleanId = appId.trim().removePrefix("/apps/").substringBefore('?')
        if (cleanId.isEmpty()) {
            return MiniAppResult.Failure("微应用 ID 为空")
        }
        val path = "/mini-app-api/production/$cleanId/launch"
        val response = client.getJson(path)
        if (!response.success) {
            return MiniAppResult.Failure(
                message = response.error?.message ?: "获取微应用信息失败",
                requiresChallenge = response.error?.requiresChallenge == true
            )
        }
        val body = response.data
            ?: return MiniAppResult.Failure("微应用启动响应为空")
        return runCatching { MiniAppResult.Success(MiniAppLaunchParser.parse(body)) }
            .getOrElse {
                // 解析失败时把原文留在日志里，方便对照站点实际返回的字段形状。
                Timber.tag("MiniAppRepo").w("launch parse failed: ${it.message}; body=${body.take(2000)}")
                MiniAppResult.Failure("解析微应用启动信息失败：${it.message}")
            }
    }

    override suspend fun listApps(page: Int): MiniAppResult<MiniAppListPage> {
        val safePage = page.coerceAtLeast(0)
        var lastMessage = "获取微应用列表失败"
        for (path in LIST_PATHS) {
            // 站点分页是 0-based 的 ?page=N；page=0 时也显式带上，与网页版行为一致。
            val pagedPath = "$path?page=$safePage"
            val response = client.getJson(pagedPath)
            if (!response.success) {
                lastMessage = response.error?.message ?: lastMessage
                continue
            }
            val body = response.data ?: continue
            val parsed = runCatching { parseList(body, safePage) }.getOrNull()
            if (parsed != null) {
                return MiniAppResult.Success(parsed)
            }
            lastMessage = "解析微应用列表失败"
        }
        return MiniAppResult.Failure(lastMessage)
    }

    /**
     * 用户资料查询。
     *
     * 走论坛公开的 `/u/<id>.json`，返回官方 SDK 约定的字段形状。
     * 头像把 avatar_template 里的 {size} 换成 288 —— 官方是「本次运行内的 Blob URL」，
     * 这里直接给 CDN 地址，功能等价（可直接作为 img.src），但生命周期不同。
     */
    override suspend fun userProfile(userId: Long): MiniAppResult<MiniAppProfile> {
        val response = client.getJson("/u/$userId.json")
        if (!response.success) {
            return MiniAppResult.Failure(
                message = response.error?.message ?: "查询用户资料失败",
                requiresChallenge = response.error?.requiresChallenge == true
            )
        }
        val body = response.data ?: return MiniAppResult.Failure("用户资料响应为空")
        return runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            val user = root["user"]?.jsonObject ?: error("响应缺少 user")
            val username = user.str("username").orEmpty()
            val name = user.str("name")?.takeIf { it.isNotBlank() } ?: username
            val avatarTemplate = user.str("avatar_template")
            MiniAppProfile(
                id = user["id"]?.jsonPrimitive?.longOrNull ?: userId,
                username = username,
                name = name,
                initials = username.take(1).uppercase(),
                trustLevel = user["trust_level"]?.jsonPrimitive?.intOrNull,
                // 论坛返回的是站内相对路径，绝对地址由 app 层（宿主运行时）补站点 origin。
                avatarUrl = avatarTemplate
                    ?.replace("{size}", "288")
                    ?.takeIf { it.isNotBlank() }
            )
        }.fold(
            onSuccess = { MiniAppResult.Success(it) },
            onFailure = { MiniAppResult.Failure("解析用户资料失败：${it.message}") }
        )
    }

    /**
     * 广场列表响应形状未固化，这里做宽容解析：
     * 顶层可能是数组，也可能是 `{ apps: [...], page, total, per_page }` 等包装。
     * 分页元数据缺失时退化为单页（hasMore=false），不阻断列表展示。
     */
    private fun parseList(body: String, requestedPage: Int): MiniAppListPage {
        val root = json.parseToJsonElement(body)
        var array: kotlinx.serialization.json.JsonArray = kotlinx.serialization.json.JsonArray(emptyList())
        var page = requestedPage
        var total = 0
        var perPage = 0
        var hasPaginationMeta = false
        if (root is JsonObject) {
            val candidates = listOf("apps", "mini_apps", "miniApps", "items", "results", "data")
            array = candidates.firstNotNullOfOrNull { key ->
                root[key]?.let { it as? kotlinx.serialization.json.JsonArray }
            } ?: array
            page = root["page"]?.jsonPrimitive?.intOrNull ?: requestedPage
            total = root["total"]?.jsonPrimitive?.intOrNull ?: 0
            perPage = root["per_page"]?.jsonPrimitive?.intOrNull ?: array.size
            // total 是判定 hasMore 的可靠依据；没有它就把响应当作不分页的单页，
            // 避免"本页恰好满"被误判成还有下一页而无限翻页。
            hasPaginationMeta = root["total"]?.jsonPrimitive?.intOrNull != null
        } else if (root is kotlinx.serialization.json.JsonArray) {
            array = root
            perPage = array.size
        }
        val apps = array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj.str("id") ?: obj.str("app_id") ?: obj.str("uuid") ?: return@mapNotNull null
            // 站点把作者信息放在嵌套的 author 对象里（id/username/name/avatar_template）。
            val author = obj["author"]?.jsonObject
            MiniAppSummary(
                id = id,
                name = obj.str("name").orEmpty(),
                description = obj.str("description") ?: obj.str("summary"),
                iconUrl = obj.str("icon") ?: obj.str("icon_url") ?: obj.str("logo"),
                version = obj.str("version"),
                tags = obj["tags"]?.jsonArray
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
                authorName = author?.str("name")?.takeIf { it.isNotBlank() }
                    ?: author?.str("username")
                    ?: obj.str("author_name") ?: obj.str("owner_name")
            )
        }
        // 有 total 时以 total 为准（服务端会 clamp 越界 page 到末页，末页不足 per_page 不代表没下一页）；
        // 没有 total 的老形状退化为"本页满且无重复"的保守判断。
        val hasMore = if (hasPaginationMeta && perPage > 0) {
            (page + 1) * perPage < total
        } else {
            false
        }
        return MiniAppListPage(apps = apps, page = page, total = total, perPage = perPage, hasMore = hasMore)
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    companion object {
        /**
         * 广场列表候选路径。
         *
         * 真机实测 `/mini-app-api/apps.json` 返回 200；`/mini-app-api/apps` 作为
         * Discourse 的可选 `.json` 后缀形式留作兜底。命中第一个即返回，
         * 避免多路径试探放大 Cloudflare 限流风险。
         */
        private val LIST_PATHS = listOf(
            "/mini-app-api/apps.json",
            "/mini-app-api/apps"
        )
    }
}
