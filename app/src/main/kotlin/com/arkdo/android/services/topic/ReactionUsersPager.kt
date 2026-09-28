package com.arkdo.android.services.topic

import com.arkdo.android.data.models.ReactionUser
import com.arkdo.android.data.models.ReactionUsersPage

/**
 * 「谁回应了」弹层的分页累积纯逻辑,对齐鸿蒙 ReactionUsersPager.ets:
 * 按 `${user.id}:${reaction}` 去重累积;totalRows 取跨页最大值;
 * hasMore = 本页非空 且 已累积人数 < totalRows(空页强制停止,防 total_rows 与分页
 * 短暂不一致时无限触底)。
 */
object ReactionUsersPager {

    fun keyOf(user: ReactionUser): String = "${user.user.id}:${user.reaction}"

    fun merge(
        existing: List<ReactionUser>,
        page: ReactionUsersPage,
        existingTotalRows: Int = 0
    ): MergeResult {
        val seen = existing.mapTo(HashSet()) { keyOf(it) }
        val merged = existing.toMutableList()
        for (user in page.users) {
            if (seen.add(keyOf(user))) merged.add(user)
        }
        // totalRows 跨页取最大:服务端该值恒定,取最大避免某页异常偏小时已读进度回缩。
        val total = maxOf(page.totalRows, existingTotalRows, 0)
        val hasMore = page.users.isNotEmpty() && merged.size < total
        return MergeResult(users = merged, totalRows = total, hasMore = hasMore)
    }

    data class MergeResult(
        val users: List<ReactionUser>,
        val totalRows: Int,
        val hasMore: Boolean
    )
}
