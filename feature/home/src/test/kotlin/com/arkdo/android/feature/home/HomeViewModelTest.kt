package com.arkdo.android.feature.home

import com.arkdo.android.core.data.home.HomeRepository
import com.arkdo.android.core.model.HomeFeed
import com.arkdo.android.core.model.HomePage
import com.arkdo.android.core.model.HomePageResult
import com.arkdo.android.core.model.HomeQuery
import com.arkdo.android.core.model.HomeTopic
import com.arkdo.android.core.model.HomeUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun loadInitial_publishesTopicsAndPaginationState() = runTest {
        val repository = FakeHomeRepository(
            HomePageResult.Success(
                HomePage(listOf(topic(1), topic(2)), page = 0, hasMore = true)
            )
        )
        val viewModel = HomeViewModel(repository)

        viewModel.loadInitial()
        advanceUntilIdle()

        assertEquals(listOf(1L, 2L), viewModel.uiState.value.topics.map(HomeTopic::id))
        assertTrue(viewModel.uiState.value.hasMore)
        assertFalse(viewModel.uiState.value.isInitialLoading)
        assertEquals(listOf(HomeFeed.LATEST to 0), repository.calls)
    }

    @Test
    fun refresh_replacesExistingTopics() = runTest {
        val repository = FakeHomeRepository(
            HomePageResult.Success(HomePage(listOf(topic(1)), page = 0, hasMore = false)),
            HomePageResult.Success(HomePage(listOf(topic(3)), page = 0, hasMore = false))
        )
        val viewModel = HomeViewModel(repository)

        viewModel.loadInitial()
        advanceUntilIdle()
        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(listOf(3L), viewModel.uiState.value.topics.map(HomeTopic::id))
        assertEquals(0, viewModel.uiState.value.page)
    }

    @Test
    fun loadMore_appendsTopicsByIdOnlyOnce() = runTest {
        val repository = FakeHomeRepository(
            HomePageResult.Success(HomePage(listOf(topic(1), topic(2)), page = 0, hasMore = true)),
            HomePageResult.Success(HomePage(listOf(topic(2), topic(3)), page = 1, hasMore = false))
        )
        val viewModel = HomeViewModel(repository)

        viewModel.loadInitial()
        advanceUntilIdle()
        viewModel.loadMore()
        advanceUntilIdle()

        assertEquals(listOf(1L, 2L, 3L), viewModel.uiState.value.topics.map(HomeTopic::id))
        assertEquals(1, viewModel.uiState.value.page)
        assertFalse(viewModel.uiState.value.hasMore)
    }

    @Test
    fun loadInitial_ignoresDuplicateRequestsWhileLoading() = runTest {
        val repository = BlockingHomeRepository()
        val viewModel = HomeViewModel(repository)

        viewModel.loadInitial()
        viewModel.loadInitial()
        advanceUntilIdle()

        assertEquals(1, repository.callCount)
    }
}

private fun topic(id: Long) = HomeTopic(
    id = id,
    title = "Topic $id",
    author = HomeUser(id = id, username = "user$id")
)

private open class FakeHomeRepository(
    private vararg val results: HomePageResult
) : HomeRepository {
    val calls = mutableListOf<Pair<HomeFeed, Int>>()
    private var index = 0

    override suspend fun load(query: HomeQuery, page: Int): HomePageResult {
        calls += query.feed to page
        return results.getOrElse(index++) { results.last() }
    }
}

private class BlockingHomeRepository : FakeHomeRepository(
    HomePageResult.Success(HomePage(listOf(topic(1)), page = 0, hasMore = false))
) {
    var callCount = 0

    override suspend fun load(query: HomeQuery, page: Int): HomePageResult {
        callCount += 1
        return super.load(query, page)
    }
}
