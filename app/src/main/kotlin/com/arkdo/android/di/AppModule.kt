package com.arkdo.android.di

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.svg.SvgDecoder
import coil3.util.DebugLogger
import com.arkdo.android.BuildConfig
import com.arkdo.android.services.network.ArkWebNetworkBridge
import com.arkdo.android.services.network.WebViewCookieJar
import com.arkdo.android.services.auth.AuthService
import com.arkdo.android.services.notification.NotificationApi
import com.arkdo.android.services.draft.DraftService
import com.arkdo.android.services.topic.TopicService
import com.arkdo.android.services.user.UserProfileService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import java.util.concurrent.TimeUnit
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
                // 自带网络层：动图头像是 CDN 原图（单文件可达数 MB），Coil 默认
                // OkHttp 的 10s 读超时在弱网下会把大文件中途判死，表现成
                // 「动图头像永远退到静态兜底」。放宽超时并复用同一个 client。
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = {
                            OkHttpClient.Builder()
                                .connectTimeout(15, TimeUnit.SECONDS)
                                .readTimeout(30, TimeUnit.SECONDS)
                                .writeTimeout(15, TimeUnit.SECONDS)
                                .callTimeout(90, TimeUnit.SECONDS)
                                // 必须挂宿主 WebView 的会话 cookie：`/uploads/short-url/*`
                                // （发帖预览里 `upload://` 短码换出来的直链）在 linux.do 上
                                // 需要登录态，匿名请求返回 404。Coil 默认没有 cookie jar，
                                // 于是「上传成功但预览里图片是空白」。
                                .cookieJar(WebViewCookieJar())
                                .build()
                        }
                    )
                )
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.25) // 使用 25% 的应用内存
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(50 * 1024 * 1024) // 50 MB
                    .build()
            }
            // 调试构建下输出请求级日志（成败、数据源、失败原因），便于排查图片加载问题。
            .logger(if (BuildConfig.DEBUG) DebugLogger() else null)
            .build()
    }

    @Provides
    @Singleton
    fun provideTopicService(): TopicService {
        return TopicService(ArkWebNetworkBridge)
    }

    @Provides
    @Singleton
    fun provideDraftService(): DraftService {
        return DraftService(ArkWebNetworkBridge)
    }

    @Provides
    @Singleton
    fun provideAuthService(): AuthService {
        return AuthService(ArkWebNetworkBridge)
    }

    @Provides
    @Singleton
    fun provideUserProfileService(): UserProfileService {
        return UserProfileService(ArkWebNetworkBridge)
    }

    @Provides
    @Singleton
    fun provideNotificationApi(): NotificationApi {
        return NotificationApi(ArkWebNetworkBridge)
    }
}
