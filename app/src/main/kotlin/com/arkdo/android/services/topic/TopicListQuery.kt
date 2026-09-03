package com.arkdo.android.services.topic

import com.arkdo.android.data.models.FeedKey
import com.arkdo.android.data.models.TopicCategory
import com.arkdo.android.data.models.TopicTag
import com.arkdo.android.services.site.FeedPrefs
import java.net.URLEncoder

/**
 * 首页列表的查询条件，逐函数对齐鸿蒙版 services/topic/TopicListQuery.ets。
 *
 * Discourse 把「看什么」拆成两个正交的维度，别把它们当成互斥的单选：
 *  · filter(latest/created/hot/top/unread/new) = 排序与范围，落在 URL 尾部的 `/l/{filter}`
 *  · scope(版块 + 标签) = 取值范围，落在 URL 的前缀
 *
 * 路径 = scope 前缀 + `/l/{filter}.json`。四种 scope 组合：
 *  · 全站      → `/{filter}.json`                                  ← 唯一没有 /l/ 的
 *  · 版块      → `/c/{cSlug}/{cId}/l/{filter}.json`
 *  · 标签      → `/tag/{tSlug}/{tId}/l/{filter}.json`
 *  · 版块+标签 → `/tags/c/{cSlug}/{cId}/{tSlug}/{tId}/l/{filter}.json`
 *
 * 「最新发布」(CREATED) 是个例外：Discourse 没有按发布时间的 filter，它走 latest 的路径
 * 再挂 `order=created`。四种 scope 与分页都支持，匿名可用。
 *
 * 标签只能有一个：linux.do 的标签过滤不支持多选。
 */
data class TopicListQuery(
    val filter: String = FeedKey.LATEST.value,
    /** null = 不限版块（全站）。 */
    val category: TopicCategory? = null,
    /** null = 不限标签。 */
    val tag: TopicTag? = null
)

/** 全默认：全站 · 最新 · 不限标签。 */
fun createDefaultTopicListQuery(): TopicListQuery = TopicListQuery()

fun TopicListQuery.isDefault(): Boolean =
    filter == FeedKey.LATEST.value && category == null && tag == null

/**
 * 两份条件是否等价。抽屉「确认」按钮据此判断有没有真的改动，避免打一次无意义的请求。
 */
fun sameTopicListQuery(a: TopicListQuery, b: TopicListQuery): Boolean {
    if (a.filter != b.filter) return false
    if ((a.category?.id ?: 0L) != (b.category?.id ?: 0L)) return false
    // 标签按名字比：列表接口回的标签常常只有名字，拿 id 比会把同一个标签认成两个。
    return (a.tag?.name ?: "") == (b.tag?.name ?: "")
}

/**
 * 该过滤器是否需要登录。本项目暴露的 6 个入口里只有 unread 与 new 属于此列 ——
 * created 走的是 latest 路由（纯服务端排序，不看个人已读状态），匿名同样能拉。
 */
fun filterRequiresLogin(filter: String): Boolean =
    filter == FeedKey.UNREAD.value || filter == FeedKey.NEW.value

/**
 * 按条件拼出主题列表的请求路径。page 从 0 起（0 即首页，不带 page 参数）。
 *
 * 注意 id 形式的标签路由带 `format: :json` 约束，`.json` 后缀不能省。
 */
fun buildTopicListPath(query: TopicListQuery, page: Int): String {
    val segment = feedFilterSegment(query.filter)
    val suffix = feedQuerySuffix(query.filter, page)
    val category = query.category
    val tag = query.tag

    return when {
        tag != null && category != null ->
            "/tags/c/${categorySegment(category)}/${tagSegment(tag)}/l/$segment.json$suffix"
        tag != null ->
            "/tag/${tagSegment(tag)}/l/$segment.json$suffix"
        category != null ->
            "/c/${categorySegment(category)}/l/$segment.json$suffix"
        // 全站是唯一不带 /l/ 前缀的 scope：/latest.json、/hot.json、/unread.json…
        else -> "/$segment.json$suffix"
    }
}

