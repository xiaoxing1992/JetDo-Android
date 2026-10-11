package com.jetdo.android.feature.notification

import com.jetdo.android.core.data.notification.NotificationPage
import com.jetdo.android.core.data.notification.NotificationPageResult
import com.jetdo.android.core.data.notification.NotificationRepository
import com.jetdo.android.core.data.notification.NotificationUnreadStore
import com.jetdo.android.core.model.NotificationItem
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationViewModelTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initial_load_exposes_items() = runTest {
        val repository = FakeNotificationRepository().apply {
            next = NotificationPageResult.Success(NotificationPage(listOf(item(1))))
        }
        val viewModel = NotificationViewModel(repository, FakeUnreadStore())

        viewModel.loadInitial()
        advanceUntilIdle()

        assertEquals(listOf(item(1)), viewModel.uiState.value.items)
        assertEquals(null, viewModel.uiState.value.error)
    }

    @Test
    fun failed_load_exposes_error_and_retry_can_succeed() = runTest {
        val repository = FakeNotificationRepository().apply {
            next = NotificationPageResult.Failure("网络错误", false)
        }
        val viewModel = NotificationViewModel(repository, FakeUnreadStore())

        viewModel.loadInitial()
        advanceUntilIdle()
        assertEquals("网络错误", viewModel.uiState.value.error)

        repository.next = NotificationPageResult.Success(NotificationPage(listOf(item(2))))
        viewModel.loadInitial()
        advanceUntilIdle()

        assertEquals(listOf(item(2)), viewModel.uiState.value.items)
    }

    @Test
    fun mark_item_read_rolls_back_when_repository_fails() = runTest {
        val repository = FakeNotificationRepository().apply {
            next = NotificationPageResult.Success(NotificationPage(listOf(item(1))))
            markReadResult = false
        }
        val viewModel = NotificationViewModel(repository, FakeUnreadStore())
        viewModel.loadInitial()
        advanceUntilIdle()

        viewModel.markItemRead(item(1))
        advanceUntilIdle()

        assertEquals(false, viewModel.uiState.value.items.single().read)
    }

    @Test
    fun mark_all_read_only_rolls_back_items_that_were_unread() = runTest {
        val repository = FakeNotificationRepository().apply {
            next = NotificationPageResult.Success(NotificationPage(listOf(item(1), item(2).copy(read = true))))
            markAllReadResult = false
        }
        val viewModel = NotificationViewModel(repository, FakeUnreadStore())
        viewModel.loadInitial()
        advanceUntilIdle()

        viewModel.markAllRead()
        advanceUntilIdle()

        assertEquals(listOf(false, true), viewModel.uiState.value.items.map { it.read })
    }

    private fun item(id: Long) = NotificationItem(id, 2, false, "", "Ada", "ada", actionText = "回复了你", title = "Title", iconId = "reply", iconColor = "#3B82F6")
}

private class FakeNotificationRepository : NotificationRepository {
    var next: NotificationPageResult = NotificationPageResult.Success(NotificationPage(emptyList()))
    var markReadResult = true
    var markAllReadResult = true
    override suspend fun load(limit: Int, offset: Int, recent: Boolean) = next
    override suspend fun markRead(id: Long) = markReadResult
    override suspend fun markAllRead() = markAllReadResult
}

private class FakeUnreadStore : NotificationUnreadStore {
    override fun publish(unread: Int, highPriority: Int) = Unit
    override fun decrement(highPriority: Boolean) = Unit
    override fun currentHighPriority() = 0
}
