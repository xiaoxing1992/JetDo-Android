package com.arkdo.android.services.network

// ==========================================================================
// 与 HarmonyOS 版 services/network/NetworkTypes.ets 逐字段对齐。
// 网络层的公共类型，被全部 service 与 bridge 引用。
// ==========================================================================

enum class ChallengeState(val value: String) {
    NONE("none"),
    SUSPECTED("suspected"),
    REQUIRED("required")
}

enum class NetworkErrorCode(val value: String) {
    NOT_IMPLEMENTED("not_implemented"),
    CHALLENGE_REQUIRED("challenge_required"),
    UNEXPECTED_RESPONSE("unexpected_response"),
    NETWORK_FAILURE("network_failure")
}

data class NetworkError(
    val code: NetworkErrorCode,
    val message: String,
    val requiresChallenge: Boolean
)

data class NetworkResult<T>(
    val success: Boolean,
    val statusCode: Int,
    val challengeState: ChallengeState,
    val data: T? = null,
    val error: NetworkError? = null
)

data class HttpHeaderEntry(
    val name: String,
    val value: String
)

/** 请求选项。notifyChallenge=false 时撞盾不弹验证（后台探测/阅读上报用）。 */
data class JsonRequestOptions(
    val notifyChallenge: Boolean = true,
    val referrer: String? = null
)

/**
 * JsonNetworkClient —— bridge 实现的契约，全部 service 以此为依赖注入。
 * getJson/postForm/postJson/putJson/putForm/deleteForm/uploadImage。
 */
interface JsonNetworkClient {
    suspend fun getJson(path: String, options: JsonRequestOptions? = null): NetworkResult<String>
    suspend fun getJsonWithHeaders(
        path: String,
        headers: List<HttpHeaderEntry>,
        referrer: String,
        options: JsonRequestOptions? = null
    ): NetworkResult<String>
    suspend fun postForm(path: String, body: String, options: JsonRequestOptions? = null): NetworkResult<String>
    suspend fun postJson(path: String, body: String, options: JsonRequestOptions? = null): NetworkResult<String>
    suspend fun putJson(path: String, options: JsonRequestOptions? = null): NetworkResult<String>
    suspend fun putForm(path: String, body: String, options: JsonRequestOptions? = null): NetworkResult<String>
    suspend fun deleteForm(path: String, body: String, options: JsonRequestOptions? = null): NetworkResult<String>
    suspend fun uploadImage(
        base64: String,
        fileName: String,
        mimeType: String,
        options: JsonRequestOptions? = null
    ): NetworkResult<String>
}

// ==========================================================================
// services/network/NetworkResults.ets —— 失败态 NetworkResult 的三个构造器。
// ==========================================================================
object NetworkResults {
    /** 下层失败原样上抛，只换泛型。 */
    fun <T> forwardFailure(response: NetworkResult<String>): NetworkResult<T> =
        NetworkResult(
            success = response.success,
            statusCode = response.statusCode,
            challengeState = response.challengeState,
            data = null,
            error = response.error
        )

    /**
     * 结构不符（非 2xx 或 JSON 解析失败）。关键：被 CF 拦时返回的 HTML 落到这里，
     * requiresChallenge 由 challengeState !== NONE 推导，上层据此区分「结构变了」与「该过盾了」。
     */
    fun <T> unexpectedResponse(
        statusCode: Int,
        challengeState: ChallengeState,
        message: String
    ): NetworkResult<T> = NetworkResult(
        success = false,
        statusCode = statusCode,
        challengeState = challengeState,
        data = null,
        error = NetworkError(
            code = NetworkErrorCode.UNEXPECTED_RESPONSE,
            message = message,
            requiresChallenge = challengeState != ChallengeState.NONE
        )
    )

    fun <T> unexpectedFrom(response: NetworkResult<String>, message: String): NetworkResult<T> =
        unexpectedResponse(response.statusCode, response.challengeState, message)

    fun <T> networkFailure(statusCode: Int, message: String): NetworkResult<T> = NetworkResult(
        success = false,
        statusCode = statusCode,
        challengeState = ChallengeState.NONE,
        data = null,
        error = NetworkError(
            code = NetworkErrorCode.NETWORK_FAILURE,
            message = message,
            requiresChallenge = false
        )
    )
}
