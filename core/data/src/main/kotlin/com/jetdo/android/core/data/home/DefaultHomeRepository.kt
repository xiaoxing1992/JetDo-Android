package com.jetdo.android.core.data.home

import com.jetdo.android.core.model.HomePage
import com.jetdo.android.core.model.HomePageResult
import com.jetdo.android.core.model.HomeQuery
import com.jetdo.android.core.model.HomeCategory
import com.jetdo.android.core.model.HomeFeed
import com.jetdo.android.core.model.HomeTag
import com.jetdo.android.core.model.HomeTopic
import com.jetdo.android.core.model.HomeUser
import com.jetdo.android.core.data.json.DiscourseJson
import com.jetdo.android.core.network.JsonNetworkClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

class DefaultHomeRepository(
    private val client: JsonNetworkClient
) : HomeRepository {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun load(query: HomeQuery, page: Int): HomePageResult {
        return loadPath(buildPath(query, page), page)
    }

    override suspend fun loadByIds(topicIds: List<Long>): HomePageResult {
        val normalizedIds = topicIds.filter { it > 0 }.distinct()
        if (normalizedIds.isEmpty()) {
            return HomePageResult.Success(HomePage(emptyList(), page = 0, hasMore = false))
        }
        return loadPath(
            path = "/latest.json?topic_ids=${normalizedIds.joinToString(",")}",
            page = 0
        )
    }

    private suspend fun loadPath(path: String, page: Int): HomePageResult {
        val response = client.getJson(path)
        if (!response.success) {
            val error = response.error
            return HomePageResult.Failure(
                message = error?.message ?: "加载失败，请重试",
                requiresChallenge = error?.requiresChallenge == true
            )
        }

        val data = response.data ?: return HomePageResult.Failure("响应为空")
        return runCatching {
            val root = json.parseToJsonElement(data).jsonObject
            val topicList = root["topic_list"]?.jsonObject
                ?: return HomePageResult.Failure("响应缺少 topic_list")
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
        }.getOrElse { HomePageResult.Failure("响应不是有效 JSON") }
    }

    private fun buildPath(query: HomeQuery, page: Int): String {
        val segment = if (query.feed == HomeFeed.CREATED) HomeFeed.LATEST.apiValue else query.feed.apiValue
        val params = buildList {
            if (query.feed == HomeFeed.CREATED) add("order=created")
            if (page > 0) add("page=$page")
        }
        val suffix = if (params.isEmpty()) "" else "?${params.joinToString("&")}"
        val encodedTag = query.tag?.let { query.tagSlug?.takeIf(String::isNotBlank) ?: it }
            ?.let(::encode)
        val scope = when {
            query.categoryId != null && encodedTag != null -> "/tags/c/${categorySegment(query)}/${tagSegment(query)}/l/$segment.json"
            query.categoryId != null -> "/c/${categorySegment(query)}/l/$segment.json"
            encodedTag != null -> "/tag/${tagSegment(query)}/l/$segment.json"
            else -> "/$segment.json"
        }
        return scope + suffix
    }

    private fun categorySegment(query: HomeQuery): String =
        query.categorySlug?.takeIf(String::isNotBlank)?.let { "${encode(it)}/${query.categoryId}" }
            ?: query.categoryId.toString()

    private fun tagSegment(query: HomeQuery): String =
        query.tagId?.takeIf { it > 0 }?.let { "${encode(query.tagSlug ?: query.tag.orEmpty())}/$it" }
            ?: encode(query.tag.orEmpty())

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}
