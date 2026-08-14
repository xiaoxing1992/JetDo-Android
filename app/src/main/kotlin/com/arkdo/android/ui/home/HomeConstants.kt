package com.arkdo.android.ui.home

import androidx.compose.ui.unit.dp

/**
 * 首页常量定义 - 1:1 对齐鸿蒙版 HomePage.ets
 *
 * 所有数值与鸿蒙版保持一致，确保交互行为和视觉效果完全对齐
 */
object HomeConstants {

    // ==================== 侧边抽屉 ====================

    /** 抽屉宽度 */
    const val DRAWER_WIDTH = 288

    /** 抽屉边缘触发区域宽度（右边缘可拖拽区域） */
    const val DRAWER_EDGE_WIDTH = 56

    /** 抽屉提交阈值比例（拖动超过此比例后松手会自动展开） */
    const val DRAWER_COMMIT_RATIO = 0.4f

    /** 抽屉提交速度阈值（px/s，快速滑动超过此速度会触发展开） */
    const val DRAWER_COMMIT_VELOCITY = 800f

    /** 抽屉展开/收起动画时长（ms） */
    const val DRAWER_COMMIT_MS = 280L

    /** 抽屉回弹动画时长（ms，拖动后松手但未达到阈值时的回弹） */
    const val DRAWER_REBOUND_MS = 220L

    // ==================== 智能 FAB ====================

    /** FAB 切换到"回顶部"的滚动阈值（屏幕高度倍数，下滑 1.5 屏后显示"回顶部"） */
    const val FAB_TOP_SHOW_RATIO = 1.5f

    /** FAB 切换回"发帖"的滚动阈值（屏幕高度倍数，回到 1.2 屏内变回"发帖"） */
    const val FAB_TOP_HIDE_RATIO = 1.2f

    /** 滚动到顶部动画时长（ms） */
    const val SCROLL_TOP_MS = 460L

    // ==================== 实时新话题提醒 ====================

    /** 新话题提示条高度 */
    val INCOMING_PROMPT_HEIGHT = 44.dp

    /** 新话题提示条进入动画时长（ms） */
    const val INCOMING_PROMPT_ENTER_MS = 240L

    /** 新话题提示条退出动画时长（ms） */
    const val INCOMING_PROMPT_EXIT_MS = 180L

    /** 新话题插入动画时长（ms，新增条目淡入下滑 + 旧条目整体下移） */
    const val INCOMING_INSERT_MS = 360L

    /** 新话题高亮持续时长（ms，置顶后背景色闪烁时长） */
    const val INCOMING_HIGHLIGHT_DURATION_MS = 2000L

    /** 新话题高亮淡出时长（ms） */
    const val INCOMING_HIGHLIGHT_FADE_MS = 600L

    // ==================== 轮询间隔 ====================

    /** 新话题轮询间隔（ms，首页激活时每 30 秒检查一次新话题） */
    const val INCOMING_POLL_INTERVAL_MS = 30_000L

    /** 新话题轮询首次延迟（ms，首次进入首页后延迟 5 秒开始轮询） */
    const val INCOMING_POLL_INITIAL_DELAY_MS = 5_000L

    // ==================== 分页加载 ====================

    /** 每页话题数量 */
    const val PAGE_SIZE = 30

    /** 触发预加载的剩余条目数（滚动到倒数第 5 条时触发加载下一页） */
    const val PRELOAD_THRESHOLD = 5

    // ==================== 下拉刷新 ====================

    /** 下拉刷新触发距离 */
    val REFRESH_TRIGGER_DISTANCE = 80.dp

    /** 下拉刷新指示器大小 */
    val REFRESH_INDICATOR_SIZE = 48.dp

    // ==================== 手势操作 ====================

    /** 左滑拉出个人中心的触发区域宽度（屏幕左边缘） */
    const val PROFILE_SWIPE_EDGE_WIDTH = 48

    /** 左滑拉出个人中心的提交阈值比例 */
    const val PROFILE_SWIPE_COMMIT_RATIO = 0.5f

    /** 左滑拉出个人中心的提交速度阈值（px/s） */
    const val PROFILE_SWIPE_COMMIT_VELOCITY = 1000f

    // ==================== 版本公告 ====================

    /** 版本公告弹窗 Z 轴层级 */
    const val ANNOUNCEMENT_DIALOG_Z_INDEX = 100f

    /** 版本公告弹窗宽度（dp） */
    val ANNOUNCEMENT_DIALOG_WIDTH = 320.dp

    /** 版本公告弹窗最大高度（屏幕高度比例） */
    const val ANNOUNCEMENT_DIALOG_MAX_HEIGHT_RATIO = 0.7f

    // ==================== 发帖浮层 ====================

    /** 发帖浮层进入动画时长（ms） */
    const val COMPOSER_ENTER_MS = 300L

    /** 发帖浮层退出动画时长（ms） */
    const val COMPOSER_EXIT_MS = 250L

    /** 发帖浮层最小高度（屏幕高度比例，全屏显示但预留状态栏） */
    const val COMPOSER_MIN_HEIGHT_RATIO = 0.92f
}
