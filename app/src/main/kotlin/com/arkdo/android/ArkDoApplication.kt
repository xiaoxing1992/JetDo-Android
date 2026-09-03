package com.arkdo.android

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.arkdo.android.services.network.DohService
import com.arkdo.android.services.session.LoginGate
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
 * 4. 初始化登录态持久化（LoginGate）
 * 5. 初始化 DoH (DNS over HTTPS) 服务，绕过 DNS 污染访问 linux.do
 * 6. 预热核心服务（WebView 网络桥）
 */
@HiltAndroidApp
class ArkDoApplication : Application(), SingletonImageLoader.Factory {

    @Inject
    lateinit var imageLoaderProvider: dagger.Lazy<ImageLoader>

    override fun onCreate() {
        super.onCreate()

        // 日志系统优先初始化
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        Timber.i("[ArkDO][Startup] Application.onCreate() ts=%d", System.currentTimeMillis())

        // 初始化登录态持久化（SharedPreferences）
        LoginGate.initPersist(this)

        // 初始化 DoH (DNS over HTTPS)：从 DohManager 读配置构造解析器。
        // 同步执行（只读 SharedPreferences，不发请求），避免 host WebView 抢在配置就绪前建起来。
        runCatching { DohService.init(this) }
            .onSuccess { Timber.i("[ArkDO][Startup] DoH service initialized url=%s", DohService.activeServerUrl()) }
            .onFailure { Timber.e(it, "[ArkDO][Startup] DoH initialization failed") }

        // 用 Hilt 提供的 ImageLoader 作为 Coil 单例（经 SingletonImageLoader.Factory 注册）。
        SingletonImageLoader.setSafe(this)
    }

    override fun newImageLoader(context: coil3.PlatformContext): ImageLoader {
        return imageLoaderProvider.get()
    }
}
