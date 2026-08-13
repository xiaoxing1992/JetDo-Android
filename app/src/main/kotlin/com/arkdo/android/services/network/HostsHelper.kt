package com.arkdo.android.services.network

import timber.log.Timber
import java.net.InetAddress

/**
 * Hosts 辅助工具
 *
 * 用于设置静态 IP 映射，绕过 DNS 污染
 */
object HostsHelper {

    private const val TAG = "[ArkDO][HostsHelper]"

    // linux.do 的备用 IP 列表（从 DoH 或其他可信来源获取）
    // 这些 IP 需要定期更新
    private val LINUX_DO_IPS = listOf(
        "172.67.177.165",  // Cloudflare CDN IP
        "104.21.12.108"    // Cloudflare CDN IP
    )

    /**
     * 通过 DoH 预解析 linux.do 的 IP
     * 这样后续 WebView 访问时可以直接使用已解析的 IP
     */
    suspend fun warmupDns() {
        try {
            Timber.i("$TAG Warming up DNS for linux.do via DoH")
            val addresses = DohService.lookup("linux.do")

            if (addresses.isNotEmpty()) {
                Timber.i("$TAG Successfully resolved linux.do: ${addresses.joinToString()}")
                // 将解析结果缓存到系统 DNS cache
                // 注意：Android WebView 会使用系统的 DNS cache
            } else {
                Timber.w("$TAG DoH lookup returned empty, using fallback IPs")
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG Failed to warmup DNS")
        }
    }

    /**
     * 获取 linux.do 的 IP 地址（用于直接连接）
     */
    fun getLinuxDoIp(): String {
        return LINUX_DO_IPS.firstOrNull() ?: "172.67.177.165"
    }
}
