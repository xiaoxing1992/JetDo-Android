package com.arkdo.android.services.draft

import com.arkdo.android.common.constants.AppConstants
import com.arkdo.android.data.models.Draft
import com.arkdo.android.data.models.DraftAction
import com.arkdo.android.data.models.DraftListPayload
import com.arkdo.android.data.models.DraftPage
import com.arkdo.android.data.models.DraftTagRef
import com.arkdo.android.data.models.SaveDraftResponse
import com.arkdo.android.services.network.JsonNetworkClient
import com.arkdo.android.services.network.JsonRequestOptions
import com.arkdo.android.services.network.NetworkResult
import com.arkdo.android.services.network.NetworkResults
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import kotlin.random.Random

/** 一页拉多少条。/drafts.json 无游标，靠本页条数是否满页判断还有没有下一页。 */
const val DRAFTS_PAGE_LIMIT = 30

/** 保存新主题草稿的入参。tags 用 {id,name}，与鸿蒙 DraftService.saveTopicDraft 一致。 */
data class SaveTopicDraftInput(
    val draftKey: String,
    val sequence: Long,
    val title: String,
    val categoryId: Long?,
    val tags: List<DraftTagRef>,
    val reply: String,
    val composerTimeMs: Long,
    val typingTimeMs: Long
)

/**
 * 草稿服务：对齐鸿蒙 DraftService.ets 里发新主题会用到的三条。
 * 保存走 POST /drafts.json 表单，删除走 DELETE /drafts/{key}.json 表单，列表走 GET。
 */
class DraftService(private val bridge: JsonNetworkClient) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun getPage(offset: Int): NetworkResult<DraftPage> {
        val response = bridge.getJson("/drafts.json?offset=$offset&limit=$DRAFTS_PAGE_LIMIT")
        if (!response.success) return NetworkResults.forwardFailure(response)
        val body = response.data
            ?: return NetworkResults.unexpectedResponse(response.statusCode, response.challengeState, "站点草稿响应为空。")
        return try {
            val source = json.decodeFromString<DraftListPayload>(body).drafts
            NetworkResult(
                success = true,
                statusCode = response.statusCode,
                challengeState = response.challengeState,
                data = DraftPage(drafts = source, hasMore = source.size >= DRAFTS_PAGE_LIMIT)
            )
        } catch (_: Exception) {
            NetworkResults.unexpectedResponse(response.statusCode, response.challengeState, "站点草稿响应不是有效 JSON。")
        }
    }

    /** 保存新主题草稿。成功时 data 是下一次必须带上的序号。 */
    suspend fun saveTopicDraft(input: SaveTopicDraftInput): NetworkResult<Long> {
        val data = TopicDraftData(
            reply = input.reply,
            action = DraftAction.NEW_TOPIC.value,
            title = input.title,
            categoryId = input.categoryId,
            tags = input.tags,
            composerTime = input.composerTimeMs.coerceAtLeast(0L),
            typingTime = input.typingTimeMs.coerceAtLeast(0L)
        )
        return postDraft(input.draftKey, input.sequence, json.encodeToString(data))
    }

    /** 删除草稿。sequence 放在表单 body 里，不放 query。 */
    suspend fun deleteDraft(draftKey: String, sequence: Long): NetworkResult<Boolean> {
        val path = "/drafts/${encode(draftKey)}.json"
        val body = "draft_key=${encode(draftKey)}&sequence=$sequence"
        val response = bridge.deleteForm(
            path,
            body,
            JsonRequestOptions(
                notifyChallenge = false,
                referrer = "${AppConstants.BASE_URL}/my/activity/drafts"
            )
        )
        if (!response.success) return NetworkResults.forwardFailure(response)
        val raw = response.data
            ?: return NetworkResults.unexpectedResponse(response.statusCode, response.challengeState, "站点删除草稿响应为空。")
        return try {
            val payload = json.decodeFromString<SaveDraftResponse>(raw)
            if (payload.success != "OK") {
                NetworkResults.unexpectedResponse(response.statusCode, response.challengeState, "站点未确认草稿已删除。")
            } else {
                NetworkResult(true, response.statusCode, response.challengeState, true)
            }
        } catch (_: Exception) {
            NetworkResults.unexpectedResponse(response.statusCode, response.challengeState, "站点删除草稿响应不是有效 JSON。")
        }
    }

    private suspend fun postDraft(draftKey: String, sequence: Long, dataJson: String): NetworkResult<Long> {
        val body = "draft_key=${encode(draftKey)}" +
            "&sequence=$sequence" +
            "&data=${encode(dataJson)}" +
            "&owner=${encode(ownerId())}" +
            "&force_save=false"
        val response = bridge.postForm(
            "/drafts.json",
            body,
            JsonRequestOptions(notifyChallenge = false, referrer = "${AppConstants.BASE_URL}/")
        )
        if (!response.success) return NetworkResults.forwardFailure(response)
        val next = response.data?.let { raw ->
            runCatching { json.decodeFromString<SaveDraftResponse>(raw).draftSequence }.getOrNull()
        } ?: sequence
        return NetworkResult(true, response.statusCode, response.challengeState, next)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        /** 官方 composer 传的客户端标识。进程内生成一次，全 App 共用。 */
        private var owner: String = ""

        fun ownerId(): String {
            if (owner.isEmpty()) {
                owner = buildString(32) { repeat(32) { append(Random.nextInt(16).toString(16)) } }
            }
            return owner
        }
    }
}

/** POST data 内层。字段对齐鸿蒙 TopicDraftData，少一个服务端都可能丢掉。 */
@Serializable
internal data class TopicDraftData(
    val reply: String,
    val action: String,
    val title: String,
    val categoryId: Long?,
    val tags: List<DraftTagRef>,
    val archetypeId: String = "regular",
    val metaData: String? = null,
    val composerTime: Long,
    val typingTime: Long,
    val original_text: String = "",
    val locale: String = "",
    val reply_to_post_number: Int? = null,
    val reply_to_user: String? = null
)

/** 标题或正文有实质内容才值得存，免得打开又关掉也留一条空草稿。 */
internal fun draftHasContent(title: String, body: String): Boolean =
    title.trim().isNotEmpty() || body.trim().isNotEmpty()

/** 首次保存才生成 key。续写必须沿用带进来的 key，否则会另存一条。 */
internal fun resolveDraftKey(existing: String, nowMs: Long): String =
    existing.ifEmpty { "new_topic_$nowMs" }

/** 草稿列表点新主题时回填用的标签。联想项还要 text/count，这里只补够续写的最小结构。 */
internal fun draftTagsAsSuggestions(tags: List<DraftTagRef>): List<com.arkdo.android.services.topic.TagSuggestion> =
    tags.map { com.arkdo.android.services.topic.TagSuggestion(id = it.id, name = it.name, text = it.name, count = 0) }

internal fun Draft.isNewTopicDraft(): Boolean = action == DraftAction.NEW_TOPIC
