package com.arkdo.android.core.data.site

import com.arkdo.android.core.data.notification.FakeJsonNetworkClient
import com.arkdo.android.core.network.ChallengeState
import com.arkdo.android.core.network.NetworkError
import com.arkdo.android.core.network.NetworkErrorCode
import com.arkdo.android.core.network.NetworkResult
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
}