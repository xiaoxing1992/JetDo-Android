package com.arkdo.android.di

import android.content.Context
import com.arkdo.android.core.data.search.SearchAuthGate
import com.arkdo.android.feature.search.SearchHistoryStore
import com.arkdo.android.services.session.LoginGate
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import org.json.JSONArray
import timber.log.Timber

private const val TAG = "[JetDo][Search]"

/** 与 FeedPrefs / DohManager 共用同一个 preferences 库,只是键不同。 */
private const val PREF_STORE_NAME = "app_settings"
private const val PREF_KEY_HISTORY = "search_history"

@Module
@InstallIn(SingletonComponent::class)
object SearchModule {

    @Provides
    @Singleton
    fun provideSearchHistoryStore(@ApplicationContext context: Context): SearchHistoryStore =
        PreferencesSearchHistoryStore(context)

    /**
     * 搜索登录守卫：已登录放行；未登录请求根层弹登录半窗并返回 false，
     * 搜索层就地拦截（linux.do 搜索接口要求登录，未登录必 403）。
     */
    @Provides
    @Singleton
    fun provideSearchAuthGate(@ApplicationContext context: Context): SearchAuthGate =
        SearchAuthGate { LoginGate.ensureLoggedIn(context) }
}

/**
 * 搜索历史的 SharedPreferences 实现。
 *
 * 纯本地、不涉网;去重后置顶、封顶 [SearchHistoryStore.MAX_HISTORY] 条。
 * 解析失败一律当空历史处理 —— 历史丢了不影响功能,不该因此崩。
 */
private class PreferencesSearchHistoryStore(context: Context) : SearchHistoryStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREF_STORE_NAME, Context.MODE_PRIVATE)

    override fun load(): List<String> {
        val raw = prefs.getString(PREF_KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val term = array.optString(i)
                    if (term.isNotEmpty() && term !in this) add(term)
                }
            }
        }.getOrElse {
            Timber.e(it, "$TAG read search history failed")
            emptyList()
        }
    }

    override fun add(term: String): List<String> {
        val trimmed = term.trim()
        if (trimmed.isEmpty()) return load()
        val next = (listOf(trimmed) + load().filterNot { it == trimmed })
            .take(SearchHistoryStore.MAX_HISTORY)
        persist(next)
        return next
    }

    override fun remove(term: String): List<String> {
        val next = load().filterNot { it == term }
        persist(next)
        return next
    }

    override fun clear() {
        persist(emptyList())
    }

    private fun persist(terms: List<String>) {
        runCatching {
            prefs.edit().putString(PREF_KEY_HISTORY, JSONArray(terms).toString()).apply()
        }.onFailure {
            Timber.e(it, "$TAG write search history failed")
        }
    }
}