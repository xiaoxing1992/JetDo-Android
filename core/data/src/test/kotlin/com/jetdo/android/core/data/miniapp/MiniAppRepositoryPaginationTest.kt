package com.jetdo.android.core.data.miniapp

import com.jetdo.android.core.network.ChallengeState
import com.jetdo.android.core.network.HttpHeaderEntry
import com.jetdo.android.core.network.JsonNetworkClient
import com.jetdo.android.core.network.JsonRequestOptions
import com.jetdo.android.core.network.NetworkResult
import com.jetdo.android.core.network.NetworkResults
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 广场列表分页解析测试。
 *
 * 站点 `apps.json?page=N` 返回 `{apps, page, total, per_page}`：
 * 0-based、末页可能不足 per_page。hasMore 必须以 total 为准，
 * 否则末页 17 个会被误判成"还有下一页"而多请求一次。
 */
class MiniAppRepositoryPaginationTest {

    private class PageFakeClient(
        private val total: Int = 177,
        private val perPage: Int = 40,
        private val rawBody: String? = null
    ) : JsonNetworkClient {
        val paths = mutableListOf<String>()

        override suspend fun getJson(path: String, options: JsonRequestOptions?): NetworkResult<String> {
            paths += path
            if (rawBody != null) return NetworkResult(true, 200, ChallengeState.NONE, rawBody)
            val page = Regex("page=(\\d+)").find(path)?.groupValues?.get(1)?.toInt() ?: 0
            val start = page * perPage
            val count = (total - start).coerceIn(0, perPage)
            val apps = (start until start + count).joinToString(",") { i ->
                """{"app_id":"id_$i","name":"app$i"}"""
            }
            return NetworkResult(
                true, 200, ChallengeState.NONE,
                """{"apps":[$apps],"page":$page,"total":$total,"per_page":$perPage,"sort":"latest"}"""
            )
        }
        override suspend fun getJsonWithHeaders(path: String, headers: List<HttpHeaderEntry>, referrer: String, options: JsonRequestOptions?): NetworkResult<String> = NetworkResults.networkFailure(0, "unused")
        override suspend fun postForm(path: String, body: String, options: JsonRequestOptions?): NetworkResult<String> = NetworkResults.networkFailure(0, "unused")
        override suspend fun postJson(path: String, body: String, options: JsonRequestOptions?): NetworkResult<String> = NetworkResults.networkFailure(0, "unused")
        override suspend fun putJson(path: String, options: JsonRequestOptions?): NetworkResult<String> = NetworkResults.networkFailure(0, "unused")
        override suspend fun putForm(path: String, body: String, options: JsonRequestOptions?): NetworkResult<String> = NetworkResults.networkFailure(0, "unused")
        override suspend fun deleteForm(path: String, body: String, options: JsonRequestOptions?): NetworkResult<String> = NetworkResults.networkFailure(0, "unused")
        override suspend fun uploadImage(base64: String, fileName: String, mimeType: String, options: JsonRequestOptions?): NetworkResult<String> = NetworkResults.networkFailure(0, "unused")
    }

    @Test
    fun `首页解析分页元数据且有下一页`() = runTest {
        val client = PageFakeClient()
        val result = DefaultMiniAppRepository(client).listApps(0)
        assertTrue(result is MiniAppResult.Success)
        val page = (result as MiniAppResult.Success).data
        assertEquals(40, page.apps.size)
        assertEquals(0, page.page)
        assertEquals(177, page.total)
        assertEquals(40, page.perPage)
        assertTrue(page.hasMore)
        assertTrue(client.paths.first().endsWith("?page=0"))
    }

    @Test
    fun `末页不足per_page仍正确判定无下一页`() = runTest {
        val page = (DefaultMiniAppRepository(PageFakeClient()).listApps(4) as MiniAppResult.Success).data
        assertEquals(17, page.apps.size)
        assertEquals(4, page.page)
        assertFalse("末页 17 个但 total=177，必须 hasMore=false", page.hasMore)
    }

    @Test
    fun `中间页有下一页`() = runTest {
        val page = (DefaultMiniAppRepository(PageFakeClient()).listApps(2) as MiniAppResult.Success).data
        assertEquals(40, page.apps.size)
        assertTrue(page.hasMore)
    }

    @Test
    fun `无分页元数据的老形状退化为单页`() = runTest {
        val client = PageFakeClient(rawBody = """{"apps":[{"app_id":"a","name":"A"}]}""")
        val page = (DefaultMiniAppRepository(client).listApps(0) as MiniAppResult.Success).data
        assertEquals(1, page.apps.size)
        assertFalse(page.hasMore)
    }
}
