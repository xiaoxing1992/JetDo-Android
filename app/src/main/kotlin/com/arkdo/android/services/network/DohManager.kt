package com.arkdo.android.services.network

import com.arkdo.android.R
import com.arkdo.android.core.data.AppContextHolder
import android.content.Context
import com.arkdo.android.common.constants.StorageKeys
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

// ==========================================================================
// 与 HarmonyOS 版 services/network/DohManager.ets 逐字段对齐。
// 集中管理 DoH 配置：preferences 持久化服务器列表/当前选择/开关/模式。
//
// Android 差异：WebviewController.setHttpDns 无等价物（Android 只有系统级 Private DNS/DoT，
// 无公开 API 给 WebView 指定 DoH 服务器）。改由 DohService 持有解析器，
// WebViewDohInterceptor 在 shouldInterceptRequest 里逐请求接管，效果等价但只覆盖 GET，
// 覆盖边界见 WebViewDohInterceptor 的注释。
// ==========================================================================

/** 内置 DoH：不可删除，首次启动种子数据，也是删除 active 后的兜底。 */
const val DOH_EDGE_ID = "builtin-edge"
private val DOH_EDGE_NAME: String get() = AppContextHolder.getString(R.string.site_linuxdo_premium)
private const val DOH_EDGE_URL = "https://edge.47258.xyz/linuxdo"

/** 保留原 id 兼容旧安装；现降为第二预设。 */
const val DOH_BUILTIN_ID = "builtin-default"
private val DOH_BUILTIN_NAME: String get() = AppContextHolder.getString(R.string.settings_system_default)
private const val DOH_BUILTIN_URL = "https://arkdo.ddd.oaifree.com/query-dns"

/** DoH 解析模式：AUTO 失败回退系统 DNS；SECURE_ONLY 只用 DoH。 */
enum class DohResolveMode(val value: String) {
    AUTO("auto"),
    SECURE_ONLY("secure_only")
}

/** 一条 DoH 服务器预设。 */
@Serializable
data class DohServer(
    val id: String,
    val name: String,
    val url: String,
    val builtin: Boolean
)

object DohManager {

    private val json = Json { ignoreUnknownKeys = true }

    private fun prefs(context: Context) =
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    /** 应用启动时调用：首次无记录则写入内置默认并选中。 */
    fun init(context: Context) {
        try {
            val store = prefs(context)
            val rawServers = store.getString(StorageKeys.PREFS_DOH_SERVERS, "") ?: ""
            if (rawServers.isEmpty()) {
                // 首次启动：种子两条内置项（edge 置顶默认激活 + arkdo 备用）+ 默认开启。
                val seed = builtinServers()
                store.edit()
                    .putString(StorageKeys.PREFS_DOH_SERVERS, json.encodeToString(ListSerializer(), seed))
                    .putString(StorageKeys.PREFS_DOH_ACTIVE, DOH_EDGE_ID)
                    .putBoolean(StorageKeys.PREFS_DOH_ENABLED, true)
                    .putString(StorageKeys.PREFS_DOH_MODE, DohResolveMode.SECURE_ONLY.value)
                    .apply()
            }
        } catch (e: Exception) {
            Timber.e(e, "$LOG_TAG init store failed")
        }
        applyActive(context)
    }

    /** 读取全部服务器预设（内置项恒在列表首位）。 */
    fun getServers(context: Context): List<DohServer> {
        return try {
            val raw = prefs(context).getString(StorageKeys.PREFS_DOH_SERVERS, "") ?: ""
            val list = parseServers(raw)
            if (list.isNotEmpty()) list else builtinServers()
        } catch (e: Exception) {
            Timber.e(e, "$LOG_TAG read servers failed")
            builtinServers()
        }
    }

    fun getActiveId(context: Context): String =
        prefs(context).getString(StorageKeys.PREFS_DOH_ACTIVE, DOH_EDGE_ID) ?: DOH_EDGE_ID

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(StorageKeys.PREFS_DOH_ENABLED, true)

    fun getMode(context: Context): DohResolveMode {
        val raw = prefs(context).getString(StorageKeys.PREFS_DOH_MODE, DohResolveMode.SECURE_ONLY.value)
        return if (raw == DohResolveMode.AUTO.value) DohResolveMode.AUTO else DohResolveMode.SECURE_ONLY
    }

    /** 便捷：返回当前选中的服务器（找不到则回退内置默认）。 */
    fun getActiveServer(context: Context): DohServer {
        val activeId = getActiveId(context)
        val servers = getServers(context)
        return servers.firstOrNull { it.id == activeId } ?: servers.first()
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(StorageKeys.PREFS_DOH_ENABLED, enabled).apply()
        applyActive(context)
    }

    fun setActive(context: Context, id: String) {
        prefs(context).edit().putString(StorageKeys.PREFS_DOH_ACTIVE, id).apply()
        applyActive(context)
    }

