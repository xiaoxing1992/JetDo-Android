package com.arkdo.android.services.topic

import com.arkdo.android.common.constants.AppConstants
import com.arkdo.android.data.models.*
import com.arkdo.android.services.network.ChallengeState
import com.arkdo.android.services.network.JsonNetworkClient
import com.arkdo.android.services.network.JsonRequestOptions
import com.arkdo.android.services.network.NetworkResult
import com.arkdo.android.services.network.NetworkResults
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

/** 标签联想每次最多回几条，对齐鸿蒙 TAG_SEARCH_LIMIT。 */
private const val TAG_SEARCH_LIMIT = 5

/**
 * Topic 服务 - 话题列表/详情/互动
 *
 * 核心功能：
 * - 话题列表：latest/hot/unread/new/category
 * - 话题详情：单帖完整内容 + 楼层窗口
 * - 互动操作：书签、点赞、表情回应、投票
 * - 发表内容：回复、新主题、boost
 * - 阅读上报：timings（后台）
 *
 * 对齐 HarmonyOS TopicService.ets 的实现模式。
 */
class TopicService(private val bridge: JsonNetworkClient) {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    // ========== 话题列表 ==========

    /**
     * 获取最新话题（首页默认流）
     */
    suspend fun getLatestTopics(): NetworkResult<List<Topic>> {
        val pageResult = getLatestTopicsPage(0)
        if (!pageResult.success || pageResult.data == null) {
            return NetworkResult(
                success = pageResult.success,
                statusCode = pageResult.statusCode,
                challengeState = pageResult.challengeState,
                data = null
            )
        }
        return NetworkResult(
            success = true,
            statusCode = pageResult.statusCode,
            challengeState = pageResult.challengeState,
            data = pageResult.data.topics
        )
    }

    /**
     * 按查询条件取一页话题。对齐鸿蒙 HomePage 的取数路径 ——
     * filter（排序）与 scope（版块/标签）两个维度正交，路径由 [buildTopicListPath] 统一拼。
     *
     * 上面那几个 getXxxTopicsPage 是只覆盖单一维度的旧入口，新代码一律走这个。
     */
    suspend fun getTopicsPage(query: TopicListQuery, page: Int): NetworkResult<TopicPage> {
        return getTopicListPage(buildTopicListPath(query, page), page)
    }

    /**
     * 获取最新话题（分页）
     */
    suspend fun getLatestTopicsPage(page: Int): NetworkResult<TopicPage> {
        return getTopicListPage("/latest.json" + if (page > 0) "?page=$page" else "", page)
    }

    /**
     * 获取热门话题（分页）
     */
    suspend fun getHotTopicsPage(page: Int): NetworkResult<TopicPage> {
        return getTopicListPage("/hot.json" + if (page > 0) "?page=$page" else "", page)
    }

    /**
     * 获取未读话题（分页）
     */
    suspend fun getUnreadTopicsPage(page: Int): NetworkResult<TopicPage> {
        return getTopicListPage("/unread.json" + if (page > 0) "?page=$page" else "", page)
    }

    /**
     * 获取新话题（分页）
     */
    suspend fun getNewTopicsPage(page: Int): NetworkResult<TopicPage> {
        return getTopicListPage("/new.json" + if (page > 0) "?page=$page" else "", page)
    }

    /**
     * 获取某分类的话题（分页）
     */
    suspend fun getCategoryTopicsPage(category: TopicCategory, page: Int): NetworkResult<TopicPage> {
        val suffix = if (page > 0) "?page=$page" else ""
        val path = if (!category.slug.isNullOrEmpty()) {
            "/c/${category.slug}/${category.id}/l/latest.json$suffix"
        } else {
            "/c/${category.id}/l/latest.json$suffix"
        }
        return getTopicListPage(path, page)
    }

