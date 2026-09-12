package com.arkdo.android.core.network

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

data class HttpHeaderEntry(val name: String, val value: String)

data class JsonRequestOptions(
    val notifyChallenge: Boolean = true,
    val referrer: String? = null
)

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

object NetworkResults {
    fun <T> forwardFailure(response: NetworkResult<String>): NetworkResult<T> = NetworkResult(
        success = response.success,
        statusCode = response.statusCode,
        challengeState = response.challengeState,
        error = response.error
    )

    fun <T> unexpectedResponse(
        statusCode: Int,
        challengeState: ChallengeState,
        message: String
    ): NetworkResult<T> = NetworkResult(
        success = false,
        statusCode = statusCode,
        challengeState = challengeState,
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
        error = NetworkError(NetworkErrorCode.NETWORK_FAILURE, message, false)
    )
}
