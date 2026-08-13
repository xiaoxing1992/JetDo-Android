package com.arkdo.android.services.network

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

private const val TAG = "[ArkDO][DoH]"
private const val PREFS_NAME = "doh_settings"

private val Context.dohDataStore by preferencesDataStore(name = PREFS_NAME)

/**
 * DoH (DNS over HTTPS) 管理器
 *
 * 功能：
 * - 使用 DoH 服务器解析 linux.do 域名，绕过 DNS 污染
 * - 对标 HarmonyOS 版本的 DohManager
 * - 支持自定义 DoH 服务器
 *
 * 使用的 DoH 服务器：
 * - 默认：edge.47258.xyz (与鸿蒙版本保持一致)
 * - 备用：arkdo.ddd.oaifree.com
 */
object DohService {
    private const val DEFAULT_DOH_URL = "https://edge.47258.xyz/linuxdo"
    private const val FALLBACK_DOH_URL = "https://arkdo.ddd.oaifree.com/query-dns"

    private val PREF_DOH_ENABLED = stringPreferencesKey("doh_enabled")
    private val PREF_DOH_SERVER = stringPreferencesKey("doh_server")

    private var dnsOverHttps: DnsOverHttps? = null
    private var isInitialized = false

    /**
     * 初始化 DoH 客户端
     *
     * 在 Application.onCreate() 中调用
     */
    suspend fun init(context: Context) {
        if (isInitialized) return

        val enabled = isEnabled(context)
        if (!enabled) {
            Log.i(TAG, "DoH disabled by user")
            return
        }

        val serverUrl = getServerUrl(context)
        Log.i(TAG, "Initializing DoH with server: $serverUrl")

        try {
            val bootstrapClient = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()

            dnsOverHttps = DnsOverHttps.Builder()
                .client(bootstrapClient)
                .url(serverUrl.toHttpUrl())
                .bootstrapDnsHosts(
                    // 使用公共 DNS 作为 bootstrap
                    InetAddress.getByName("8.8.8.8"),
                    InetAddress.getByName("1.1.1.1")
                )
                .build()

            isInitialized = true
            Log.i(TAG, "DoH initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize DoH: ${e.message}", e)
        }
    }

    /**
     * 通过 DoH 解析域名
     *
     * @param hostname 要解析的域名
     * @return 解析得到的 IP 地址列表
     */
    fun lookup(hostname: String): List<InetAddress> {
        if (!isInitialized || dnsOverHttps == null) {
            Log.w(TAG, "DoH not initialized, falling back to system DNS")
            return try {
                InetAddress.getAllByName(hostname).toList()
            } catch (e: UnknownHostException) {
                emptyList()
            }
        }

        return try {
            Log.d(TAG, "Looking up $hostname via DoH")
            val addresses = dnsOverHttps!!.lookup(hostname)
            Log.i(TAG, "DoH lookup for $hostname: ${addresses.joinToString()}")
            addresses
        } catch (e: Exception) {
            Log.e(TAG, "DoH lookup failed for $hostname: ${e.message}")
            // 降级到系统 DNS
            try {
                InetAddress.getAllByName(hostname).toList()
            } catch (e2: UnknownHostException) {
                emptyList()
            }
        }
    }

    /**
     * 检查 DoH 是否已启用
     */
    suspend fun isEnabled(context: Context): Boolean {
        return context.dohDataStore.data.map { prefs ->
            prefs[PREF_DOH_ENABLED]?.toBoolean() ?: true // 默认启用
        }.first()
    }

    /**
     * 设置 DoH 启用状态
     */
    suspend fun setEnabled(context: Context, enabled: Boolean) {
        context.dohDataStore.edit { prefs ->
            prefs[PREF_DOH_ENABLED] = enabled.toString()
        }
        if (enabled && !isInitialized) {
            init(context)
        }
    }

    /**
     * 获取当前 DoH 服务器 URL
     */
    suspend fun getServerUrl(context: Context): String {
        return context.dohDataStore.data.map { prefs ->
            prefs[PREF_DOH_SERVER] ?: DEFAULT_DOH_URL
        }.first()
    }

    /**
     * 设置 DoH 服务器 URL
     */
    suspend fun setServerUrl(context: Context, url: String) {
        context.dohDataStore.edit { prefs ->
            prefs[PREF_DOH_SERVER] = url
        }
        // 需要重新初始化
        isInitialized = false
        init(context)
    }
}

// HttpUrl 扩展
private fun String.toHttpUrl(): okhttp3.HttpUrl {
    return okhttp3.HttpUrl.Builder()
        .scheme(if (startsWith("https://")) "https" else "http")
        .host(substringAfter("://").substringBefore("/"))
        .apply {
            val path = substringAfter("://").substringAfter("/", "")
            if (path.isNotEmpty()) {
                addPathSegments(path)
            }
        }
        .build()
}
