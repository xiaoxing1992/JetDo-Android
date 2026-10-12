package com.jetdo.android.di

import com.jetdo.android.core.data.pm.DefaultPrivateMessageRepository
import com.jetdo.android.core.data.pm.PrivateMessageRepository
import com.jetdo.android.core.network.JsonNetworkClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 私信数据源的注入绑定。
 *
 * 注意这里**不重复提供 JsonNetworkClient** —— 它已由 [NotificationModule] 在同一
 * SingletonComponent 里提供，重复的 @Provides 会造成重复绑定编译失败。
 */
@Module
@InstallIn(SingletonComponent::class)
object PrivateMessageModule {
    @Provides
    @Singleton
    fun providePrivateMessageRepository(client: JsonNetworkClient): PrivateMessageRepository =
        DefaultPrivateMessageRepository(client)
}
