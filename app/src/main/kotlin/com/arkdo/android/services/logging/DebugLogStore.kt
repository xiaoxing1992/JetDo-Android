package com.arkdo.android.services.logging

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import timber.log.Timber

/**
 * 应用内日志缓冲。抽成纯类供 JVM 测试。
 *
 * 环形结构：超过 [maxLines] 淘汰最旧的。线程安全（Timber 回调与崩溃线程并发写）。
 */
class LogBuffer(
    private val maxLines: Int = 2000,
    private val now: () -> Long = System::currentTimeMillis,
    private val timeFormat: SimpleDateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
) {
    private val lines = ArrayDeque<String>()
    private val lock = Any()

    val size: Int get() = synchronized(lock) { lines.size }

    fun record(priority: Char, tag: String?, message: String, throwable: Throwable? = null) {
        val stamp = timeFormat.format(Date(now()))
        val line = "[$stamp][${priority}/${tag ?: "App"}] $message"
        val stack = throwable?.stackTraceToString()
        synchronized(lock) {
            lines.addLast(line)
            if (stack != null) lines.addLast(stack)
            while (lines.size > maxLines) lines.removeFirst()
        }
    }

    /** 回读上次崩溃落盘的日志，排在本次会话之前。 */
    fun restore(restored: List<String>) {
        synchronized(lock) {
            lines.addLast(RESTORE_SEPARATOR)
            restored.forEach { lines.addLast(it) }
            while (lines.size > maxLines) lines.removeFirst()
        }
    }

    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

    fun clear() = synchronized(lock) { lines.clear() }

    /** 拼上头部信息（版本/机型/系统），一键复制的就是它。 */
    fun dump(header: String): String = synchronized(lock) {
        buildString {
            if (header.isNotEmpty()) appendLine(header)
            lines.forEach { appendLine(it) }
        }
    }

    companion object {
        const val RESTORE_SEPARATOR = "===== 以下为上次崩溃日志 ====="
    }
}

/** 把 Timber 的输出抄一份进缓冲。与 DebugTree 并存，互不影响。 */
class RecordingTree(private val buffer: LogBuffer) : Timber.Tree() {

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (priority <= Log.VERBOSE) return
        buffer.record(priorityChar(priority), tag ?: "App", message, t)
    }

    private fun priorityChar(priority: Int): Char = when (priority) {
        Log.DEBUG -> 'D'
        Log.INFO -> 'I'
        Log.WARN -> 'W'
        Log.ERROR, Log.ASSERT -> 'E'
        else -> 'V'
    }
}

/**
 * 应用内日志中心。
 *
 * 背景：测试手机没有数据线，Android Studio/logcat 抓不到现场。闪退时进程直接没了，
 * 所以崩溃瞬间把缓冲落盘，下次启动回读；日志页一键复制后可以粘到任何地方传出来。
 */
object DebugLogStore {

    private val buffer = LogBuffer()

    @Volatile
    private var header = ""

    @Volatile
    private var crashFile: File? = null

    fun install(context: Context) {
        val appInfo = runCatching {
            val pm = context.packageManager.getPackageInfo(context.packageName, 0)
            val version = pm.versionName ?: "?"
            val model = android.os.Build.MODEL
            val api = android.os.Build.VERSION.SDK_INT
            "app=$version model=$model api=$api"
        }.getOrDefault("")

        header = appInfo
        crashFile = File(context.filesDir, "debug_log.txt")

        // 上次崩溃落盘的日志回读进缓冲，别让现场丢在文件里没人看。
        crashFile?.takeIf { it.exists() && it.length() > 0 }?.let { file ->
            runCatching {
                buffer.restore(file.readLines().dropLastWhile { it.isBlank() })
            }.onSuccess {
                runCatching { file.delete() }
            }
        }

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            buffer.record('C', "CRASH", "未捕获异常 thread=${thread.name}", throwable)
            runCatching { crashFile?.writeText(buffer.dump(header)) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** 在 Application 里 plant，与 DebugTree 并存。 */
    fun tree(): Timber.Tree = RecordingTree(buffer)

    fun snapshot(): List<String> = buffer.snapshot()

    fun dumpText(): String = buffer.dump(header)

    /** 把当前日志写到 filesDir/logs/ 下的新文件（时间戳命名），供 FileProvider 分享。 */
    fun dumpToFile(context: Context): File {
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        val file = File(dir, "debug_log_${System.currentTimeMillis()}.txt")
        file.writeText(dumpText())
        return file
    }

    fun clear() {
        buffer.clear()
        runCatching { crashFile?.delete() }
    }
}
