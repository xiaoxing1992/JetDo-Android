package com.arkdo.android.core.data.home

import com.arkdo.android.core.model.HomePageResult
import com.arkdo.android.core.model.HomeQuery

interface HomeRepository {
    suspend fun load(query: HomeQuery, page: Int): HomePageResult
}
