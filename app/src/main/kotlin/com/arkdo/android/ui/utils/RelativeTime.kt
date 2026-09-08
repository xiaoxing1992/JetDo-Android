package com.arkdo.android.ui.utils

import java.util.Calendar

/**
 * 相对时间格式化,1:1 对齐鸿蒙 utils/RelativeTime.ets。
 *
 * 输入 Discourse 返回的 UTC ISO 字符串(结尾带 Z),输出与网页 relativeAgeTiny 对齐的
 * 相对时间文案:刚刚 / N 分钟 / N 小时 / N 天,超出范围回退绝对日期。
 * 分档与取整刻意对齐 relativeAgeTiny(小时、天都用 Math.round),避免小时边界差 1。
 */
object RelativeTime {

    /** 输入 ISO 字符串,返回相对时间文案;空串或解析失败返回 ''。 */
    fun fromNow(iso: String?): String {
        if (iso.isNullOrEmpty()) return ""
        val then = parseUtcMillis(iso) ?: return ""
        var seconds = ((System.currentTimeMillis() - then) / 1000).toLong()
        if (seconds < 0) seconds = 0
        val minutes = (seconds / 60.0).let { kotlin.math.round(it).toLong() }
        if (minutes < 1) return "刚刚"
        if (minutes <= 44) return "$minutes 分钟"
        if (minutes <= 89) return "1 小时"
        if (minutes <= 1409) return "${kotlin.math.round(minutes / 60.0).toLong()} 小时"
        if (minutes <= 2519) return "1 天"
        if (minutes <= 20160) return "${kotlin.math.round(minutes / 1440.0).toLong()} 天"
        return formatAbsoluteDate(iso)
    }

    private fun formatAbsoluteDate(iso: String): String {
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

    /** Discourse 时间戳恒为 UTC(结尾带 Z),按 UTC 解析避免 Date.parse 再叠本地偏移。 */
    private fun parseUtcMillis(iso: String): Long? {
        val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})[T ](\\d{2}):(\\d{2}):(\\d{2})").find(iso)
            ?: return null
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(
                m.groupValues[1].toInt(),
                m.groupValues[2].toInt() - 1,
                m.groupValues[3].toInt(),
                m.groupValues[4].toInt(),
                m.groupValues[5].toInt(),
                m.groupValues[6].toInt()
            )
        }
        return cal.timeInMillis
    }
}
