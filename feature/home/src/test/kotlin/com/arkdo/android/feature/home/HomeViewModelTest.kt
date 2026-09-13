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

    @Test
    fun scroll_past_one_and_a_half_viewports_shows_back_to_top_fab() = runTest {
        val viewModel = HomeViewModel(FakeHomeRepository())

        viewModel.updateFabMode(scrollOffset = 1_501f, viewportHeight = 1_000f)

        assertTrue(viewModel.uiState.value.showBackToTopFab)
    }

    @Test
    fun scrolling_back_inside_hysteresis_hides_back_to_top_fab() = runTest {
        val viewModel = HomeViewModel(FakeHomeRepository())

        viewModel.updateFabMode(scrollOffset = 1_501f, viewportHeight = 1_000f)
        viewModel.updateFabMode(scrollOffset = 1_199f, viewportHeight = 1_000f)

        assertFalse(viewModel.uiState.value.showBackToTopFab)
    }

    @Test
    fun setQuery_reloads_using_the_complete_legacy_scope() = runTest {
        val repository = FakeHomeRepository(
            HomePageResult.Success(HomePage(listOf(topic(1)), page = 0, hasMore = false)),
            HomePageResult.Success(HomePage(listOf(topic(2)), page = 0, hasMore = false))
        )
        val viewModel = HomeViewModel(repository)

        viewModel.loadInitial()
        advanceUntilIdle()
        viewModel.setQuery(
            HomeQuery(
                feed = HomeFeed.TOP,
                categoryId = 42L,
                categorySlug = "android",
                tag = "kotlin",
                tagId = 7L,
                tagSlug = "kotlin"
            )
        )
        advanceUntilIdle()

        assertEquals(HomeFeed.TOP, repository.queries.last().feed)
        assertEquals(42L, repository.queries.last().categoryId)
        assertEquals(7L, repository.queries.last().tagId)
        assertEquals(listOf(2L), viewModel.uiState.value.topics.map(HomeTopic::id))
    }

    @Test
    fun incomingTopics_are_deduplicated_and_show_prompt() = runTest {
        val viewModel = HomeViewModel(FakeHomeRepository())

        viewModel.addIncomingTopic(11L)
        viewModel.addIncomingTopic(11L)
        viewModel.addIncomingTopic(12L)

        assertEquals(listOf(11L, 12L), viewModel.uiState.value.incomingTopicIds)
        assertTrue(viewModel.uiState.value.incomingPromptVisible)
    }

    @Test
    fun loadIncomingTopics_prepends_only_topics_missing_from_current_list() = runTest {
        val repository = FakeHomeRepository(
            HomePageResult.Success(HomePage(listOf(topic(1)), page = 0, hasMore = true)),
            byIds = listOf(topic(1), topic(2))
        )
        val viewModel = HomeViewModel(repository)

        viewModel.loadInitial()
        advanceUntilIdle()
        viewModel.addIncomingTopic(1L)
        viewModel.addIncomingTopic(2L)
        viewModel.loadIncomingTopics()
        advanceUntilIdle()

        assertEquals(listOf(2L, 1L), viewModel.uiState.value.topics.map(HomeTopic::id))
        assertEquals(listOf(1L, 2L), repository.byIdsCalls)
        assertTrue(viewModel.uiState.value.incomingTopicIds.isEmpty())
        assertFalse(viewModel.uiState.value.incomingPromptVisible)
    }

    @Test
    fun incomingTopics_wait_for_current_list_request_to_finish() = runTest {
        val repository = DeferredHomeRepository()
        val viewModel = HomeViewModel(repository)

        viewModel.loadInitial()
        viewModel.addIncomingTopic(2L)
        viewModel.loadIncomingTopics()

        assertTrue(viewModel.uiState.value.pendingIncomingRefresh)
        assertTrue(repository.byIdsCalls.isEmpty())

        repository.initial.complete(HomePageResult.Success(HomePage(listOf(topic(1)), 0, true)))
        advanceUntilIdle()

        assertEquals(listOf(2L), repository.byIdsCalls)
    }

    @Test
    fun refresh_clears_pending_incoming_topics() = runTest {
        val repository = FakeHomeRepository(
            HomePageResult.Success(HomePage(listOf(topic(1)), page = 0, hasMore = true)),
            HomePageResult.Success(HomePage(listOf(topic(3)), page = 0, hasMore = false))
        )
        val viewModel = HomeViewModel(repository)

        viewModel.loadInitial()
        advanceUntilIdle()
        viewModel.addIncomingTopic(2L)
        viewModel.refresh()

        assertTrue(viewModel.uiState.value.incomingTopicIds.isEmpty())
        assertFalse(viewModel.uiState.value.incomingPromptVisible)
    }
}

private fun topic(id: Long) = HomeTopic(
    id = id,
    title = "Topic $id",
    author = HomeUser(id = id, username = "user$id")
)

private open class FakeHomeRepository(
    private vararg val results: HomePageResult,
    private val byIds: List<HomeTopic> = emptyList()
) : HomeRepository {
    val calls = mutableListOf<Pair<HomeFeed, Int>>()
    val queries = mutableListOf<HomeQuery>()
    val byIdsCalls = mutableListOf<Long>()
    private var index = 0

    override suspend fun load(query: HomeQuery, page: Int): HomePageResult {
        calls += query.feed to page
        queries += query
        return results.getOrElse(index++) { results.last() }
    }

    override suspend fun loadByIds(topicIds: List<Long>): HomePageResult {
        byIdsCalls += topicIds
        return HomePageResult.Success(HomePage(byIds, page = 0, hasMore = false))
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

private class DeferredHomeRepository : HomeRepository {
    val initial = kotlinx.coroutines.CompletableDeferred<HomePageResult>()
    val byIdsCalls = mutableListOf<Long>()

    override suspend fun load(query: HomeQuery, page: Int): HomePageResult = initial.await()

    override suspend fun loadByIds(topicIds: List<Long>): HomePageResult {
        byIdsCalls += topicIds
        return HomePageResult.Success(HomePage(listOf(topic(2)), page = 0, hasMore = false))
    }
}
