package com.arkdo.android.core.data.notification

import com.arkdo.android.core.network.HttpHeaderEntry
import com.arkdo.android.core.network.JsonNetworkClient
import com.arkdo.android.core.network.JsonRequestOptions
import com.arkdo.android.core.network.NetworkResult
import com.arkdo.android.core.network.NetworkResults

class FakeJsonNetworkClient : JsonNetworkClient {
    var getResponse: NetworkResult<String> = NetworkResult(true, 200, com.arkdo.android.core.network.ChallengeState.NONE, "{}")
    var putFormResponse: NetworkResult<String> = NetworkResult(true, 200, com.arkdo.android.core.network.ChallengeState.NONE, "{}")
    var lastGetPath: String? = null

    override suspend fun getJson(path: String, options: JsonRequestOptions?): NetworkResult<String> {
        lastGetPath = path
        return getResponse
    }
    override suspend fun getJsonWithHeaders(path: String, headers: List<HttpHeaderEntry>, referrer: String, options: JsonRequestOptions?): NetworkResult<String> = NetworkResults.networkFailure(0, "unused")
    override suspend fun postForm(path: String, body: String, options: JsonRequestOptions?): NetworkResult<String> = NetworkResults.networkFailure(0, "unused")
    override suspend fun postJson(path: String, body: String, options: JsonRequestOptions?): NetworkResult<String> = NetworkResults.networkFailure(0, "unused")
    override suspend fun putJson(path: String, options: JsonRequestOptions?): NetworkResult<String> = NetworkResults.networkFailure(0, "unused")
    override suspend fun putForm(path: String, body: String, options: JsonRequestOptions?) = putFormResponse
    override suspend fun deleteForm(path: String, body: String, options: JsonRequestOptions?): NetworkResult<String> = NetworkResults.networkFailure(0, "unused")
    override suspend fun uploadImage(base64: String, fileName: String, mimeType: String, options: JsonRequestOptions?): NetworkResult<String> = NetworkResults.networkFailure(0, "unused")
}
