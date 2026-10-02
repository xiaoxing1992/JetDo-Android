package com.arkdo.android.services.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class BlockedImageHostsTest {

    @Before
    fun setUp() {
        BlockedImageHosts.reset()
    }

    @Test
    fun hostOf_parses_host_case_insensitively() {
        assertEquals("cdn.example.com", BlockedImageHosts.hostOf("https://CDN.example.com/a/b.gif"))
        assertEquals("cdn.example.com", BlockedImageHosts.hostOf("https://cdn.example.com:8443/a.gif"))
        assertEquals("cdn.example.com", BlockedImageHosts.hostOf("https://user@cdn.example.com/a.gif"))
        assertEquals("cdn.example.com", BlockedImageHosts.hostOf("https://cdn.example.com"))
        // 非法 URL / 相对路径取不到 host。
        assertEquals("", BlockedImageHosts.hostOf("/user_avatar/a.gif"))
        assertEquals("", BlockedImageHosts.hostOf("not-a-url"))
    }

    @Test
    fun host_marked_blocked_only_after_threshold_distinct_urls() {
        val a = "https://dead.host/1.gif"
        val b = "https://dead.host/2.gif"

        BlockedImageHosts.markNetworkFailure(a)
        assertFalse("单张图失败不构成域名证据", BlockedImageHosts.isBlocked(a))

        // 同一张图重复失败不累计（对齐鸿蒙 Set 去重）。
        BlockedImageHosts.markNetworkFailure(a)
        assertFalse(BlockedImageHosts.isBlocked(a))

        BlockedImageHosts.markNetworkFailure(b)
        assertTrue("两张不同的图网络层失败 → 域名判定走不通", BlockedImageHosts.isBlocked(a))
        assertTrue(BlockedImageHosts.isBlocked(b))
        assertTrue(BlockedImageHosts.hasAny())
    }

    @Test
    fun other_hosts_are_not_blocked_together() {
        BlockedImageHosts.markNetworkFailure("https://dead.host/1.gif")
        BlockedImageHosts.markNetworkFailure("https://dead.host/2.gif")
        assertFalse(BlockedImageHosts.isBlocked("https://alive.host/1.gif"))
    }

    @Test
    fun network_level_failures_record_evidence_but_http_errors_do_not() {
        assertTrue(isNetworkLevelFailure(UnknownHostException("dns")))
        assertTrue(isNetworkLevelFailure(SocketTimeoutException("timeout")))
        assertTrue(isNetworkLevelFailure(IOException("connection reset", null)))
        // cause 链上的网络层失败也算。
        assertTrue(isNetworkLevelFailure(RuntimeException("wrap", SocketTimeoutException("t"))))

        // 服务端明确应答（404/403…）：这张图真没了，不是域名被封。
        assertFalse(isNetworkLevelFailure(coil3.network.HttpException(coil3.network.NetworkResponse())))
        assertFalse(isNetworkLevelFailure(null))
    }

    @Test
    fun blocked_state_is_in_memory_only() {
        BlockedImageHosts.markNetworkFailure("https://dead.host/1.gif")
        BlockedImageHosts.markNetworkFailure("https://dead.host/2.gif")
        assertTrue(BlockedImageHosts.isBlocked("https://dead.host/3.gif"))

        BlockedImageHosts.reset()
        assertFalse("冷启动复位：重启即给网络一次重新证明自己的机会", BlockedImageHosts.hasAny())
    }
}
