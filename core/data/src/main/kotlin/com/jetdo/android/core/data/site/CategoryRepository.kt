package com.jetdo.android.core.data.site

import com.jetdo.android.core.model.HomeCategory
import com.jetdo.android.core.model.TagSuggestion

/**
 * Result of a site-metadata fetch: either the category index or a failure reason.
 */
sealed interface CategoryLoadResult {
    data class Success(
        val categories: List<HomeCategory>,
        /**
         * 同一份 /site.json 里的站点热门标签（`top_tags`）。
         *
         * 抽屉的「热门标签」组用它，而不是空查询去打 /tags/filter/search —— 后者受
         * `max_tag_search_results` 限制（默认只有 5），拿不到完整的热门榜；而且首页
         * 早就为版块树拉过 /site.json，顺手解析等于零成本。
         */
        val topTags: List<TagSuggestion> = emptyList()
    ) : CategoryLoadResult

    data class Failure(val message: String, val requiresChallenge: Boolean = false) : CategoryLoadResult
}

/** Contract for loading the site category index. */
interface CategoryRepository {
    /** Loads the full category list from the site. */
    suspend fun load(): CategoryLoadResult

    /**
     * 标签联想。空查询返回热门标签；失败返回空列表，不打断筛选。
     * 对应 `GET /tags/filter/search`。
     *
     * [categoryId] 非空时只回该版块下的标签 —— 标签是挂在版块上的，选了版块还在几百个
     * 全站标签里翻没有意义。传 null 即全站联想（搜索页的用法）。
     */
    suspend fun suggestTags(
        query: String,
        categoryId: Long? = null,
        limit: Int = TAG_SUGGEST_LIMIT
    ): List<TagSuggestion>

    companion object {
        const val TAG_SUGGEST_LIMIT = 5
    }
}
