package com.arkdo.android.data.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Transient
import kotlinx.serialization.Serializable

// ==========================================================================
// 与 HarmonyOS 版 models/Topic.ets、models/User.ets、models/TopicDetail.ets 逐字段对齐。
// 原项目用 @Observed class（可观测），Compose 端用 immutable data class，
// 可变状态由 ViewModel 的 mutableStateOf/mutableStateListOf 持有（等价精准重渲染）。
// ==========================================================================

/** 话题标签。注意列表接口 tags 可能是纯字符串数组，映射器逐个兼容。 */
@Serializable
data class TopicTag(
    @SerialName("id") val id: Long? = null,
    @SerialName("name") val name: String = "",
    @SerialName("slug") val slug: String? = null
)

/** 话题分类。color/textColor 已剥 # 前缀（6 位 hex），渲染时补 #。 */
@Serializable
data class TopicCategory(
    @SerialName("id") val id: Long = 0,
    @SerialName("name") val name: String = "",
    @SerialName("color") val color: String = "6B7280",
    @SerialName("text_color") val textColor: String = "FFFFFF",
    @SerialName("slug") val slug: String? = null,
    @SerialName("parent_category_id") val parentCategoryId: Long? = null,
    @SerialName("uploaded_logo") val uploadedLogo: String? = null,
    @SerialName("icon") val icon: String? = null,
    @SerialName("read_restricted") val readRestricted: Boolean? = null
)

/** 话题列表项。unseen=标题后蓝点；unreadPosts=数字徽标。 */
@Serializable
data class Topic(
    @SerialName("id") val id: Long = 0,
    @SerialName("title") val title: String = "",
    @SerialName("slug") val slug: String? = null,
    @SerialName("category_id") val categoryId: Long? = null,
    @SerialName("tags") val tags: List<TopicTag> = emptyList(),
    @SerialName("posts_count") val postsCount: Int? = null,
    @SerialName("reply_count") val replyCount: Int? = null,
    @SerialName("like_count") val likeCount: Int? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("last_posted_at") val lastPostedAt: String? = null,
    @SerialName("unseen") val unseen: Boolean? = null,
    @SerialName("unread_posts") val unreadPosts: Int? = null,
    @SerialName("last_read_post_number") val lastReadPostNumber: Int? = null,
    @SerialName("highest_post_number") val highestPostNumber: Int? = null,
    @SerialName("has_accepted_answer") val hasAcceptedAnswer: Boolean? = null,
    @SerialName("bookmarked") val bookmarked: Boolean? = null,
    @SerialName("excerpt") val excerpt: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("views") val views: Int? = null,
    @SerialName("pinned") val pinned: Boolean = false,
    @SerialName("closed") val closed: Boolean = false,
    @SerialName("archived") val archived: Boolean = false,
    @SerialName("visible") val visible: Boolean = true,
    @SerialName("posters") val posters: List<TopicPoster> = emptyList()
) {
    /** 搜索结果专用：命中楼层的高亮摘要纯文本（仅搜索列表填充）。 */
    var searchBlurb: String? = null

    /** 列表页作者 = posters[0].user_id 到 payload.users[] 查表，映射时填充。 */
    var author: User? = null

    /**
     * 未读行的跳楼目标：第一条未读楼（lastRead+1），封顶最高楼。
     * 无阅读记录（lastRead<=0）时返回 null —— 按普通打开处理。
     * 对齐鸿蒙 HomePage.handleUnreadBadgeClick。
     */
    fun firstUnreadPostNumber(): Int? {
        val lastRead = lastReadPostNumber ?: 0
        if (lastRead <= 0) return null
        val highest = highestPostNumber ?: 0
        return if (highest > 0) minOf(lastRead + 1, highest) else lastRead + 1
    }
}

/** 话题参与者（列表接口 posters[]）。 */
@Serializable
data class TopicPoster(
    @SerialName("user_id") val userId: Long,
    @SerialName("description") val description: String = "",
    @SerialName("extras") val extras: String? = null
)

