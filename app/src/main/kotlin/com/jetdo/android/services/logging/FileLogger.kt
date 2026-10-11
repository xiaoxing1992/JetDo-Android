package com.jetdo.android.services.logging

import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 文件日志器：把日志持续落盘到应用沙箱，支持大小轮转与合并导出。
 *
 * 背景：此前只有 [DebugLogStore] 的内存环形缓冲 —— 它只在**崩溃瞬间**落盘一次，
 * App 正常跑一天再被杀（系统回收 / 手动清理），这一天的现场全部蒸发。用户脱机
 * 跑一天后导出日志给开发者分析（过盾失败、列表加载不出来等）根本无从谈起。
 *
 * 与鸿蒙 `services/logging/FileLogger.ets` 对齐的部分：
 *  · 目录 `app_logs/`，当前文件 `jetdo.log`，归档 `jetdo.1.log` / `jetdo.2.log`
 *  · 单文件封顶 1MB，归档保留 2 个（current + 2 = 3 份，总占用 ≤3MB）
 *  · 攒够 64KB 或定时 5 秒刷盘一次，避免高峰期（列表刷新）频繁同步 IO
 *  · 导出按「归档(旧) → 当前(新)」拼成一份，单份读失败只跳过它
 *
 * 与鸿蒙不同的地方（Android 侧必须处理）：
 *  · **线程安全**。鸿蒙的 Log 门面固定跑在 UI 线程；Android 的 Timber 会被任意线程调用，
 *    所以对缓冲与文件的每一次读写都在同一把锁里。flush() 是同步写盘且持锁，
 *    与鸿蒙一致 —— 批量后单次数据量可控，且 5 秒一次不值得为它引入异步写队列。
 *  · **ERROR 级别立即刷盘**。鸿蒙要等 5 秒定时器；Android 上崩溃是常态，
 *    错误那条必须当场落盘，否则进程没了它就永远在缓冲区里。
 *  · **时钟可注入**，时间戳与轮转逻辑才能在 JVM 单测里断言。
 *
 * 该类本身不依赖 Android（只用 java.io / java.time），因此可以直接在 JVM 上测。
 * 与 Android 的胶水（SharedPreferences 开关、定时器、崩溃钩子、Timber.Tree）
 * 全部在 [FileLoggerDaemon] 里。
 */
