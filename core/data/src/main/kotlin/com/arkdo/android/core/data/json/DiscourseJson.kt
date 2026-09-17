package com.arkdo.android.core.data.json

import com.arkdo.android.core.model.HomeCategory
import com.arkdo.android.core.model.HomeTag
import com.arkdo.android.core.model.HomeTopic
import com.arkdo.android.core.model.HomeUser
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Discourse JSON 的公共解析器。
 *
 * 首页列表、搜索结果、通知等多个界面拿到的 topic/user/category 是**同一套结构**
 * (search 的 `topics`/`users` 与 latest 的完全同构),解析规则必须一致 ——
 * 各自抄一份迟早会在某个字段上漂移(比如 `uploaded_logo` 的两种形态)。
 * 这里抽出单一实现,各处共用。
 *
 * 全部按"缺字段就退到安全默认值"处理:站点字段会随版本增删,解析不该因一个
 * 可选字段缺失就整条结果作废。
 */
internal object DiscourseJson {

    fun string(obj: JsonObject, key: String): String? =
        obj[key]?.jsonPrimitive?.contentOrNull

    fun long(obj: JsonObject, key: String): Long? =
        obj[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()

    fun int(obj: JsonObject, key: String): Int? =
        obj[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

    fun boolean(obj: JsonObject, key: String): Boolean =
        obj[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true

    fun parseUser(element: JsonElement): HomeUser? {
        val obj = element.jsonObject
        val id = long(obj, "id") ?: return null
        val username = string(obj, "username") ?: return null
        return HomeUser(
            id = id,
            username = username,
            name = string(obj, "name"),
            avatarTemplate = string(obj, "avatar_template"),
            animatedAvatar = string(obj, "animated_avatar"),
            flairUrl = string(obj, "flair_url"),
            flairBgColor = string(obj, "flair_bg_color"),
            flairColor = string(obj, "flair_color"),
            flairName = string(obj, "flair_name")
        )
    }

    /** users 数组 → 按 id 索引,供 topic 的 posters 反查作者。 */
    fun parseUsers(element: JsonElement?): Map<Long, HomeUser> =
        element?.jsonArray.orEmpty()
            .mapNotNull { runCatching { parseUser(it) }.getOrNull() }
            .associateBy { it.id }

    fun parseTopic(obj: JsonObject, users: Map<Long, HomeUser>): HomeTopic? {
        val id = long(obj, "id") ?: return null
        val title = string(obj, "title") ?: return null
        val authorId = obj["posters"]?.jsonArray?.firstOrNull()?.jsonObject?.let { long(it, "user_id") }
        return HomeTopic(
            id = id,
            title = title,
            slug = string(obj, "slug"),
            categoryId = long(obj, "category_id"),
            tags = parseTags(obj["tags"]),
            createdAt = string(obj, "created_at"),
            replyCount = int(obj, "reply_count"),
            views = int(obj, "views"),
            lastPostedAt = string(obj, "last_posted_at"),
            unseen = boolean(obj, "unseen"),
            unreadPosts = int(obj, "unread_posts") ?: 0,
            lastReadPostNumber = int(obj, "last_read_post_number"),
            highestPostNumber = int(obj, "highest_post_number"),
            bookmarked = boolean(obj, "bookmarked"),
            hasAcceptedAnswer = boolean(obj, "has_accepted_answer"),
            searchBlurb = string(obj, "excerpt"),
            category = obj["category"]?.jsonObject?.let(::parseCategory),
            author = authorId?.let(users::get)
        )
    }

    fun parseCategory(obj: JsonObject): HomeCategory? {
        val id = long(obj, "id") ?: return null
        val name = string(obj, "name") ?: return null
        return HomeCategory(
            id = id,
            name = name,
            color = string(obj, "color") ?: "6B7280",
            textColor = string(obj, "text_color") ?: "FFFFFF",
            parentCategoryId = long(obj, "parent_category_id"),
            slug = string(obj, "slug"),
            uploadedLogo = string(obj, "uploaded_logo"),
            icon = string(obj, "icon"),
            readRestricted = boolean(obj, "read_restricted")
        )
    }

    /** tags 有两种形态:纯字符串数组,或 `{name,id,slug}` 对象数组。 */
    fun parseTags(element: JsonElement?): List<HomeTag> =
        element?.jsonArray.orEmpty().mapNotNull { tag ->
            when (tag) {
                is JsonPrimitive -> tag.contentOrNull?.takeIf(String::isNotBlank)?.let(::HomeTag)
                is JsonObject -> string(tag, "name")?.takeIf(String::isNotBlank)?.let {
                    HomeTag(it, long(tag, "id"), string(tag, "slug"))
                }
                else -> null
            }
        }
}