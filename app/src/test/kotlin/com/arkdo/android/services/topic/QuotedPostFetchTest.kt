package com.arkdo.android.services.topic

import com.arkdo.android.services.network.ChallengeState
import com.arkdo.android.services.network.HttpHeaderEntry
import com.arkdo.android.services.network.JsonNetworkClient
import com.arkdo.android.services.network.JsonRequestOptions
import com.arkdo.android.services.network.NetworkResult
import com.arkdo.android.services.network.NetworkResults
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `GET /posts/by_number/{topicId}/{postNumber}` —— 引用卡「展开查看被引用楼层全文」的取数口。
 *
 * 这条测试存在的理由：这条路是引用卡展开的**唯一**数据来源（已加载楼层由 ViewModel 先兜一层），
 * 一旦端点拼错（少个 s、把 topicId/postNumber 顺序写反）或 cooked 缺失时静默返回空串，
 * 表现是「引用卡的展开箭头点了没反应」—— 没有崩溃、没有日志，只能靠单测钉住判据。
 */
class QuotedPostFetchTest {

    @Test
    fun `正常响应取 cooked 且端点拼接正确`() = runBlocking {
        val client = FetchClient("""{"id":22398145,"post_number":3,"cooked":"<p>被引用的正文</p>"}""")

        val result = TopicService(client).getPostByNumber(2860794L, 3)

        assertTrue(result.success)
        assertEquals("<p>被引用的正文</p>", result.data)
        assertEquals("/posts/by_number/2860794/3", client.lastPath)
    }

    @Test
    fun `响应缺 cooked 判为异常响应而不是空串成功`() = runBlocking {
        // 关键区别：返回「成功但空串」会让渲染层把箭头留在原地、点了永远展不开，
        // 比直接失败更难查。这里必须走失败分支，渲染层才会隐藏箭头。
        val result = TopicService(FetchClient("""{"id":1,"post_number":3}"""))
            .getPostByNumber(1L, 3)

        assertFalse(result.success)
    }

    @Test
    fun `参数非法时直接短路 不发请求`() = runBlocking {
        val client = FetchClient("{}")
        val service = TopicService(client)

        assertFalse(service.getPostByNumber(0L, 3).success)
        assertFalse(service.getPostByNumber(1L, 0).success)
        assertFalse(service.getPostByNumber(-5L, -1).success)
        // 一次网络都不该发出去。
        assertNull(client.lastPath)
    }

    @Test
    fun `网络失败原样透传状态码`() = runBlocking {
        val result = TopicService(FetchClient(body = null, success = false, statusCode = 503))
            .getPostByNumber(1L, 3)

        assertFalse(result.success)
        assertEquals(503, result.statusCode)
    }
}

/** 只让 getJson 有行为的最小 fake；其余方法在被调用时直接判失败（本测试不该走到）。 */
private class FetchClient(
    private val body: String?,
    private val success: Boolean = true,
    private val statusCode: Int = 200
) : JsonNetworkClient {

    var lastPath: String? = null
        private set

    override suspend fun getJson(path: String, options: JsonRequestOptions?): NetworkResult<String> {
        lastPath = path
        return if (success) {
            NetworkResult(success = true, statusCode = statusCode, challengeState = ChallengeState.NONE, data = body)
        } else {
            NetworkResults.networkFailure(statusCode, "unused")
        }
    }

    override suspend fun getJsonWithHeaders(
        path: String,
        headers: List<HttpHeaderEntry>,
        referrer: String,
        options: JsonRequestOptions?
    ) = NetworkResults.networkFailure<String>(0, "unused")

    override suspend fun postForm(path: String, body: String, options: JsonRequestOptions?) =
        NetworkResults.networkFailure<String>(0, "unused")

    override suspend fun postJson(path: String, body: String, options: JsonRequestOptions?) =
        NetworkResults.networkFailure<String>(0, "unused")

    override suspend fun putJson(path: String, options: JsonRequestOptions?) =
        NetworkResults.networkFailure<String>(0, "unused")

    override suspend fun putForm(path: String, body: String, options: JsonRequestOptions?) =
        NetworkResults.networkFailure<String>(0, "unused")

    override suspend fun deleteForm(path: String, body: String, options: JsonRequestOptions?) =
        NetworkResults.networkFailure<String>(0, "unused")

    override suspend fun uploadImage(
        base64: String,
        fileName: String,
        mimeType: String,
        options: JsonRequestOptions?
    ) = NetworkResults.networkFailure<String>(0, "unused")
}
