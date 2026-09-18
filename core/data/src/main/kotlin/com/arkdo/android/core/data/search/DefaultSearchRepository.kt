package com.arkdo.android.core.data.search

import com.arkdo.android.core.data.json.DiscourseJson
import com.arkdo.android.core.model.HomeTopic
import com.arkdo.android.core.model.HomeUser
import com.arkdo.android.core.model.SearchHit
import com.arkdo.android.core.model.SearchPage
import com.arkdo.android.core.model.SearchPageResult
import com.arkdo.android.core.model.SearchUsersResult
import com.arkdo.android.core.model.TagSuggestion
import com.arkdo.android.core.network.JsonNetworkClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/**
 * 搜索 Repository 实现。
 *
 * 帖子搜索的响应结构(见鸿蒙 TopicApiTypes 的注释):
 * 命中楼层在 `posts`、其主题元数据在 `topics`、命中作者在 `users`;
 * `grouped_search_result.post_ids` 给出**命中顺序** —— 官方就是这么排的,
 * 按它的顺序去 posts 里查 topic_id,才能让"最相关的主题"排在最前。
 */
class DefaultSearchRepository(
    private val client: JsonNetworkClient
) : SearchRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun searchTopics(query: String, page: Int): SearchPageResult {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return SearchPageResult.Success(SearchPage(emptyList(), page = 1, hasMore = false))
        }
        // 第 1 页不带 page 参数(对齐官方与鸿蒙实现)。
        val suffix = if (page > 1) "&page=$page" else ""
        val path = "/search.json?q=${encode(trimmed)}$suffix"

        val response = client.getJson(path)
        if (!response.success) {
            val error = response.error
            return SearchPageResult.Failure(
                message = error?.message ?: "搜索失败，请检查网络后重试",
                requiresChallenge = error?.requiresChallenge == true
            )
        }
        val data = response.data ?: return SearchPageResult.Failure("站点搜索响应为空")

        return runCatching {
            val root = json.parseToJsonElement(data).jsonObject
            SearchPageResult.Success(mapSearch(root, page))
        }.getOrElse {
            SearchPageResult.Failure("站点搜索响应不是有效 JSON")
        }
    }

    private fun mapSearch(root: JsonObject, page: Int): SearchPage {
        val grouped = root["grouped_search_result"]?.jsonObject
        val postIds = grouped?.get("post_ids")?.jsonArray.orEmpty()
            .mapNotNull { it.jsonPrimitive.contentOrNull?.toLongOrNull() }

        // 命中楼层按 id 建索引(取 topic_id 与 blurb)。
        val postById = HashMap<Long, JsonObject>()
        root["posts"]?.jsonArray.orEmpty().forEach { element ->
            runCatching {
                val obj = element.jsonObject
                DiscourseJson.long(obj, "id")?.let { postById[it] = obj }
            }
        }

        // 主题元数据复用公共解析(与首页同一套规则),按 id 建索引。
        val users = DiscourseJson.parseUsers(root["users"])
        val mappedTopics = root["topics"]?.jsonArray.orEmpty().mapNotNull { element ->
            runCatching { DiscourseJson.parseTopic(element.jsonObject, users) }.getOrNull()
        }
        val topicById = mappedTopics.associateBy { it.id }

        val seen = HashSet<Long>()
        val ordered = ArrayList<SearchHit>(postIds.size)
        for (postId in postIds) {
            val post = postById[postId] ?: continue
            val topicId = DiscourseJson.long(post, "topic_id") ?: continue
            if (!seen.add(topicId)) continue
            val topic = topicById[topicId] ?: continue
            // 命中摘要同时带给 SearchHit.blurb(搜索页高亮渲染用)与 topic.searchBlurb
            // (复用首页那条渲染链路时的兜底),两者同源。
            val blurb = blurbOf(post) ?: topic.searchBlurb
            // topics 没给作者(或 posters 在 users 里查不到)时,用命中楼作者兜底出头像 —— 对齐鸿蒙 mapSearch。
            val author = topic.author ?: DiscourseJson.string(post, "username")
                ?.takeIf { it.isNotEmpty() }
                ?.let { username ->
                    HomeUser(
                        id = DiscourseJson.long(post, "user_id") ?: 0L,
                        username = username,
                        name = DiscourseJson.string(post, "name"),
                        avatarTemplate = DiscourseJson.string(post, "avatar_template")
                    )
                }
            ordered.add(SearchHit(topic = topic.copy(searchBlurb = blurb, author = author), blurb = blurb))
        }

        // 兜底:某些响应只给 topics 不给 post_ids(如按标题命中),按 topics 原序补齐,避免结果空白。
        if (ordered.isEmpty()) {
            mappedTopics.forEach { topic ->
                if (seen.add(topic.id)) ordered.add(SearchHit(topic))
            }
        }

        return SearchPage(
            hits = ordered,
            page = page,
            hasMore = grouped?.get("more_full_page_results")?.jsonPrimitive?.contentOrNull == "true"
        )
    }

    /**
     * 命中楼层的 blurb 是 HTML 片段(含 `<span class="search-highlight">` 之类),
     * 这里剥成纯文本;高亮染色交给 UI 层按搜索词自己算。
     */
    private fun blurbOf(post: JsonObject): String? {
        val raw = DiscourseJson.string(post, "blurb") ?: return null
        return htmlToPlainText(raw).takeIf { it.isNotBlank() }
    }

    private fun htmlToPlainText(html: String): String =
        html.replace(Regex("<[^>]*>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .trim()

    override suspend fun searchUsers(term: String, limit: Int): SearchUsersResult {
        val trimmed = term.trim()
        if (trimmed.isEmpty()) {
            return SearchUsersResult(users = emptyList())
        }
        // 用户搜索是独立接口,不带 page —— 一次性返回。
        val path = "/u/search/users?term=${encode(trimmed)}&limit=$limit"

        val response = client.getJson(path)
        if (!response.success) {
            val error = response.error
            return SearchUsersResult(
                users = emptyList(),
                error = error?.message ?: "搜索失败，请检查网络后重试",
                requiresChallenge = error?.requiresChallenge == true
            )
        }
        val data = response.data ?: return SearchUsersResult(emptyList(), error = "站点搜索响应为空")

        return runCatching {
            val root = json.parseToJsonElement(data).jsonObject
            // 响应形如 {"users":[...]}。
            val users = DiscourseJson.parseUsers(root["users"]).values.toList()
            SearchUsersResult(users = users.sortedBy { it.id })
        }.getOrElse {
            SearchUsersResult(users = emptyList(), error = "站点搜索响应不是有效 JSON")
        }
    }

    override suspend fun suggestTags(query: String, limit: Int): List<TagSuggestion> {
        // 与鸿蒙 fetchTagSuggestions 同一路径:categoryId 省略即全站候选,
        // filterForInput=true 让服务端按输入过滤(pm_only 等不可选标签由前端再滤)。
        val path = "/tags/filter/search?q=${encode(query.trim())}&limit=$limit&filterForInput=true"

        val response = client.getJson(path)
        if (!response.success) return emptyList()
        val data = response.data ?: return emptyList()

        return runCatching {
            val root = json.parseToJsonElement(data).jsonObject
            root["results"]?.jsonArray.orEmpty().mapNotNull { element ->
                runCatching {
                    val obj = element.jsonObject
                    // pm_only(私信专属)标签不可选,跳过;id/name 缺失同样跳过。
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
}

/** 供 UI 层判断主题是否命中 —— 与 `Theme` 无关的纯逻辑,放这里避免各层各写一份。 */
internal fun HomeTopic.matchesTerm(term: String): Boolean =
    title.contains(term, ignoreCase = true)