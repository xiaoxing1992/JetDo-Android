package com.jetdo.android.services.challenge

import com.jetdo.android.common.constants.AppConstants

// ==========================================================================
// 与 HarmonyOS 版 services/challenge/ChallengeCompletionDetector.ets 逐字段对齐。
// 判定「CF Managed Challenge 过盾成功后回跳的 404」，供 CfChallengePage 自动关窗。
// ==========================================================================

data class ChallengeCompletionResponse(
    val url: String,
    val statusCode: Int,
    val isMainFrame: Boolean,
    val cfMitigatedHeader: String
)

object ChallengeCompletionDetector {

    /**
     * isMainFrame && statusCode===404 && url 以 CHALLENGE_URL 开头 && cf-mitigated 不含 challenge。
     * 用 startsWith 而非严格等于：真过盾后的回跳 URL 带查询参数（如 /challenge?__cf_chl_rt_tk=...）。
     */
    fun isChallengeCompletionResponse(response: ChallengeCompletionResponse): Boolean {
        if (!response.isMainFrame) return false
        if (response.statusCode != 404) return false
        if (!response.url.startsWith(AppConstants.CHALLENGE_URL)) return false
        if (response.cfMitigatedHeader.lowercase().contains("challenge")) return false
        return true
    }
}