/** 用户。id 为必填，username 通常必填（历史/匿名数据可能缺省为 ""）。 */
@Serializable
data class User(
    @SerialName("id") val id: Long,
    @SerialName("username") val username: String = "",
    @SerialName("name") val name: String? = null,
    @SerialName("avatar_template") val avatarTemplate: String? = null,
    @SerialName("animated_avatar") val animatedAvatar: String? = null,
    @SerialName("flair_url") val flairUrl: String? = null,
    @SerialName("flair_bg_color") val flairBgColor: String? = null,
    @SerialName("flair_color") val flairColor: String? = null,
    @SerialName("flair_name") val flairName: String? = null,
    @SerialName("unread_notifications") val unreadNotifications: Int? = null,
    @SerialName("unread_high_priority_notifications") val unreadHighPriorityNotifications: Int? = null,
    @SerialName("draft_count") val draftCount: Int? = null,
    @SerialName("badge_ids") val badgeIds: List<Long>? = null,
    // 会话检查随 current_user 下发的身份字段：LoginGate 附件权限判定用（编辑器切片接）。
    @SerialName("trust_level") val trustLevel: Int? = null,
    @SerialName("admin") val admin: Boolean? = null,
    @SerialName("moderator") val moderator: Boolean? = null
) {
    val displayName: String
        get() = name ?: username
}

// ==========================================================================
// 详情页（models/TopicDetail.ets）
// ==========================================================================

/** discourse-reactions：一条楼层获得的单个表情回应（短名 + 数量）。 */
@Serializable
data class PostReaction(
    @SerialName("id") val id: String,
    @SerialName("count") val count: Int
)

/** discourse-reactions：对某楼做出回应的用户，reaction 为用户选择的短名。 */
@Serializable
data class ReactionUser(
    val user: User,
    @SerialName("reaction") val reaction: String
)

/** /reactions-users-list.json 分页结果。 */
@Serializable
data class ReactionUsersPage(
    @SerialName("users") val users: List<ReactionUser>,
    @SerialName("total_rows") val totalRows: Int
)

/** discourse-boosts：一条楼层下的 boost（头像 + 短内容，保留 cooked HTML）。 */
@Serializable
data class PostBoost(
    @SerialName("id") val id: Long,
    @SerialName("cooked") val cookedHtml: String,
    val user: User
)

/** linux.do shared issue（"俺也一样"）toggle 后的服务端权威新态。 */
@Serializable
data class SharedIssueResult(
    @SerialName("user_created_shared_issue") val created: Boolean,
    @SerialName("count") val count: Int
)

/** discourse-poll：一个投票选项。 */
@Serializable
data class PollOption(
    @SerialName("id") val id: String,
    @SerialName("html") val label: String,
    @SerialName("votes") val votes: Int
)

/** 投票/撤销后服务端返回的真值，用于成功后校正本地。 */
@Serializable
data class PollVoteResult(
    @SerialName("poll") val options: List<PollOption>,
    @SerialName("vote") val chosenOptionIds: List<String>,
    @SerialName("voters") val voters: Int
)

/**
 * `PUT/DELETE /polls/vote` 的响应外壳。
 * 撤销投票时响应**不带** `vote` 字段(即已投项为空),故它可空。
 */
@Serializable
internal data class DiscoursePollVoteResponse(
    @SerialName("poll") val poll: Poll? = null,
    @SerialName("vote") val vote: List<String>? = null
)

/** discourse-poll：一个投票的完整状态。 */
@Serializable
data class Poll(
    @SerialName("name") val name: String = "poll",
    @SerialName("title") val title: String? = null,
    @SerialName("type") val type: String = "regular",
    @SerialName("status") val status: String = "open",
    @SerialName("public") val isPublic: Boolean = false,
    @SerialName("results") val results: String = "always",
    @SerialName("options") val options: List<PollOption> = emptyList(),
    @SerialName("voters") val voters: Int = 0,
    @SerialName("chosen_option_ids") val chosenOptionIds: List<String> = emptyList(),
    @SerialName("can_vote") val canVote: Boolean = false
)

