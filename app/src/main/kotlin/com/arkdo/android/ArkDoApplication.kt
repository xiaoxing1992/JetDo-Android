package com.arkdo.android

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.arkdo.android.common.theme.ThemeModeStore
import com.arkdo.android.core.designsystem.FabGlassStore
import com.arkdo.android.core.designsystem.TopicListStyleStore
import com.arkdo.android.services.announce.VersionAnnouncePrefs
import com.arkdo.android.services.topic.LotteryTipPrefs
import com.arkdo.android.services.realtime.RealtimePostsPrefs
import com.arkdo.android.services.realtime.RealtimeTopicsPrefs
import com.arkdo.android.services.logging.DebugLogStore
import com.arkdo.android.services.logging.FileLoggerDaemon
import com.arkdo.android.services.notification.SystemNotifier
import com.arkdo.android.services.network.DohManager
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

        // 日志系统优先初始化：崩溃处理器要赶在任何业务代码之前装好，
        // 否则最早的异常抓不到。RecordingTree 把 Timber 输出抄进内存环形缓冲，
        // 崩溃瞬间落盘，下次启动在「设置 → 诊断日志」里回读、一键复制。
        Timber.plant(DebugLogStore.tree())
        // 第二条腿：同一个 Timber 出口再抄一份进沙箱文件（app_logs/arkdo.log），
        // 支持 1MB 轮转与合并导出。内存缓冲只在崩溃瞬间落盘一次，App 正常跑一天
        // 再被系统回收的话现场全丢 —— 脱机跑一天后导出日志靠的就是这条。
        Timber.plant(FileLoggerDaemon.tree())
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
            // chrome://inspect 直接看 WebView(含 Turnstile iframe)的 console/network,排障用。
            android.webkit.WebView.setWebContentsDebuggingEnabled(true)
        }
        DebugLogStore.install(this)
        // 必须排在 DebugLogStore.install 之后：崩溃钩子是「包装上一个 handler」的链式结构，
        // 谁后装谁在外层。文件日志先刷盘，再交给内存缓冲那条去写 debug_log.txt。
        FileLoggerDaemon.init(this)

        Timber.i("[ArkDO][Startup] Application.onCreate() ts=%d", System.currentTimeMillis())

        // 初始化登录态持久化（SharedPreferences）
        LoginGate.initPersist(this)
        // 主题三态要在首帧之前读出来，否则设置页选的浅色/深色要等下一次重组才生效。
        ThemeModeStore.init(this)
        TopicListStyleStore.init(this)
        FabGlassStore.init(this)
        RealtimeTopicsPrefs.init(this)
        RealtimePostsPrefs.init(this)
        VersionAnnouncePrefs.init(this)
        LotteryTipPrefs.init(this)
        // 通知渠道：API 26 起，没有渠道的通知会被系统**直接丢弃且不报错**，
        // 所以渠道必须在第一次发通知之前就位。在这里建（而不是发通知时惰性建）
        // 是因为渠道名与描述会出现在系统设置里，早建早让用户看到。
        SystemNotifier.ensureChannel(this)
        // 上传短码 → 真实直链：预览解析 URL 时要查它，必须在任何预览渲染之前载入。
        com.arkdo.android.services.content.UploadShortcodeRegistry.init(this)

        // 初始化 DoH (DNS over HTTPS)：DohManager 先把配置种子化到 preferences
        // （首次启动写入内置服务器并选中），DohService 再读配置构造解析器。
        // 同步执行（只读/写 SharedPreferences，不发请求），避免 host WebView 抢在配置就绪前建起来。
        runCatching {
            DohManager.init(this)
            DohService.init(this)
        }
            .onSuccess { Timber.i("[ArkDO][Startup] DoH service initialized url=%s", DohService.activeServerUrl()) }
            .onFailure { Timber.e(it, "[ArkDO][Startup] DoH initialization failed") }

        // 用 Hilt 提供的 ImageLoader 作为 Coil 单例（经 SingletonImageLoader.Factory 注册）。
        SingletonImageLoader.setSafe(this)
    }

    override fun newImageLoader(context: coil3.PlatformContext): ImageLoader {
        return imageLoaderProvider.get()
    }
}
