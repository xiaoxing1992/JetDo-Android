package com.jetdo.android.services.network

import android.content.Context
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import timber.log.Timber
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

private const val TAG = "[JetDo][DoH]"

/**
 * DoH 引擎。配置源是 [DohManager]（服务器列表 / 当前选择 / 开关 / 模式，与鸿蒙版同构），
 * 这里只负责把配置变成可用的 [Dns] 与 [OkHttpClient]。
 *
 * 鸿蒙版是 webview.WebviewController.setHttpDns 在 ArkWeb 引擎层开 DoH，一次下发全局生效。
 * Android WebView 没有等价 API，所以解析结果要靠 [WebViewDohInterceptor] 在
 * shouldInterceptRequest 里逐请求接管才能用上，见那个文件的说明。
 */
object DohService {

    @Volatile private var dnsOverHttps: DnsOverHttps? = null

    /** SECURE_ONLY：DoH 失败不回退系统 DNS，直接抛。对应鸿蒙 SecureDnsMode.SECURE_ONLY。 */
    @Volatile private var secureOnly: Boolean = false

    /** 当前生效的 DoH 服务器地址；空串表示 DoH 已关闭。 */
    @Volatile private var activeUrl: String = ""

    /** DoH 是否处于可用状态。拦截器据此决定要不要接管请求。 */
    val isActive: Boolean
        get() = dnsOverHttps != null

    /** 当前生效的 DoH 服务器地址，供日志与设置页展示。 */
    fun activeServerUrl(): String = activeUrl

    /**
     * 从 [DohManager] 读取配置并下发。可重复调用（切服务器/改模式时）。
     * 同步方法：内部只读 SharedPreferences 和构造对象，不发网络请求。
     */
    fun init(context: Context) {
        val enabled = DohManager.isEnabled(context)
        val server = DohManager.getActiveServer(context)
        if (!enabled || server.url.isEmpty()) {
            configure("", secureOnly = false)
            return
        }
        configure(server.url, DohManager.getMode(context) == DohResolveMode.SECURE_ONLY)
    }

    /**
     * 直接下发一份配置。[url] 为空表示关闭 DoH（此后拦截器不再接管，全部走系统 DNS）。
     */
    fun configure(url: String, secureOnly: Boolean) {
        this.secureOnly = secureOnly

        if (url.isEmpty()) {
            if (dnsOverHttps != null) Timber.i("$TAG 已关闭，回落系统 DNS")
            dnsOverHttps = null
            activeUrl = ""
            return
        }

        if (url == activeUrl && dnsOverHttps != null) {
            Timber.i("$TAG 配置未变，跳过重建 url=%s secureOnly=%b", url, secureOnly)
            return
        }

        runCatching {
            // bootstrap 客户端负责解析 DoH 服务器自己的域名，只能走系统 DNS，
            // 所以内置 IP 兜底：DoH 服务器域名本身被污染时仍能建连。
            val bootstrapClient = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()

            DnsOverHttps.Builder()
                .client(bootstrapClient)
                .url(url.toHttpUrl())
                .bootstrapDnsHosts(
                    InetAddress.getByName("8.8.8.8"),
                    InetAddress.getByName("1.1.1.1")
                )
                .build()
        }.onSuccess {
            dnsOverHttps = it
            activeUrl = url
            Timber.i("$TAG 已启用 url=%s secureOnly=%b", url, secureOnly)
        }.onFailure {
            dnsOverHttps = null
            activeUrl = ""
            Timber.e(it, "$TAG 初始化失败 url=%s", url)
        }
    }

    /**
     * 供 OkHttp 使用的解析器。
     *
     * 注意这只替换地址解析：TLS 的 SNI 与证书校验仍按原域名走，
     * 所以调用方不需要（也不应该）手工改写 Host 头。
     */
    val dns: Dns = Dns { hostname ->
        val doh = dnsOverHttps
        if (doh == null) {
            if (secureOnly) throw UnknownHostException("$TAG DoH 未就绪且为 SECURE_ONLY：$hostname")
            return@Dns Dns.SYSTEM.lookup(hostname)
        }
        try {
            doh.lookup(hostname).ifEmpty {
                throw UnknownHostException("$TAG DoH 解析结果为空：$hostname")
            }
        } catch (e: Exception) {
            if (secureOnly) {
                throw e as? UnknownHostException ?: UnknownHostException("$TAG DoH 解析失败：$hostname")
            }
            Timber.w("$TAG %s 解析失败，回退系统 DNS：%s", hostname, e.message)
            Dns.SYSTEM.lookup(hostname)
        }
    }

    /**
     * 拦截 WebView 请求时用的客户端。
     *
     * followRedirects=false：3xx 原样交回 WebView 自己跟，
     * 这样地址栏 / origin / 同源判定都还是 WebView 说了算，不会被我们悄悄改掉。
     */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(dns)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** 主动解析一个域名，仅用于诊断/日志。失败返回空列表。 */
    fun lookup(hostname: String): List<InetAddress> = runCatching { dns.lookup(hostname) }
        .onFailure { Timber.w("$TAG lookup(%s) 失败：%s", hostname, it.message) }
        .getOrDefault(emptyList())
}
