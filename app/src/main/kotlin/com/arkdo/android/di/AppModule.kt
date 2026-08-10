package com.arkdo.android.di

import android.content.Context
import coil3.ImageLoader
import coil3.decode.SvgDecoder
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 应用级依赖注入模块
 *
 * 提供全局单例的核心服务：
 * - ImageLoader（Coil 图片加载器，支持 SVG）
 * - ArkWebNetworkBridge（网络桥接）
 * - DohManager（DoH DNS 管理器）
 * - 其他基础设施
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context
    ): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                // 支持 SVG 图片（用于徽章、图标）
                add(SvgDecoder.Factory())
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.25) // 使用 25% 的应用内存
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(50 * 1024 * 1024) // 50 MB
                    .build()
            }
            .crossfade(true) // 淡入动画
            .build()
    }
}
