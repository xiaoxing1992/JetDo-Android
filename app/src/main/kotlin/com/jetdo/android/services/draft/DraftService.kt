package com.jetdo.android.services.draft

import android.content.Context
import com.jetdo.android.core.data.AppContextHolder
import com.jetdo.android.R
import com.jetdo.android.common.constants.AppConstants
import com.jetdo.android.data.models.Draft
import com.jetdo.android.data.models.DraftAction
import com.jetdo.android.data.models.DraftListPayload
import com.jetdo.android.data.models.DraftPage
import com.jetdo.android.data.models.DraftTagRef
import com.jetdo.android.data.models.SaveDraftResponse
import com.jetdo.android.services.network.JsonNetworkClient
import com.jetdo.android.services.network.JsonRequestOptions
import com.jetdo.android.services.network.NetworkResult
import com.jetdo.android.services.network.NetworkResults
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

/** 保存回帖草稿的入参。与编辑草稿**共用同一个 draftKey**（`topic_<id>`），靠 data 里的 action 区分。 */
data class SaveReplyDraftInput(
    val draftKey: String,
    val sequence: Long,
    /** 详情页带下来的分类；拿不到时传 -1，落盘时转 null（对齐鸿蒙 `categoryId >= 0 ? : null`）。 */
    val categoryId: Long,
    val reply: String,
    /** 定向回复的目标楼层；回复话题本身时为 null。 */
    val replyToPostNumber: Int?,
    val composerTimeMs: Long,
    val typingTimeMs: Long
)

/** 保存编辑草稿的入参。 */
data class SaveEditDraftInput(
    val draftKey: String,
    val sequence: Long,
    val categoryId: Long,
    val reply: String,
    val postId: Long,
    /** 乐观并发基线原文；回来提交时原样带上。 */
    val originalText: String,
    val composerTimeMs: Long,
    val typingTimeMs: Long
)

/**
 * 草稿服务：对齐鸿蒙 DraftService.ets 里会用到的几条（新主题 / 回帖 / 编辑）。
 * 保存走 POST /drafts.json 表单，删除走 DELETE /drafts/{key}.json 表单，列表走 GET。
 */
