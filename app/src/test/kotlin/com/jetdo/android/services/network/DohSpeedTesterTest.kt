package com.jetdo.android.services.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DoH 测速的报文层，对齐鸿蒙 DohSpeedTester.ets。
 * 只测纯计算部分（不触网）：wireformat 查询报文与 ANCOUNT 解析。
 */
class DohSpeedTesterTest {

    @Test
    fun `answerCount 短于 12 字节时返回 0`() {
        assertEquals(0, DohSpeedTester.answerCount(ByteArray(11)))
    }

    @Test
    fun `answerCount 读第 6 到 7 字节的大端值`() {
        val bytes = ByteArray(12)
        bytes[6] = 0x01
        bytes[7] = 0x02
        assertEquals(0x0102, DohSpeedTester.answerCount(bytes))
    }

    @Test
    fun `answerCount 高位字节不为 0 时不做符号扩展`() {
        val bytes = ByteArray(12)
        bytes[6] = 0xFF.toByte()
        bytes[7] = 0xFF.toByte()
        assertEquals(0xFFFF, DohSpeedTester.answerCount(bytes))
    }

    @Test
    fun `buildQuery 产出标准 A 记录查询报文`() {
        val packet = DohSpeedTester.buildQuery("a.b")
        // 12 字节头 + QNAME(1+1 + 1+1 + 结尾1) + QTYPE/QCLASS 4 字节 = 21。
        assertEquals(21, packet.size)
        // Header: ID=0，flags=0x0100(RD)，QDCOUNT=1。
        assertEquals(0, packet[0].toInt())
        assertEquals(0x01, packet[2].toInt())
        assertEquals(0x00, packet[3].toInt())
        assertEquals(0x01, packet[5].toInt())
        // QNAME: 长度前缀 + 字符 + 结尾 0。
        assertEquals(1, packet[12].toInt())
        assertEquals('a'.code, packet[13].toInt())
        assertEquals(1, packet[14].toInt())
        assertEquals('b'.code, packet[15].toInt())
        assertEquals(0, packet[16].toInt())
        // QTYPE=A(0x0001)，QCLASS=IN(0x0001) —— 只需低位字节非 0。
        assertEquals(0x01, packet[18].toInt())
        assertEquals(0x01, packet[20].toInt())
    }

    @Test
    fun `buildQueryParam 产出 base64url 且无 padding`() {
        val param = DohSpeedTester.buildQueryParam("example.com")
        assertTrue(param.isNotEmpty())
        // base64url 用 - 和 _ 替掉 + 和 /，且不带 = 填充，否则放进 URL query 会被转义。
        assertFalse("不应含 +", param.contains('+'))
        assertFalse("不应含 /", param.contains('/'))
        assertFalse("不应含 =", param.contains('='))
    }
}
