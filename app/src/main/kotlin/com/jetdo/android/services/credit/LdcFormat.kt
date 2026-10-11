package com.jetdo.android.services.credit

// ==========================================================================
// 与 HarmonyOS 版 common/utils/LdcFormat.ets 对齐。
// 金额只做字符串修剪,不转数值再转回——1395.01 的浮点尾差会让金额差一分钱。
// 比大小/画条时才用 ldcAmountValue 临时取数值。
// ==========================================================================

/** '1395.01' → '1395.01','16.00' → '16','0.50' → '0.5'。非数字形状原样返回。 */
fun formatLdcAmount(amount: String): String {
    val trimmed = amount.trim()
    if (trimmed.isEmpty()) return "0"
    val dotIndex = trimmed.indexOf('.')
    if (dotIndex < 0) return trimmed
    var end = trimmed.length
    while (end > dotIndex && trimmed[end - 1] == '0') end--
    if (end - 1 == dotIndex) end = dotIndex
    return trimmed.substring(0, end)
}

/** 仅用于比较和画条。非数字返回 0。 */
fun ldcAmountValue(amount: String): Double {
    val value = amount.toDoubleOrNull()
    return if (value != null && value.isFinite()) value else 0.0
}

/** '2026-07-29' → '07-29'。接口给的是本地日期串,不做时区换算。 */
fun ldcDayLabel(date: String): String {
    val parts = date.split('-')
    if (parts.size < 3) return date
    return "${parts[1]}-${parts[2]}"
}
