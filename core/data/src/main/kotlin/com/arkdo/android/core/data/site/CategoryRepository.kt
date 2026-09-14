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
}