/** 单楼。cooked=HTML 内容，plainText 本地解析填充。 */
@Serializable
data class TopicPost(
    @SerialName("id") val id: Long = 0,
    @SerialName("topic_id") val topicId: Long? = null,
    @SerialName("post_number") val postNumber: Int = 0,
    @SerialName("cooked") val cookedHtml: String = "",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("reply_count") val replyCount: Int? = null,
    @SerialName("reply_to_post_number") val replyToPostNumber: Int? = null,
    @SerialName("like_count") val likeCount: Int? = null,
    @SerialName("read") val read: Boolean? = null,
    @SerialName("user_title") val userTitle: String? = null,
    /** 正文链接点击数表（键 = resolveUrl 后的 href），渲染层挂灰底角标。服务层构建，非服务端字段。 */
    @Transient
    var linkClicks: Map<String, Int>? = null,
    @SerialName("reactions") val reactions: List<PostReaction>? = null,
    @SerialName("reaction_users_count") val reactionUsersCount: Int? = null,
    @SerialName("current_user_reaction") val currentUserReaction: String? = null,
    @SerialName("boosts") val boosts: List<PostBoost>? = null,
    @SerialName("can_boost") val canBoost: Boolean? = null,
    @SerialName("polls") val polls: List<Poll>? = null,
    @SerialName("bookmarked") val bookmarked: Boolean? = null,
    @SerialName("bookmark_id") val bookmarkId: Long? = null,
    @SerialName("user_status") val userStatus: UserStatus? = null,
    @SerialName("user_id") val userId: Long? = null,
    @SerialName("username") val username: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("avatar_template") val avatarTemplate: String? = null,
    @SerialName("animated_avatar") val animatedAvatar: String? = null,
    @SerialName("flair_url") val flairUrl: String? = null,
    @SerialName("flair_bg_color") val flairBgColor: String? = null,
    @SerialName("flair_color") val flairColor: String? = null,
    @SerialName("flair_name") val flairName: String? = null,
    // 本楼是不是当前登录用户发的（PostSerializer#yours），为真时不能给自己点赞，详情页把心形换成编辑入口。
    @SerialName("yours") val yours: Boolean? = null,
    // 当前用户能不能改这一楼（PostSerializer#can_edit，走 Guardian）。「是我的」不等于「还能改」。
    @SerialName("can_edit") val canEdit: Boolean? = null,
    // 作者自己删掉的楼（PostSerializer#user_deleted）。楼还在，正文换成「已被作者删除」，操作栏改成恢复。
    @SerialName("user_deleted") val userDeleted: Boolean? = null,
    // 当前用户能不能把这楼恢复回来（PostSerializer#can_recover）。作者在时限内为真。
    @SerialName("can_recover") val canRecover: Boolean? = null,

    // ↓↓↓ 以下五个是「本地态」：服务端不下发，由服务层构造后回填。
    // 必须声明在主构造里并标 @Transient（序列化时忽略），与上面的 linkClicks 同一写法。
    // 原因：data class 的 copy() 只搬主构造参数。以前它们写在类体里，任何一次 copy 都会把它们
    // 清回默认值 —— 实测 bug：已读上报 markPostsRead 里的 post.copy(read = true) 会把
    // 「回复某人」头像（replyToUser）连带作者/徽章/正文纯文本一次性抹掉。

    /** 由 cooked 本地解析（本地态，非服务端字段）。 */
    @Transient var plainText: String = "",

    /** 上报 /topics/timings 进行中（本地态，蓝点呼吸）。 */
    @Transient var reporting: Boolean = false,

    /** 被回复者（reply_to_post_number 对应的用户，头部右上角显示）。 */
    @Transient var replyToUser: User? = null,

    /** 作者（内联 user_id/username/... 映射）。 */
    @Transient var author: User? = null,

    /** 昵称后徽章图标名数组（顶层 user_badges 按 user_id 解出）。 */
    @Transient var badgeIcons: List<String>? = null
) {
    /** 昵称后状态表情短名（user_status.emoji）。派生属性，不参与 copy，留在类体。 */
    val statusEmoji: String?
        get() = userStatus?.emoji
}

