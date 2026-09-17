package com.arkdo.android.di

import com.arkdo.android.core.data.home.DefaultHomeRepository
import com.arkdo.android.core.data.home.HomeRepository
import com.arkdo.android.core.data.site.CategoryRepository
import com.arkdo.android.core.data.search.DefaultSearchRepository
import com.arkdo.android.core.data.search.SearchRepository
import com.arkdo.android.core.data.site.DefaultCategoryRepository
import com.arkdo.android.core.network.JsonNetworkClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object HomeModule {
    @Provides
    @Singleton
    fun provideHomeRepository(client: JsonNetworkClient): HomeRepository =
        DefaultHomeRepository(client)

    @Provides
    @Singleton
    fun provideCategoryRepository(client: JsonNetworkClient): CategoryRepository =
        DefaultCategoryRepository(client)

    @Provides
    @Singleton
    fun provideSearchRepository(client: JsonNetworkClient): SearchRepository =
        DefaultSearchRepository(client)
}
