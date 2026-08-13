package com.arkdo.android.services.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL

/**
 * 网络可达性检测器
 *
 * 检测 linux.do 是否可访问，用于判断是否需要引导用户配置 Private DNS
 */
object NetworkAccessibilityChecker {

    private const val TAG = "[ArkDO][NetworkChecker]"
    private const val TEST_URL = "https://linux.do/srv/status"
    private const val TIMEOUT_MS = 5000

    /**
     * 检测 linux.do 是否可访问
     *
     * @return true 表示可访问，false 表示被墙或网络问题
     */
    suspend fun canAccessLinuxDo(): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL(TEST_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "ArkDO-Android")

            val responseCode = connection.responseCode
            connection.disconnect()

            val accessible = responseCode in 200..399
            Timber.d("$TAG canAccessLinuxDo: $accessible (code=$responseCode)")
            accessible
        } catch (e: Exception) {
            Timber.w(e, "$TAG canAccessLinuxDo failed")
            false
        }
    }
}