    /**
     * 全站标签联想。抽屉的标签搜索框用它，对齐鸿蒙 TopicService.searchTagsSiteWide()。
     *
     * pm_only 的标签不在公开列表里出现，直接滤掉。
     */
    suspend fun searchTagsSiteWide(query: String): NetworkResult<List<TagSuggestion>> {
        val path = "/tags/filter/search?q=${
            java.net.URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        }&limit=$TAG_SEARCH_LIMIT&filterForInput=true"

        val response = bridge.getJson(path)
        if (!response.success || response.data == null) {
            return NetworkResult(
                success = false,
                statusCode = response.statusCode,
                challengeState = response.challengeState,
                data = null
            )
        }

        return runCatching {
            val payload = json.decodeFromString<DiscourseTagFilterResponse>(response.data)
            val tags = payload.results.orEmpty().mapNotNull { item ->
                if (item.pm_only == true || item.id == null || item.name == null) {
                    null
                } else {
                    TagSuggestion(
                        id = item.id,
                        name = item.name,
                        text = item.text ?: item.name,
                        count = item.count ?: 0,
                        description = item.description
                    )
                }
            }
            NetworkResult(
                success = true,
                statusCode = response.statusCode,
                challengeState = response.challengeState,
                data = tags
            )
        }.getOrElse {
            Timber.e(it, "[ArkDO][Topic] 标签联想解析失败")
            NetworkResult(
                success = false,
                statusCode = response.statusCode,
                challengeState = response.challengeState,
                data = null
            )
        }
    }

    /**
     * 按 ID 列表获取话题（去重、过滤无效 ID）
     */
    suspend fun getLatestTopicsByIds(topicIds: List<Long>): NetworkResult<List<Topic>> {
        val normalizedIds = topicIds
            .filter { it > 0 }
            .distinct()

        if (normalizedIds.isEmpty()) {
            return NetworkResult(
                success = true,
                statusCode = 200,
                challengeState = ChallengeState.NONE,
                data = emptyList()
            )
        }

        val path = "/latest.json?topic_ids=${normalizedIds.joinToString(",")}"
        val pageResult = getTopicListPage(path, 0)
        if (!pageResult.success || pageResult.data == null) {
            return NetworkResult(
                success = pageResult.success,
                statusCode = pageResult.statusCode,
                challengeState = pageResult.challengeState,
                data = null
            )
        }
        return NetworkResult(
            success = true,
            statusCode = pageResult.statusCode,
            challengeState = pageResult.challengeState,
            data = pageResult.data.topics
        )
    }

    /**
     * 话题列表通用解析
     */
    private suspend fun getTopicListPage(path: String, page: Int): NetworkResult<TopicPage> {
        timber.log.Timber.i("[ArkDO][TopicService] getTopicListPage: path=%s", path)
        val response = bridge.getJson(path)
        timber.log.Timber.i("[ArkDO][TopicService] getTopicListPage: response success=%s statusCode=%d dataLength=%d",
            response.success, response.statusCode, response.data?.length ?: 0)

        if (!response.success) {
            return NetworkResults.forwardFailure(response)
        }

        val data = response.data
        if (data == null) {
            timber.log.Timber.e("[ArkDO][TopicService] getTopicListPage: response data is null")
            return NetworkResults.unexpectedResponse(
                response.statusCode,
                response.challengeState,
                "Linux.do 帖子列表响应为空。"
            )
        }

        return try {
            timber.log.Timber.d("[ArkDO][TopicService] getTopicListPage: parsing JSON, length=%d", data.length)
            val payload = json.decodeFromString<DiscourseLatestResponse>(data)
            val topicList = payload.topic_list

            if (topicList == null || topicList.topics == null) {
                timber.log.Timber.e("[ArkDO][TopicService] getTopicListPage: topicList=%s topics=%s",
                    topicList, topicList?.topics)
                return NetworkResults.unexpectedResponse(
                    response.statusCode,
                    response.challengeState,
                    "Linux.do 帖子列表响应缺少 topic_list.topics。"
                )
            }

            val mappedTopics = mapTopics(topicList.topics, payload.users)
            timber.log.Timber.i("[ArkDO][TopicService] getTopicListPage: parsed %d topics", mappedTopics.size)
            NetworkResult(
                success = true,
                statusCode = response.statusCode,
                challengeState = response.challengeState,
                data = TopicPage(
                    topics = mappedTopics,
                    page = page,
                    hasMore = topicList.more_topics_url != null
                )
            )
        } catch (e: Exception) {
            // e.message 含 kotlinx 的字段路径与 offset;单独一行输出并附响应体开头,
            // 避免堆栈首行被 logcat 环形缓冲冲掉后丢失定位信息(2026-08-14 曾发生)。
            timber.log.Timber.e("[ArkDO][TopicService] getTopicListPage: JSON parse failed: %s | bodyHead=%s",
                e.message, data.take(400))
            timber.log.Timber.e(e, "[ArkDO][TopicService] getTopicListPage: JSON parse failed")
            NetworkResults.unexpectedResponse(
                response.statusCode,
                response.challengeState,
                "Linux.do 帖子列表响应不是有效 JSON: ${e.message}"
            )
        }
    }

    // ========== 话题详情 ==========

    /**
     * 获取话题详情（完整首屏）
     * @param postNumber 指定楼层锚点（服务端以该楼为中心返回窗口）
     */
    suspend fun getTopicDetail(
        topicId: Long,
        slug: String? = null,
        postNumber: Int? = null
    ): NetworkResult<TopicDetail> {
        val anchor = if (postNumber != null && postNumber > 1) "/$postNumber" else ""
        val path = if (!slug.isNullOrEmpty()) {
            "/t/$slug/$topicId$anchor.json"
        } else {
            "/t/$topicId$anchor.json"
        }

        val response = bridge.getJson(path)
        if (!response.success) {
            return NetworkResults.forwardFailure(response)
        }

        val data = response.data
        if (data == null) {
            return NetworkResults.unexpectedResponse(
                response.statusCode,
                response.challengeState,
                "Linux.do 帖子详情响应为空。"
            )
        }

        return try {
            val payload = json.decodeFromString<DiscourseTopicDetail>(data)

            if (payload.id == null || payload.title == null || payload.post_stream?.posts == null) {
                return NetworkResults.unexpectedResponse(
                    response.statusCode,
                    response.challengeState,
                    "Linux.do 帖子详情响应缺少必要字段。"
                )
            }

            NetworkResult(
                success = true,
                statusCode = response.statusCode,
                challengeState = response.challengeState,
                data = mapTopicDetail(payload)
            )
        } catch (e: Exception) {
            // 别让异常静默：日志页/adb 都靠它定位是字段类型不符还是响应被盾页顶掉。
            Timber.e(e, "[ArkDO][TopicService] getTopicDetail parse failed length=${data.length}")
            NetworkResults.unexpectedResponse(
                response.statusCode,
                response.challengeState,
                "Linux.do 帖子详情响应不是有效 JSON。"
            )
        }
    }

    /**
     * 加载更多楼层（按 postIds[] 窗口）
     */
    suspend fun loadMorePosts(topicId: Long, postIds: List<Long>): NetworkResult<List<TopicPost>> {
        if (postIds.isEmpty()) {
            return NetworkResult(
                success = true,
                statusCode = 200,
                challengeState = ChallengeState.NONE,
                data = emptyList()
            )
        }

        val params = postIds.joinToString("&") { "post_ids%5B%5D=$it" }
        val path = "/t/$topicId/posts.json?$params&include_suggested=false"

        val response = bridge.getJson(path)
        if (!response.success) {
            return NetworkResults.forwardFailure(response)
        }

        val data = response.data
        if (data == null) {
            return NetworkResults.unexpectedResponse(
                response.statusCode,
                response.challengeState,
                "Linux.do 加载更多楼层响应为空。"
            )
        }

        return try {
            val payload = json.decodeFromString<DiscourseTopicDetail>(data)
            NetworkResult(
                success = true,
                statusCode = response.statusCode,
                challengeState = response.challengeState,
                data = mapPosts(payload.post_stream?.posts ?: emptyList())
            )
        } catch (e: Exception) {
            Timber.e(e, "[ArkDO][TopicService] loadMorePosts parse failed length=${data.length}")
            NetworkResults.unexpectedResponse(
                response.statusCode,
                response.challengeState,
                "Linux.do 加载更多楼层响应不是有效 JSON。"
            )
        }
    }

    // ========== 互动操作 ==========

    /**
     * 添加书签
     * @return bookmarkId（供后续取消使用）
     */
    suspend fun addBookmark(postId: Long, topicId: Long): NetworkResult<Long> {
        val body = "reminder_at=&auto_delete_preference=3&bookmarkable_id=$postId&bookmarkable_type=Post"
        val referrer = "${AppConstants.BASE_URL}/t/topic/$topicId"

        val response = bridge.postForm("/bookmarks.json", body, JsonRequestOptions(referrer = referrer))
        if (!response.success) {
            return NetworkResults.forwardFailure(response)
        }

        val data = response.data
        if (data == null) {
            return NetworkResults.unexpectedResponse(
                response.statusCode,
                response.challengeState,
                "Linux.do 添加书签响应为空。"
            )
        }

        return try {
            val wrapper = json.decodeFromString<DiscourseBookmarkResponse>(data)
            if (wrapper.id == null) {
                return NetworkResults.unexpectedResponse(
                    response.statusCode,
                    response.challengeState,
                    "Linux.do 添加书签响应缺少 id。"
                )
            }
            NetworkResult(
                success = true,
                statusCode = response.statusCode,
                challengeState = response.challengeState,
                data = wrapper.id
            )
        } catch (e: Exception) {
            NetworkResults.unexpectedResponse(
                response.statusCode,
                response.challengeState,
                "Linux.do 添加书签响应不是有效 JSON。"
            )
        }
    }

    /**
     * 取消书签
     * @return topic_bookmarked（主题是否仍有书签）
     */
    suspend fun removeBookmark(bookmarkId: Long, topicId: Long): NetworkResult<Boolean?> {
        val referrer = "${AppConstants.BASE_URL}/t/topic/$topicId"
        val response = bridge.deleteForm("/bookmarks/$bookmarkId.json", "", JsonRequestOptions(referrer = referrer))

        if (!response.success) {
            return NetworkResults.forwardFailure(response)
        }

        // 删除本身已成功，尽力解析 topic_bookmarked
        val data = response.data ?: return NetworkResult(
            success = true,
            statusCode = response.statusCode,
            challengeState = response.challengeState,
            data = null
        )

        return try {
            val wrapper = json.decodeFromString<DiscourseBookmarkDeleteResponse>(data)
            NetworkResult(
                success = true,
                statusCode = response.statusCode,
                challengeState = response.challengeState,
                data = wrapper.topic_bookmarked
            )
        } catch (e: Exception) {
            NetworkResult(
                success = true,
                statusCode = response.statusCode,
                challengeState = response.challengeState,
                data = null
            )
        }
    }

    /**
     * 切换表情回应（点亮/取消/切换）
     * PUT 空 body，同一楼同一用户只保留一个回应
     */
    suspend fun toggleReaction(postId: Long, topicId: Long, reaction: String): NetworkResult<String> {
        val path = "/discourse-reactions/posts/$postId/custom-reactions/${reaction}/toggle.json"
        val referrer = "${AppConstants.BASE_URL}/t/topic/$topicId"
        return bridge.putJson(path, JsonRequestOptions(notifyChallenge = false, referrer = referrer))
    }

    /**
     * 阅读上报（后台 POST，不触发 Challenge 弹窗）
     */
    suspend fun reportTimings(
        topicId: Long,
        timings: Map<Int, Int>,
        topicTime: Int
    ): NetworkResult<String> {
        val parts = mutableListOf<String>()
        timings.forEach { (postNumber, milliseconds) ->
            parts.add("timings%5B$postNumber%5D=${milliseconds.coerceAtLeast(0)}")
        }
        parts.add("topic_time=${topicTime.coerceAtLeast(0)}")
        parts.add("topic_id=$topicId")

        val body = parts.joinToString("&")
        val referrer = "${AppConstants.BASE_URL}/t/topic/$topicId"

        return bridge.postForm(
            "/topics/timings",
            body,
            JsonRequestOptions(notifyChallenge = false, referrer = referrer)
        )
    }

    // ========== 数据映射（精简版，完整实现需参考 TopicModels.kt） ==========

    private fun mapTopics(sourceTopics: List<DiscourseTopic>, sourceUsers: List<DiscourseUser>?): List<Topic> {
        val userMap = sourceUsers?.associateBy { it.id } ?: emptyMap()
        return sourceTopics.mapNotNull { source ->
            if (source.id == null || source.title == null) return@mapNotNull null

            val authorId = source.posters?.firstOrNull()?.user_id
            val author = if (authorId != null) userMap[authorId]?.let { mapUser(it) } else null

            val posters = source.posters?.map {
                TopicPoster(userId = it.user_id ?: 0L, description = "", extras = null)
            } ?: emptyList()

            val tags = source.tags?.map { tag ->
                TopicTag(id = tag.id, name = tag.name ?: "", slug = tag.slug)
            } ?: emptyList()

            // 调试日志：检查原始 API 数据和映射后的标签
            if (source.tags != null && source.tags.isNotEmpty()) {
                android.util.Log.d("TopicService", "Topic ${source.id}: Raw tags from API = ${source.tags.map { "${it.name}(${it.id})" }}")
                android.util.Log.d("TopicService", "Topic ${source.id}: Mapped tags = ${tags.map { it.name }}")
            } else {
                android.util.Log.d("TopicService", "Topic ${source.id}: No tags in API response")
            }

            Topic(
                id = source.id,
                title = source.title,
                slug = source.slug,
                categoryId = source.category_id,
                tags = tags,
                postsCount = source.posts_count,
                replyCount = source.reply_count,
                likeCount = source.like_count,
                createdAt = source.created_at,
                lastPostedAt = source.last_posted_at,
                unseen = null,
                unreadPosts = null,
                lastReadPostNumber = null,
                highestPostNumber = null,
                hasAcceptedAnswer = source.has_accepted_answer,
                bookmarked = null,
                excerpt = source.excerpt,
                imageUrl = source.image_url,
                views = source.views,
                pinned = source.pinned ?: false,
                closed = source.closed ?: false,
                archived = source.archived ?: false,
                visible = true,
                posters = posters
            ).apply {
                this.author = author
            }
        }
    }

    private fun mapTopicDetail(payload: DiscourseTopicDetail): TopicDetail {
        // 简化版映射，完整实现需处理 polls/reactions/boosts/badges 等
        val posts = mapPosts(payload.post_stream?.posts ?: emptyList())
        val stream = payload.post_stream?.stream ?: emptyList()

        val tags = payload.tags?.map { tag ->
            TopicTag(id = tag.id, name = tag.name ?: "", slug = tag.slug)
        } ?: emptyList()

        return TopicDetail(
            id = payload.id!!,
            title = payload.title!!,
            slug = payload.slug,
            categoryId = payload.category_id,
            tags = tags,
            postsCount = payload.posts_count,
            replyCount = payload.reply_count,
            likeCount = payload.like_count,
            viewCount = payload.views,
            createdAt = payload.created_at,
            postStream = PostStream(posts = posts, stream = stream)
        )
    }

    private fun mapPosts(sourcePosts: List<DiscoursePost>): List<TopicPost> {
        return sourcePosts.mapNotNull { source ->
            if (source.id == null || source.cooked == null) return@mapNotNull null

            TopicPost(
                id = source.id,
                topicId = null,
                postNumber = source.post_number ?: 0,
                cookedHtml = source.cooked,
                createdAt = source.created_at,
                updatedAt = source.updated_at,
                replyCount = source.reply_count,
                replyToPostNumber = source.reply_to_post_number,
                likeCount = source.likes_count,
                read = source.read,
                userTitle = source.user_title?.takeIf { it.isNotEmpty() },
                reactions = null,
                reactionUsersCount = null,
                currentUserReaction = null,
                boosts = null,
                canBoost = null,
                polls = null,
                bookmarked = null,
                bookmarkId = null,
                userStatus = null,
                userId = source.user_id,
                username = source.username,
                name = source.name,
                avatarTemplate = source.avatar_template,
                animatedAvatar = source.animated_avatar?.takeIf { it.isNotEmpty() },
                linkClicks = buildLinkClicks(source.cooked ?: "", source.link_counts),
                flairUrl = source.flair_url?.takeIf { it.isNotEmpty() },
                flairBgColor = source.flair_bg_color?.takeIf { it.isNotEmpty() },
                flairColor = source.flair_color?.takeIf { it.isNotEmpty() },
                flairName = source.flair_name?.takeIf { it.isNotEmpty() }
            ).also { post ->
                // replyToUser 是类体可变字段（非构造参数），构造后赋值。
                post.replyToUser = source.reply_to_user?.let {
                    User(
                        id = it.id ?: 0,
                        username = it.username ?: "",
                        name = it.name,
                        avatarTemplate = it.avatar_template,
                        animatedAvatar = it.animated_avatar?.takeIf { a -> a.isNotEmpty() }
                    )
                }
            }
        }
    }

    /**
     * DiscourseUser -> User。空串一律归一成 null，
     * 下游只需判 null，不用再各自 isNotEmpty 一遍。
     */
    private fun mapUser(source: DiscourseUser): User {
        return User(
            id = source.id ?: 0,
            username = source.username ?: "",
            name = source.name,
            avatarTemplate = source.avatar_template,
            animatedAvatar = source.animated_avatar?.takeIf { it.isNotEmpty() },
            flairUrl = source.flair_url?.takeIf { it.isNotEmpty() },
            flairBgColor = source.flair_bg_color?.takeIf { it.isNotEmpty() },
            flairColor = source.flair_color?.takeIf { it.isNotEmpty() },
            flairName = source.flair_name?.takeIf { it.isNotEmpty() }
        )
    }
}

