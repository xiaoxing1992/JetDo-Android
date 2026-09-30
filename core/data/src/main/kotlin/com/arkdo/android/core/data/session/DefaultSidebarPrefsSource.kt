package com.arkdo.android.core.data.session

import com.arkdo.android.core.data.json.DiscourseJson
import com.arkdo.android.core.model.HomeTag
import com.arkdo.android.core.model.SidebarPrefs
import com.arkdo.android.core.network.JsonNetworkClient
import com.arkdo.android.core.network.JsonRequestOptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 侧栏自选（抽屉的「我的版块 / 我的标签」）来源。
 *
 * 数据其实在 `/session/current.json` 的 `current_user` 里，与 app 层的会话检查
 * （SessionChecker）读的是同一份响应。但 feature 层看不到 app 层，所以把取数收口到这里，
 * 由 app 的 DI 绑到同一套网络客户端上 —— 与 [SessionVersionSource] 同一套做法。
 *
 * 未登录（Discourse 对匿名请求回 404，不是 200 + `current_user: null`）或登录了却
 * 没配置过，都返回 [SidebarPrefs.Empty]：不抛错、不弹提示，抽屉只是少两组置顶，照旧能用。
 */
interface SidebarPrefsSource {
    suspend fun load(): SidebarPrefs
}

class DefaultSidebarPrefsSource(
    private val client: JsonNetworkClient
) : SidebarPrefsSource {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun load(): SidebarPrefs {
        // notifyChallenge = false：匿名会话是 404 而不是挑战页，不值得为它走过盾链路。
        val response = client.getJson(PATH, JsonRequestOptions(notifyChallenge = false))
        val data = if (response.success) response.data else null
        if (data.isNullOrBlank()) return SidebarPrefs.Empty

        return runCatching {
            val user = json.parseToJsonElement(data).jsonObject["current_user"]?.jsonObject
                ?: return SidebarPrefs.Empty
            SidebarPrefs(
                categoryIds = user["sidebar_category_ids"]?.jsonArray.orEmpty()
                    .mapNotNull { it.jsonPrimitive.longOrNull },
                tags = user["sidebar_tags"]?.jsonArray.orEmpty().mapNotNull { element ->
                    runCatching {
                        val obj = element.jsonObject
                        val name = DiscourseJson.string(obj, "name") ?: return@runCatching null
                        HomeTag(
                            name = name,
                            id = DiscourseJson.long(obj, "id"),
                            slug = DiscourseJson.string(obj, "slug")
                        )
                    }.getOrNull()
                }
            )
        }.getOrElse { SidebarPrefs.Empty }
    }

    private companion object {
        const val PATH = "/session/current.json"
    }
}
