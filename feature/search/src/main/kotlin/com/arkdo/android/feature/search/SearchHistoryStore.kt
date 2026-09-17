package com.arkdo.android.feature.search

/**
 * 搜索历史的存储口。
 *
 * 定义在 feature 层是为了让 ViewModel 可测 —— 线上实现走 app 层的 SharedPreferences
 * (与 FeedPrefs / DohManager 共用 `app_settings` 库),测试用内存实现。
 */
interface SearchHistoryStore {
    /** 读取历史(最新在前)。 */
    fun load(): List<String>

    /** 记一次搜索:去重后置顶、封顶 [MAX_HISTORY],返回更新后的列表。 */
    fun add(term: String): List<String>

    /** 删一条历史,返回更新后的列表。 */
    fun remove(term: String): List<String>

    /** 清空历史。 */
    fun clear()

    companion object {
        /** 历史封顶条数:超出丢弃最旧的。 */
        const val MAX_HISTORY = 10
    }
}

/** 内存实现,供测试与预览使用。 */
class InMemorySearchHistoryStore(initial: List<String> = emptyList()) : SearchHistoryStore {
    private var terms: List<String> = initial

    override fun load(): List<String> = terms

    override fun add(term: String): List<String> {
        val trimmed = term.trim()
        if (trimmed.isEmpty()) return terms
        terms = (listOf(trimmed) + terms.filterNot { it == trimmed })
            .take(SearchHistoryStore.MAX_HISTORY)
        return terms
    }

    override fun remove(term: String): List<String> {
        terms = terms.filterNot { it == term }
        return terms
    }

    override fun clear() {
        terms = emptyList()
    }
}