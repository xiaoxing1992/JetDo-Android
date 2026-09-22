package com.arkdo.android.services.challenge

import com.arkdo.android.services.network.ArkWebNetworkBridge
import com.arkdo.android.services.network.JsonNetworkClient
import com.arkdo.android.services.network.JsonRequestOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import timber.log.Timber
import kotlin.coroutines.resume

// ==========================================================================
// 与 HarmonyOS 版 services/challenge/ChallengeResolutionProbe.ets 逐字段对齐。
// 权威探测「盾是否已放行」：用 /latest.json 探测，不看可能残留的 cf_clearance。
// ==========================================================================

class ChallengeResolutionProbe(
    private val client: JsonNetworkClient
) {
    /**
     * 探测盾是否已放行。notifyChallenge:false —— 探测本身不触发挑战信号（避免自激）。
     *
     * /latest.json 能返回话题列表不够：宿主文档还可能停在 “Just a moment...”，
     * 写请求从那一页读 csrf 会失败。宿主仍是挑战页时不算恢复。
     */
    suspend fun canAccessLatestJson(): Boolean {
        if (hostDocumentChallenged()) {
            Timber.i("[ArkDO][Challenge] probe host document still challenged")
            return false
        }
        val response = client.getJson("/latest.json", JsonRequestOptions(notifyChallenge = false))
        if (!response.success || response.statusCode != 200 || response.challengeState != com.arkdo.android.services.network.ChallengeState.NONE) {
            return false
        }
        val data = response.data ?: return false
        return runCatching {
            val root = Json { ignoreUnknownKeys = true }.parseToJsonElement(data).jsonObject
            val topicList = root["topic_list"]?.jsonObject
            topicList?.get("topics")?.jsonArray != null
        }.getOrDefault(false)
    }

    private suspend fun hostDocumentChallenged(): Boolean = suspendCancellableCoroutine { cont ->
        val web = ArkWebNetworkBridge.peekWebView()
        if (web == null) {
            Timber.i("[ArkDO][Challenge] probe host webview missing, treat as challenged")
            cont.resume(true)
            return@suspendCancellableCoroutine
        }
        web.post {
            web.evaluateJavascript(
                "(function(){return JSON.stringify({title:document.title||'',html:document.documentElement?document.documentElement.outerHTML.slice(0,4000):''});})()"
            ) { raw ->
                if (!cont.isActive) return@evaluateJavascript
                val text = raw ?: ""
                cont.resume(
                    text.contains("Just a moment") ||
                        text.contains("cf-challenge") ||
                        text.contains("challenge-platform") ||
                        text.contains("cf-turnstile")
                )
            }
        }
    }
}