// ========== 数据类（对应 Discourse API 响应） ==========

@Serializable
data class TopicPage(
    val topics: List<Topic>,
    val page: Int,
    val hasMore: Boolean
)

@Serializable
data class DiscourseLatestResponse(
    val users: List<DiscourseUser>? = null,
    val topic_list: DiscourseTopicList? = null
)

@Serializable
data class DiscourseTopicList(
    val topics: List<DiscourseTopic>? = null,
    val more_topics_url: String? = null
)

@Serializable
data class DiscourseTopic(
    val id: Long? = null,
    val title: String? = null,
    val slug: String? = null,
    val posts_count: Int? = null,
    val reply_count: Int? = null,
    val views: Int? = null,
    val like_count: Int? = null,
    val category_id: Long? = null,
    val pinned: Boolean? = null,
    val closed: Boolean? = null,
    val archived: Boolean? = null,
    val created_at: String? = null,
    val bumped_at: String? = null,
    val last_posted_at: String? = null,
    val excerpt: String? = null,
    val image_url: String? = null,
    val posters: List<DiscoursePoster>? = null,
    val tags: List<DiscourseTag>? = null,
    val fancy_title: String? = null,
    val has_accepted_answer: Boolean? = null
)

@Serializable
data class DiscourseTag(
    val id: Long? = null,
    val name: String? = null,
    val slug: String? = null
)