/**
 * 顶栏主标题：取最有辨识度的那一项（版块 → 标签 → 过滤器）。
 * 全默认时回空串，由调用方换成应用名。
 */
fun topicListPrimaryTitle(query: TopicListQuery): String = when {
    query.category != null -> query.category.name
    query.tag != null -> query.tag.name
    query.filter == FeedKey.LATEST.value -> ""
    else -> filterLabel(query.filter)
}

/**
 * 顶栏要不要在主标题下面长出一行副行。两种情况才需要：
 *  · 换过排序（非「最新」）—— 但没有 scope 时不算，那时排序名本身就是主标题
 *  · 版块和标签都选了 —— 主标题被版块占着，标签没别处可去
 */
fun hasTopicListSummary(query: TopicListQuery): Boolean {
    val hasScope = query.category != null || query.tag != null
    if (!hasScope) return false
    return query.filter != FeedKey.LATEST.value || topicListSummaryTag(query) != null
}

/** 副行前半段：当前排序名。副行不出现时回空串。 */
fun topicListSummaryLead(query: TopicListQuery): String =
    if (hasTopicListSummary(query)) filterLabel(query.filter) else ""

/**
 * 副行里要不要再挂一个标签。只有「版块 + 标签」都选了时才需要：
 * 此时主标题被版块占着，标签没别的地方可显示。
 */
fun topicListSummaryTag(query: TopicListQuery): TopicTag? =
    if (query.category != null) query.tag else null

/**
 * 抽屉确认区用的完整描述：与顶栏副行不同，这里不省略任何一项
 * （用户正盯着它决定要不要点「查看结果」），且没有 scope 时显式写出「全站」。
 */
fun describeTopicListQuery(query: TopicListQuery): String {
    val parts = mutableListOf(filterLabel(query.filter))
    when {
        query.category != null -> parts.add(query.category.name)
        query.tag == null -> parts.add("全站")
    }
    query.tag?.let { parts.add(it.name) }
    return parts.joinToString(" · ")
}

// ==================== 私有辅助 ====================

private fun filterLabel(filter: String): String = FeedPrefs.feedDef(filter)?.label ?: ""

/**
 * `category_slug_path_with_id` 是 glob 段：末段是数字 id 时服务端直接按 id 认，
 * 所以子版块也只需要 `{slug}/{id}` 两段。拿不到 slug 时退回纯 id。
 */
private fun categorySegment(category: TopicCategory): String {
    val slug = category.slug.orEmpty()
    return if (slug.isNotEmpty()) "${encodeUriComponent(slug)}/${category.id}" else "${category.id}"
}

/**
 * slug + id 是 linux.do 前端自己在用的形式，对中文标签名最稳；
 * 列表接口常常只回标签名，拿不到 id/slug 时退回按名字的路由（官方同样支持）。
 */
private fun tagSegment(tag: TopicTag): String {
    val slug = tag.slug.orEmpty()
    val id = tag.id ?: 0L
    return if (id > 0 && slug.isNotEmpty()) "${encodeUriComponent(slug)}/$id" else encodeUriComponent(tag.name)
}

/**
 * 落到 URL 里的那一段 filter 名。除「最新发布」外都是 key 本身；
 * 「最新发布」没有对应的 Discourse filter，借 latest 的路由，靠 order 参数换排序键。
 */
private fun feedFilterSegment(filter: String): String =
    if (filter == FeedKey.CREATED.value) FeedKey.LATEST.value else filter

/**
 * query 串。order 与 page 都可能出现，必须在一处统一拼 —— 各自带 `?` 会拼出两个问号。
 */
private fun feedQuerySuffix(filter: String, page: Int): String {
    val params = mutableListOf<String>()
    // ascending 不写：Discourse 对 order 默认降序，正是「新发的在前」。
    if (filter == FeedKey.CREATED.value) params.add("order=created")
    if (page > 0) params.add("page=$page")
    return if (params.isEmpty()) "" else "?${params.joinToString("&")}"
}

/** URLEncoder 把空格编成 `+`，与 encodeURIComponent 不同，这里补回 %20。 */
private fun encodeUriComponent(value: String): String =
    URLEncoder.encode(value, "UTF-8").replace("+", "%20")
