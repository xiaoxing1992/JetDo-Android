package com.arkdo.android.services.topic

import com.arkdo.android.common.constants.AppConstants
import com.arkdo.android.data.models.*
import com.arkdo.android.services.network.ChallengeState
import com.arkdo.android.services.network.JsonNetworkClient
import com.arkdo.android.services.network.JsonRequestOptions
import com.arkdo.android.services.network.NetworkResult
import com.arkdo.android.services.network.NetworkResults
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
     * 发帖/回复时限定分类的标签联想,对齐鸿蒙 TopicService.searchTags(categoryId, ...)。
     * 标签隶属分类,/tags/filter/search 带 categoryId 只回该分类下的候选(空 q 即热门前几个)。
     *
     * pm_only 的标签不在公开列表里出现,直接滤掉。
     */
    suspend fun searchTags(categoryId: Long, query: String): NetworkResult<List<TagSuggestion>> {
        return fetchTagSuggestions(query, emptyList(), categoryId)
    }

    /**
     * 全站标签联想。抽屉的标签搜索框用它,对齐鸿蒙 TopicService.searchTagsSiteWide()。
     *
     * pm_only 的标签不在公开列表里出现,直接滤掉。
     */
    suspend fun searchTagsSiteWide(query: String): NetworkResult<List<TagSuggestion>> {
        return fetchTagSuggestions(query, emptyList(), null)
    }

    /** 标签联想请求与解析:发帖(限定分类)与搜索(全站)共用,只差 categoryId 带不带。 */
    private suspend fun fetchTagSuggestions(
        query: String,
        selectedTagIds: List<Long>,
        categoryId: Long?
    ): NetworkResult<List<TagSuggestion>> {
        val path = buildString {
            append("/tags/filter/search?q=")
            append(java.net.URLEncoder.encode(query, "UTF-8").replace("+", "%20"))
            append("&limit=$TAG_SEARCH_LIMIT")
            if (categoryId != null) append("&categoryId=$categoryId")
            selectedTagIds.forEach { append("&selected_tag_ids%5B%5D=$it") }
            append("&filterForInput=true")
        }

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
     * 某楼的被回复列表（/posts/{id}/replies?after=1，响应为帖子数组）。
     */
    suspend fun getPostReplies(postId: Long): NetworkResult<List<TopicPost>> {
        val response = bridge.getJson("/posts/$postId/replies?after=1")
        if (!response.success) {
            return NetworkResults.forwardFailure(response)
        }
        val data = response.data ?: return NetworkResults.unexpectedResponse(
            response.statusCode,
            response.challengeState,
            "Linux.do 被回复列表响应为空。"
        )
        return try {
            val payload = json.decodeFromString<List<DiscoursePost>>(data)
            NetworkResult(
                success = true,
                statusCode = response.statusCode,
                challengeState = response.challengeState,
                data = mapPosts(payload)
            )
        } catch (e: Exception) {
            Timber.e(e, "[ArkDO][TopicService] getPostReplies parse failed length=${data.length}")
            NetworkResults.unexpectedResponse(
                response.statusCode,
                response.challengeState,
                "Linux.do 被回复列表响应不是有效 JSON。"
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
     * linux.do shared issue（"俺也一样"）toggle：POST /solution/shared_issue，form body topic_id。
     * 纯翻转端点——点/撤同一地址；响应 { count, user_created_shared_issue } 即翻转后的权威新态。
     * notifyChallenge:true——与书签/评论一致：撞 CF 时触发静默恢复，下一次点击就能过。
     * （对齐鸿蒙 toggleSharedIssue。）
     */
    suspend fun toggleSharedIssue(topicId: Long): NetworkResult<SharedIssueResult> {
        val referrer = "${AppConstants.BASE_URL}/t/topic/$topicId"
        val response = bridge.postForm(
            "/solution/shared_issue",
            "topic_id=$topicId",
            JsonRequestOptions(notifyChallenge = true, referrer = referrer)
        )
        if (!response.success) {
            return NetworkResults.forwardFailure(response)
        }
        val data = response.data
            ?: return NetworkResults.unexpectedResponse<SharedIssueResult>(
                response.statusCode,
                response.challengeState,
                "站点\"俺也一样\"响应为空。"
            )
        return try {
            // SharedIssueResult 的 @SerialName 已对齐响应字段（user_created_shared_issue / count），
            // 直接反序列化即得权威新态（created 语义 = user_created_shared_issue）。
            val result = json.decodeFromString<SharedIssueResult>(data)
            NetworkResult(
                success = true,
                statusCode = response.statusCode,
                challengeState = response.challengeState,
                data = result
            )
        } catch (_: Exception) {
            NetworkResults.unexpectedResponse(
                response.statusCode,
                response.challengeState,
                "站点\"俺也一样\"响应不是有效 JSON。"
            )
        }
    }

    /**
     * discourse-poll：给某楼的投票投票。
     * `PUT /polls/vote`，form body：post_id、poll_name、options[]。
     * 响应 200 返回 `{ poll: {更新后}, vote: [已投项] }`。
     * （对齐鸿蒙 votePoll。）
     */
    suspend fun votePoll(
        postId: Long,
        topicId: Long,
        pollName: String,
        optionIds: List<String>
    ): NetworkResult<PollVoteResult> {
        val parts = mutableListOf("post_id=$postId", "poll_name=${encode(pollName)}")
        optionIds.forEach { parts.add("options%5B%5D=${encode(it)}") }
        val referrer = "${AppConstants.BASE_URL}/t/topic/$topicId"
        val response = bridge.putForm(
            "/polls/vote",
            parts.joinToString("&"),
            JsonRequestOptions(notifyChallenge = false, referrer = referrer)
        )
        return parsePollVoteResponse(response)
    }

    /**
     * discourse-poll：撤销某楼投票。
     * `DELETE /polls/vote`，form body：post_id、poll_name。
     * 响应无 `vote` 字段，即已投项为空。
     */
    suspend fun removePollVote(
        postId: Long,
        topicId: Long,
        pollName: String
    ): NetworkResult<PollVoteResult> {
        val body = "post_id=$postId&poll_name=${encode(pollName)}"
        val referrer = "${AppConstants.BASE_URL}/t/topic/$topicId"
        val response = bridge.deleteForm(
            "/polls/vote",
            body,
            JsonRequestOptions(notifyChallenge = false, referrer = referrer)
        )
        return parsePollVoteResponse(response)
    }

    /**
     * 投票响应的公共解析。
     *
     * `vote` 字段在撤销时缺省，`PollVoteResult.chosenOptionIds` 的默认值正好是空列表，
     * 所以直接反序列化即可覆盖两种情况。
     */
    private fun parsePollVoteResponse(response: NetworkResult<String>): NetworkResult<PollVoteResult> {
        if (!response.success) {
            return NetworkResults.forwardFailure(response)
        }
        val data = response.data
            ?: return NetworkResults.unexpectedResponse(
                response.statusCode,
                response.challengeState,
                "站点投票响应为空。"
            )
        return try {
            val payload = json.decodeFromString<DiscoursePollVoteResponse>(data)
            val poll = payload.poll
                ?: return NetworkResults.unexpectedResponse(
                    response.statusCode,
                    response.challengeState,
                    "站点投票响应缺少 poll。"
                )
            NetworkResult(
                success = true,
                statusCode = response.statusCode,
                challengeState = response.challengeState,
                data = PollVoteResult(
                    options = poll.options,
                    chosenOptionIds = payload.vote.orEmpty(),
                    voters = poll.voters
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "[ArkDO][TopicService] poll vote parse failed length=${data.length}")
            NetworkResults.unexpectedResponse(
                response.statusCode,
                response.challengeState,
                "站点投票响应不是有效 JSON。"
            )
        }
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

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

    // ========== 发表内容 ==========

    /**
     * discourse-boosts：发一条 boost。POST /discourse-boosts/posts/{postId}/boosts，
     * form body raw（对齐鸿蒙 createBoost）。
     */
    suspend fun createBoost(postId: Long, topicId: Long, raw: String): NetworkResult<PostBoost> {
        val path = "/discourse-boosts/posts/$postId/boosts"
        val referrer = "${AppConstants.BASE_URL}/t/topic/$topicId"
        val response = bridge.postForm(
            path,
            "raw=${java.net.URLEncoder.encode(raw, "UTF-8")}",
            JsonRequestOptions(notifyChallenge = true, referrer = referrer)
        )
        if (!response.success) {
            return NetworkResults.forwardFailure(response)
        }
        val data = response.data
            ?: return NetworkResults.unexpectedResponse<PostBoost>(
                response.statusCode,
                response.challengeState,
                "站点发送 boost 响应为空。"
            )
        return try {
            val created = json.decodeFromString<DiscourseBoost>(data)
            val boosts = mapBoosts(listOf(created))
            if (boosts.isNotEmpty()) {
                NetworkResult(
                    success = true,
                    statusCode = response.statusCode,
                    challengeState = response.challengeState,
                    data = boosts.first()
                )
            } else {
                NetworkResults.unexpectedResponse(
                    response.statusCode,
                    response.challengeState,
                    "站点发送 boost 响应缺少 boost 数据。"
                )
            }
        } catch (_: Exception) {
            NetworkResults.unexpectedResponse(
                response.statusCode,
                response.challengeState,
                "站点发送 boost 响应不是有效 JSON。"
            )
        }
    }

    /**
     * discourse-boosts：删除自己发的一条 boost。DELETE /discourse-boosts/boosts/{boostId}，
     * 成功返回 204 无 body（对齐鸿蒙 deleteBoost，只看 success）。
     */
    suspend fun deleteBoost(boostId: Long, topicId: Long): NetworkResult<Boolean> {
        val referrer = "${AppConstants.BASE_URL}/t/topic/$topicId"
        val response = bridge.deleteForm(
            "/discourse-boosts/boosts/$boostId",
            "",
            JsonRequestOptions(notifyChallenge = true, referrer = referrer)
        )
        if (!response.success) {
            return NetworkResults.forwardFailure(response)
        }
        return NetworkResult(
            success = true,
            statusCode = response.statusCode,
            challengeState = response.challengeState,
            data = true
        )
    }

    /**
     * 发表评论/回复：POST /posts，body 为 JSON（对齐鸿蒙 createReply，即官方 composer 提交）。
     * 关键字段：raw(正文)、topic_id、category、archetype；其余为 composer 默认/遥测字段。
     * typingDurationMs / composerOpenDurationMs 由调用方按真实交互时长传入（缺省给合理值）。
     */
    suspend fun createReply(
        topicId: Long,
        categoryId: Long?,
        raw: String,
        typingDurationMs: Long = 0,
        composerOpenDurationMs: Long = 0,
        replyToPostNumber: Int? = null
    ): NetworkResult<CreateReplyOutcome> {
        val payload = buildCreateReplyPayload(
            topicId = topicId,
            categoryId = categoryId,
            raw = raw,
            typingDurationMs = typingDurationMs,
            composerOpenDurationMs = composerOpenDurationMs,
            replyToPostNumber = replyToPostNumber
        )
        val referrer = "${AppConstants.BASE_URL}/t/topic/$topicId"

        val response = bridge.postJson(
            "/posts",
            payload.toString(),
            JsonRequestOptions(notifyChallenge = true, referrer = referrer)
        )
        if (!response.success) {
            return NetworkResults.forwardFailure<CreateReplyOutcome>(response)
        }
        val data = response.data
            ?: return NetworkResults.unexpectedResponse<CreateReplyOutcome>(
                response.statusCode,
                response.challengeState,
                "站点发表评论响应为空。"
            )

        val outcome = parseCreateReplyResponse(data, json, ::mapPosts)
            ?: return NetworkResults.unexpectedResponse<CreateReplyOutcome>(
                response.statusCode,
                response.challengeState,
                "站点发表评论响应缺少楼层数据。"
            )
        return NetworkResult(
            success = true,
            statusCode = response.statusCode,
            challengeState = response.challengeState,
            data = outcome
        )
    }

    /**
     * 发新主题:POST /posts,与回帖同端点,靠 title + category、且不带 topic_id 来区分。
     * 成功响应同样是 { action:'create_post', post:{...} },post.topic_id 即新主题 id;
     * 进审核队列时为 { action:'enqueued', pending_post:{...} },此时没有可跳转的主题。
     * 标题长度/分类权限等规则不在本地猜,交服务端裁决,错误原文经 error.message 上抛。
     * (对齐鸿蒙 TopicService.createTopic + CreateTopicFlow.submitNewTopic。)
     */
    suspend fun createTopic(
        title: String,
        categoryId: Long,
        raw: String,
        tags: List<String> = emptyList(),
        typingDurationMs: Long = 0,
        composerOpenDurationMs: Long = 0
    ): NetworkResult<CreateTopicOutcome> {
        val payload = buildCreateNewTopicPayload(
            title = title,
            categoryId = categoryId,
            raw = raw,
            tags = tags,
            typingDurationMs = typingDurationMs,
            composerOpenDurationMs = composerOpenDurationMs
        )
        // 官方 composer 发新帖时停在列表页,Referer 给站点根即可(带上以过 CSRF/来源校验)。
        val referrer = "${AppConstants.BASE_URL}/"

        val response = bridge.postJson(
            "/posts",
            payload.toString(),
            JsonRequestOptions(notifyChallenge = true, referrer = referrer)
        )
        if (!response.success) {
            return NetworkResults.forwardFailure(response)
        }
        val data = response.data
            ?: return NetworkResults.unexpectedResponse(
                response.statusCode,
                response.challengeState,
                "站点发帖响应为空。"
            )
        return runCatching {
            val outcome = parseCreateTopicOutcome(data, json)
                ?: return@runCatching NetworkResults.unexpectedResponse(
                    response.statusCode,
                    response.challengeState,
                    "站点发帖响应缺少主题数据。"
                )
            NetworkResult(
                success = true,
                statusCode = response.statusCode,
                challengeState = response.challengeState,
                data = outcome
            )
        }.getOrElse {
            NetworkResults.unexpectedResponse(
                response.statusCode,
                response.challengeState,
                "站点发帖响应不是有效 JSON。"
            )
        }
    }

    // ========== 数据映射(精简版,完整实现需参考 TopicModels.kt) ==========

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
            postStream = PostStream(posts = posts, stream = stream),
            userId = payload.user_id,
            details = payload.details?.let { d ->
                TopicDetails(
                    createdBy = d.createdBy?.id?.let { id -> CreatedBy(id = id, username = "") },
                    notificationLevel = null
                )
            }
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
                reactions = source.reactions,
                reactionUsersCount = source.reaction_users_count,
                currentUserReaction = source.current_user_reaction?.id?.takeIf { it.isNotEmpty() },
                boosts = mapBoosts(source.boosts),
                canBoost = source.can_boost,
                polls = mapPolls(source),
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
    private fun mapBoosts(sourceBoosts: List<DiscourseBoost>?): List<PostBoost> {
        val boosts = mutableListOf<PostBoost>()
        sourceBoosts?.forEach { item ->
            if (item.id == null) return@forEach
            val boostUser = item.user
            if (boostUser == null) return@forEach
            boosts.add(
                PostBoost(
                    id = item.id,
                    cookedHtml = item.cooked ?: "",
                    user = User(
                        id = boostUser.id ?: 0,
                        username = boostUser.username ?: "",
                        name = boostUser.name,
                        avatarTemplate = boostUser.avatar_template,
                        animatedAvatar = boostUser.animated_avatar?.takeIf { it.isNotEmpty() }
                    )
                )
            )
        }
        return boosts
    }

    /**
     * discourse-poll：某楼的投票数组（对齐鸿蒙 mapPolls）。
     * 每个投票的「当前用户已投项」来自楼级 polls_votes 表（按 poll.name 键），
     * can_vote 是楼级开关，统一下放给各投票。
     */
    private fun mapPolls(source: DiscoursePost): List<Poll> {
        val polls = mutableListOf<Poll>()
        val sourcePolls = source.polls ?: return polls
        val canVote = source.can_vote == true
        val votesMap = source.polls_votes ?: emptyMap()
        sourcePolls.forEach { item ->
            polls.add(
                Poll(
                    name = item.name?.takeIf { it.isNotEmpty() } ?: "poll",
                    title = item.title,
                    type = item.type ?: "regular",
                    status = item.status ?: "open",
                    isPublic = item.public == true,
                    results = item.results ?: "always",
                    options = mapPollOptions(item.options),
                    voters = item.voters ?: 0,
                    chosenOptionIds = votesMap[item.name] ?: emptyList(),
                    canVote = canVote
                )
            )
        }
        return polls
    }

    private fun mapPollOptions(sourceOptions: List<DiscoursePollOption>?): List<PollOption> {
        val options = mutableListOf<PollOption>()
        sourceOptions?.forEach { item ->
            if (item.id.isNullOrEmpty()) return@forEach
            options.add(
                PollOption(
                    id = item.id,
                    label = item.html ?: "",
                    votes = item.votes ?: 0
                )
            )
        }
        return options
    }

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
    val fancy_title: String? = null,
    // 主题作者：details.created_by.id 优先，回退顶层 user_id（对齐鸿蒙 mapCreatedById）。
    val user_id: Long? = null,
    val details: DiscourseTopicDetails? = null
)

@Serializable
data class DiscourseTopicDetails(
    @SerialName("created_by") val createdBy: DiscourseCreatedBy? = null
)

@Serializable
data class DiscourseCreatedBy(
    val id: Long? = null
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
    // 发新主题响应(POST /posts)会回带 topic_id/topic_slug,createTopic 解析跳转用。
    val topic_id: Long? = null,
    val topic_slug: String? = null,
    val username: String? = null,
    val name: String? = null,
    val avatar_template: String? = null,
    // 帖子作者同样有动图候选（linux.do 对 BasicUser 系列序列化都附带动图地址）。
    val animated_avatar: String? = null,
    // 用户头衔（如「不二之选」），楼层作者栏第二行显示。
    val user_title: String? = null,
    // 表情回应：短名→数量（id 即短名如 heart/smile）、回应人数、我的当前回应。
    val reactions: List<PostReaction>? = null,
    val reaction_users_count: Int? = null,
    val current_user_reaction: DiscourseCurrentUserReaction? = null,
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
    val read: Boolean? = null,
    // discourse-boosts：楼下别人发的 boost（随 posts.json 返回）+ 能否发（can_boost，火箭入口依据）。
    val boosts: List<DiscourseBoost>? = null,
    val can_boost: Boolean? = null,
    // discourse-poll：该楼的投票列表、当前用户各投票的已投项、能否投票（对齐鸿蒙 DiscoursePost）。
    val polls: List<DiscoursePoll>? = null,
    val polls_votes: Map<String, List<String>>? = null,
    val can_vote: Boolean? = null
)

/** discourse-poll：一个投票选项（id + 展示 html + 票数）。 */
@Serializable
data class DiscoursePollOption(
    val id: String? = null,
    val html: String? = null,
    val votes: Int? = null
)

/** discourse-poll：一个投票的服务端结构。 */
@Serializable
data class DiscoursePoll(
    val name: String? = null,
    val title: String? = null,
    val type: String? = null,
    val status: String? = null,
    val public: Boolean? = null,
    val results: String? = null,
    val options: List<DiscoursePollOption>? = null,
    val voters: Int? = null
)

/** discourse-boosts：一条 boost（id + cooked 短文本 + 用户），对齐鸿蒙 DiscourseBoost。 */
@Serializable
data class DiscourseBoost(
    val id: Long? = null,
    val cooked: String? = null,
    val user: DiscourseBoostUser? = null
)

@Serializable
data class DiscourseBoostUser(
    val id: Long? = null,
    val username: String? = null,
    val name: String? = null,
    val avatar_template: String? = null,
    val animated_avatar: String? = null
)

/** current_user_reaction:我的当前回应短名。 */
@Serializable
data class DiscourseCurrentUserReaction(
    val id: String? = null
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

// ========== 发表回复（POST /posts） ==========

/** createReply 结果：直接发布带新楼层；进审核队列只带待审快照（无即时楼层）。 */
data class CreateReplyOutcome(
    val post: TopicPost? = null,
    val pending: Boolean = false,
    val pendingPost: PendingPost? = null
)

/** 发新主题结果:直接发布则带新主题 id(可跳转);进审核队列则只有 pending。 */
data class CreateTopicOutcome(
    val topicId: Long? = null,
    val pending: Boolean = false
)

/** POST /posts 响应包装。pending_post 字段结构与 PendingPost（id/raw/created_at）一致，直接复用。 */
@Serializable
data class DiscourseCreatePostResponse(
    val action: String? = null,
    val post: DiscoursePost? = null,
    @SerialName("pending_post") val pendingPost: PendingPost? = null
)

/**
 * 解析 POST /posts 响应（纯函数，便于单测）：
 * - 直接发布：{ action:'create_post', post:{…} } → 新楼层走 mapPosts 映射；
 * - 进审核队列：{ action:'enqueued', pending_post:{…} } → 提交成功但无即时楼层。
 * 两类都不满足视为无效响应（返回 null，由调用方转为 unexpectedResponse）。
 */
internal fun parseCreateReplyResponse(
    body: String,
    json: Json,
    mapPosts: (List<DiscoursePost>) -> List<TopicPost>
): CreateReplyOutcome? {
    val wrapper = try {
        json.decodeFromString<DiscourseCreatePostResponse>(body)
    } catch (_: SerializationException) {
        return null
    } catch (_: IllegalArgumentException) {
        return null
    }
    val created = wrapper.post
    if (created != null) {
        val posts = mapPosts(listOf(created))
        if (posts.isNotEmpty()) {
            return CreateReplyOutcome(post = posts.first(), pending = false)
        }
    }
    if (wrapper.action == "enqueued" || wrapper.pendingPost != null) {
        return CreateReplyOutcome(pending = true, pendingPost = wrapper.pendingPost)
    }
    return null
}

/**
 * 解析 POST /posts 发新主题响应(纯函数,便于单测):
 * - 直接发布:{ post:{ topic_id } } → 带新主题 id;
 * - 进审核队列:{ action:'enqueued' } → 只有 pending;
 * - 两类都不满足 → null(由调用方转为 unexpectedResponse「缺少主题数据」)。
 */
internal fun parseCreateTopicOutcome(body: String, json: Json): CreateTopicOutcome? {
    val wrapper = try {
        json.decodeFromString<DiscourseCreatePostResponse>(body)
    } catch (_: SerializationException) {
        return null
    } catch (_: IllegalArgumentException) {
        return null
    }
    val created = wrapper.post
    if (created != null && created.topic_id != null) {
        return CreateTopicOutcome(topicId = created.topic_id, pending = false)
    }
    if (wrapper.action == "enqueued" || wrapper.pendingPost != null) {
        return CreateTopicOutcome(topicId = null, pending = true)
    }
    return null
}

/**
 * createReply 的 POST body（纯函数，便于单测）。逐字段对齐鸿蒙 CreateReplyPayload：
 * category / reply_to_post_number 仅在非空时下发（条件赋值）。
 */
internal fun buildCreateReplyPayload(
    topicId: Long,
    categoryId: Long?,
    raw: String,
    typingDurationMs: Long = 0,
    composerOpenDurationMs: Long = 0,
    replyToPostNumber: Int? = null
): JsonObject = buildJsonObject {
    put("raw", raw)
    put("unlist_topic", false)
    put("topic_id", topicId)
    put("is_warning", false)
    put("whisper", false)
    put("archetype", "regular")
    put("typing_duration_msecs", typingDurationMs.coerceAtLeast(0L))
    put("composer_open_duration_msecs", composerOpenDurationMs.coerceAtLeast(0L))
    put("composer_version", 1)
    put("tags", buildJsonArray {})
    put("featured_link", JsonNull)
    put("shared_draft", false)
    put("draft_key", "topic_$topicId")
    put("locale", "")
    put("image_sizes", buildJsonObject {})
    put("nested_post", true)
    if (categoryId != null) {
        put("category", categoryId)
    }
    if (replyToPostNumber != null) {
        put("reply_to_post_number", replyToPostNumber)
    }
}

/**
 * createTopic 的 POST body(纯函数,便于单测)。逐字段对齐鸿蒙 CreateTopicPayload:
 * 比回帖多了 title、category 恒带、draft_key 固定 'new_topic',且不带 topic_id;
 * tags[] 传标签 name(不是 id,对齐官方 composer)。
 */
internal fun buildCreateNewTopicPayload(
    title: String,
    categoryId: Long,
    raw: String,
    tags: List<String> = emptyList(),
    typingDurationMs: Long = 0,
    composerOpenDurationMs: Long = 0
): JsonObject = buildJsonObject {
    put("raw", raw)
    put("title", title)
    put("category", categoryId)
    put("unlist_topic", false)
    put("is_warning", false)
    put("archetype", "regular")
    put("typing_duration_msecs", typingDurationMs.coerceAtLeast(0L))
    put("composer_open_duration_msecs", composerOpenDurationMs.coerceAtLeast(0L))
    put("composer_version", 1)
    put("tags", buildJsonArray { tags.forEach { add(JsonPrimitive(it)) } })
    put("featured_link", JsonNull)
    put("shared_draft", false)
    put("draft_key", "new_topic")
    put("locale", "")
    put("image_sizes", buildJsonObject {})
    put("nested_post", true)
}

/** POST /solution/shared_issue 响应包装（对齐鸿蒙 DiscourseSharedIssueResponse）。 */
@Serializable
data class DiscourseSharedIssueResponse(
    val count: Int? = null,
    @SerialName("user_created_shared_issue") val userCreatedSharedIssue: Boolean? = null
)