@Serializable
data class DiscoursePoster(
    val user_id: Long? = null
)

@Serializable
data class DiscourseUser(
    val id: Long? = null,
    val username: String? = null,
    val name: String? = null,
    val avatar_template: String? = null,
    // 下面这些以前没声明，导致头像角标和动图头像在列表页永远拿不到值。
    val animated_avatar: String? = null,
    val flair_url: String? = null,
    val flair_bg_color: String? = null,
    val flair_color: String? = null,
    val flair_name: String? = null
)

@Serializable
data class DiscourseTopicDetail(
    val id: Long? = null,
    val title: String? = null,
    val slug: String? = null,
    val posts_count: Int? = null,
    val reply_count: Int? = null,
    val views: Int? = null,
    val like_count: Int? = null,
    val category_id: Long? = null,
    val pinned: Boolean? = null,
    val closed: Boolean? = null,
    val archived: Boolean? = null,
    val created_at: String? = null,
    val post_stream: DiscoursePostStream? = null,
    // linux.do 详情接口的 tags 是对象数组（{id,name,slug}），不是字符串数组 ——
    // 按 List<String> 解析会在 $.tags[0] 直接抛 "Expected beginning of the string"。
    val tags: List<DiscourseTag>? = null,
    val fancy_title: String? = null
)

