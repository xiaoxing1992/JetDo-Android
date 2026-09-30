package com.arkdo.android.core.model

/**
 * 网页版侧栏里「自选」的那批版块与标签（`/session/current.json` 的
 * `current_user.sidebar_category_ids` / `current_user.sidebar_tags`）。
 *
 * 首页筛选抽屉据此把「我的版块 / 我的标签」置顶，省得在几十个版块、几百个标签里翻。
 * 未登录、或登录了但从没在网页版侧栏点过「编辑」，两者都是空 —— 此时抽屉退回
 * 只给「所有版块 / 热门标签」，不做任何替代展示。
 *
 * `tags` 复用 [HomeTag]（name/id/slug 三个字段与 sidebars 完全一致）。鸿蒙侧另立了一份
 * `SidebarTag`，是因为 ArkTS 的 models 里 Topic 单向 import User、反向再引会成环；
 * Kotlin 这边两者同在 core:model，没有这个问题。
 */
data class SidebarPrefs(
    val categoryIds: List<Long> = emptyList(),
    val tags: List<HomeTag> = emptyList()
) {
    val isEmpty: Boolean get() = categoryIds.isEmpty() && tags.isEmpty()

    companion object {
        val Empty = SidebarPrefs()
    }
}
