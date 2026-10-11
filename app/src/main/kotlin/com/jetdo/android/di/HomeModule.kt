package com.jetdo.android.di

import com.jetdo.android.core.data.bookmark.DefaultBookmarkRepository
import com.jetdo.android.core.data.home.DefaultHomeRepository
import com.jetdo.android.core.data.home.HomeRepository
import com.jetdo.android.core.data.site.CategoryRepository
import com.jetdo.android.core.data.search.DefaultSearchRepository
import com.jetdo.android.core.data.search.SearchRepository
import com.jetdo.android.core.data.session.SessionVersionSource
import com.jetdo.android.core.data.session.DefaultSidebarPrefsSource
import com.jetdo.android.core.data.session.DefaultTopicReadStateSource
import com.jetdo.android.core.data.session.SidebarPrefsSource
import com.jetdo.android.core.data.session.TopicReadStateSource
import com.jetdo.android.core.data.site.DefaultCategoryRepository
import com.jetdo.android.core.network.JsonNetworkClient
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
    fun provideBookmarkRepository(client: JsonNetworkClient): DefaultBookmarkRepository =
        DefaultBookmarkRepository(client)

    @Provides
    @Singleton
    fun provideCategoryRepository(client: JsonNetworkClient): CategoryRepository =
        DefaultCategoryRepository(client)

    @Provides
    @Singleton
    fun provideSidebarPrefsSource(client: JsonNetworkClient): SidebarPrefsSource =
        DefaultSidebarPrefsSource(client)

    @Provides
    @Singleton
    fun provideSearchRepository(client: JsonNetworkClient): SearchRepository =
        DefaultSearchRepository(client)

    @Provides
    @Singleton
    fun provideSessionVersionSource(): SessionVersionSource = object : SessionVersionSource {
        override fun currentVersion(): Long =
            com.jetdo.android.services.session.SessionEventBus.getCurrentVersion()

        override fun subscribe(listener: (Long) -> Unit): () -> Unit =
            com.jetdo.android.services.session.SessionEventBus.subscribe(listener)
    }

    @Provides
    @Singleton
    fun provideTopicReadStateSource(): TopicReadStateSource = DefaultTopicReadStateSource()
}