@Serializable
data class DiscoursePostStream(
    val posts: List<DiscoursePost>? = null,
    val stream: List<Long>? = null
)

@Serializable
data class DiscoursePost(
    val id: Long? = null,
    val post_number: Int? = null,
    val username: String? = null,
    val name: String? = null,
    val avatar_template: String? = null,
    // 帖子作者同样有动图候选（linux.do 对 BasicUser 系列序列化都附带动图地址）。
    val animated_avatar: String? = null,
    // 用户头衔（如「不二之选」），楼层作者栏第二行显示。
    val user_title: String? = null,
    // 作者 flair 四件套（详情页头像右下角角标），与列表 users[] 同构。
    val flair_url: String? = null,
    val flair_bg_color: String? = null,
    val flair_color: String? = null,
    val flair_name: String? = null,
    // 「回复别人」指示器的被回复用户（头像 20dp 用）。
    val reply_to_user: DiscoursePostUser? = null,
    // 本楼正文各链接的被点击次数（PostSerializer#link_counts），渲染层据此挂灰底角标。
    val link_counts: List<DiscourseLinkCount>? = null,
    val cooked: String? = null,
    val created_at: String? = null,
    val updated_at: String? = null,
    val reply_count: Int? = null,
    val reply_to_post_number: Int? = null,
    val user_id: Long? = null,
    val likes_count: Int? = null,
    val read: Boolean? = null
)

