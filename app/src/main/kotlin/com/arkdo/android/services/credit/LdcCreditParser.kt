package com.arkdo.android.services.credit

import com.arkdo.android.data.models.LdcDailyStat
import com.arkdo.android.data.models.LdcUserInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// ==========================================================================
// 与 HarmonyOS 版 services/credit/LdcCreditParser.ets 对齐。
// 响应统一 { error_msg, data }。金额保持字符串;daily_limit 服务端给数字,转成字符串。
// ==========================================================================

object LdcCreditParser {
    private val json = Json { ignoreUnknownKeys = true }

    /** data 缺失(未登录时后端只回 error_msg)返回 null,由调用方转成「去登录」。 */
    fun parseUserInfo(body: String?): LdcUserInfo? {
        val envelope = decodeObject(body) ?: return null
        val raw = envelope["data"]?.takeIf { it.toString() != "null" }?.jsonObject ?: return null
        return LdcUserInfo(
            username = raw.string("username"),
            nickname = raw.string("nickname"),
            trustLevel = raw["trust_level"]?.jsonPrimitive?.intOrNull ?: 0,
            availableBalance = amountOf(raw.stringOrNull("available_balance")),
            communityBalance = amountOf(raw.stringOrNull("community_balance")),
            pendingBalance = amountOf(raw.stringOrNull("pending_balance")),
            remainQuota = amountOf(raw.stringOrNull("remain_quota")),
            dailyLimit = dailyLimitOf(raw["daily_limit"]),
            totalReceive = amountOf(raw.stringOrNull("total_receive")),
            totalPayment = amountOf(raw.stringOrNull("total_payment"))
        )
    }

    /** data 不是数组返回 null;空数组是合法结果。缺日期的行丢弃。 */
    fun parseDailyStats(body: String?): List<LdcDailyStat>? {
        val root = decodeObject(body) ?: return null
        val raw = root["data"]?.takeIf { it.toString() != "null" } ?: return null
        val array = runCatching { raw.jsonArray }.getOrNull() ?: return null
        return array.mapNotNull { element ->
            val item = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val date = item.string("date")
            if (date.isEmpty()) return@mapNotNull null
            LdcDailyStat(
                date = date,
                income = amountOf(item.stringOrNull("income")),
                expense = amountOf(item.stringOrNull("expense"))
            )
        }
    }

    private fun decodeObject(body: String?): JsonObject? {
        if (body.isNullOrBlank()) return null
        return runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
    }

    /** 字段缺失展示成 0:空字符串会让金额行看起来像加载失败。 */
    private fun amountOf(value: String?): String =
        if (!value.isNullOrEmpty()) value else "0"

    /** daily_limit 是数字,其余额度是字符串。两种都收成字符串。 */
    private fun dailyLimitOf(element: JsonElement?): String {
        val primitive = element as? JsonPrimitive ?: return ""
        primitive.intOrNull?.let { return it.toString() }
        primitive.doubleOrNull?.let { return formatWhole(it) }
        return primitive.contentOrNull.orEmpty()
    }

    private fun formatWhole(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

    private fun JsonObject.string(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun JsonObject.stringOrNull(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull
}

