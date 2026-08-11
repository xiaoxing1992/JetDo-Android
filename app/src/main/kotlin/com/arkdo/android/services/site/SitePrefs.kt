package com.arkdo.android.services.site

import android.content.Context
import android.util.Log
import com.arkdo.android.data.models.SiteDef
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 用户站点偏好设置
 *
 * 用户站点的持久化读写。与 FeedPrefs 同款职责边界：本类只管存取与校验，
 * 增删改排序的数组组装在站点管理页里做，写回时整体 setSites。
 *
 * 不预置任何站点——站点全部由用户自己添加，App 只做容器。
 */
class SitePrefs private constructor() {

    companion object {
        private const val TAG = "SitePrefs"
        private const val PREF_STORE_NAME = "app_settings"
        private const val PREF_KEY_SITES = "user_sites"
        private const val PREF_KEY_ENTRY_ENABLED = "sites_entry_enabled"

        private val json = Json { ignoreUnknownKeys = true }

        private fun getStore(context: Context) =
            context.getSharedPreferences(PREF_STORE_NAME, Context.MODE_PRIVATE)

        /**
         * 抽屉「我的站点」区是否显示（设置页开关，默认开）
         */
        fun isEntryEnabled(context: Context): Boolean {
            return try {
                val store = getStore(context)
                store.getBoolean(PREF_KEY_ENTRY_ENABLED, true)
            } catch (e: Exception) {
                Log.e(TAG, "read entry flag failed", e)
                true
            }
        }

        /**
         * 设置抽屉「我的站点」区显示开关
         */
        fun setEntryEnabled(context: Context, enabled: Boolean) {
            try {
                val store = getStore(context)
                store.edit()
                    .putBoolean(PREF_KEY_ENTRY_ENABLED, enabled)
                    .apply()
            } catch (e: Exception) {
                Log.e(TAG, "write entry flag failed", e)
            }
        }

        /**
         * 读全量站点（有序；非法项与重复 id 被剔除）
         */
        fun getSites(context: Context): List<SiteDef> {
            return try {
                val store = getStore(context)
                val raw = store.getString(PREF_KEY_SITES, "") ?: ""

                if (raw.isEmpty()) {
                    return emptyList()
                }

                val parsed = json.decodeFromString<List<SiteDef>>(raw)
                val valid = mutableListOf<SiteDef>()
                val seenIds = mutableSetOf<String>()

                for (site in parsed) {
                    val validSite = parseSite(site)
                    if (validSite != null && !seenIds.contains(validSite.id)) {
                        // 图标现查现填：favicon 由 WEB 页在用户打开站点时异步落盘
                        val iconUri = SiteIconStore.uriOf(context, validSite.id)
                        valid.add(validSite.copy(iconUri = iconUri))
                        seenIds.add(validSite.id)
                    }
                }

                valid
            } catch (e: Exception) {
                Log.e(TAG, "read sites failed", e)
                emptyList()
            }
        }

        /**
         * 写全量站点。调用方须保证「已显示在前、未显示在后」，数组顺序即抽屉顺序。
         */
        fun setSites(context: Context, sites: List<SiteDef>) {
            try {
                val store = getStore(context)
                store.edit()
                    .putString(PREF_KEY_SITES, json.encodeToString(sites))
                    .apply()
            } catch (e: Exception) {
                Log.e(TAG, "write sites failed", e)
            }
        }

        /**
         * 抽屉要渲染的站点：入口开关开 + 站点自身 showInDrawer，按存储顺序
         */
        fun drawerSites(context: Context): List<SiteDef> {
            if (!isEntryEnabled(context)) {
                return emptyList()
            }
            return getSites(context).filter { it.showInDrawer }
        }

        /**
         * 新站点 id。时间戳 + 随机后缀：同一毫秒内连加两个站点也不撞。
         */
        fun newId(): String {
            val random = (Math.random() * 100000).toInt()
            return "s${System.currentTimeMillis()}$random"
        }

        // ==================== URL 处理 ====================

        /**
         * 补全裸域名为 https:// 并去首尾空白
         */
        fun normalizeUrl(input: String): String {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) {
                return ""
            }
            val lower = trimmed.lowercase()
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                return trimmed
            }
            return "https://$trimmed"
        }

        /**
         * 粗校验：必须 http(s) 且 host 带点
         */
        fun isValidUrl(url: String): Boolean {
            val lower = url.trim().lowercase()
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                return false
            }
            val host = hostOf(url)
            return host.isNotEmpty() && host.contains(".")
        }

        /**
         * 取 host（不含协议/路径/端口）
         */
        fun hostOf(url: String): String {
            val trimmed = url.trim()
            val schemeEnd = trimmed.indexOf("://")
            if (schemeEnd < 0) {
                return ""
            }

            var rest = trimmed.substring(schemeEnd + 3)

            val slash = rest.indexOf('/')
            if (slash >= 0) {
                rest = rest.substring(0, slash)
            }

            val colon = rest.indexOf(':')
            if (colon >= 0) {
                rest = rest.substring(0, colon)
            }

            return rest
        }

        // ==================== 内部 ====================

        /**
         * 逐字段校验：id/url 不合法即整条丢弃；name 空则用域名兜底
         */
        private fun parseSite(raw: SiteDef): SiteDef? {
            if (raw.id.isEmpty()) {
                return null
            }
            if (!isValidUrl(raw.url)) {
                return null
            }

            val name = if (raw.name.isNotEmpty()) {
                raw.name
            } else {
                hostOf(raw.url)
            }

            // iconUri 一律留空，由 getSites 查文件系统回填
            return SiteDef(
                id = raw.id,
                name = name,
                url = raw.url,
                showInDrawer = raw.showInDrawer,
                iconUri = ""
            )
        }
    }
}
