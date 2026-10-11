package com.jetdo.android.core.data.search

import com.jetdo.android.core.data.notification.FakeJsonNetworkClient
import com.jetdo.android.core.network.ChallengeState
import com.jetdo.android.core.network.NetworkError
import com.jetdo.android.core.network.NetworkErrorCode
import com.jetdo.android.core.network.NetworkResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultSearchRepositoryTest {

    private fun client(body: String) = FakeJsonNetworkClient().apply {
        getResponse = NetworkResult(true, 200, ChallengeState.NONE, data = body)
    }

    @Test
    fun `帖子搜索按 post_ids 顺序排列并带上命中摘要`() = runTest {
        val repo = DefaultSearchRepository(
            client(
                """
                {"users":[{"id":7,"username":"ada","name":"Ada","avatar_template":"/u/ada/{size}.png"}],
                 "posts":[{"id":101,"topic_id":11,"username":"ada","user_id":7,
                           "blurb":"命中 <span class=\"search-highlight\">kotlin</span> 的楼层"},
                          {"id":102,"topic_id":12,"username":"ada","user_id":7,"blurb":"第二条"}],
                 "topics":[{"id":11,"title":"第一个主题","slug":"t11","category_id":3,
                            "posters":[{"user_id":7}]},
                           {"id":12,"title":"第二个主题","slug":"t12","category_id":3,
                            "posters":[{"user_id":7}]}],
                 "grouped_search_result":{"post_ids":[102,101],"more_full_page_results":true}}
                """.trimIndent()
            )
        )

        val result = repo.searchTopics("kotlin", page = 1)

        assertTrue(result is com.jetdo.android.core.model.SearchPageResult.Success)
        val page = (result as com.jetdo.android.core.model.SearchPageResult.Success).page
        // post_ids 说 102 在前,主题顺序必须照它走,而不是 topics 数组原序。
        assertEquals(listOf(12L, 11L), page.hits.map { it.topic.id })
        assertEquals("第二个主题", page.hits.first().topic.title)
        // blurb 里的 HTML 标签剥掉。
        assertEquals("命中 kotlin 的楼层", page.hits[1].blurb)
        assertTrue(page.hasMore)
    }

    @Test
    fun `同主题多楼层命中只保留第一条`() = runTest {
        val repo = DefaultSearchRepository(
            client(
                """
                {"users":[],"posts":[{"id":101,"topic_id":11,"blurb":"a"},
                                     {"id":102,"topic_id":11,"blurb":"b"}],
                 "topics":[{"id":11,"title":"主题"}],
                 "grouped_search_result":{"post_ids":[101,102]}}
                """.trimIndent()
            )
        )

        val page = (repo.searchTopics("x", 1) as com.jetdo.android.core.model.SearchPageResult.Success).page

        assertEquals(1, page.hits.size)
        assertEquals("a", page.hits.single().blurb)
    }

    @Test
    fun `topics 没给作者时用命中楼作者兜底出头像`() = runTest {
        // 真实 /search.json 的 topics 常缺可用的 posters(或 posters 在 users 里查不到),
        // 此时作者取命中楼层的 username/avatar_template —— 对齐鸿蒙 mapSearch 的兜底。
        val repo = DefaultSearchRepository(
            client(
                """
                {"users":[],
                 "posts":[{"id":101,"topic_id":11,"user_id":42,"username":"bob",
                           "name":"Bob","avatar_template":"/user_avatar/linux.do/bob/{size}/2_3.png"}],
                 "topics":[{"id":11,"title":"没有 posters 的主题"}],
                 "grouped_search_result":{"post_ids":[101]}}
                """.trimIndent()
            )
        )

        val page = (repo.searchTopics("x", 1) as com.jetdo.android.core.model.SearchPageResult.Success).page

        val author = page.hits.single().topic.author
        assertEquals("bob", author?.username)
        assertEquals(42L, author?.id)
        assertEquals("Bob", author?.name)
        assertEquals("/user_avatar/linux.do/bob/{size}/2_3.png", author?.avatarTemplate)
    }

    @Test
    fun `无 post_ids 时按 topics 原序兜底`() = runTest {
        val repo = DefaultSearchRepository(
            client(
                """
                {"users":[],"topics":[{"id":11,"title":"标题命中一"},{"id":12,"title":"标题命中二"}]}
                """.trimIndent()
            )
        )

        val page = (repo.searchTopics("标题", 1) as com.jetdo.android.core.model.SearchPageResult.Success).page

        assertEquals(listOf(11L, 12L), page.hits.map { it.topic.id })
        assertFalse(page.hasMore)
    }

    @Test
    fun `第二页带 page 参数第一页不带`() = runTest {
        val c = client("""{"users":[],"topics":[]}""")
        val repo = DefaultSearchRepository(c)

        repo.searchTopics("kotlin", page = 1)
        assertEquals("/search.json?q=kotlin", c.lastGetPath)

        repo.searchTopics("kotlin", page = 3)
        assertEquals("/search.json?q=kotlin&page=3", c.lastGetPath)
    }

    @Test
    fun `高级语法原样编码进 q 串`() = runTest {
        val c = client("""{"users":[],"topics":[]}""")
        val repo = DefaultSearchRepository(c)

        repo.searchTopics("rx #android tags:kotlin @ada", page = 1)

        // 空格编码成 %20(不是 +),井号与冒号同样要转义。
        assertEquals("/search.json?q=rx%20%23android%20tags%3Akotlin%20%40ada", c.lastGetPath)
    }

    @Test
    fun `空搜索词不发请求`() = runTest {
        val c = client("""{"users":[],"topics":[]}""")
        val repo = DefaultSearchRepository(c)

        val result = repo.searchTopics("   ", page = 1)

        assertTrue(result is com.jetdo.android.core.model.SearchPageResult.Success)
        assertEquals(null, c.lastGetPath)
    }

    @Test
    fun `需要过盾时失败结果标记 requiresChallenge`() = runTest {
        val c = FakeJsonNetworkClient().apply {
            getResponse = NetworkResult(
                false, 403, ChallengeState.REQUIRED,
                error = NetworkError(NetworkErrorCode.CHALLENGE_REQUIRED, "challenge", true)
            )
        }

        val result = DefaultSearchRepository(c).searchTopics("kotlin", page = 1)

        assertEquals(
            com.jetdo.android.core.model.SearchPageResult.Failure("challenge", true),
            result
        )
    }

    @Test
    fun `用户搜索走独立接口且不带分页`() = runTest {
        val c = client(
            """{"users":[{"id":9,"username":"bob"},{"id":3,"username":"ada"}]}"""
        )

        val result = DefaultSearchRepository(c).searchUsers("ada")

        assertEquals("/u/search/users?term=ada&limit=20", c.lastGetPath)
        assertTrue(result.isSuccess)
        assertEquals(listOf(3L, 9L), result.users.map { it.id })
    }

    @Test
    fun `用户搜索空词不发请求`() = runTest {
        val c = client("""{"users":[]}""")

        val result = DefaultSearchRepository(c).searchUsers("  ")

        assertEquals(null, c.lastGetPath)
        assertTrue(result.users.isEmpty())
    }

    @Test
    fun `用户搜索失败带错误信息`() = runTest {
        val c = FakeJsonNetworkClient().apply {
            getResponse = NetworkResult(
                false, 503, ChallengeState.REQUIRED,
                error = NetworkError(NetworkErrorCode.CHALLENGE_REQUIRED, "challenge", true)
            )
        }

        val result = DefaultSearchRepository(c).searchUsers("ada")

        assertFalse(result.isSuccess)
        assertTrue(result.requiresChallenge)
        assertEquals("challenge", result.error)
    }

    @Test
    fun `非法 JSON 返回解析失败而非崩溃`() = runTest {
        val result = DefaultSearchRepository(client("not json")).searchTopics("kotlin", 1)

        assertEquals(
            com.jetdo.android.core.model.SearchPageResult.Failure("站点搜索响应不是有效 JSON"),
            result
        )
    }
}