package com.arkdo.android.core.data.home

import com.arkdo.android.core.model.HomePage
import com.arkdo.android.core.model.HomePageResult
import com.arkdo.android.core.model.HomeQuery
import com.arkdo.android.core.model.HomeCategory
import com.arkdo.android.core.model.HomeFeed
import com.arkdo.android.core.model.HomeTag
import com.arkdo.android.core.model.HomeTopic
import com.arkdo.android.core.model.HomeUser
import com.arkdo.android.core.network.JsonNetworkClient
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
            val users = root["users"]?.jsonArray.orEmpty()
                .mapNotNull(::parseUser)
                .associateBy { it.id }
            val items = topicList["topics"]?.jsonArray.orEmpty().mapNotNull { element ->
                parseTopic(element.jsonObject, users)
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

    private fun parseUser(element: kotlinx.serialization.json.JsonElement): HomeUser? {
        val obj = element.jsonObject
        val id = obj.long("id") ?: return null
        val username = obj.string("username") ?: return null
        return HomeUser(
            id = id,
            username = username,
            name = obj.string("name"),
            avatarTemplate = obj.string("avatar_template"),
            animatedAvatar = obj.string("animated_avatar"),
            flairUrl = obj.string("flair_url"),
            flairBgColor = obj.string("flair_bg_color"),
            flairColor = obj.string("flair_color"),
            flairName = obj.string("flair_name")
        )
    }

    private fun parseTopic(obj: JsonObject, users: Map<Long, HomeUser>): HomeTopic? {
        val id = obj.long("id") ?: return null
        val title = obj.string("title") ?: return null
        val authorId = obj["posters"]?.jsonArray?.firstOrNull()?.jsonObject?.long("user_id")
        return HomeTopic(
            id = id,
            title = title,
            slug = obj.string("slug"),
            categoryId = obj.long("category_id"),
            tags = parseTags(obj["tags"]),
            createdAt = obj.string("created_at"),
            replyCount = obj.int("reply_count"),
            views = obj.int("views"),
            lastPostedAt = obj.string("last_posted_at"),
            unseen = obj.boolean("unseen"),
            unreadPosts = obj.int("unread_posts") ?: 0,
            lastReadPostNumber = obj.int("last_read_post_number"),
            highestPostNumber = obj.int("highest_post_number"),
            bookmarked = obj.boolean("bookmarked"),
            hasAcceptedAnswer = obj.boolean("has_accepted_answer"),
            searchBlurb = obj.string("excerpt"),
            category = obj["category"]?.jsonObject?.let(::parseCategory),
            author = authorId?.let(users::get)
        )
    }

    private fun parseCategory(obj: JsonObject): HomeCategory? {
        val id = obj.long("id") ?: return null
        val name = obj.string("name") ?: return null
        return HomeCategory(
            id = id,
            name = name,
            color = obj.string("color") ?: "6B7280",
            textColor = obj.string("text_color") ?: "FFFFFF",
            parentCategoryId = obj.long("parent_category_id"),
            slug = obj.string("slug"),
            uploadedLogo = obj.string("uploaded_logo"),
            icon = obj.string("icon"),
            readRestricted = obj.boolean("read_restricted")
        )
    }

    private fun parseTags(element: kotlinx.serialization.json.JsonElement?): List<HomeTag> =
        element?.jsonArray.orEmpty().mapNotNull { tag ->
            when (tag) {
                is JsonPrimitive -> tag.contentOrNull?.takeIf(String::isNotBlank)?.let(::HomeTag)
                is JsonObject -> tag.string("name")?.takeIf(String::isNotBlank)?.let {
                    HomeTag(it, tag.long("id"), tag.string("slug"))
                }
                else -> null
            }
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

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.long(key: String): Long? =
        this[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()

    private fun JsonObject.int(key: String): Int? =
        this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

    private fun JsonObject.boolean(key: String): Boolean =
        this[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true
}
