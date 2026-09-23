package com.arkdo.android.core.data.site

import com.arkdo.android.core.model.HomeCategory

/** Result of a site-metadata fetch: either the category index or a failure reason. */
sealed interface CategoryLoadResult {
    data class Success(val categories: List<HomeCategory>) : CategoryLoadResult
    data class Failure(val message: String, val requiresChallenge: Boolean = false) : CategoryLoadResult
}

/** Contract for loading the site category index. */
interface CategoryRepository {
    /** Loads the full category list from the site. */
    suspend fun load(): CategoryLoadResult

    /**
     * 全站标签联想。空查询返回热门标签；失败返回空列表，不打断筛选。
     * 对应 `GET /tags/filter/search`。
     */
    suspend fun suggestTags(query: String, limit: Int = TAG_SUGGEST_LIMIT): List<com.arkdo.android.core.model.TagSuggestion>

    companion object {
        const val TAG_SUGGEST_LIMIT = 5
    }
}