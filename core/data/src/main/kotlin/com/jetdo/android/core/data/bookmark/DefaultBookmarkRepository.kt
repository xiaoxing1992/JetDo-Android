package com.jetdo.android.core.data.bookmark

import com.jetdo.android.core.data.json.DiscourseJson
import com.jetdo.android.core.model.HomePage
import com.jetdo.android.core.model.HomePageResult
import com.jetdo.android.core.model.HomeTopic
import com.jetdo.android.core.model.HomeUser
import com.jetdo.android.core.network.JsonNetworkClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

class DefaultBookmarkRepository(
    private val client: JsonNetworkClient
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun firstPage(username: String): BookmarkPageResult =
        fetchPage("/u/${encode(username)}/bookmarks.json")

    suspend fun nextPage(moreUrl: String): BookmarkPageResult = fetchPage(moreUrl)

    suspend fun delete(id: Long): Boolean {
        val response = client.deleteForm("/bookmarks/$id", "")
        return response.success
    }

    suspend fun togglePin(id: Long): Boolean {
        val response = client.putJson("/bookmarks/$id/toggle_pin")
        return response.success
    }

    suspend fun readTopics(page: Int): HomePageResult {
        val path = if (page > 0) "/read.json?page=$page" else "/read.json"
        val response = client.getJson(path)
        if (!response.success) {
            return HomePageResult.Failure(
                message = response.error?.message ?: "加载失败，请重试",
                requiresChallenge = response.error?.requiresChallenge == true
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

    private suspend fun fetchPage(path: String): BookmarkPageResult {
        val response = client.getJson(path)
        if (!response.success) {
            return BookmarkPageResult.Failure(
                message = response.error?.message ?: "书签加载失败，请重试",
                requiresChallenge = response.error?.requiresChallenge == true
            )
        }
        val data = response.data ?: return BookmarkPageResult.Failure("站点书签响应为空。")
        return runCatching {
            val root = json.parseToJsonElement(data).jsonObject
            val list = root["user_bookmark_list"]?.jsonObject
                ?: return BookmarkPageResult.Failure("站点书签响应缺少 user_bookmark_list。")
            val items = list["bookmarks"]?.jsonArray.orEmpty().mapNotNull { element ->
                runCatching { mapBookmark(element.jsonObject) }.getOrNull()
            }
            BookmarkPageResult.Success(
                BookmarkPage(
                    items = items,
                    moreUrl = list["more_bookmarks_url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
                )
            )
        }.getOrElse { BookmarkPageResult.Failure("站点书签响应不是有效 JSON。") }
    }

    private fun mapBookmark(source: JsonObject): BookmarkItem? {
        val topicId = DiscourseJson.long(source, "topic_id") ?: return null
        if (DiscourseJson.string(source, "title") == null && DiscourseJson.string(source, "fancy_title") == null) {
            return null
        }
        return BookmarkItem(
            id = DiscourseJson.long(source, "id") ?: 0L,
            topicId = topicId,
            title = bookmarkTitle(DiscourseJson.string(source, "fancy_title"), DiscourseJson.string(source, "title")),
            excerpt = bookmarkPlainText(DiscourseJson.string(source, "excerpt")),
            linkedPostNumber = bookmarkPostNumber(DiscourseJson.int(source, "linked_post_number")),
            pinned = DiscourseJson.boolean(source, "pinned"),
            categoryId = DiscourseJson.long(source, "category_id"),
            tags = DiscourseJson.parseTags(source["tags"]),
            author = mapAuthor(source["user"]?.jsonObject),
            highestPostNumber = DiscourseJson.int(source, "highest_post_number"),
            createdAt = DiscourseJson.string(source, "created_at")?.takeIf { it.isNotEmpty() }
        )
    }

    private fun mapAuthor(source: JsonObject?): HomeUser? {
        if (source == null) return null
        val id = DiscourseJson.long(source, "id") ?: return null
        val username = DiscourseJson.string(source, "username") ?: return null
        return HomeUser(
            id = id,
            username = username,
            name = DiscourseJson.string(source, "name")?.takeIf { it.isNotEmpty() },
            avatarTemplate = DiscourseJson.string(source, "avatar_template")?.takeIf { it.isNotEmpty() },
            animatedAvatar = DiscourseJson.string(source, "animated_avatar")?.takeIf { it.isNotEmpty() }
        )
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}

/** 翻页时按 id 去重，保留先出现的那条。 */
fun mergeReadTopics(existing: List<HomeTopic>, next: List<HomeTopic>): List<HomeTopic> {
    val seen = HashSet<Long>()
    val merged = ArrayList<HomeTopic>(existing.size + next.size)
    for (topic in existing) {
        if (seen.add(topic.id)) merged += topic
    }
    for (topic in next) {
        if (seen.add(topic.id)) merged += topic
    }
    return merged
}