class DraftService(private val bridge: JsonNetworkClient) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun getPage(offset: Int): NetworkResult<DraftPage> {
        val response = bridge.getJson("/drafts.json?offset=$offset&limit=$DRAFTS_PAGE_LIMIT")
        if (!response.success) return NetworkResults.forwardFailure(response)
        val body = response.data
            ?: return NetworkResults.unexpectedResponse(response.statusCode, response.challengeState, AppContextHolder.getString(R.string.err_drafts_empty))
        return try {
            val source = json.decodeFromString<DraftListPayload>(body).drafts
            NetworkResult(
                success = true,
                statusCode = response.statusCode,
                challengeState = response.challengeState,
                data = DraftPage(drafts = source, hasMore = source.size >= DRAFTS_PAGE_LIMIT)
            )
        } catch (_: Exception) {
            NetworkResults.unexpectedResponse(response.statusCode, response.challengeState, AppContextHolder.getString(R.string.err_drafts_json))
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

    /**
     * 保存回帖草稿。与 [saveTopicDraft] 的差别只在 data 内层：无 title、action='reply'、
     * 带 `reply_to_post_number`。成功时 data 同样是下一次必须带上的序号。
     *
     * ⚠️ 回帖草稿与编辑草稿**共用 key 与同一个序号**：各存各的序号，交替保存时必有一方
     * 拿着过期序号被服务端拒（鸿蒙 TopicDetailPage 里对这点有明确注释）。
     */
    suspend fun saveReplyDraft(input: SaveReplyDraftInput): NetworkResult<Long> {
        val data = ReplyDraftData(
            reply = input.reply,
            action = DraftAction.REPLY.value,
            categoryId = if (input.categoryId >= 0) input.categoryId else null,
            tags = emptyList(),
            composerTime = input.composerTimeMs.coerceAtLeast(0L),
            typingTime = input.typingTimeMs.coerceAtLeast(0L),
            reply_to_post_number = input.replyToPostNumber
        )
        return postDraft(input.draftKey, input.sequence, json.encodeToString(data))
    }

    /** 保存编辑草稿。同 key，靠 action='edit' + postId 区分。 */
    suspend fun saveEditDraft(input: SaveEditDraftInput): NetworkResult<Long> {
        val data = EditDraftData(
            reply = input.reply,
            action = DraftAction.EDIT.value,
            categoryId = if (input.categoryId >= 0) input.categoryId else null,
            tags = emptyList(),
            composerTime = input.composerTimeMs.coerceAtLeast(0L),
            typingTime = input.typingTimeMs.coerceAtLeast(0L),
            postId = input.postId,
            original_text = input.originalText
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
            ?: return NetworkResults.unexpectedResponse(response.statusCode, response.challengeState, AppContextHolder.getString(R.string.err_draft_delete_empty))
        return try {
            val payload = json.decodeFromString<SaveDraftResponse>(raw)
            if (payload.success != "OK") {
                NetworkResults.unexpectedResponse(response.statusCode, response.challengeState, AppContextHolder.getString(R.string.err_draft_delete_unconfirmed))
            } else {
                NetworkResult(true, response.statusCode, response.challengeState, true)
            }
        } catch (_: Exception) {
            NetworkResults.unexpectedResponse(response.statusCode, response.challengeState, AppContextHolder.getString(R.string.err_draft_delete_json))
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

/**
 * 回帖草稿的 data 内层。字段集**刻意与发帖不同**（没有 title / original_text），
 * 对齐鸿蒙 ReplyDraftData —— 多发字段虽然通常会被服务端忽略，但没必要赌。
 */
@Serializable
internal data class ReplyDraftData(
    val reply: String,
    val action: String,
    val categoryId: Long?,
    val tags: List<DraftTagRef>,
    val archetypeId: String = "regular",
    val metaData: String? = null,
    val composerTime: Long,
    val typingTime: Long,
    val locale: String = "",
    val reply_to_post_number: Int? = null,
    val reply_to_user: String? = null
)

/** 编辑草稿的 data 内层。对齐鸿蒙 EditDraftData（带 postId + original_text，无 reply_to_*）。 */
@Serializable
internal data class EditDraftData(
    val reply: String,
    val action: String,
    val categoryId: Long?,
    val tags: List<DraftTagRef>,
    val archetypeId: String = "regular",
    val metaData: String? = null,
    val composerTime: Long,
    val typingTime: Long,
    val locale: String = "",
    val postId: Long,
    val original_text: String
)

/** 回帖/编辑草稿的 key 约定：一条话题一条草稿，两态共用（对齐鸿蒙 `topic_${id}`）。 */
internal fun replyDraftKey(topicId: Long): String = "topic_$topicId"

/** 标题或正文有实质内容才值得存，免得打开又关掉也留一条空草稿。 */
internal fun draftHasContent(title: String, body: String): Boolean =
    title.trim().isNotEmpty() || body.trim().isNotEmpty()

/** 首次保存才生成 key。续写必须沿用带进来的 key，否则会另存一条。 */
internal fun resolveDraftKey(existing: String, nowMs: Long): String =
    existing.ifEmpty { "new_topic_$nowMs" }

/** 草稿列表点新主题时回填用的标签。联想项还要 text/count，这里只补够续写的最小结构。 */
internal fun draftTagsAsSuggestions(tags: List<DraftTagRef>): List<com.jetdo.android.services.topic.TagSuggestion> =
    tags.map { com.jetdo.android.services.topic.TagSuggestion(id = it.id, name = it.name, text = it.name, count = 0) }

internal fun Draft.isNewTopicDraft(): Boolean = action == DraftAction.NEW_TOPIC
