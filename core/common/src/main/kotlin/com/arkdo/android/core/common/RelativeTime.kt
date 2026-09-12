package com.arkdo.android.core.common

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.round

/** Formats Discourse UTC timestamps using the notification list's compact labels. */
object RelativeTime {
    fun fromNow(iso: String?): String {
        if (iso.isNullOrEmpty()) return ""
        val then = parseUtcMillis(iso) ?: return ""
        val seconds = ((System.currentTimeMillis() - then) / 1000).coerceAtLeast(0)
        val minutes = round(seconds / 60.0).toLong()
        return when {
            minutes < 1 -> "刚刚"
            minutes <= 44 -> "$minutes 分钟"
            minutes <= 89 -> "1 小时"
            minutes <= 1409 -> "${round(minutes / 60.0).toLong()} 小时"
            minutes <= 2519 -> "1 天"
            minutes <= 20160 -> "${round(minutes / 1440.0).toLong()} 天"
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

    private fun parseUtcMillis(iso: String): Long? = runCatching {
        val match = Regex("^(\\d{4})-(\\d{2})-(\\d{2})[T ](\\d{2}):(\\d{2}):(\\d{2})").find(iso)
            ?: return null
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(
                match.groupValues[1].toInt(), match.groupValues[2].toInt() - 1,
                match.groupValues[3].toInt(), match.groupValues[4].toInt(),
                match.groupValues[5].toInt(), match.groupValues[6].toInt()
            )
        }.timeInMillis
    }.getOrNull()
}
