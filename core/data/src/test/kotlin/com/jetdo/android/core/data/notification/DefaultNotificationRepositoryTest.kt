package com.jetdo.android.core.data.notification

import com.jetdo.android.core.network.ChallengeState
import com.jetdo.android.core.network.NetworkError
import com.jetdo.android.core.network.NetworkErrorCode
import com.jetdo.android.core.network.NetworkResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultNotificationRepositoryTest {
    @Test
    fun load_maps_valid_response_to_notification_items() = kotlinx.coroutines.test.runTest {
        val client = FakeJsonNetworkClient().apply {
            getResponse = NetworkResult(true, 200, ChallengeState.NONE, """
                {"notifications":[
                  {"id":7,"notification_type":2,"read":false,"created_at":"2026-09-12T00:00:00Z","topic_id":9,"fancy_title":"Architecture","acting_user_name":"Ada"},
                  {"notification_type":2,"acting_user_name":"Ignored"}
                ]}
            """.trimIndent())
        }

        val result = DefaultNotificationRepository(client).load(30, 0, true)

        val success = result as NotificationPageResult.Success
        assertEquals(1, success.page.items.size)
        assertEquals("Ada", success.page.items.single().actorName)
        assertEquals("Architecture", success.page.items.single().title)
        assertEquals("/notifications.json?limit=30&offset=0&recent=true&bump_last_seen_reviewable=true", client.lastGetPath)
    }

    @Test
    fun load_returns_failure_when_network_client_fails() = kotlinx.coroutines.test.runTest {
        val client = FakeJsonNetworkClient().apply {
            getResponse = NetworkResult(
                success = false,
                statusCode = 403,
                challengeState = ChallengeState.REQUIRED,
                error = NetworkError(NetworkErrorCode.CHALLENGE_REQUIRED, "需要验证", true)
            )
        }

        val result = DefaultNotificationRepository(client).load(30, 0, false)

        assertEquals(NotificationPageResult.Failure("需要验证", true), result)
    }

    @Test
    fun markRead_returns_the_network_success_flag() = kotlinx.coroutines.test.runTest {
        val client = FakeJsonNetworkClient().apply {
            putFormResponse = NetworkResult(false, 500, ChallengeState.NONE)
        }

        assertTrue(!DefaultNotificationRepository(client).markRead(7))
    }
}