@Serializable
data class UserStatus(
    @SerialName("emoji") val emoji: String? = null,
    @SerialName("message") val message: String? = null
)

/** discourse-solved：被采纳为解决方案的答案楼快照（自给 cooked HTML）。 */
@Serializable
data class AcceptedAnswer(
    @SerialName("post_number") val postNumber: Int = 0,
    @SerialName("username") val username: String = "",
    @SerialName("avatar_template") val avatarTemplate: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("cooked") val cookedHtml: String = ""
)

/** 话题详情。posts 是窗口式加载的核心；stream 是全量有序楼 id。 */
@Serializable
data class TopicDetail(
    @SerialName("id") val id: Long = 0,
    @SerialName("title") val title: String = "",
    @SerialName("slug") val slug: String? = null,
    @SerialName("category_id") val categoryId: Long? = null,
    @SerialName("tags") val tags: List<TopicTag> = emptyList(),
    @SerialName("posts_count") val postsCount: Int? = null,
    @SerialName("reply_count") val replyCount: Int? = null,
    @SerialName("like_count") val likeCount: Int? = null,
    @SerialName("views") val viewCount: Int? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("post_stream") val postStream: PostStream? = null,
    @SerialName("user_id") val userId: Long? = null,
    @SerialName("details") val details: TopicDetails? = null,
    @SerialName("last_read_post_number") val lastReadPostNumber: Int? = null,
    @SerialName("highest_post_number") val highestPostNumber: Int? = null,
    @SerialName("unread_posts") val unreadPosts: Int? = null,
    @SerialName("bookmarked") val bookmarked: Boolean? = null,
    @SerialName("shared_issue_visible") val sharedIssueVisible: Boolean? = null,
    @SerialName("can_create_shared_issue") val canCreateSharedIssue: Boolean? = null,
    @SerialName("user_created_shared_issue") val userCreatedSharedIssue: Boolean? = null,
    @SerialName("shared_issue_count") val sharedIssueCount: Int? = null,
    @SerialName("pending_posts") val pendingPosts: List<PendingPost>? = null,
    @SerialName("message_bus_last_id") val messageBusLastId: Long? = null,
    @SerialName("accepted_answers") val acceptedAnswers: List<AcceptedAnswer>? = null,
    @SerialName("polls_votes") val pollsVotes: Map<String, List<String>>? = null,
    @SerialName("can_vote") val canVote: Boolean? = null
) {
    /** 已加载楼层列表（来自 post_stream.posts）。 */
    val posts: List<TopicPost>
        get() = postStream?.posts ?: emptyList()

    /** 全量有序楼 id（分页/红线/Scrubber 依据）。 */
    val stream: List<Long>
        get() = postStream?.stream ?: emptyList()

    /** 主题作者（details.created_by.id 优先，回退顶层 user_id）。 */
    val createdById: Long?
        get() = details?.createdBy?.id ?: userId
}

@Serializable
data class PostStream(
    @SerialName("posts") val posts: List<TopicPost> = emptyList(),
    @SerialName("stream") val stream: List<Long> = emptyList()
)

@Serializable
data class TopicDetails(
    @SerialName("created_by") val createdBy: CreatedBy? = null,
    @SerialName("notification_level") val notificationLevel: Int? = null
)

@Serializable
data class CreatedBy(
    @SerialName("id") val id: Long,
    @SerialName("username") val username: String
)

/** 待审帖快照。raw 是 Markdown 原文。 */
@Serializable
data class PendingPost(
    @SerialName("id") val id: Long = 0,
    @SerialName("raw") val raw: String = "",
    @SerialName("created_at") val createdAt: String = ""
)

/** 话题列表响应（/latest.json /hot.json 等共用）。 */
@Serializable
data class TopicListPayload(
    @SerialName("users") val users: List<User> = emptyList(),
    @SerialName("topic_list") val topicList: TopicList? = null
)

@Serializable
data class TopicList(
    @SerialName("topics") val topics: List<Topic> = emptyList(),
    @SerialName("more_topics_url") val moreTopicsUrl: String? = null,
    @SerialName("can_create_topic") val canCreateTopic: Boolean = false
)