/** 帖子内嵌用户（reply_to_user 等）：头像候选 + 展示字段。 */
@Serializable
data class DiscoursePostUser(
    val id: Long? = null,
    val username: String? = null,
    val name: String? = null,
    val avatar_template: String? = null,
    val animated_avatar: String? = null
)

/** link_counts 的一项。url 与 cooked 里的 href 同源（站内链接也是绝对地址），据此配对挂角标。 */
@Serializable
data class DiscourseLinkCount(
    val url: String? = null,
    val clicks: Int? = null,
    // reflection = 反链（别的帖引用了本帖），不在本楼正文里，不挂角标。
    val reflection: Boolean? = null
)

@Serializable
data class DiscourseBookmarkResponse(
    val id: Long? = null
)

@Serializable
data class DiscourseBookmarkDeleteResponse(
    val topic_bookmarked: Boolean? = null
)

/**
 * 标签联想项。发帖 payload 里 tags[] 用的是 name（不是 slug），搜索 refine 用的是 id。
 * count/description 供选择器展示（热度 + 版块说明）。
 */
data class TagSuggestion(
    val id: Long,
    val name: String,
    val text: String,
    val count: Int,
    val description: String? = null
)

@Serializable
data class DiscourseTagFilterResponse(
    val results: List<DiscourseTagResult>? = null
)

@Serializable
data class DiscourseTagResult(
    val id: Long? = null,
    val name: String? = null,
    val text: String? = null,
    val count: Int? = null,
    val description: String? = null,
    val pm_only: Boolean? = null
)
