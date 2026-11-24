package com.jetdo.android.di

import com.jetdo.android.core.data.miniapp.DefaultMiniAppRepository
import com.jetdo.android.core.data.miniapp.MiniAppRepository
import com.jetdo.android.core.network.JsonNetworkClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MiniAppModule {

    @Provides
    @Singleton
    fun provideMiniAppRepository(client: JsonNetworkClient): MiniAppRepository =
        DefaultMiniAppRepository(client)
}
