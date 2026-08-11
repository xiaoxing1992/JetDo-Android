package com.arkdo.android.services.site

import android.util.Log
import com.arkdo.android.data.models.SiteMetadata
import com.arkdo.android.data.models.TopicCategory
import com.arkdo.android.services.network.JsonNetworkClient
import com.arkdo.android.services.network.NetworkResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 站点分类索引
 *
 * 封装 /site.json 分类的按需加载与按 id 查找，供列表页/详情页/个人中心共用。
 *
 * 分类全站通用且几乎不变，因此结果缓存在应用级静态字段里：全 App 生命周期只请求一次
 * /site.json（该响应体积不小），后续页面进入直接命中缓存；并发首载共享同一个 in-flight
 * Promise，避免冷启动时首页/个人中心同时各拉一份。
 */
class CategoryIndex {

    companion object {
        private const val TAG = "CategoryIndex"

        private var cachedCategories: List<TopicCategory>? = null
        private val mutex = Mutex()
        private var inflight: Deferred<List<TopicCategory>?>? = null

        /**
         * 按需加载分类
         *
         * 未就绪/页面已有数据(alreadyLoaded)时返回 null 表示无需更新；
         * 失败也返回 null（不打断页面主流程，下次调用再试）。
         */
        suspend fun loadIfNeeded(
            bridge: JsonNetworkClient,
            ready: Boolean,
            alreadyLoaded: Boolean,
            logTag: String
        ): List<TopicCategory>? {
            if (!ready || alreadyLoaded) {
                return null
            }

            cachedCategories?.let { return it }

            return mutex.withLock {
                // Double-check after acquiring lock
                cachedCategories?.let { return it }

                if (inflight == null) {
                    inflight = CoroutineScope(Dispatchers.IO).async {
                        fetchCategories(bridge, logTag)
                    }
                }

                val loaded = inflight?.await()
                inflight = null
                loaded
            }
        }

        private suspend fun fetchCategories(
            bridge: JsonNetworkClient,
            logTag: String
        ): List<TopicCategory>? {
            val result: NetworkResult<SiteMetadata> = SiteService(bridge).getSiteMetadata()

            return if (result.success && result.data != null) {
                cachedCategories = result.data.categories
                Log.i(
                    TAG,
                    "$logTag site metadata loaded categories=${result.data.categories.size}"
                )
                cachedCategories
            } else {
                Log.i(TAG, "$logTag site metadata skipped status=${result.statusCode}")
                null
            }
        }

        /**
         * 仅供单测复位静态缓存
         */
        fun resetForTest() {
            cachedCategories = null
            inflight = null
        }

        /**
         * 按 id 查找分类
         */
        fun find(categories: List<TopicCategory>, categoryId: Long?): TopicCategory? {
            if (categoryId == null || categories.isEmpty()) {
                return null
            }
            return categories.find { it.id == categoryId }
        }

        /**
         * 查找父分类
         */
        fun findParent(categories: List<TopicCategory>, categoryId: Long?): TopicCategory? {
            val category = find(categories, categoryId) ?: return null
            val parentId = category.parentCategoryId ?: return null
            return find(categories, parentId)
        }

        // ========== 版块可见性与树 ==========

        /**
         * 判断是否为「未分类」版块
         *
         * Discourse 默认的「未分类」版块：不进任何版块列表。slug 与 name 任一命中即算
         * (linux.do 名为「未分类」，slug 可能为空或非标准)。
         */
        fun isUncategorized(category: TopicCategory): Boolean {
            val slug = (category.slug ?: "").trim().lowercase()
            if (slug == "uncategorized") {
                return true
            }
            val name = category.name.trim()
            return name == "未分类" || name.lowercase() == "uncategorized"
        }

        /**
         * 获取顶级版块
         *
         * 顶级版块：无父、且非「未分类」。
         */
        fun topLevelCategories(categories: List<TopicCategory>): List<TopicCategory> {
            return categories.filter {
                it.parentCategoryId == null && !isUncategorized(it)
            }
        }

        /**
         * 获取某父版块下的全部子版块
         */
        fun childrenOf(categories: List<TopicCategory>, parentId: Long): List<TopicCategory> {
            return categories.filter { it.parentCategoryId == parentId }
        }

        /**
         * 判断是否有子版块
         */
        fun hasChildren(categories: List<TopicCategory>, parentId: Long): Boolean {
            return categories.any { it.parentCategoryId == parentId }
        }
    }
}