class FileLogger(
    private val logDir: File,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    private val maxArchiveFiles: Int = DEFAULT_MAX_ARCHIVE_FILES,
    private val flushSizeThreshold: Int = DEFAULT_FLUSH_SIZE_THRESHOLD,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onError: (String, Throwable) -> Unit = { _, _ -> }
) {

    private val lock = Any()
    private val buffer = StringBuilder()
    private var bufferBytes = 0
    private var currentFileBytes = 0L
    private val ready: Boolean

    init {
        ready = runCatching {
            if (!logDir.isDirectory) logDir.mkdirs()
            val current = currentFile()
            // 续写已有文件：先量出它现在多大，轮转阈值才有正确的起点。
            currentFileBytes = if (current.isFile) current.length() else 0L
            logDir.isDirectory
        }.onFailure { onError("init", it) }.getOrDefault(false)
    }

    /** 当前文件已占字节数（不含尚未刷盘的缓冲）。 */
    val currentBytes: Long get() = synchronized(lock) { currentFileBytes }

    /** 缓冲里还没落盘的字节数。设置页「日志大小」要把这两者一起显示。 */
    val pendingBytes: Int get() = synchronized(lock) { bufferBytes }

    /**
     * 记一行。攒够 [flushSizeThreshold] 或级别为 E/C 时立即刷盘。
     *
     * E/C 立即刷盘是刻意偏离鸿蒙实现的一点：ERROR 之后经常紧跟崩溃，
     * 等 5 秒定时器等于放弃这一行。
     */
    fun append(level: Char, message: String) {
        if (!ready) return
        val line = "${timestamp()} $level $message\n"
        synchronized(lock) {
            buffer.append(line)
            bufferBytes += utf8Length(line)
            if (bufferBytes >= flushSizeThreshold || level == 'E' || level == 'C') {
                flush()
            }
        }
    }

    /**
     * 同步刷盘。退后台 / 崩溃 / 导出前必须调用。
     *
     * 整个写盘过程持锁：并发的 append 必须等这次写完，否则「判断是否该轮转」
     * 与实际写入之间会被插进别的数据，轮转阈值算错、两份写入交错。
     */
    fun flush() {
        synchronized(lock) {
            if (!ready || buffer.isEmpty()) return
            val chunk = buffer.toString()
            val chunkBytes = bufferBytes
            buffer.setLength(0)
            bufferBytes = 0
            try {
                val target = currentFile()
                if (currentFileBytes + chunkBytes > maxFileBytes) {
                    rotateLocked()
                    currentFileBytes = 0L
                }
                target.appendText(chunk, Charsets.UTF_8)
                // 用实际长度回填而不是累加 chunkBytes：文件被外部删除/截断时计数能自愈。
                currentFileBytes = target.length()
            } catch (error: Exception) {
                onError("flush", error)
            }
        }
    }

    /**
     * 把归档与当前日志按时间顺序（旧 → 新）合并写进 [outFile]。
     *
     * 单份日志读失败不毁掉整次导出：记一条错误后跳过它，继续拼下一份。
     * 缺一段日志远好过一份都拿不到 —— 导出本就是出问题时才用的，
     * 这时候更不能因为某个归档损坏就交白卷。
     *
     * @return true 表示成功写出文件（内容可能不完整）
     */
    fun exportTo(outFile: File): Boolean {
        flush()
        synchronized(lock) {
            if (!ready) return false
            return try {
                outFile.parentFile?.mkdirs()
                outFile.outputStream().buffered().use { out ->
                    for (index in maxArchiveFiles downTo 1) appendFileTo(out, archiveFile(index))
                    appendFileTo(out, currentFile())
                }
                true
            } catch (error: Exception) {
                onError("export", error)
                false
            }
        }
    }

    /** 清空缓冲与磁盘上的当前/归档文件。 */
    fun clear() {
        synchronized(lock) {
            buffer.setLength(0)
            bufferBytes = 0
            if (!ready) return
            try {
                // 当前日志确实删掉了才把计数归零：还在却清零的话轮转阈值会算错。
                var currentRemoved = false
                val current = currentFile()
                if (current.isFile) currentRemoved = current.delete()
                for (index in 1..maxArchiveFiles) archiveFile(index).delete()
                if (currentRemoved) currentFileBytes = 0L
            } catch (error: Exception) {
                onError("clear", error)
            }
        }
    }

    /** 磁盘占用总量（当前 + 归档 + 尚未刷盘的缓冲）。 */
    fun totalSizeBytes(): Long = synchronized(lock) {
        var total = bufferBytes.toLong()
        if (ready) {
            total += currentFile().takeIf { it.isFile }?.length() ?: 0L
            for (index in 1..maxArchiveFiles) {
                total += archiveFile(index).takeIf { it.isFile }?.length() ?: 0L
            }
        }
        total
    }

    /** 磁盘上的日志文件清单，旧 → 新。导出与大小统计共用。 */
    fun logFiles(): List<File> = synchronized(lock) {
        (maxArchiveFiles downTo 1).map { archiveFile(it) }.filter { it.isFile } +
            listOf(currentFile()).filter { it.isFile }
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    /** current → jetdo.1.log → jetdo.2.log；超出的最老一份被丢弃。调用方须持锁。 */
    private fun rotateLocked() {
        try {
            archiveFile(maxArchiveFiles).delete()
            for (index in maxArchiveFiles - 1 downTo 1) {
                val from = archiveFile(index)
                if (from.isFile) from.renameTo(archiveFile(index + 1))
            }
            val current = currentFile()
            if (current.isFile) current.renameTo(archiveFile(1))
        } catch (error: Exception) {
            onError("rotate", error)
        }
    }

    private fun appendFileTo(out: OutputStream, source: File) {
        try {
            if (!source.isFile) return
            source.inputStream().buffered().use { input -> input.copyTo(out, COPY_BUFFER_BYTES) }
        } catch (error: Exception) {
            onError("append ${source.name}", error)
        }
    }

    private fun currentFile(): File = File(logDir, CURRENT_FILE_NAME)

    private fun archiveFile(index: Int): File = File(logDir, "jetdo.$index.log")

    /** 与 logcat / hdc hilog 抓取一致的 MM-dd HH:mm:ss.SSS 前缀，便于沿用既有分析方法。 */
    private fun timestamp(): String {
        val instant = Instant.ofEpochMilli(clock())
        return TIME_FORMATTER.format(LocalDateTime.ofInstant(instant, ZoneId.systemDefault()))
    }

    companion object {
        const val LOG_DIR_NAME = "app_logs"
        const val CURRENT_FILE_NAME = "jetdo.log"
        const val EXPORT_FILE_NAME = "jetdo-log-export.txt"

        /** 单文件封顶 1MB，归档保留 2 个 → 总占用 ≤3MB。 */
        const val DEFAULT_MAX_FILE_BYTES = 1L * 1024 * 1024
        const val DEFAULT_MAX_ARCHIVE_FILES = 2

        /** 攒够 64KB 立即刷盘。 */
        const val DEFAULT_FLUSH_SIZE_THRESHOLD = 64 * 1024

        /** 或者每 5 秒刷一次（[FileLoggerDaemon] 起定时器）。 */
        const val DEFAULT_FLUSH_INTERVAL_MS = 5_000L

        const val COPY_BUFFER_BYTES = 256 * 1024

        private val TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS", Locale.US)

        /**
         * 字符串写成 UTF-8 后的字节数。
         *
         * 必须自己算，不能用 `String.length` —— 那是 UTF-16 码元数，而落盘的是 UTF-8。
         * 本项目日志几乎全是中文（一个汉字 length 记 1、实际 3 字节），用 length 的话：
         *  · 64KB 的刷盘门槛实际要攒到约 190KB 才触发，单次卡顿也是三倍；
         *  · 1MB 的轮转阈值同样偏晚，「单文件 ≤1MB、总占用 ≤3MB」的承诺兑现不了；
         *  · 设置页显示的日志占用偏小。
         *
         * 代理对（emoji）按 4 字节算：遇到高代理时直接记 4 并跳过下一个码元。
         */
        internal fun utf8Length(text: String): Int {
            var bytes = 0
            var index = 0
            while (index < text.length) {
                val code = text[index].code
                bytes += when {
                    code < 0x80 -> 1
                    code < 0x800 -> 2
                    code in 0xD800..0xDBFF && index + 1 < text.length -> {
                        index++
                        4
                    }
                    else -> 3
                }
                index++
            }
            return bytes
        }
    }
}
