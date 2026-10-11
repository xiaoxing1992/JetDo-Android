package com.jetdo.android.services.network

// ==========================================================================
// 与 HarmonyOS 版 services/network/ChallengeDetector.ets 逐字段对齐。
// 判定一次响应是否为 Cloudflare 挑战页。
// ==========================================================================

object ChallengeDetector {

    private val CLOUDFLARE_MARKERS = listOf(
        "cloudflare", "cf-chl", "challenge-platform", "turnstile", "just a moment"
    )

    /**
     * 判定优先级：
     * 1. 2xx + 合法 JSON → 直接 NONE（防帖子标题误判）
     * 2. cfMitigated 含 challenge → REQUIRED
     * 3. 非 JSON 且正文含任一 marker → REQUIRED
     * 4. 其余（403/429/普通登录页）→ NONE（不把掉登录误报成过盾）
     */
    fun detectChallenge(
        statusCode: Int,
        contentType: String,
        body: String,
        cfMitigated: String = ""
    ): ChallengeState {
        val isJson = contentType.lowercase().contains("application/json") ||
            body.trim().startsWith("{") || body.trim().startsWith("[")

        // 2xx + 合法 JSON → 放行
        if (statusCode in 200..299 && isJson) {
            return ChallengeState.NONE
        }

        val mitigated = cfMitigated.lowercase()
        if (mitigated.contains("challenge")) {
            return ChallengeState.REQUIRED
        }

        if (!isJson) {
            val lower = body.lowercase()
            if (CLOUDFLARE_MARKERS.any { lower.contains(it) }) {
                return ChallengeState.REQUIRED
            }
        }

        return ChallengeState.NONE
    }
}
