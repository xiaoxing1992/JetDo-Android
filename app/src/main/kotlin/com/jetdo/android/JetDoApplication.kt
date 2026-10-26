package com.jetdo.android

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.jetdo.android.common.theme.ThemeModeStore
import com.jetdo.android.core.designsystem.FabGlassStore
import com.jetdo.android.core.designsystem.TopicListStyleStore
import com.jetdo.android.feature.home.BadgeFilterPrefs
import com.jetdo.android.services.announce.VersionAnnouncePrefs
import com.jetdo.android.services.challenge.ChallengeShortcutPrefs
import com.jetdo.android.services.compliance.PrivacyConsentStore
import com.jetdo.android.services.topic.LotteryTipPrefs
import com.jetdo.android.services.topic.TwoPageReadingPrefs
import com.jetdo.android.services.realtime.RealtimePostsPrefs
import com.jetdo.android.services.realtime.RealtimeTopicsPrefs
import com.jetdo.android.services.logging.DebugLogStore
import com.jetdo.android.services.logging.FileLoggerDaemon
import com.jetdo.android.services.notification.SystemNotifier
import com.jetdo.android.services.network.DohManager
import com.jetdo.android.services.network.DohService
import com.jetdo.android.services.session.LoginGate
import dagger.hilt.android.HiltAndroidApp
import org.acra.ACRA
import org.acra.ReportField
import org.acra.config.httpSender
import org.acra.config.limiter
import org.acra.data.StringFormat
import org.acra.ktx.initAcra
import org.acra.sender.HttpSender
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * JetDo Android 应用入口
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
class JetDoApplication : Application(), SingletonImageLoader.Factory {

    @Inject
    lateinit var imageLoaderProvider: dagger.Lazy<ImageLoader>

    override fun onCreate() {
        super.onCreate()

        // 服务层 object 单例的 Context 入口（本地化文案等），必须最先就位。
        com.jetdo.android.core.data.AppContextHolder.init(this)

        // 日志系统优先初始化：崩溃处理器要赶在任何业务代码之前装好，
        // 否则最早的异常抓不到。RecordingTree 把 Timber 输出抄进内存环形缓冲，
        // 崩溃瞬间落盘，下次启动在「设置 → 诊断日志」里回读、一键复制。
        Timber.plant(DebugLogStore.tree())
        // 第二条腿：同一个 Timber 出口再抄一份进沙箱文件（app_logs/jetdo.log），
        // 支持 1MB 轮转与合并导出。内存缓冲只在崩溃瞬间落盘一次，App 正常跑一天
        // 再被系统回收的话现场全丢 —— 脱机跑一天后导出日志靠的就是这条。
        Timber.plant(FileLoggerDaemon.tree())
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
            // chrome://inspect 直接看 WebView(含 Turnstile iframe)的 console/network,排障用。
            android.webkit.WebView.setWebContentsDebuggingEnabled(true)
        }
        // 崩溃上报（ACRA）：必须赶在本地日志链路**之前**初始化。
        // UncaughtExceptionHandler 是「谁后装谁在外层」的链式结构，ACRA 先装就处于内层——
        // 崩溃时 DebugLogStore / FileLoggerDaemon 先落盘，再把异常交给 ACRA 上报。
        // 这样网络不通、服务器挂了都不影响本地现场保全。
        // 合规闸门：未同意隐私政策前不装（见 initCrashReportingIfAllowed）。
        initCrashReportingIfAllowed()
        DebugLogStore.install(this)
        // 必须排在 DebugLogStore.install 之后：崩溃钩子是「包装上一个 handler」的链式结构，
        // 谁后装谁在外层。文件日志先刷盘，再交给内存缓冲那条去写 debug_log.txt。
        FileLoggerDaemon.init(this)

        Timber.i("[JetDo][Startup] Application.onCreate() ts=%d", System.currentTimeMillis())

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
        TwoPageReadingPrefs.init(this)
        BadgeFilterPrefs.init(this)
        ChallengeShortcutPrefs.init(this)
        com.jetdo.android.services.avatar.AnimatedAvatarPrefs.init(this)
        // 通知渠道：API 26 起，没有渠道的通知会被系统**直接丢弃且不报错**，
        // 所以渠道必须在第一次发通知之前就位。在这里建（而不是发通知时惰性建）
        // 是因为渠道名与描述会出现在系统设置里，早建早让用户看到。
        SystemNotifier.ensureChannel(this)
        // 上传短码 → 真实直链：预览解析 URL 时要查它，必须在任何预览渲染之前载入。
        com.jetdo.android.services.content.UploadShortcodeRegistry.init(this)

        // 初始化 DoH (DNS over HTTPS)：DohManager 先把配置种子化到 preferences
        // （首次启动写入内置服务器并选中），DohService 再读配置构造解析器。
        // 同步执行（只读/写 SharedPreferences，不发请求），避免 host WebView 抢在配置就绪前建起来。
        runCatching {
            DohManager.init(this)
            DohService.init(this)
        }
            .onSuccess { Timber.i("[JetDo][Startup] DoH service initialized url=%s", DohService.activeServerUrl()) }
            .onFailure { Timber.e(it, "[JetDo][Startup] DoH initialization failed") }

