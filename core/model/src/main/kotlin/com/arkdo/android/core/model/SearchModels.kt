package com.arkdo.android.core.model

/**
 * 搜索类型(对齐 Discourse 整页搜索右上角那个下拉,只取我们要的两种)。
 *
 * 注意:这两种类型在官方**不是同一个接口**,别想当然地都往 /search.json 上挂
 * (frontend/discourse/app/controllers/full-page-search.js 里是个 switch):
 *
 * - [TOPICS] → `GET /search.json?q=&page=`,高级语法(category:/tags:/@user…)只对它生效;
 * - [USERS]  → `GET /u/search/users?term=&limit=`,与 /search.json 无关,且无分页。
 *
 * 所以「高级筛选」只在 [TOPICS] 下显示 —— 用户搜索压根不经过 Discourse 的
 * advanced_filter 那套解析,把 category:xxx 拼进去只会被当成普通字符串。
 */ 
enum class SearchType(val value: String, val label: String, val placeholder: String) {
    TOPICS("topics_posts", "帖子", "搜索帖子…"),
    USERS("users", "用户", "搜索用户…");

    companion object {
        fun fromValue(value: String?): SearchType =
            entries.firstOrNull { it.value == value } ?: TOPICS
    }
}

/** 筛选面板里可多选/单选的一项。 */
data class SearchFilterOption(val value: String, val label: String)

/**
 * 高级搜索筛选条件:UI 上选出来的条件 → /search.json 的 q 串。
 *
 * 语法逐条对齐 Discourse 官方实现(服务端 core `lib/search.rb` 的 advanced_filter
 * 正则 + 前端 `search-advanced-options.gjs` 的拼串规则):
 *
 * - 分类:官方优先拼 slug 简写 `#slug`,子分类是 `#父slug:子slug`,没有 slug 才退回
 *   `category:id`。`category:` 的值服务端按 slug/name/纯数字 id 三种方式匹配,id 兜底有效。
 * - 标签:`tags:a,b` 是「任一」,`tags:a+b` 是「全部」 —— 分隔符本身就是语义,不能换。
 * - 位置:大部分是 `in:xxx`,只有图片是 `with:images`(官方 IN_OPTIONS_MAPPING 里的特例)。
 * - 时间:`before:` / `after:` 的值服务端 `Search.word_to_date` 支持 `YYYY-MM-DD` 与
 *   「N 天前」的纯数字;这里统一折算成绝对日期,避免相对值随缓存漂移。
 * - 帖数/浏览量:`min_posts:` `max_posts:` `min_views:` `max_views:`(注意是 posts 不是 post)。
 * - 排序:`order:` 只有 latest/oldest/latest_topic/oldest_topic/views/likes/read 有实现分支,
 *   表外的值服务端当未知排序忽略;**相关性是「不拼 order」**,不是 `order:relevance`。
 */
data class SearchFilters(
    /** 分类:id 用于兜底 `category:id`,slug/parentSlug 用于官方优先的 `#slug` 简写,name 只用于展示。 */
    val categoryId: Long = -1,
    val categorySlug: String = "",
    val categoryParentSlug: String = "",
    val categoryName: String = "",
    /** 标签名(非 id):`tags:` 过滤器按名字匹配。 */
    val tags: List<String> = emptyList(),
    /** true → `tags:a+b`(同时含全部);false → `tags:a,b`(含任一)。 */
    val tagsMatchAll: Boolean = false,
    /** 发帖人用户名,拼成 `@username`。 */
    val username: String = "",
    /** `in:` / `with:` 多选,值取自 [SearchFiltersOptions.IN_USER] / [SearchFiltersOptions.IN_POST]。 */
    val inFilters: List<String> = emptyList(),
    val status: String = "",
    val timeWhen: String = SearchFiltersOptions.TIME_AFTER,
    val timeDate: String = "",
    val minPosts: String = "",
    val maxPosts: String = "",
    val minViews: String = "",
    val maxViews: String = "",
    val order: String = ""
) {
    /** 是否有任何条件生效(决定筛选按钮是否高亮、是否需要先输关键词)。 */
    fun hasAny(): Boolean = activeCount() > 0

    /** 生效条件条数,用于筛选按钮上的角标。 */
    fun activeCount(): Int {
        var count = 0
        if (categoryId >= 0) count++
        if (tags.isNotEmpty()) count++
        if (username.isNotEmpty()) count++
        count += inFilters.size
        if (status.isNotEmpty()) count++
        if (timeDate.isNotEmpty()) count++
        if (minPosts.isNotEmpty()) count++
        if (maxPosts.isNotEmpty()) count++
        if (minViews.isNotEmpty()) count++
        if (maxViews.isNotEmpty()) count++
        if (order.isNotEmpty()) count++
        return count
    }

    /** 分类的 q 片段:优先 `#slug` / `#父:子`,无 slug 退回 `category:id`(对齐官方)。 */
    internal fun categoryToken(): String {
        if (categoryId < 0) return ""
        if (categorySlug.isEmpty()) return "category:$categoryId"
        if (categoryParentSlug.isNotEmpty()) return "#$categoryParentSlug:$categorySlug"
        return "#$categorySlug"
    }
}

