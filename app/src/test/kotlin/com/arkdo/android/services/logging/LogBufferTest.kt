package com.arkdo.android.services.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 应用内日志缓冲。
 *
 * 这条测试存在的理由：手机没数据线连不了 Android Studio，「加载更多」闪退后
 * 进程直接没了，没有任何现场。日志必须在 App 内自己留底 —— 环形缓冲存运行期
 * 日志，崩溃时落盘，下次启动回读，日志页一键复制导出。
 * 缓冲与格式化抽成纯类，JVM 上直接测。
 */
class LogBufferTest {

    /** 固定时钟，时间断言才稳定。 */
    private val buffer = LogBuffer(maxLines = 4, now = { 1_725_500_000_000L })

    @Test
    fun `日志行包含时间 级别 标签 消息`() {
        buffer.record('I', "TopicService", "getTopicListPage: page=2")
        val line = buffer.snapshot().single()

        assertTrue(line.contains("[I/TopicService]"))
        assertTrue(line.contains("getTopicListPage: page=2"))
        assertTrue(line.matches(Regex("\\[\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\].*")))
    }

    @Test
    fun `异常堆栈完整记录`() {
        val boom = IllegalStateException("boom")

        buffer.record('E', "Home", "load more failed", boom)
        val text = buffer.snapshot().joinToString("\n")

        assertTrue(text.contains("load more failed"))
        assertTrue(text.contains("java.lang.IllegalStateException: boom"))
        assertTrue(text.contains("at ")) // 至少一行堆栈帧
    }

    @Test
    fun `超过容量淘汰最旧的`() {
        repeat(6) { buffer.record('D', "T", "line-$it") }

        val lines = buffer.snapshot()
        assertEquals(4, lines.size)
        assertTrue("最旧两条应被淘汰", lines.none { it.contains("line-0") })
        assertTrue(lines.none { it.contains("line-1") })
        assertTrue(lines.first().contains("line-2"))
        assertTrue(lines.last().contains("line-5"))
    }

    @Test
    fun `清空后为空`() {
        buffer.record('D', "T", "x")
        buffer.clear()

        assertEquals(0, buffer.size)
        assertEquals(emptyList<String>(), buffer.snapshot())
    }

    @Test
    fun `dump 带头部信息且按行拼接`() {
        buffer.record('I', "A", "hello")
        val dump = buffer.dump("app=1.0 device=Pixel")

        assertTrue(dump.startsWith("app=1.0 device=Pixel"))
        assertTrue(dump.contains("[I/A] hello"))
    }

    @Test
    fun `回读的上次崩溃日志排在本次日志之前`() {
        buffer.restore(listOf("[旧日志1]", "[旧日志2]"))
        buffer.record('I', "New", "本次启动的日志")

        val lines = buffer.snapshot()
        assertEquals(LogBuffer.RESTORE_SEPARATOR, lines[0])
        assertEquals("[旧日志1]", lines[1])
        assertEquals("[旧日志2]", lines[2])
        assertTrue(lines.last().contains("[I/New]"))
        assertTrue(lines.last().contains("本次启动的日志"))
    }

    @Test
    fun `回读也受容量约束`() {
        val small = LogBuffer(maxLines = 3, now = { 0L })
        small.restore(listOf("[旧1]", "[旧2]", "[旧3]", "[旧4]"))

        val lines = small.snapshot()
        assertEquals(3, lines.size)
        // 环形淘汰最旧的：分隔线和旧1、旧2 依次出局，保住最新的现场。
        assertEquals("[旧2]", lines[0])
        assertEquals("[旧3]", lines[1])
        assertEquals("[旧4]", lines[2])
    }
}
