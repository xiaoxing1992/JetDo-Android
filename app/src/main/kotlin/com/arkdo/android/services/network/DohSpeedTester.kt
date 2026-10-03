package com.arkdo.android.services.network

import android.content.Context
import com.arkdo.android.core.data.AppContextHolder
import com.arkdo.android.R
import com.arkdo.android.common.constants.AppConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.Base64
import java.util.concurrent.TimeUnit

// ==========================================================================
// 与 HarmonyOS 版 services/network/DohSpeedTester.ets 同算法：
// 向 DoH 端点发 RFC 8484 的 wireformat 查询（GET ?dns=base64url），
// 计量往返延迟并校验应答里确有解析结果（ANCOUNT > 0）。
//
// 用 wireformat 而不是 JSON，因为自定义端点不保证支持 JSON。
// 探针域名取「当前论坛域名本身」：既测延迟，又顺带验证「能否正确解析目标站」——
// 拿一个固定域名去测，等于测了个跟用户无关的站。
// ==========================================================================

private const val TAG = "[ArkDO][DohTest]"

/** 未选定论坛时的兜底探针域名，免得拼出空 query 让整轮测速失败。 */
private const val FALLBACK_PROBE_DOMAIN = "example.com"

private const val PER_REQUEST_TIMEOUT_MS = 3000L
private const val SAMPLE_COUNT = 3

/** 单次测速结果：latencyMs = -1 表示全部失败/超时。 */
data class DohTestResult(
    val ok: Boolean,
    val latencyMs: Int,
    val message: String
)

object DohSpeedTester {

    /**
     * 独立 OkHttpClient —— 绝不能复用 [DohService.client]：
     * 它挂着 DoH 解析器，而 DoH 服务器自己的域名又得靠 DoH 去解析，会绕成死循环。
     * 这里走系统 DNS 建连，与 [DohService] 里的 bootstrap 客户端同理。
     */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(PER_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(PER_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    /** 多次采样取成功样本的最小值；第一次（含 TLS 握手）不计入。 */
    suspend fun test(url: String): DohTestResult = withContext(Dispatchers.IO) {
        if (!url.startsWith("https://")) {
            return@withContext DohTestResult(false, -1, AppContextHolder.getString(R.string.doh_invalid_address))
        }
        val query = buildQueryParam(probeDomain())
        val requestUrl = if (url.contains("?")) "$url&dns=$query" else "$url?dns=$query"

        val latencies = mutableListOf<Int>()
        var lastMessage = AppContextHolder.getString(R.string.common_request_failed)
        repeat(SAMPLE_COUNT) { index ->
            val sample = probeOnce(requestUrl)
            if (sample.ok) {
                // 丢弃第一次（冷连接偏高）；若只有第一次成功，就退而用之。
                if (index > 0 || latencies.isEmpty()) latencies.add(sample.latencyMs)
            } else {
                lastMessage = sample.message
            }
        }

        if (latencies.isEmpty()) {
            return@withContext DohTestResult(false, -1, lastMessage)
        }
        DohTestResult(ok = true, latencyMs = latencies.minOrNull() ?: -1, message = AppContextHolder.getString(R.string.doh_ok))
    }

    /** 单次探测：发 wireformat GET，校验 200 + 应答确有解析结果。 */
    private fun probeOnce(requestUrl: String): DohTestResult {
        val request = Request.Builder()
            .url(requestUrl)
            .header("Accept", "application/dns-message")
            .get()
            .build()
        val start = System.currentTimeMillis()
        return try {
            client.newCall(request).execute().use { response ->
                val elapsed = (System.currentTimeMillis() - start).toInt()
                if (!response.isSuccessful) {
                    return DohTestResult(false, -1, "HTTP ${response.code}")
                }
                val bytes = response.body.bytes()
                if (answerCount(bytes) <= 0) {
                    return DohTestResult(false, -1, AppContextHolder.getString(R.string.doh_no_result))
                }
                DohTestResult(true, elapsed, AppContextHolder.getString(R.string.doh_ok))
            }
        } catch (e: Exception) {
            Timber.i(e, "%s probe failed", TAG)
            // 超时 / DNS 失败 / TLS 失败统一归为「超时/不可达」，对用户是同一件事。
            DohTestResult(false, -1, AppContextHolder.getString(R.string.doh_unreachable))
        }
    }

    private fun probeDomain(): String {
        val host = hostOf(AppConstants.BASE_URL)
        return if (host.isNotEmpty()) host else FALLBACK_PROBE_DOMAIN
    }

    private fun hostOf(url: String): String =
        Regex("^https?://([^/?#]+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1).orEmpty()

    /**
     * 读 DNS 应答报文头第 6~7 字节（ANCOUNT）。> 0 表示服务器确实返回了解析记录。
     * 抽成 internal 纯函数，方便在 JVM 单测里直接验报文解析。
     */
    internal fun answerCount(bytes: ByteArray): Int {
        if (bytes.size < 12) return 0
        return ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
    }

    /** 构造 A 记录查询报文并转 base64url（无 padding），供 GET ?dns= 使用。 */
    internal fun buildQueryParam(domain: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(buildQuery(domain))

    /** 标准 DNS 查询报文：12 字节头（RD=1, QDCOUNT=1）+ 问题区（QNAME + A + IN）。 */
    internal fun buildQuery(domain: String): ByteArray {
        val labels = domain.split('.')
        var qnameLen = labels.sumOf { 1 + it.length } + 1 // 结尾的 0 长度字节
        val packet = ByteArray(12 + qnameLen + 4)
        // Header: ID=0, flags=0x0100(RD), QDCOUNT=1，其余为 0。
        packet[2] = 0x01
        packet[5] = 0x01
        var offset = 12
        labels.forEach { label ->
            packet[offset] = label.length.toByte()
            offset += 1
            label.forEach { ch ->
                packet[offset] = ch.code.toByte()
                offset += 1
            }
        }
        packet[offset] = 0x00
        offset += 1
        // QTYPE=A(0x0001), QCLASS=IN(0x0001)。
        packet[offset + 1] = 0x01
        packet[offset + 3] = 0x01
        return packet
    }
}
