package com.jetdo.android.core.data.site

import com.jetdo.android.core.data.notification.FakeJsonNetworkClient
import com.jetdo.android.core.model.TagSuggestion
import com.jetdo.android.core.network.ChallengeState
import com.jetdo.android.core.network.NetworkError
import com.jetdo.android.core.network.NetworkErrorCode
import com.jetdo.android.core.network.NetworkResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultCategoryRepositoryTest {

    @Test
    fun load_maps_categories_and_parent_relationship() = runTest {
        val client = FakeJsonNetworkClient().apply {
            getResponse = NetworkResult(
                success = true,
                statusCode = 200,
                challengeState = ChallengeState.NONE,
                data = """
                    {"categories":[
                      {"id":1,"name":"开发","color":"112233","text_color":"FFFFFF",
                       "slug":"dev","parent_category_id":null,"icon":"code","read_restricted":false},
                      {"id":2,"name":"Android","color":"445566","slug":"android",
                       "parent_category_id":1,"icon":"fa-solid fa-mobile","read_restricted":true}
                    ]}
                """.trimIndent()
            )
        }

        val result = DefaultCategoryRepository(client).load()

        assertTrue(result is CategoryLoadResult.Success)
        val categories = (result as CategoryLoadResult.Success).categories
        assertEquals(2, categories.size)
        assertEquals(1L, categories[0].id)
        assertEquals("开发", categories[0].name)
        assertEquals("code", categories[0].icon)
        assertEquals(1L, categories[1].parentCategoryId)
        assertTrue(categories[1].readRestricted)
        assertEquals("/site.json", client.lastGetPath)
    }

    @Test
    fun load_accepts_uploaded_logo_object_form() = runTest {
        val client = FakeJsonNetworkClient().apply {
            getResponse = NetworkResult(
                success = true,
                statusCode = 200,
                challengeState = ChallengeState.NONE,
                data = """
                    {"categories":[
                      {"id":3,"name":"公告","uploaded_logo":{"url":"/uploads/logo.png","width":80,"height":80}}
                    ]}
                """.trimIndent()
            )
        }

        val result = DefaultCategoryRepository(client).load()

        val category = (result as CategoryLoadResult.Success).categories.single()
        assertEquals("/uploads/logo.png", category.uploadedLogo)
        assertEquals("6B7280", category.color)
    }

    @Test
    fun load_returns_failure_when_challenge_required() = runTest {
        val client = FakeJsonNetworkClient().apply {
            getResponse = NetworkResult(
                success = false,
                statusCode = 503,
                challengeState = ChallengeState.REQUIRED,
                error = NetworkError(NetworkErrorCode.CHALLENGE_REQUIRED, "challenge", true)
            )
        }

        val result = DefaultCategoryRepository(client).load()

        assertEquals(CategoryLoadResult.Failure("challenge", true), result)
    }

    @Test
    fun load_returns_failure_for_malformed_payload() = runTest {
        val client = FakeJsonNetworkClient().apply {
            getResponse = NetworkResult(true, 200, ChallengeState.NONE, data = "not json")
        }

        val result = DefaultCategoryRepository(client).load()

        assertTrue(result is CategoryLoadResult.Failure)
    }

    /**
     * top_tags 只有 {id,name,slug}，没有 count/description —— 抽屉的「热门标签」组用它，
     * 免得空查询去打受 max_tag_search_results(默认 5) 限制的 /tags/filter/search。
     */
    @Test
    fun load_maps_top_tags_from_same_site_payload() = runTest {
        val client = FakeJsonNetworkClient().apply {
            getResponse = NetworkResult(
                success = true,
                statusCode = 200,
                challengeState = ChallengeState.NONE,
                data = """
                    {"categories":[{"id":1,"name":"开发"}],
                     "top_tags":[
                       {"id":1461,"name":"纯水","slug":"1461-tag"},
                       {"id":1436,"name":"快问快答","slug":"1436-tag"},
                       {"name":"无名氏"}
                     ]}
                """.trimIndent()
            )
        }

        val top = (DefaultCategoryRepository(client).load() as CategoryLoadResult.Success).topTags

        assertEquals(3, top.size)
        assertEquals(TagSuggestion(id = 1461L, name = "纯水", text = "纯水", count = 0), top[0])
        // 缺 id 的一条不丢，退到 0 —— 抽屉的标签行只用到 name。
        assertEquals(0L, top[2].id)
        assertEquals("无名氏", top[2].name)
    }

    @Test
    fun load_returns_empty_top_tags_when_field_absent() = runTest {
        val client = FakeJsonNetworkClient().apply {
            getResponse = NetworkResult(
                success = true,
                statusCode = 200,
                challengeState = ChallengeState.NONE,
                data = """{"categories":[{"id":1,"name":"开发"}]}"""
            )
        }

        val result = DefaultCategoryRepository(client).load() as CategoryLoadResult.Success

        assertTrue(result.topTags.isEmpty())
    }

    @Test
    fun suggestTags_appends_category_id_only_when_present() = runTest {
        val client = FakeJsonNetworkClient().apply {
            getResponse = NetworkResult(true, 200, ChallengeState.NONE, data = """{"results":[]}""")
        }
        val repository = DefaultCategoryRepository(client)

        repository.suggestTags("安卓", categoryId = 12L)
        assertEquals(
            "/tags/filter/search?q=%E5%AE%89%E5%8D%93&limit=5&categoryId=12&filterForInput=true",
            client.lastGetPath
        )

        repository.suggestTags("安卓", categoryId = null)
        assertEquals(
            "/tags/filter/search?q=%E5%AE%89%E5%8D%93&limit=5&filterForInput=true",
            client.lastGetPath
        )
    }
}