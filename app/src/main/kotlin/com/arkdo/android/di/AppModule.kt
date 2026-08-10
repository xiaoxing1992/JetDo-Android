package com.arkdo.android.di

import android.content.Context
import coil.ImageLoader
import coil.decode.SvgDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
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
 * - WebViewNetworkBridge（网络桥接）
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
                MemoryCache.Builder(context)
                    .maxSizePercent(0.25) // 使用 25% 的应用内存
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(50 * 1024 * 1024) // 50 MB
                    .build()
            }
            .respectCacheHeaders(false) // 忽略服务端 Cache-Control
            .crossfade(true) // 淡入动画
            .build()
    }
}
