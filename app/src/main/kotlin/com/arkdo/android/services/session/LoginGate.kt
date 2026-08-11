package com.arkdo.android.services.session

import android.content.Context
import timber.log.Timber

// ==========================================================================
// 与 HarmonyOS 版 services/session/LoginGate.ets 逐字段对齐。
// 全局登录态镜像（SharedPreferences 持久化）+「请求登录」事件总线。
//
// 各写操作（点赞/评论/投票/收藏/通知已读等）在发网络请求前调 ensureLoggedIn()：
// 已登录放行；未登录广播一次请求 → RootTabPage 弹出登录页，并返回 false 让调用方就地拦截。
// 登录态真源仍是 RootTabPage 的会话检查，这里只做一份可全局读取的镜像。
// ==========================================================================

const val LOGIN_GATE_LOG_TAG = "[ArkDO][LoginGate]"
const val STORAGE_KEY_LOGGED_IN = "arkdoIsLoggedIn"

typealias RequireLoginListener = () -> Unit
typealias LoginStateListener = () -> Unit

object LoginGate {
    private val requireLoginListeners = mutableMapOf<Long, RequireLoginListener>()
    private var nextListenerId = 0L

    private val loginStateListeners = mutableMapOf<Long, LoginStateListener>()
    private var nextLoginStateListenerId = 0L

    /** 由 Application 在任何页面读取之前调用：把登录态镜像持久化（SharedPreferences）。 */
    fun initPersist(context: Context) {
        // 首次默认 false；此后每次 setLoggedIn 写盘。
        if (!prefs(context).contains(STORAGE_KEY_LOGGED_IN)) {
            prefs(context).edit().putBoolean(STORAGE_KEY_LOGGED_IN, false).apply()
        }
    }

    /** 由 RootTabPage 在 /session/current.json「成功」后同步写入。从非登录翻成登录时广播一次。 */
    fun setLoggedIn(context: Context, loggedIn: Boolean) {
        val wasLoggedIn = isLoggedIn(context)
        prefs(context).edit().putBoolean(STORAGE_KEY_LOGGED_IN, loggedIn).apply()
        if (loggedIn && !wasLoggedIn) {
            notifyBecameLoggedIn()
        }
    }

    fun isLoggedIn(context: Context): Boolean =
        prefs(context).getBoolean(STORAGE_KEY_LOGGED_IN, false)

    /** 写操作前置闸门：已登录返回 true 放行；未登录返回 false 并请求弹登录页。 */
    fun ensureLoggedIn(context: Context): Boolean {
        if (isLoggedIn(context)) return true
        Timber.i("%s write action blocked: not logged in, request login sheet", LOGIN_GATE_LOG_TAG)
        requestLogin()
        return false
    }

    fun requestLogin() {
        val snapshot = requireLoginListeners.values.toList()
        snapshot.forEach { runCatching { it() } }
    }

    fun subscribe(listener: RequireLoginListener): () -> Unit {
        val id = nextListenerId++
        requireLoginListeners[id] = listener
        return { requireLoginListeners.remove(id) }
    }

    /** 订阅「登录态翻真」：信号对齐 isLoggedIn 由 false→true 的那一刻。 */
    fun subscribeBecameLoggedIn(listener: LoginStateListener): () -> Unit {
        val id = nextLoginStateListenerId++
        loginStateListeners[id] = listener
        return { loginStateListeners.remove(id) }
    }

    private fun notifyBecameLoggedIn() {
        val snapshot = loginStateListeners.values.toList()
        snapshot.forEach { runCatching { it() } }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
}