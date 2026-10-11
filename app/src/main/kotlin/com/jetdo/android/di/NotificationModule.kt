package com.jetdo.android.di

import com.jetdo.android.core.data.notification.DefaultNotificationRepository
import com.jetdo.android.core.data.notification.NotificationRepository
import com.jetdo.android.core.data.notification.NotificationUnreadStore
import com.jetdo.android.core.network.JsonNetworkClient
import com.jetdo.android.services.notification.NotificationEventBus
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NotificationModule {
    @Provides
    @Singleton
    fun provideCoreNetworkClient(): JsonNetworkClient = CoreNetworkClientAdapter()

    @Provides
    @Singleton
    fun provideNotificationRepository(client: JsonNetworkClient): NotificationRepository =
        DefaultNotificationRepository(client)

    @Provides
    @Singleton
    fun provideNotificationUnreadStore(): NotificationUnreadStore = object : NotificationUnreadStore {
        override fun publish(unread: Int, highPriority: Int) =
            NotificationEventBus.publish(com.jetdo.android.services.notification.NotificationSnapshot(unread, highPriority))

        override fun decrement(highPriority: Boolean) = NotificationEventBus.decrementUnread(highPriority)

        override fun currentHighPriority(): Int = NotificationEventBus.current().highPriority
    }
}
