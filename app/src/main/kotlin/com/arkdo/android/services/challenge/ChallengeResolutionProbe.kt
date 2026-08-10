package com.arkdo.android.services.challenge

import com.arkdo.android.services.network.JsonNetworkClient
import com.arkdo.android.services.network.JsonRequestOptions
import com.arkdo.android.services.network.NetworkResults
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.contentOrNull

// ==========================================================================
// 与 HarmonyOS 版 services/challenge/ChallengeResolutionProbe.ets 逐字段对齐。
// 权威探测「盾是否已放行」：用 /latest.json 探测，不看可能残留的 cf_clearance。
// ==========================================================================

class ChallengeResolutionProbe(
    private val client: JsonNetworkClient
) {
    /** 探测能否访问最新话题列表。notifyChallenge:false —— 探测本身不触发挑战信号（避免自激）。 */
    suspend fun canAccessLatestJson(): Boolean {
        val response = client.getJson("/latest.json", JsonRequestOptions(notifyChallenge = false))
        if (!response.success || response.statusCode != 200 || response.challengeState != com.arkdo.android.services.network.ChallengeState.NONE) {
            return false
        }
        val data = response.data ?: return false
        return runCatching {
            val root = Json { ignoreUnknownKeys = true }.parseToJsonElement(data).jsonObject
            val topicList = root["topic_list"]?.jsonObject
            val topics = topicList?.get("topics")?.jsonArray ?: return@runCatching false
            true
        }.getOrDefault(false)
    }
}
