package com.arkdo.android.services.site

import android.content.Context
import android.util.Log
import com.arkdo.android.data.models.FeedDef
import com.arkdo.android.data.models.FeedKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 内容入口偏好设置
 *
 * 集中管理「首页内容入口」配置：SharedPreferences 持久化用户在设置页勾选启用的可选入口及其排序。
 * 抽屉顶部按「最新 + 启用入口(有序)」渲染；设置页读写启用列表。
 */
class FeedPrefs private constructor() {

    companion object {
        private const val TAG = "FeedPrefs"
        private const val PREF_STORE_NAME = "app_settings"
        private const val PREF_KEY_ENABLED_ORDER = "feed_enabled_order"

        private val json = Json { ignoreUnknownKeys = true }

        // 「最新」恒在抽屉首位，常开、不可关不可拖，不入库
        private val LATEST_FEED = FeedDef(
            key = FeedKey.LATEST.value,
            label = "最新",
            requiresLogin = false
        )

        // 可在设置页勾选启用/排序的内置入口（顺序即设置页未启用区的默认排列）
        private val OPTIONAL_FEEDS = listOf(
            FeedDef(key = FeedKey.CREATED.value, label = "最新发布", requiresLogin = false),
            // 「热门」原先挂在 top 上是名实不符(top 是历史高分榜)，现已归位：hot=热门、top=排行榜
            FeedDef(key = FeedKey.HOT.value, label = "热门", requiresLogin = false),
            FeedDef(key = FeedKey.TOP.value, label = "排行榜", requiresLogin = false),
            FeedDef(key = FeedKey.UNREAD.value, label = "未读", requiresLogin = true),
            FeedDef(key = FeedKey.NEW.value, label = "未读新帖", requiresLogin = true)
        )

        /**
         * 「最新」入口（恒在首位）
         */
        fun latestFeed(): FeedDef = LATEST_FEED

        /**
         * 全部可选入口（未过滤启用状态，供设置页列出）
         */
        fun optionalFeeds(): List<FeedDef> = OPTIONAL_FEEDS.toList()

        /**
         * 按 key 找入口元数据（含 latest）
         */
        fun feedDef(key: String): FeedDef? {
            if (key == FeedKey.LATEST.value) {
                return LATEST_FEED
            }
            return OPTIONAL_FEEDS.find { it.key == key }
        }

        private fun getStore(context: Context) =
            context.getSharedPreferences(PREF_STORE_NAME, Context.MODE_PRIVATE)

        /**
         * 读取启用且有序的可选入口 key 列表（不含 latest；非法/未知 key 被剔除）
         */
        fun getEnabledKeys(context: Context): List<String> {
            return try {
                val store = getStore(context)
                val raw = store.getString(PREF_KEY_ENABLED_ORDER, "") ?: ""

                if (raw.isEmpty()) {
                    return emptyList()
                }

                val parsed = json.decodeFromString<List<String>>(raw)
                val valid = mutableListOf<String>()

                for (key in parsed) {
                    if (key != FeedKey.LATEST.value &&
                        feedDef(key) != null &&
                        !valid.contains(key)) {
                        valid.add(key)
                    }
                }

                valid
            } catch (e: Exception) {
                Log.e(TAG, "read enabled feeds failed", e)
                emptyList()
            }
        }

        /**
         * 写入启用且有序的可选入口 key 列表
         */
        fun setEnabledKeys(context: Context, keys: List<String>) {
            try {
                val store = getStore(context)
                store.edit()
                    .putString(PREF_KEY_ENABLED_ORDER, json.encodeToString(keys))
                    .apply()
            } catch (e: Exception) {
                Log.e(TAG, "write enabled feeds failed", e)
            }
        }
    }
}
