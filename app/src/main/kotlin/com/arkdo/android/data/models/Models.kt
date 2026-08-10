package com.arkdo.android.data.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 话题数据模型
 *
 * 对齐 Discourse API 返回的 Topic 结构
 */
@Serializable
data class Topic(
    @SerialName("id") val id: Int,
    @SerialName("title") val title: String,
    @SerialName("fancy_title") val fancyTitle: String? = null,
    @SerialName("slug") val slug: String,
    @SerialName("posts_count") val postsCount: Int,
    @SerialName("reply_count") val replyCount: Int,
    @SerialName("highest_post_number") val highestPostNumber: Int,
    @SerialName("created_at") val createdAt: String,
    @SerialName("last_posted_at") val lastPostedAt: String? = null,
    @SerialName("bumped_at") val bumpedAt: String? = null,
    @SerialName("pinned") val pinned: Boolean = false,
    @SerialName("visible") val visible: Boolean = true,
    @SerialName("closed") val closed: Boolean = false,
    @SerialName("archived") val archived: Boolean = false,
    @SerialName("bookmarked") val bookmarked: Boolean = false,
    @SerialName("liked") val liked: Boolean = false,
    @SerialName("views") val views: Int,
    @SerialName("like_count") val likeCount: Int,
    @SerialName("has_summary") val hasSummary: Boolean = false,
    @SerialName("category_id") val categoryId: Int? = null,
    @SerialName("pinned_globally") val pinnedGlobally: Boolean = false,
    @SerialName("featured_link") val featuredLink: String? = null,
    @SerialName("posters") val posters: List<TopicPoster> = emptyList(),
    @SerialName("tags") val tags: List<String> = emptyList(),
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("excerpt") val excerpt: String? = null
)

/**
 * 话题参与者
 */
@Serializable
data class TopicPoster(
    @SerialName("user_id") val userId: Int,
    @SerialName("description") val description: String,
    @SerialName("extras") val extras: String? = null
)

/**
 * 话题列表响应
 */
@Serializable
data class TopicListResponse(
    @SerialName("users") val users: List<User>,
    @SerialName("topic_list") val topicList: TopicList
)

/**
 * 话题列表
 */
@Serializable
data class TopicList(
    @SerialName("can_create_topic") val canCreateTopic: Boolean,
    @SerialName("topics") val topics: List<Topic>,
    @SerialName("more_topics_url") val moreTopicsUrl: String? = null,
    @SerialName("per_page") val perPage: Int = 30
)

/**
 * 用户数据模型
 */
@Serializable
data class User(
    @SerialName("id") val id: Int,
    @SerialName("username") val username: String,
    @SerialName("name") val name: String? = null,
    @SerialName("avatar_template") val avatarTemplate: String,
    @SerialName("title") val title: String? = null,
    @SerialName("trust_level") val trustLevel: Int = 0,
    @SerialName("admin") val admin: Boolean = false,
    @SerialName("moderator") val moderator: Boolean = false
)

/**
 * 帖子数据模型
 */
@Serializable
data class Post(
    @SerialName("id") val id: Int,
    @SerialName("username") val username: String,
    @SerialName("avatar_template") val avatarTemplate: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("cooked") val cooked: String, // HTML 内容
    @SerialName("post_number") val postNumber: Int,
    @SerialName("post_type") val postType: Int,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("reply_count") val replyCount: Int,
    @SerialName("quote_count") val quoteCount: Int,
    @SerialName("incoming_link_count") val incomingLinkCount: Int,
    @SerialName("reads") val reads: Int,
    @SerialName("score") val score: Double,
    @SerialName("yours") val yours: Boolean = false,
    @SerialName("topic_id") val topicId: Int,
    @SerialName("topic_slug") val topicSlug: String,
    @SerialName("display_username") val displayUsername: String? = null,
    @SerialName("primary_group_name") val primaryGroupName: String? = null,
    @SerialName("version") val version: Int,
    @SerialName("can_edit") val canEdit: Boolean = false,
    @SerialName("can_delete") val canDelete: Boolean = false,
    @SerialName("can_recover") val canRecover: Boolean = false,
    @SerialName("user_id") val userId: Int,
    @SerialName("bookmarked") val bookmarked: Boolean = false,
    @SerialName("reactions") val reactions: List<Reaction>? = null
)

/**
 * 表情回应
 */
@Serializable
data class Reaction(
    @SerialName("id") val id: String,
    @SerialName("type") val type: String,
    @SerialName("count") val count: Int,
    @SerialName("can_undo") val canUndo: Boolean = false
)
