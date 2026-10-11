package com.jetdo.android.services.logging

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.jetdo.android.common.constants.StorageKeys
import timber.log.Timber
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 文件日志器的 Android 胶水层。
 *
 * [FileLogger] 只负责「攒批 + 轮转 + 导出」的纯逻辑，这里补上它不该关心的三件事：
 *  · 开关的持久化（SharedPreferences，对齐鸿蒙 PREF_STORE_NAME = app_settings）
 *  · 5 秒一次的定时刷盘（鸿蒙用 setTimeout，Android 用守护线程的调度器）
 *  · 崩溃钩子与 Timber.Tree 接线
 *
 * 崩溃钩子是**包装**现有的 handler 而不是替换：DebugLogStore 在更早的位置已经装了
 * 一个（负责把内存缓冲写进 debug_log.txt），时序上 FileLoggerDaemon 装在外层，
 * 崩溃时先刷文件日志、再交给内层 —— 两份现场都留得下。
 */
object FileLoggerDaemon {

    private const val PREF_STORE_NAME = "app_settings"
    private const val SCHEDULER_THREAD_NAME = "jetdo-file-logger"

    @Volatile
    private var logger: FileLogger? = null

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var enabled = true

    @Volatile
    private var verbose = false

    /** 应用启动时调用（JetDoApplication.onCreate）。重复调用是幂等的。 */
    fun init(context: Context) {
        if (logger != null) return
        val app = context.applicationContext
        val store = app.getSharedPreferences(PREF_STORE_NAME, Context.MODE_PRIVATE)
        prefs = store
        enabled = store.getBoolean(StorageKeys.PREFS_FILE_LOG_ENABLED, true)
        verbose = store.getBoolean(StorageKeys.PREFS_FILE_LOG_VERBOSE, false)

        val created = FileLogger(
            logDir = File(app.filesDir, FileLogger.LOG_DIR_NAME),
            onError = { stage, error -> Timber.tag("FileLogger").w(error, "「%s」失败", stage) }
        )
        logger = created

        // 崩溃：文件日志先落盘，再把异常交给内层 handler（DebugLogStore / 系统默认）。
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                created.append('C', "未捕获异常 thread=${thread.name}\n${throwable.stackTraceToString()}")
            }
            previous?.uncaughtException(thread, throwable)
        }

        startScheduler(created)
        Timber.i("[JetDo][FileLogger] init ok dir=%s enabled=%s", FileLogger.LOG_DIR_NAME, enabled)
    }

    /** 在 Application 里 plant，与 DebugLogStore 的 RecordingTree、DebugTree 并存。 */
    fun tree(): Timber.Tree = FileLoggingTree()

    /**
     * 供 [FileLoggingTree] 写入的入口。init 尚未跑完时静默丢弃 ——
     * Application.onCreate 里 plant 与 init 的先后顺序不构成约束。
     */
    internal fun appendEntry(level: Char, message: String) {
        logger?.append(level, message)
    }

    /**
     * 把归档与当前日志合并导出到 filesDir/logs/ 下（时间戳命名），供 FileProvider 分享。
     * 失败返回 null —— 调用方负责提示，不要在这里吞掉。
     */
    fun exportToFile(context: Context): File? {
        val current = logger ?: return null
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        val out = File(dir, "jetdo-log-${System.currentTimeMillis()}.txt")
        return if (current.exportTo(out)) out else null
    }

    /** 手动刷盘（退后台、切页面前）。 */
    fun flush() {
        logger?.flush()
    }

    fun clear() {
        logger?.clear()
    }

    /** 磁盘占用总量，设置页的「日志大小」标签用。 */
    fun totalSizeBytes(): Long = logger?.totalSizeBytes() ?: 0L

    fun isEnabled(): Boolean = enabled

    fun isVerbose(): Boolean = verbose

    fun setEnabled(value: Boolean) {
        enabled = value
        prefs?.edit()?.putBoolean(StorageKeys.PREFS_FILE_LOG_ENABLED, value)?.apply()
    }

    fun setVerbose(value: Boolean) {
        verbose = value
        prefs?.edit()?.putBoolean(StorageKeys.PREFS_FILE_LOG_VERBOSE, value)?.apply()
    }

    /** 退到后台时立刻刷盘，别把最后几秒的日志留在缓冲里等系统杀进程。 */
    fun onBackground() {
        flush()
    }

    private fun startScheduler(target: FileLogger) {
        val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, SCHEDULER_THREAD_NAME).apply { isDaemon = true }
        }
        executor.scheduleWithFixedDelay(
            { runCatching { target.flush() } },
            FileLogger.DEFAULT_FLUSH_INTERVAL_MS,
            FileLogger.DEFAULT_FLUSH_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )
        // 不保存 executor 引用：守护线程本身是 GC root，调度会一直跑到进程结束，
        // 而 App 退出时也不需要主动 shutdown（进程没了线程自然结束）。
    }
}

/**
 * 把 Timber 的输出抄一份进文件。与 [RecordingTree] 是同构的两条腿：
 * 内存缓冲负责「崩了当场看一眼」，文件负责「跑了一天回头看」。
 */
internal class FileLoggingTree : Timber.Tree() {

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (!FileLoggerDaemon.isEnabled()) return
        if (priority <= Log.VERBOSE) return
        // 默认只记 I 以上：文件日志是长期驻留的，把 D 级噪声也写进去会加速轮转、
        // 把真正有用的现场挤掉。需要时在设置里打开「详细日志」（见 S17）。
        if (priority <= Log.DEBUG && !FileLoggerDaemon.isVerbose()) return

        val text = buildString {
            append(message)
            if (t != null) {
                append('\n')
                append(t.stackTraceToString())
            }
        }
        val line = "[${tag ?: "App"}] $text"
        // 通过公开入口而不是直接持有 FileLogger：单测/预览里没有 init 过时静默丢弃。
        FileLoggerDaemon.appendEntry(levelChar(priority), line)
    }

    private fun levelChar(priority: Int): Char = when (priority) {
        Log.DEBUG -> 'D'
        Log.INFO -> 'I'
        Log.WARN -> 'W'
        Log.ERROR, Log.ASSERT -> 'E'
        else -> 'V'
    }
}
