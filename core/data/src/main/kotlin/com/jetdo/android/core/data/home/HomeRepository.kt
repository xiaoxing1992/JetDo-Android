package com.jetdo.android.core.data.home

import com.jetdo.android.core.model.HomePageResult
import com.jetdo.android.core.model.HomeQuery

interface HomeRepository {
    suspend fun load(query: HomeQuery, page: Int): HomePageResult

    /** Loads the latest representation of a set of topics for realtime insertion. */
    suspend fun loadByIds(topicIds: List<Long>): HomePageResult
}
