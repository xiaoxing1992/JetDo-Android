package com.arkdo.android.core.model

import java.util.Calendar

/**
 * 相对时间格式化,1:1 对齐鸿蒙 utils/RelativeTime.ets。
 *
 * 输入 Discourse 返回的 UTC ISO 字符串(结尾带 Z),输出与网页 relativeAgeTiny 对齐的
 * 相对时间文案:刚刚 / N 分钟 / N 小时 / N 天,超出范围回退绝对日期。
 * 分档与取整刻意对齐 relativeAgeTiny(小时、天都用 Math.round),避免小时边界差 1。
 *
 * 原在 core:common(现摘除),落位 core:model:全部消费方(core:data /
 * feature:notification / feature:search / app)本就依赖本模块,无 Android 依赖,
 * 纯 JVM 可测。
 */
object RelativeTime {

    /** 输入 ISO 字符串,返回相对时间文案;空串或解析失败返回 ''。 */
    fun fromNow(iso: String?): String {
        if (iso.isNullOrEmpty()) return ""
        val then = parseUtcMillis(iso) ?: return ""
        val seconds = ((System.currentTimeMillis() - then) / 1000).coerceAtLeast(0)
        val minutes = kotlin.math.round(seconds / 60.0).toLong()
        return when {
            minutes < 1 -> "刚刚"
            minutes <= 44 -> "$minutes 分钟"
            minutes <= 89 -> "1 小时"
            minutes <= 1409 -> "${kotlin.math.round(minutes / 60.0).toLong()} 小时"
            minutes <= 2519 -> "1 天"
            minutes <= 20160 -> "${kotlin.math.round(minutes / 1440.0).toLong()} 天"
            else -> absoluteDate(iso)
        }
    }

    private fun absoluteDate(iso: String): String {
        val match = Regex("^(\\d{4})-(\\d{2})-(\\d{2})").find(iso) ?: return ""
        val year = match.groupValues[1].toInt()
        val month = match.groupValues[2].toInt()
        val day = match.groupValues[3].toInt()
        return if (year == Calendar.getInstance().get(Calendar.YEAR)) {
            "${month}月${day}日"
        } else {
            "${year}年${month}月${day}日"
        }
    }

    /** Discourse 时间戳恒为 UTC(结尾带 Z),按 UTC 解析避免叠加本地偏移。 */
    private fun parseUtcMillis(iso: String): Long? = runCatching {
        val match = Regex("^(\\d{4})-(\\d{2})-(\\d{2})[T ](\\d{2}):(\\d{2}):(\\d{2})").find(iso)
            ?: return null
        Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(
                match.groupValues[1].toInt(), match.groupValues[2].toInt() - 1,
                match.groupValues[3].toInt(), match.groupValues[4].toInt(),
                match.groupValues[5].toInt(), match.groupValues[6].toInt()
            )
        }.timeInMillis
    }.getOrNull()
}
