package com.arkdo.android.services.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 文件日志器。
 *
 * 存在的理由：内存缓冲（[LogBuffer]）只在崩溃瞬间落盘一次，App 正常跑一天被系统
 * 回收的话这一天现场全丢。文件日志要撑住「用户脱机跑一天、第二天从设置里导出」
 * 这个场景，所以轮转不能算错 —— 阈值偏晚会让单文件涨到几 MB，偏早则归档被立刻
 * 覆盖掉。这些边界值全在这条测试里钉死。
 *
 * 时钟注入成固定值，时间前缀与轮转断言才稳定。
 */
class FileLoggerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val fixedClock = 1_725_500_000_000L

    private fun logDir(): File = File(temp.root, FileLogger.LOG_DIR_NAME)

    private fun currentFile(): File = File(logDir(), FileLogger.CURRENT_FILE_NAME)

    private fun archiveFile(index: Int): File = File(logDir(), "arkdo.$index.log")

    private fun newLogger(
        maxBytes: Long = FileLogger.DEFAULT_MAX_FILE_BYTES,
        archives: Int = FileLogger.DEFAULT_MAX_ARCHIVE_FILES,
        threshold: Int = FileLogger.DEFAULT_FLUSH_SIZE_THRESHOLD
    ) = FileLogger(
        logDir = File(temp.root, FileLogger.LOG_DIR_NAME),
        maxFileBytes = maxBytes,
        maxArchiveFiles = archives,
        flushSizeThreshold = threshold,
        clock = { fixedClock }
    )

    @Test
    fun `落盘的行带时间前缀与级别`() {
        val logger = newLogger()
        logger.append('I', "[ArkDO][Home] 列表加载完成")
        logger.flush()

        val text = currentFile().readText()
        assertTrue(text.contains("[ArkDO][Home] 列表加载完成"))
        assertTrue(
            "行首应为 MM-dd HH:mm:ss.SSS + 级别",
            text.lines().first().matches(Regex("\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} I .*"))
        )
    }

    @Test
    fun `攒够阈值自动刷盘`() {
        // 阈值调到 64 字节：一行 100 字符（加上前缀）必然越过它。
        val logger = newLogger(threshold = 64)
        logger.append('I', "x".repeat(100))

        assertTrue("越过阈值应自动落盘，不必显式 flush", currentFile().length() > 0)
    }

    @Test
    fun `错误级别立即刷盘`() {
        // 阈值调到 1MB：正常情况绝不会触发。ERROR 必须自己把数据推下去 ——
        // 错误之后经常紧跟崩溃，等 5 秒定时器等于放弃这一行。
        val logger = newLogger(threshold = 1024 * 1024)
        logger.append('E', "过盾失败")

        assertTrue(currentFile().length() > 0)
        assertEquals(0, logger.pendingBytes)
    }

    @Test
    fun `超过单文件上限时轮转到归档`() {
        val logger = newLogger(maxBytes = 200)
        logger.append('E', "a".repeat(300))
        assertFalse("第一行不该轮转，还没超限", archiveFile(1).exists())

        logger.append('E', "b".repeat(300))

        assertTrue("第二行写入前应先轮转", archiveFile(1).isFile)
        assertTrue(currentFile().isFile)
        // 总占用 = 当前 + 归档，不掺第三个数。
        assertEquals(currentFile().length() + archiveFile(1).length(), logger.totalSizeBytes())
    }

    @Test
    fun `归档超过上限时最老一份被丢弃`() {
        val logger = newLogger(maxBytes = 100, archives = 2)
        repeat(6) { index -> logger.append('E', "line$index".padEnd(150, 'x')) }

        assertTrue(archiveFile(1).isFile)
        assertTrue(archiveFile(2).isFile)
        assertFalse("归档只保留 2 份，第 3 份不该存在", archiveFile(3).exists())
        // 磁盘上永远只有 3 份：current + 2 归档。这条比「总字节数小于某值」更有约束力 ——
        // 轮转没删最老那份时这里会直接变成 4。
        assertEquals("当前 + 2 份归档", 3, logger.logFiles().size)
    }

    @Test
    fun `clear 连归档一起删掉`() {
        val logger = newLogger(maxBytes = 100)
        repeat(4) { index -> logger.append('E', "line$index".padEnd(150, 'x')) }
        assertTrue(currentFile().isFile)

        logger.clear()

        assertFalse(currentFile().exists())
        assertFalse(archiveFile(1).exists())
        assertFalse(archiveFile(2).exists())
        assertEquals(0L, logger.totalSizeBytes())
    }

    @Test
    fun `导出按旧到新的顺序拼合归档与当前`() {
        val dir = logDir().apply { mkdirs() }
        archiveFile(2).writeText("OLDEST\n")
        archiveFile(1).writeText("MIDDLE\n")
        currentFile().writeText("NEWEST\n")

        val logger = newLogger()
        val out = File(temp.root, "export.txt")

        assertTrue(logger.exportTo(out))
        assertEquals(listOf("OLDEST", "MIDDLE", "NEWEST"), out.readText().trim().lines())
    }

    @Test
    fun `某一份归档不可读时跳过它继续拼后面的`() {
        val dir = logDir().apply { mkdirs() }
        // 用目录占住归档位：读它会失败，模拟「某份日志损坏」。
        File(dir, "arkdo.2.log").mkdirs()
        archiveFile(1).writeText("MIDDLE\n")
        currentFile().writeText("NEWEST\n")

        val logger = newLogger()
        val out = File(temp.root, "export.txt")

        assertTrue("一份坏掉不该让整次导出交白卷", logger.exportTo(out))
        assertEquals(listOf("MIDDLE", "NEWEST"), out.readText().trim().lines())
    }

    @Test
    fun `未刷盘的内容算进总占用`() {
        val logger = newLogger(threshold = 1024 * 1024)
        logger.append('I', "只进缓冲的一行")

        assertTrue(logger.pendingBytes > 0)
        assertFalse("不该已经落盘", currentFile().exists())
        assertEquals(logger.pendingBytes.toLong(), logger.totalSizeBytes())
    }

    @Test
    fun `续写已有文件时按现有大小起算轮转阈值`() {
        logDir().mkdirs()
        // 预置一个已经很大的当前文件：下一次写入必须立刻轮转，而不是把 1MB 当作 0。
        currentFile().writeText("x".repeat(500))

        val logger = newLogger(maxBytes = 200)
        logger.append('E', "y".repeat(100))

        assertTrue(archiveFile(1).isFile)
    }

    @Test
    fun `utf8 长度按字节算而不是 UTF-16 码元`() {
        assertEquals(0, FileLogger.utf8Length(""))
        assertEquals(1, FileLogger.utf8Length("a"))
        // 汉字在 String.length 里是 1、UTF-8 里是 3 —— 用 length 会让 1MB 阈值迟到三倍。
        assertEquals(3, FileLogger.utf8Length("中"))
        assertEquals(6, FileLogger.utf8Length("中文"))
        assertEquals(4, FileLogger.utf8Length("a中"))
        // emoji 是代理对：两个 UTF-16 码元合成一个 4 字节序列。
        assertEquals(4, FileLogger.utf8Length("\uD83D\uDE00"))
        assertEquals(5, FileLogger.utf8Length("a\uD83D\uDE00"))
    }
}
