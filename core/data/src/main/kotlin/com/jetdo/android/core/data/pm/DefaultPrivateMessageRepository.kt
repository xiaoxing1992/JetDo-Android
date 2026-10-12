package com.jetdo.android.core.data.pm

import com.jetdo.android.core.model.HomePage
import com.jetdo.android.core.model.HomePageResult
import com.jetdo.android.core.network.JsonNetworkClient
import com.jetdo.android.core.data.json.DiscourseJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URLEncoder

/**
 * 私信列表的数据源。
 *
 * 私信在 Discourse 里**就是话题**，只是标了私密 —— 走的是 core 的原生端点，不依赖 Chat 插件：
 *   /topics/private-messages/{username}.json           收件箱
 *   /topics/private-messages-sent/{username}.json      已发送
 *   /topics/private-messages-archive/{username}.json   归档
 *
 * 响应结构与 /latest.json 等完全同构（topic_list.topics + users），因此解析与
 * [com.jetdo.android.core.data.bookmark.DefaultBookmarkRepository.readTopics] 一致，
 * 复用同一套 HomePage/HomeTopic 模型，列表 UI 与详情页都无需另造。
 *
 * 与 /read.json 的关键差别：**路径里带用户名**（read 取的是当前登录用户，不带名字），
 * 所以调用方必须先从 CurrentUserHolder 取到 username。
 */
interface PrivateMessageRepository {
    /** 收件箱。 */
    suspend fun inbox(username: String, page: Int): HomePageResult

    /** 已发送。 */
    suspend fun sent(username: String, page: Int): HomePageResult

    /** 归档。 */
    suspend fun archive(username: String, page: Int): HomePageResult
}

class DefaultPrivateMessageRepository(
    private val client: JsonNetworkClient
) : PrivateMessageRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun inbox(username: String, page: Int): HomePageResult =
        fetch("/topics/private-messages/${encode(username)}.json", page)

    override suspend fun sent(username: String, page: Int): HomePageResult =
        fetch("/topics/private-messages-sent/${encode(username)}.json", page)

    override suspend fun archive(username: String, page: Int): HomePageResult =
        fetch("/topics/private-messages-archive/${encode(username)}.json", page)

    private suspend fun fetch(basePath: String, page: Int): HomePageResult {
        val path = if (page > 0) "$basePath?page=$page" else basePath
        val response = client.getJson(path)
        if (!response.success) {
            return HomePageResult.Failure(
                message = response.error?.message ?: "私信加载失败，请重试",
                requiresChallenge = response.error?.requiresChallenge == true
            )
        }
        val data = response.data ?: return HomePageResult.Failure("私信响应为空")
        return runCatching {
            val root = json.parseToJsonElement(data).jsonObject
            val topicList = root["topic_list"]?.jsonObject
                ?: return HomePageResult.Failure("私信响应缺少 topic_list")
            val users = DiscourseJson.parseUsers(root["users"])
            val items = topicList["topics"]?.jsonArray.orEmpty().mapNotNull { element ->
                runCatching { DiscourseJson.parseTopic(element.jsonObject, users) }.getOrNull()
            }
            HomePageResult.Success(
                HomePage(
                    items = items,
                    page = page,
                    hasMore = !topicList["more_topics_url"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()
                )
            )
        }.getOrElse { HomePageResult.Failure("私信响应不是有效 JSON") }
    }

    /**
     * 用户名进 URL 前必须转义：Discourse 允许用户名含 `.`/`-`/`_`，但编码后再拼更稳，
     * 也避免将来允许特殊字符时在这里踩坑（对齐 BookmarkRepository 的 encode 做法）。
     */
    private fun encode(username: String): String = URLEncoder.encode(username, "UTF-8")
}
