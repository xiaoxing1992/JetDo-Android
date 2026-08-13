package com.arkdo.android

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.arkdo.android.services.network.DohService
import com.arkdo.android.services.session.LoginGate
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()

        // 日志系统优先初始化
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        Timber.i("[ArkDO][Startup] Application.onCreate() ts=%d", System.currentTimeMillis())

        // 初始化登录态持久化（SharedPreferences）
        LoginGate.initPersist(this)

        // 初始化 DoH (DNS over HTTPS) 服务
        // 使用 edge.47258.xyz 绕过 DNS 污染，与鸿蒙版本保持一致
        applicationScope.launch {
            try {
                DohService.init(this@ArkDoApplication)
                Timber.i("[ArkDO][Startup] DoH service initialized")
            } catch (e: Exception) {
                Timber.e(e, "[ArkDO][Startup] DoH initialization failed")
            }
        }

        // 用 Hilt 提供的 ImageLoader 作为 Coil 单例（经 SingletonImageLoader.Factory 注册）。
        SingletonImageLoader.setSafe(this)
    }

    override fun newImageLoader(context: coil3.PlatformContext): ImageLoader {
        return imageLoaderProvider.get()
    }
}
