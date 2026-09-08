package com.arkdo.android.ui.utils

import com.arkdo.android.data.models.UserProfileDetail

// ==========================================================================
// 个人资料展示格式化，逐行对齐鸿蒙 common/utils/ProfileFormat.ets。
// 「我的」页与「他人主页」共用这份格式规则。
// ==========================================================================

/** Discourse 信任等级中文名（0-4）。user.title 缺省时的兜底展示。 */
val TRUST_LEVEL_NAMES = listOf("新用户", "基本用户", "成员", "活跃用户", "领导者")

/** 计数缩写：1000 以下原样，1000-9999 保留一位小数（1.2k），万以上取整（12k）。 */
fun formatCount(value: Int): String {
    if (value < 1000) return "$value"
    if (value < 10000) {
        val tenths = value / 100
        val whole = tenths / 10
        val frac = tenths % 10
        return if (frac == 0) "${whole}k" else "${whole}.${frac}k"
    }
    return "${value / 1000}k"
}

/** 阅读时长：不足 1 小时按分钟（至少 1m），否则按小时。 */
fun formatTimeRead(seconds: Int): String {
    if (seconds < 3600) return "${maxOf(1, (seconds + 30) / 60)}m"
    return "${(seconds + 1800) / 3600}h"
}

/** 只用信任等级名，形如「TL3 活跃用户」。用于页面别处已展示过头衔的场合。 */
fun trustLabelOf(detail: UserProfileDetail?): String {
    val level = detail?.trustLevel ?: return ""
    val levelName = trustLevelNameOf(level)
    return if (levelName.isNotEmpty()) "TL$level $levelName" else "TL$level"
}

/** 头衔优先，缺省回落到等级名。用于头衔没有独立展示位的场合。 */
fun trustLabelWithTitleOf(detail: UserProfileDetail?): String {
    val level = detail?.trustLevel ?: return ""
    val title = detail.title?.takeIf { it.isNotEmpty() } ?: ""
    val name = if (title.isNotEmpty()) title else trustLevelNameOf(level)
    return if (name.isNotEmpty()) "TL$level $name" else "TL$level"
}

private fun trustLevelNameOf(level: Int): String =
    if (level in TRUST_LEVEL_NAMES.indices) TRUST_LEVEL_NAMES[level] else ""

/** 注册至今天数。ISO 时间戳只取日期段做 UTC 归一。 */
fun daysSince(iso: String?): Int {
    val parts = isoDateParts(iso) ?: return 0
    val then = java.time.Instant.parse("${parts[0]}-${parts[1].toString().padStart(2, '0')}-${parts[2].toString().padStart(2, '0')}T00:00:00Z").toEpochMilli()
    val days = ((System.currentTimeMillis() - then) / 86400000L).toInt()
    return if (days > 0) days else 1
}

/** 注册日期，形如「2024年1月18日」。 */
fun formatJoinDate(iso: String?): String {
    val parts = isoDateParts(iso) ?: return ""
    return "${parts[0]}年${parts[1]}月${parts[2]}日"
}

private fun isoDateParts(iso: String?): List<Int>? {
    if (iso.isNullOrEmpty()) return null
    val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})").find(iso) ?: return null
    return listOf(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
}
