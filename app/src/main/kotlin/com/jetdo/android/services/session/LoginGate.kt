package com.jetdo.android.services.session

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

const val LOGIN_GATE_LOG_TAG = "[JetDo][LoginGate]"
const val STORAGE_KEY_LOGGED_IN = "jetdoIsLoggedIn"
const val STORAGE_KEY_CURRENT_USER_ID = "jetdoCurrentUserId"

/** 信任等级「还没查到」。区别于 TL0(已确认是新用户),两者要走不同分支。 */
const val TRUST_LEVEL_UNKNOWN = -1

typealias RequireLoginListener = () -> Unit
typealias LoginStateListener = () -> Unit

object LoginGate {
    private val requireLoginListeners = mutableMapOf<Long, RequireLoginListener>()
    private var nextListenerId = 0L

    // 只驻内存,不落盘 —— 信任等级是按站点的,持久化会让切站后的首个会话检查回来前用错等级。
    private var trustLevelValue: Int = TRUST_LEVEL_UNKNOWN
    private var staffValue: Boolean = false

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
    fun setLoggedIn(context: Context, loggedIn: Boolean, userId: Long? = null) {
        val wasLoggedIn = isLoggedIn(context)
        prefs(context).edit()
            .putBoolean(STORAGE_KEY_LOGGED_IN, loggedIn)
            .apply {
                if (userId != null) putLong(STORAGE_KEY_CURRENT_USER_ID, userId)
            }
            .apply()
        if (loggedIn && !wasLoggedIn) {
            notifyBecameLoggedIn()
        }
    }

    /** 当前登录用户 id；未登录为 0（对齐鸿蒙 RootTabPage 下发的 currentUserId 语义）。 */
    fun currentUserId(context: Context): Long =
        prefs(context).getLong(STORAGE_KEY_CURRENT_USER_ID, 0L)

    fun isLoggedIn(context: Context): Boolean =
        prefs(context).getBoolean(STORAGE_KEY_LOGGED_IN, false)

    /** 同步当前用户的信任等级与管理身份。与 setLoggedIn 同一处调用（会话检查成功后）。 */
    fun setUserPrivileges(trustLevel: Int, staff: Boolean) {
        trustLevelValue = trustLevel
        staffValue = staff
    }

    /** 登出/切站时复位成「未知」。 */
    fun clearUserPrivileges() {
        trustLevelValue = TRUST_LEVEL_UNKNOWN
        staffValue = false
    }

    /** 当前信任等级；-1 表示还没查到。 */
    fun trustLevel(): Int = trustLevelValue

    fun isStaff(): Boolean = staffValue

    /**
     * 当前用户能否发附件（对齐鸿蒙 LoginGate.canUploadAttachment）。
     *
     * Discourse 默认 `newuser_max_attachments = 0`，即 TL0 一个附件都发不出去 —— 编辑器据此
     * 决定要不要收附件入口，免得让新用户点开选择器、选完传上去才被拒。
     *
     * 身份未知时**放行**：trustLevel 初值是「未知」，只有会话检查落地才写真；对版主来说
     * 首次检查回来之前它会一直是未知。此时若按 TL0 处理，附件入口会凭空消失一段时间。
     */
    fun canUploadAttachment(newuserMaxAttachments: Int): Boolean {
        if (staffValue) return true
        val level = trustLevelValue
        if (level == TRUST_LEVEL_UNKNOWN) return true
        return level > 0 || newuserMaxAttachments > 0
    }

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