    fun setMode(context: Context, mode: DohResolveMode) {
        prefs(context).edit().putString(StorageKeys.PREFS_DOH_MODE, mode.value).apply()
        applyActive(context)
    }

    /** 新增自定义服务器；id 由持久化自增计数器生成。 */
    fun addServer(context: Context, name: String, url: String): DohServer? {
        return try {
            val store = prefs(context)
            val seq = store.getInt(StorageKeys.PREFS_DOH_SEQ, 0) + 1
            val server = DohServer(id = "custom-$seq", name = name, url = url, builtin = false)
            val servers = getServers(context) + server
            store.edit()
                .putInt(StorageKeys.PREFS_DOH_SEQ, seq)
                .putString(StorageKeys.PREFS_DOH_SERVERS, json.encodeToString(ListSerializer(), servers))
                .apply()
            server
        } catch (e: Exception) {
            Timber.e(e, "$LOG_TAG add server failed")
            null
        }
    }

    /** 删除自定义服务器；内置项拒删。删 active 回退内置默认。 */
    fun removeServer(context: Context, id: String) {
        if (id == DOH_BUILTIN_ID || id == DOH_EDGE_ID) return
        try {
            val store = prefs(context)
            val servers = getServers(context).filter { it.id != id }
            store.edit().putString(StorageKeys.PREFS_DOH_SERVERS, json.encodeToString(ListSerializer(), servers)).apply()
            if (getActiveId(context) == id) {
                store.edit().putString(StorageKeys.PREFS_DOH_ACTIVE, DOH_EDGE_ID).apply()
            }
        } catch (e: Exception) {
            Timber.e(e, "$LOG_TAG remove server failed")
        }
        applyActive(context)
    }

    /**
     * 把当前选择下发到网络引擎。
     *
     * 鸿蒙版这里调 webview.WebviewController.setHttpDns，ArkWeb 引擎层全局生效。
     * Android 没有等价 API，改为配置 [DohService]，由 [WebViewDohInterceptor] 在
     * shouldInterceptRequest 里逐请求接管才真正用上，覆盖范围见那个文件的说明。
     */
    fun applyActive(context: Context) {
        val enabled = isEnabled(context)
        val active = getActiveServer(context)
        if (!enabled || active.url.isEmpty()) {
            DohService.configure("", secureOnly = false)
            Timber.i("%s DoH OFF", LOG_TAG)
            return
        }
        val mode = getMode(context)
        DohService.configure(active.url, secureOnly = mode == DohResolveMode.SECURE_ONLY)
        Timber.i("%s DoH ON mode=%s name=%s", LOG_TAG, mode, active.name)
    }

    /** 过盾期间临时借用 AUTO：只改引擎，不写 preferences，用户的持久化设置不变。 */
    fun applyTransientMode(context: Context, mode: DohResolveMode) {
        val enabled = isEnabled(context)
        val active = getActiveServer(context)
        if (!enabled || active.url.isEmpty()) return
        DohService.configure(active.url, secureOnly = mode == DohResolveMode.SECURE_ONLY)
        Timber.i("%s DoH transient mode=%s name=%s", LOG_TAG, mode, active.name)
    }

    // ==================== 私有辅助 ====================

    private fun builtinServers(): List<DohServer> = listOf(
        DohServer(DOH_EDGE_ID, DOH_EDGE_NAME, DOH_EDGE_URL, builtin = true),
        DohServer(DOH_BUILTIN_ID, DOH_BUILTIN_NAME, DOH_BUILTIN_URL, builtin = true)
    )

    /** 解析持久化 JSON：逐项校验字段类型，内置项缺失时补回并置顶。 */
    private fun parseServers(raw: String): List<DohServer> {
        if (raw.isEmpty()) return emptyList()
        val result = runCatching {
            json.decodeFromString(ListSerializer(), raw)
        }.getOrElse {
            Timber.e("$LOG_TAG parse servers failed: %s", it.message)
            return builtinServers()
        }
        // 补回缺失的内置项：先补 arkdo 置顶，再补 edge 置顶，最终顺序 edge → arkdo → 其它。
        var final = result
        if (final.none { it.id == DOH_BUILTIN_ID }) {
            final = listOf(DohServer(DOH_BUILTIN_ID, DOH_BUILTIN_NAME, DOH_BUILTIN_URL, builtin = true)) + final
        }
        if (final.none { it.id == DOH_EDGE_ID }) {
            final = listOf(DohServer(DOH_EDGE_ID, DOH_EDGE_NAME, DOH_EDGE_URL, builtin = true)) + final
        }
        return final
    }

    private fun ListSerializer(): kotlinx.serialization.KSerializer<List<DohServer>> =
        kotlinx.serialization.builtins.ListSerializer(DohServer.serializer())

    private const val LOG_TAG = "[JetDo][Doh]"
}