/**
 * 筛选面板的选项表。逐条对齐 Discourse 官方 `search-advanced-options.gjs` 与
 * core 的 advanced_filter 正则;`in:` 多选分「需要登录」与「与登录无关」两组。
 */
object SearchFiltersOptions {
    const val TIME_BEFORE = "before"
    const val TIME_AFTER = "after"

    /** 需要登录态才有意义的「我的」相关位置筛选(对齐官方 inOptionsForUsers)。 */
    val IN_USER = listOf(
        SearchFilterOption("created", "我创建的"),
        SearchFilterOption("posted", "我发言过的"),
        SearchFilterOption("likes", "我点赞的"),
        SearchFilterOption("bookmarks", "我收藏的"),
        SearchFilterOption("seen", "我读过的"),
        SearchFilterOption("unseen", "我没读过的"),
        SearchFilterOption("watching", "我关注的"),
        SearchFilterOption("tracking", "我跟踪的")
    )

    /** 与登录无关的帖子属性 / 搜索范围(对齐官方 inOptionsForAll)。 */
    val IN_POST = listOf(
        SearchFilterOption("title", "仅搜标题"),
        SearchFilterOption("first", "仅搜主楼"),
        SearchFilterOption("pinned", "置顶话题"),
        SearchFilterOption("wiki", "Wiki 帖"),
        SearchFilterOption("images", "含图片")
    )

    /**
     * 话题状态。前 6 项是 core 的 `status:` 过滤器;solved / unsolved 由 discourse-solved
     * 插件注册,linux.do 装了该插件(帖子有「已解决」标记),故一并给出。
     */
    val STATUS = listOf(
        SearchFilterOption("open", "开放中"),
        SearchFilterOption("closed", "已关闭"),
        SearchFilterOption("archived", "已归档"),
        SearchFilterOption("public", "公开"),
        SearchFilterOption("noreplies", "无回复"),
        SearchFilterOption("single_user", "仅一人参与"),
        SearchFilterOption("solved", "已解决"),
        SearchFilterOption("unsolved", "未解决")
    )

    /**
     * 排序,取官方整页搜索「排序依据」下拉里的六项(名字照抄官方中文)。
     * 空值 = 相关性,是默认项,且**不拼 order token**(拼 `order:relevance` 会被当未知排序忽略)。
     * `order:read` 只对登录用户生效(search.rb 里是 `@order == :read && @guardian.user`)。
     */
    val ORDER = listOf(
        SearchFilterOption("", "相关性"),
        SearchFilterOption("latest", "最新帖子"),
        SearchFilterOption("latest_topic", "最新话题"),
        SearchFilterOption("likes", "赞最多"),
        SearchFilterOption("views", "浏览最多"),
        SearchFilterOption("read", "最近阅读")
    )

    /** 发布时间的快捷区间(天数),选中即折算成绝对日期拼 `after:`。 */
    val TIME_PRESETS = listOf(
        SearchFilterOption("7", "最近 7 天"),
        SearchFilterOption("30", "最近 30 天"),
        SearchFilterOption("90", "最近 90 天"),
        SearchFilterOption("365", "最近一年")
    )

    /** 官方 IN_OPTIONS_MAPPING:只有 images 走 `with:`,其余都是 `in:`。 */
    internal fun inKeywordFor(value: String): String = if (value == "images") "with" else "in"

    /** 只留数字:`min_posts` 等服务端正则是 `(\d+)`,混进别的字符整个 token 会被当普通搜索词。 */
    fun digitsOnly(raw: String): String = raw.filter { it.isDigit() }

