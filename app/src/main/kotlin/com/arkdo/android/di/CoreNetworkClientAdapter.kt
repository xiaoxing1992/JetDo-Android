package com.arkdo.android.di

import com.arkdo.android.services.network.ArkWebNetworkBridge
import com.arkdo.android.services.network.ChallengeState as AppChallengeState
import com.arkdo.android.services.network.HttpHeaderEntry as AppHeaderEntry
import com.arkdo.android.services.network.JsonNetworkClient as AppNetworkClient
import com.arkdo.android.services.network.JsonRequestOptions as AppRequestOptions
import com.arkdo.android.services.network.NetworkError as AppNetworkError
import com.arkdo.android.services.network.NetworkErrorCode as AppErrorCode
import com.arkdo.android.services.network.NetworkResult as AppNetworkResult
import com.arkdo.android.core.network.ChallengeState
import com.arkdo.android.core.network.HttpHeaderEntry
import com.arkdo.android.core.network.JsonNetworkClient
import com.arkdo.android.core.network.JsonRequestOptions
import com.arkdo.android.core.network.NetworkError
import com.arkdo.android.core.network.NetworkErrorCode
import com.arkdo.android.core.network.NetworkResult

/** Adapts the legacy app bridge to the modular network contract. */
class CoreNetworkClientAdapter(
    private val delegate: AppNetworkClient = ArkWebNetworkBridge
) : JsonNetworkClient {
    override suspend fun getJson(path: String, options: JsonRequestOptions?): NetworkResult<String> =
        delegate.getJson(path, options.toApp()).toCore()

    override suspend fun getJsonWithHeaders(
        path: String,
        headers: List<HttpHeaderEntry>,
        referrer: String,
        options: JsonRequestOptions?
    ): NetworkResult<String> = delegate.getJsonWithHeaders(
        path,
        headers.map { AppHeaderEntry(it.name, it.value) },
        referrer,
        options.toApp()
    ).toCore()

    override suspend fun postForm(path: String, body: String, options: JsonRequestOptions?): NetworkResult<String> =
        delegate.postForm(path, body, options.toApp()).toCore()

    override suspend fun postJson(path: String, body: String, options: JsonRequestOptions?): NetworkResult<String> =
        delegate.postJson(path, body, options.toApp()).toCore()

    override suspend fun putJson(path: String, options: JsonRequestOptions?): NetworkResult<String> =
        delegate.putJson(path, options.toApp()).toCore()

    override suspend fun putForm(path: String, body: String, options: JsonRequestOptions?): NetworkResult<String> =
        delegate.putForm(path, body, options.toApp()).toCore()

    override suspend fun deleteForm(path: String, body: String, options: JsonRequestOptions?): NetworkResult<String> =
        delegate.deleteForm(path, body, options.toApp()).toCore()

    override suspend fun uploadImage(
        base64: String,
        fileName: String,
        mimeType: String,
        options: JsonRequestOptions?
    ): NetworkResult<String> = delegate.uploadImage(base64, fileName, mimeType, options.toApp()).toCore()
}

private fun JsonRequestOptions?.toApp(): AppRequestOptions? = this?.let {
    AppRequestOptions(notifyChallenge = it.notifyChallenge, referrer = it.referrer)
}

private fun AppNetworkResult<String>.toCore(): NetworkResult<String> = NetworkResult(
    success = success,
    statusCode = statusCode,
    challengeState = challengeState.toCore(),
    data = data,
    error = error?.toCore()
)

private fun AppChallengeState.toCore(): ChallengeState = when (this) {
    AppChallengeState.NONE -> ChallengeState.NONE
    AppChallengeState.SUSPECTED -> ChallengeState.SUSPECTED
    AppChallengeState.REQUIRED -> ChallengeState.REQUIRED
}

private fun AppNetworkError.toCore(): NetworkError = NetworkError(
    code = code.toCore(),
    message = message,
    requiresChallenge = requiresChallenge
)

private fun AppErrorCode.toCore(): NetworkErrorCode = when (this) {
    AppErrorCode.NOT_IMPLEMENTED -> NetworkErrorCode.NOT_IMPLEMENTED
    AppErrorCode.CHALLENGE_REQUIRED -> NetworkErrorCode.CHALLENGE_REQUIRED
    AppErrorCode.UNEXPECTED_RESPONSE -> NetworkErrorCode.UNEXPECTED_RESPONSE
    AppErrorCode.NETWORK_FAILURE -> NetworkErrorCode.NETWORK_FAILURE
}
