package com.arkdo.android

import android.app.Application
import coil3.ImageLoader
import coil3.ImageLoaderFactory
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

/**
 * ArkDO Android 应用入口
 *
 * 职责：
 * 1. 初始化日志系统（Timber）
 * 2. 配置图片加载器（Coil，支持 SVG）
 * 3. 初始化依赖注入（Hilt）
 * 4. 预热核心服务（WebView 网络桥、DoH 管理器）
 */
@HiltAndroidApp
class ArkDoApplication : Application(), ImageLoaderFactory {

    @Inject
    lateinit var imageLoaderProvider: dagger.Lazy<ImageLoader>

    override fun onCreate() {
        super.onCreate()

        // 日志系统优先初始化
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        Timber.i("[ArkDO][Startup] Application.onCreate() ts=%d", System.currentTimeMillis())

        // WebView 初始化会在首次使用时自动触发
        // DoH、主题管理器等会在 MainActivity 的 onCreate 中按需初始化
    }

    override fun newImageLoader(): ImageLoader {
        return imageLoaderProvider.get()
    }
}
