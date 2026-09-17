package com.arkdo.android.core.data.search

import com.arkdo.android.core.model.SearchPageResult
import com.arkdo.android.core.model.SearchUsersResult

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

    companion object {
        const val USER_SEARCH_LIMIT = 20
    }
}