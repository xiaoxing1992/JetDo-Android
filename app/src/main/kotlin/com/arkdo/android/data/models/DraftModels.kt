package com.arkdo.android.data.models

import androidx.compose.ui.res.stringResource
import com.arkdo.android.core.data.AppContextHolder
import com.arkdo.android.R
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ==========================================================================
// 与 HarmonyOS 版 models/Draft.ets 逐字段对齐。
// 来源：/drafts.json 的 drafts[]。关键信息藏在 data 字段（内嵌 JSON 字符串）。
// ==========================================================================

/** 草稿动作类型。 */
@Serializable
enum class DraftAction(val value: String) {
    @SerialName("createTopic") NEW_TOPIC("createTopic"),
    @SerialName("reply") REPLY("reply"),
    @SerialName("privateMessage") PRIVATE_MESSAGE("privateMessage"),
    @SerialName("edit") EDIT("edit"),
    OTHER("other");

    companion object {
        fun fromValue(v: String?): DraftAction =
            entries.firstOrNull { it.value == v } ?: OTHER
    }
}

/** 草稿里的标签引用（续写/回存要带 id）。 */
@Serializable
data class DraftTagRef(
    @SerialName("id") val id: Long = 0,
    @SerialName("name") val name: String = ""
)

/** 草稿列表项。 */
@Serializable
data class Draft(
    @SerialName("draft_key") val draftKey: String = "",
    @SerialName("sequence") val sequence: Long = 0,
    @SerialName("data") val dataRaw: String? = null,
    @SerialName("title") val titleRaw: String? = null,
    @SerialName("excerpt") val excerptRaw: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("topic_id") val topicId: Long? = null,
    @SerialName("category_id") val categoryIdTop: Long? = null
) {
    /** data 字段解析出的草稿内容（懒解析）。 */
    val parsed: DraftData? by lazy {
        dataRaw?.let { runCatching { kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<DraftData>(it) }.getOrNull() }
    }

    val action: DraftAction
        get() = DraftAction.fromValue(parsed?.action)

    /** 真实标题：新主题/私信取 data.title，回帖取顶层 title。可能为空。 */
    val title: String
        get() = if (action == DraftAction.REPLY) {
            titleRaw ?: ""
        } else {
            parsed?.title ?: ""
        }

    /** 列表展示用：title 为空时回退占位文案。 */
    val displayTitle: String
        get() = title.ifEmpty { AppContextHolder.getString(R.string.drafts_untitled) }

    /** 续写用分类 id：data.categoryId 优先，回退顶层 category_id。 */
    val categoryId: Long?
        get() = parsed?.categoryId ?: categoryIdTop

    /** 续写用标签（新主题草稿）。 */
    val tags: List<DraftTagRef>
        get() = parsed?.tags ?: emptyList()

    /** 正文原文。 */
    val reply: String
        get() = parsed?.reply ?: ""

    /** 编辑草稿的目标楼。缺了就不能开编辑态。 */
    val postId: Long?
        get() = parsed?.postId?.takeIf { it > 0 }

    /** 编辑开始时的服务端原文，409 冲突比对用。 */
    val originalText: String?
        get() = parsed?.originalText

    /** 列表两行预览：优先服务端 excerpt，没有再退回正文，并清掉图片和引用块。 */
    val excerpt: String
        get() = cleanDraftExcerpt(excerptRaw, reply)
}

/** Draft.data 的内嵌 JSON 结构。 */
@Serializable
data class DraftData(
    @SerialName("reply") val reply: String = "",
    @SerialName("title") val title: String? = null,
    @SerialName("action") val action: String? = null,
    @SerialName("categoryId") val categoryId: Long? = null,
    @SerialName("tags") val tags: List<DraftTagRef> = emptyList(),
    @SerialName("archetypeId") val archetypeId: String? = null,
    @SerialName("metaData") val metaData: String? = null,
    @SerialName("composerTime") val composerTime: Long? = null,
    @SerialName("typingTime") val typingTime: Long? = null,
    @SerialName("locale") val locale: String? = null,
    @SerialName("reply_to_post_number") val replyToPostNumber: Int? = null,
    @SerialName("reply_to_user") val replyToUser: DraftUserRef? = null,
    @SerialName("original_text") val originalText: String? = null,
    @SerialName("postId") val postId: Long? = null
)

@Serializable
data class DraftUserRef(
    @SerialName("id") val id: Long? = null,
    @SerialName("username") val username: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("avatar_template") val avatarTemplate: String? = null
)

/** 一页草稿。/drafts.json 无分页游标，靠"本页条数 == limit"推断 hasMore。 */
@Serializable
data class DraftPage(
    val drafts: List<Draft> = emptyList(),
    val hasMore: Boolean = false
)

/** /drafts.json 响应外壳。 */
@Serializable
data class DraftListPayload(
    @SerialName("drafts") val drafts: List<Draft> = emptyList()
)

/** 保存草稿的响应：{ success, draft_sequence }，draft_sequence 是下次必须携带的序号。 */
@Serializable
data class SaveDraftResponse(
    @SerialName("success") val success: String? = null,
    @SerialName("draft_sequence") val draftSequence: Long? = null
)

/** 列表预览清洗，对齐鸿蒙 DraftService.cleanExcerpt。 */
internal fun cleanDraftExcerpt(excerpt: String?, reply: String?): String {
    val source = excerpt?.takeIf { it.isNotEmpty() } ?: reply.orEmpty()
    return source
        .replace(Regex("""!\[[^\]]*]\([^)]*\)"""), "[图片]")
        .replace(Regex("""\[quote=[^\]]*\][\s\S]*?\[/quote\]"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()
}
