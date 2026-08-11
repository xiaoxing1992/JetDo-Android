package com.arkdo.android.common.constants

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

// ==========================================================================
// 与 HarmonyOS 版 common/constants/StorageKeys.ets 对齐。
//
// 原项目用 AppStorage（内存镜像）+ PersistentStorage（磁盘持久化）。
// Android 端：
//   * 根级浮层可见态 / tick 信号 → 内存 StateFlow（跨 Composable 用全局单例）
//   * 持久化 key（登录态等）→ DataStore（见 AppPreferences）
//
// 命名约定：
//   *_VISIBLE_KEY   —— 根级浮层可见态镜像（boolean）
//   *_BACK_TICK_KEY —— 返回键信号（number，单调自增）
//   *_TICK          —— 通用变更信号（number，单调自增）
// ==========================================================================
object StorageKeys {
    // 搜索 Flow（根级覆盖层）
    const val SEARCH_OVERLAY_VISIBLE_KEY = "searchOverlayVisible"
    const val SEARCH_OVERLAY_BACK_TICK_KEY = "searchOverlayBackTick"

    // 图片预览（根级，层级最高，返回键优先消费）
    const val IMAGE_VIEWER_VISIBLE_KEY = "imageViewerVisible"
    const val IMAGE_VIEWER_BACK_TICK_KEY = "imageViewerBackTick"

    // 首页发帖浮层
    const val HOME_COMPOSER_VISIBLE_KEY = "homeComposerVisible"
    const val HOME_COMPOSER_BACK_TICK_KEY = "homeComposerBackTick"

    // 帖子详情页的回帖浮层
    const val COMPOSER_OVERLAY_VISIBLE_KEY = "composerOverlayVisible"
    const val COMPOSER_OVERLAY_BACK_TICK_KEY = "composerOverlayBackTick"

    // 草稿页的续写浮层 + 刷新信号
    const val DRAFTS_COMPOSER_VISIBLE_KEY = "draftsComposerVisible"
    const val DRAFTS_COMPOSER_BACK_TICK_KEY = "draftsComposerBackTick"
    const val DRAFTS_REFRESH_TICK_KEY = "draftsRefreshTick"
    const val PROFILE_DRAFT_COUNT_KEY = "profileDraftCount"

    // 站点列表变更信号
    const val SITES_TICK = "sites_tick"

    // DoH 设置变更信号
    const val SETTINGS_DOH_TICK = "settings_doh_tick"

    // 会话与主题
    const val LOGGED_IN_STORAGE_KEY = "arkdoIsLoggedIn"
    const val APP_IS_DARK_STORAGE_KEY = "arkdoIsDark"
    const val APP_IS_WIDE_STORAGE_KEY = "arkdoIsWide"
    const val MATH_HOST_ACTIVE_KEY = "mathHostActive"

    // 各类开关 prefs key（DataStore，值见 AppPreferences）
    const val PREFS_REALTIME_TOPICS = "realtimeTopicsEnabled"
    const val PREFS_REALTIME_POSTS = "realtimePostsEnabled"
    const val PREFS_FAB_GLASS = "fabGlassEnabled"
    const val PREFS_ANIMATED_AVATAR = "animatedAvatarEnabled"
    const val PREFS_CHALLENGE_SHORTCUT = "challengeShortcutEnabled"
    const val PREFS_DOH_ENABLED = "dohEnabled"
    const val PREFS_DOH_ACTIVE = "dohActiveId"
    const val PREFS_DOH_MODE = "dohMode"
    const val PREFS_DOH_SERVERS = "dohServers"
    const val PREFS_DOH_SEQ = "dohIdSeq"
    const val PREFS_THEME_MODE = "themeMode"
    const val PREFS_FEED_ENABLED_ORDER = "feedEnabledOrder"
    const val PREFS_USER_SITES = "userSites"
    const val PREFS_SITES_ENTRY_ENABLED = "sitesEntryEnabled"
}

/**
 * 单调自增 tick 信号（原 bumpTick）。Android 端用全局 AtomicLong 或
 * 每个 key 一个 MutableStateFlow<Int>。这里提供最简单的全局自增器。
 */
object TickBump {
    private val ticks = ConcurrentHashMap<String, AtomicLong>()

    fun bump(key: String): Long = ticks.computeIfAbsent(key) { AtomicLong(0) }.incrementAndGet()

    fun value(key: String): Long = ticks[key]?.get() ?: 0L
}