        // 用 Hilt 提供的 ImageLoader 作为 Coil 单例（经 SingletonImageLoader.Factory 注册）。
        SingletonImageLoader.setSafe(this)
    }

    override fun newImageLoader(context: coil3.PlatformContext): ImageLoader {
        return imageLoaderProvider.get()
    }

    @Volatile
    private var crashReportingInitialized = false

    /**
     * 初始化崩溃上报（ACRA，幂等）。
     *
     * 三个前置条件，任一不满足就静默跳过：
     * 1. `BuildConfig.ACRA_URI` 非空 —— 端点由编译期从 local.properties 注入，
     *    F-Droid 构建机没有这个文件，产物自然不带任何远程上报。
     * 2. 用户已同意隐私政策 —— 工信部要求个人信息处理须在同意后进行；未同意的新装
     *    用户由 MainActivity 首启弹窗拿到同意后回调这里（幂等，重复调用无害）。
     * 3. 尚未初始化过。
     *
     * 上报内容由下面的 reportContent 白名单显式限定：设备环境 + 堆栈 + 本进程 logcat 尾部。
     * **不能依赖 ACRA 的默认字段集**——默认会带 SHARED_PREFERENCES / USER_EMAIL /
     * INSTALLATION_ID / SETTINGS_SECURE，其中 SHARED_PREFERENCES 会把 app_settings 等
     * prefs 文件整份打进去（登录态、DoH 配置都在里面），与隐私政策「不含账号与浏览记录」
     * 的表述直接冲突，也会被 F-Droid 判为 Tracking。
     */
    fun initCrashReportingIfAllowed() {
        if (crashReportingInitialized) return
        val endpoint = BuildConfig.ACRA_URI
        if (endpoint.isBlank()) {
            Timber.i("[JetDo][Startup] ACRA skipped: no endpoint configured in this build")
            return
        }
        if (PrivacyConsentStore.needsConsent(this)) {
            Timber.i("[JetDo][Startup] ACRA deferred: privacy consent not given yet")
            return
        }
        runCatching {
            ACRA.DEV_LOGGING = BuildConfig.DEBUG
            initAcra {
                buildConfigClass = BuildConfig::class.java
                reportFormat = StringFormat.JSON
                // 显式白名单 = 隐私政策里声明的那几项，一项不多。
                // 刻意排除：SHARED_PREFERENCES（含登录态）、SETTINGS_SECURE/GLOBAL（系统配置）、
                // DEVICE_ID / INSTALLATION_ID（设备标识）、USER_EMAIL、USER_COMMENT、
                // EVENTSLOG / RADIOLOG（系统日志）。
                // LOGCAT 保留，但 ACRA 默认 logcatFilterByPid = true，只取本进程日志。
                reportContent = listOf(
                    ReportField.REPORT_ID,
                    ReportField.APP_VERSION_CODE,
                    ReportField.APP_VERSION_NAME,
                    ReportField.PACKAGE_NAME,
                    ReportField.BUILD_CONFIG,
                    ReportField.STACK_TRACE,
                    // 设备环境
                    ReportField.PHONE_MODEL,
                    ReportField.BRAND,
                    ReportField.PRODUCT,
                    ReportField.ANDROID_VERSION,
                    ReportField.BUILD,
                    ReportField.TOTAL_MEM_SIZE,
                    ReportField.AVAILABLE_MEM_SIZE,
                    ReportField.ENVIRONMENT,
                    ReportField.DEVICE_FEATURES,
                    // 崩溃现场
                    ReportField.DISPLAY,
                    ReportField.INITIAL_CONFIGURATION,
                    ReportField.CRASH_CONFIGURATION,
                    ReportField.LOGCAT,
                    ReportField.USER_APP_START_DATE,
                    ReportField.USER_CRASH_DATE,
                    ReportField.CUSTOM_DATA,
                )
                httpSender {
                    uri = endpoint
                    httpMethod = HttpSender.Method.POST
                    connectionTimeout = 8_000
                    socketTimeout = 15_000
                    // 报告含 logcat 尾部时可达数百 KB，压缩后通常 < 30 KB
                    compress = true
                }
                limiter {
                    // 单机每天最多 20 条 —— 客户端礼貌，也是服务器磁盘的第一道闸。
                    // 崩溃循环（一启动就炸）时单次会话能触发几十次上报，
                    // 没有这层限流，几分钟就能把服务器的 100MB 配额顶满。
                    // 注意 ACRA 5.8+ 的 limiter 用「周期 + 总体上限」而非旧的
                    // numberOfReportsPerDay（后者已不存在）。
                    period = 1
                    periodUnit = TimeUnit.DAYS
                    overallLimit = 20
                    // 同一堆栈只加重叠计数，不无限累计新上报
                    stacktraceLimit = 5
                }
            }
        }
            .onSuccess {
                crashReportingInitialized = true
                Timber.i("[JetDo][Startup] ACRA initialized -> %s", endpoint)
            }
            .onFailure { Timber.e(it, "[JetDo][Startup] ACRA init failed") }
    }
}
