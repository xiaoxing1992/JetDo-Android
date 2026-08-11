package com.arkdo.android.data.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ==========================================================================
// 与 HarmonyOS 版 models/LdcCredit.ets 逐字段对齐。
// 来源：https://credit.linux.do（独立域名、独立会话）。
// 金额一律保留字符串（服务端用字符串避浮点误差），严禁转 Double。
// ==========================================================================

@Serializable
data class LdcUserInfo(
    @SerialName("username") val username: String = "",
    @SerialName("nickname") val nickname: String = "",
    @SerialName("trust_level") val trustLevel: Int = 0,
    @SerialName("available_balance") val availableBalance: String = "0",
    @SerialName("community_balance") val communityBalance: String = "0",
    @SerialName("pending_balance") val pendingBalance: String = "0",
    @SerialName("remain_quota") val remainQuota: String = "0",
    @SerialName("daily_limit") val dailyLimit: String = "0",
    @SerialName("total_receive") val totalReceive: String = "0",
    @SerialName("total_payment") val totalPayment: String = "0"
)

/** 单日收支。date 形如 2026-07-29。 */
@Serializable
data class LdcDailyStat(
    @SerialName("date") val date: String,
    @SerialName("income") val income: String,
    @SerialName("expense") val expense: String
)

/** LDC 响应统一外壳 { error_msg, data }；未登录时只有 error_msg。 */
@Serializable
data class LdcEnvelope<T>(
    @SerialName("error_msg") val errorMsg: String? = null,
    @SerialName("data") val data: T? = null
)
