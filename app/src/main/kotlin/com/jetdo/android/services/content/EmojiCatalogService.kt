package com.jetdo.android.services.content

import android.content.Context
import com.jetdo.android.common.constants.AppConstants
import com.jetdo.android.services.network.JsonNetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

// ==========================================================================
// 表情字典的获取与缓存，对齐鸿蒙 services/content/EmojiCatalogService.ets。
//
// 数据源是 Discourse 标准接口 `GET /emojis.json`。响应结构与原先内置 assets 里那份
// 284KB 的导出**完全一致**，因此解析逻辑一行未改（见 EmojiCatalogRegistry），
// 只是把来源从"打进包里"换成"按站点拉取"。
//
// ## 三级取数
// 1. **内存**：已加载且是当前站点的，直接用。
// 2. **磁盘缓存**（`filesDir/emoji_catalog/<host>.json`）：冷启动/切站时立即可用，不等网络。
//    表情选择器与作者状态表情因此不会空着等一个来回。
// 3. **网络**：缓存缺失时立刻拉；缓存存在但超过一天，先用缓存再后台刷新（不阻塞 UI）。
//
// 拉不到就保持空表——消费方本就按"查不到"降级（getEmojiUrl 返回 null、调用方跳过渲染），
// 不会出错，只是选择器暂时没内容。
// ==========================================================================

private const val LOG_TAG = "[JetDo][EmojiCatalog]"
private const val CACHE_DIR_NAME = "emoji_catalog"

/** 表情表不常变，一天刷一次足够。过期只影响"新加的自定义表情多久能看到"，不影响可用性。 */
private const val REFRESH_INTERVAL_MS = 24 * 60 * 60 * 1000L

/** 站点返回的表情表异常大时不落盘，免得一个坏站点把磁盘撑爆。linux.do 那份约 284KB，留足余量。 */
private const val MAX_CATALOG_BYTES = 4 * 1024 * 1024

object EmojiCatalogService {

    /** 内存里这份表属于哪个站点。与当前站点不一致即视为失效——切站后必须重取。 */
    @Volatile
    private var loadedHost: String = ""

    /** 同一站点的并发请求只发一次（首页与表情选择器可能同时触发）。 */
    private val mutex = Mutex()

    /**
     * 确保当前站点的表情表可用。可安全重复调用（命中内存即返回）。
     *
     * 调用方**不必 await**：它内部自己管并发与错误，失败只是留一张空表。
     * 用 `scope.launch { ensureLoaded(...) }` 触发即可，不要放在会被读的同步路径上。
     */
    suspend fun ensureLoaded(context: Context, bridge: JsonNetworkClient) {
        val host = currentHost()
        if (host.isEmpty()) return
        // 快速路径：已就绪时连锁都不取（这是绝大多数调用的形态）。
        if (loadedHost == host && emojiCount() > 0) return
        mutex.withLock {
            if (loadedHost == host && emojiCount() > 0) return@withLock
            load(context.applicationContext, bridge, host)
        }
    }

    /** 切站：丢掉内存里上一个站点的表。新站的表由 ensureLoaded 重新取。 */
    fun onSiteChanged() {
        if (loadedHost.isNotEmpty()) {
            Timber.i("%s reset (was %s)", LOG_TAG, loadedHost)
        }
        loadedHost = ""
        resetEmojiUrls()
    }

    /** 当前站点 host。多站点（P0-6）落地后这里改读站点配置，其余代码不动。 */
    private fun currentHost(): String =
        AppConstants.BASE_URL.substringAfter("://").substringBefore('/')

    private suspend fun load(context: Context, bridge: JsonNetworkClient, host: String) {
        val cached = readCache(context, host)
        if (cached.isNotEmpty()) {
            val count = loadEmojiUrlsFromJson(cached)
            loadedHost = host
            Timber.i("%s loaded from cache host=%s count=%d", LOG_TAG, host, count)
            // 缓存够新就到此为止；过期则后台刷新，不让用户等。
            if (!isCacheStale(context, host)) return
            fetchAndStore(context, bridge, host, background = true)
            return
        }
        fetchAndStore(context, bridge, host, background = false)
    }

    /** background = true 时是纯后台刷新：失败不影响已在用的缓存，也不改 loadedHost。 */
    private suspend fun fetchAndStore(
        context: Context,
        bridge: JsonNetworkClient,
        host: String,
        background: Boolean
    ) {
        val response = runCatching { bridge.getJson("/emojis.json") }.getOrElse {
            Timber.i(it, "%s fetch threw host=%s background=%s", LOG_TAG, host, background)
            return
        }
        // 站点可能在响应回来之前被切走——那这份数据属于上一个站，丢掉。
        if (currentHost() != host) {
            Timber.i("%s discard response: site changed (was %s)", LOG_TAG, host)
            return
        }
        val body = response.data
        if (!response.success || body.isNullOrEmpty()) {
            Timber.i(
                "%s fetch failed host=%s status=%d background=%s",
                LOG_TAG, host, response.statusCode, background
            )
            return
        }
        val count = loadEmojiUrlsFromJson(body)
        if (count == 0) {
            Timber.i("%s fetch parsed 0 entries host=%s, keep as-is", LOG_TAG, host)
            return
        }
        loadedHost = host
        writeCache(context, host, body)
        Timber.i("%s fetched host=%s count=%d background=%s", LOG_TAG, host, count, background)
    }

    // 文件名用 host。host 里只可能出现域名合法字符（字母数字点横线），不必转义。
    private fun cacheFile(context: Context, host: String): File =
        File(File(context.filesDir, CACHE_DIR_NAME), "$host.json")

    private suspend fun readCache(context: Context, host: String): String = withContext(Dispatchers.IO) {
        val file = cacheFile(context, host)
        runCatching { if (file.isFile) file.readText() else "" }.getOrElse {
            Timber.i("%s read cache failed host=%s: %s", LOG_TAG, host, it.message)
            ""
        }
    }

    private suspend fun isCacheStale(context: Context, host: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val file = cacheFile(context, host)
            System.currentTimeMillis() - file.lastModified() > REFRESH_INTERVAL_MS
        }.getOrElse {
            // 取不到时间就当过期：多刷一次的代价远小于永远用着旧表。
            true
        }
    }

    private suspend fun writeCache(context: Context, host: String, json: String) {
        if (json.length > MAX_CATALOG_BYTES) {
            Timber.i("%s skip cache host=%s: payload too large (%d)", LOG_TAG, host, json.length)
            return
        }
        withContext(Dispatchers.IO) {
            runCatching {
                val file = cacheFile(context, host)
                file.parentFile?.mkdirs()
                file.writeText(json)
            }.onFailure {
                Timber.i("%s write cache failed host=%s: %s", LOG_TAG, host, it.message)
            }
        }
    }
}
