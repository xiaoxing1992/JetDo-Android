package com.arkdo.android.core.data.search

import com.arkdo.android.core.model.SearchPageResult
import com.arkdo.android.core.model.SearchUsersResult
import com.arkdo.android.core.model.TagSuggestion

/**
 * 搜索数据源。
 *
 * Discourse 的两种搜索类型走**两个不同接口**(不是都挂 /search.json):
 * - [searchTopics] → `GET /search.json?q=&page=`,支持高级语法且可分页;
 * - [searchUsers]  → `GET /u/search/users?term=&limit=`,一次性返回、无分页。
 */
interface SearchRepository {
    /**
     * 帖子搜索。[query] 是已拼好的完整 q 串(含高级语法),
     * 见 `com.arkdo.android.core.model.buildSearchQuery`。
     */
    suspend fun searchTopics(query: String, page: Int): SearchPageResult

    /** 用户搜索。官方整页搜索用的也是 20 条上限。 */
    suspend fun searchUsers(term: String, limit: Int = USER_SEARCH_LIMIT): SearchUsersResult

    /**
     * 标签联想:`GET /tags/filter/search?q=&limit=5&filterForInput=true`。
     * 空 q 即热门标签前几个;失败返回空列表(联想是锦上添花,不值得报错打断筛选)。
     */
    suspend fun suggestTags(query: String, limit: Int = TAG_SUGGEST_LIMIT): List<TagSuggestion>

    companion object {
        const val USER_SEARCH_LIMIT = 20

        /** 与鸿蒙 TAG_SEARCH_LIMIT 一致:联想只给 5 个候选。 */
        const val TAG_SUGGEST_LIMIT = 5
    }
}