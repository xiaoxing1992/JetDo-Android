package com.arkdo.android.ui.utils

import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 相对时间工具 - 1:1 对齐鸿蒙版 RelativeTime.ets
 *
 * 将时间戳转换为相对时间描述（如"3分钟前"、"2小时前"）
 */
object RelativeTime {
    /**
     * 从 ISO8601 时间字符串计算相对时间
     *
     * @param isoString ISO8601 格式时间字符串，如 "2024-01-15T10:30:00.000Z"
     * @return 相对时间描述，如 "3分钟前"、"2小时前"、"昨天"、"3天前"
     */
    fun fromNow(isoString: String?): String {
        if (isoString.isNullOrEmpty()) {
            return ""
        }

        return try {
            val timestamp = ZonedDateTime.parse(isoString, DateTimeFormatter.ISO_DATE_TIME)
            val now = Instant.now()
            val then = timestamp.toInstant()
            val duration = Duration.between(then, now)

            when {
                duration.isNegative -> "刚刚"
                duration.toMinutes() < 1 -> "刚刚"
                duration.toMinutes() < 60 -> "${duration.toMinutes()}分钟前"
                duration.toHours() < 24 -> "${duration.toHours()}小时前"
                duration.toDays() == 1L -> "昨天"
                duration.toDays() < 7 -> "${duration.toDays()}天前"
                duration.toDays() < 30 -> "${duration.toDays() / 7}周前"
                duration.toDays() < 365 -> "${duration.toDays() / 30}个月前"
                else -> "${duration.toDays() / 365}年前"
            }
        } catch (e: Exception) {
            ""
        }
    }
}