    /** Date → `YYYY-MM-DD`(服务端 `word_to_date` 认这个格式)。 */
    fun formatSearchDate(date: java.util.Calendar): String {
        val month = date.get(java.util.Calendar.MONTH) + 1
        val day = date.get(java.util.Calendar.DAY_OF_MONTH)
        val mm = if (month < 10) "0$month" else "$month"
        val dd = if (day < 10) "0$day" else "$day"
        return "${date.get(java.util.Calendar.YEAR)}-$mm-$dd"
    }

    /** 「最近 N 天」→ 绝对日期(拼 `after:` 用)。 */
    fun daysAgoToSearchDate(days: Int): String =
        formatSearchDate(java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_MONTH, -days) })
}

/**
 * 把关键词与筛选条件拼成最终的 `q`。关键词在前、筛选 token 在后,与官方一致。
 *
 * 服务端其实允许「不输关键词、只带筛选条件」(search.rb 判无效是 `@term.blank? &&
 * @filters.blank? && @order.blank?` 三者全空),但产品上要求必须先有关键词,
 * 筛选条件只用来收窄结果 —— 见 feature 层的 canSearch()。
 */
fun buildSearchQuery(term: String, filters: SearchFilters): String {
    val parts = mutableListOf<String>()
    val trimmed = term.trim()
    if (trimmed.isNotEmpty()) parts.add(trimmed)

    filters.categoryToken().takeIf { it.isNotEmpty() }?.let(parts::add)

    if (filters.tags.isNotEmpty()) {
        // 分隔符即语义:+ 全部命中,`,` 任一命中。
        parts.add("tags:${filters.tags.joinToString(if (filters.tagsMatchAll) "+" else ",")}")
    }

    if (filters.username.isNotEmpty()) parts.add("@${filters.username}")

    for (value in filters.inFilters) {
        parts.add("${SearchFiltersOptions.inKeywordFor(value)}:$value")
    }

    if (filters.status.isNotEmpty()) parts.add("status:${filters.status}")
    if (filters.timeDate.isNotEmpty()) parts.add("${filters.timeWhen}:${filters.timeDate}")
    if (filters.minPosts.isNotEmpty()) parts.add("min_posts:${filters.minPosts}")
    if (filters.maxPosts.isNotEmpty()) parts.add("max_posts:${filters.maxPosts}")
    if (filters.minViews.isNotEmpty()) parts.add("min_views:${filters.minViews}")
    if (filters.maxViews.isNotEmpty()) parts.add("max_views:${filters.maxViews}")

    // 相关度是「不拼 order」,拼 order:relevance 反而会被服务端当未知排序忽略。
    if (filters.order.isNotEmpty()) parts.add("order:${filters.order}")

    return parts.joinToString(" ")
}

/**
 * 从搜索词拆出普通高亮词:去掉高级语法(`in:title` / `category:` / `#版块` / `@用户` / `-排除`)。
 *
 * 长度只挡空串 —— 站点把 `min_search_term_length` 调低了,单个汉字也能搜;若这里要求
 * ≥2 个字符,就会出现「搜『搞』搜得到但结果里不高亮」。
 */
fun parseSearchHighlights(term: String): List<String> =
    term.split(Regex("\\s+")).filter { part ->
        part.isNotEmpty() &&
            ':' !in part &&
            part.first() !in listOf('#', '@', '-', '+')
    }

/** 一条帖子搜索结果:主题 + 可选命中楼层的摘要。 */
data class SearchHit(
    val topic: HomeTopic,
    /** 命中楼层的高亮摘要(纯文本);标题命中等场景可能为空。 */
    val blurb: String? = null
)

data class SearchPage(
    val hits: List<SearchHit>,
    val page: Int,
    val hasMore: Boolean
)

/**
 * 帖子搜索的分页结果。Discourse 整页搜索硬上限 10 页,超出不再翻。
 */
sealed interface SearchPageResult {
    data class Success(val page: SearchPage) : SearchPageResult
    data class Failure(val message: String, val requiresChallenge: Boolean = false) : SearchPageResult
}

/** 用户搜索结果(`/u/search/users` 一次性返回,无分页)。 */
data class SearchUsersResult(
    val users: List<HomeUser>,
    val error: String? = null,
    val requiresChallenge: Boolean = false
) {
    val isSuccess: Boolean get() = error == null
}