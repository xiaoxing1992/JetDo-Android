package com.arkdo.android.feature.home

import com.arkdo.android.core.data.home.HomeRepository
import com.arkdo.android.core.data.session.SessionVersionSource
import com.arkdo.android.core.data.session.DefaultTopicReadStateSource
import com.arkdo.android.core.data.session.TopicReadStateSource
import com.arkdo.android.core.data.session.TopicReadStateUpdate
import com.arkdo.android.core.data.session.SidebarPrefsSource
import com.arkdo.android.core.data.site.CategoryLoadResult
import com.arkdo.android.core.data.site.CategoryRepository
import com.arkdo.android.core.model.HomeCategory
import com.arkdo.android.core.model.HomeFeed
import com.arkdo.android.core.model.HomePage
import com.arkdo.android.core.model.HomePageResult
import com.arkdo.android.core.model.HomeQuery
import com.arkdo.android.core.model.HomeTag
import com.arkdo.android.core.model.HomeTopic
import com.arkdo.android.core.model.HomeUser
import com.arkdo.android.core.model.SidebarPrefs
import com.arkdo.android.core.model.TagSuggestion
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

    @Before
    fun installStrings() {
        com.arkdo.android.core.data.AppContextHolder.resolver = { resId, _ ->
            if (resId == R.string.home_no_new_topics) "暂未获取到新话题，请稍后重试" else "x"
        }
    }

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        repository: HomeRepository = FakeHomeRepository(),
        categories: CategoryRepository = FakeCategoryRepository(),
        sidebar: SidebarPrefsSource = FakeSidebarPrefsSource(),
        session: SessionVersionSource = FakeSessionVersionSource(),
        readState: TopicReadStateSource = DefaultTopicReadStateSource()
    ): HomeViewModel = HomeViewModel(repository, categories, sidebar, session, readState)

    @Test
    fun loadInitial_publishesTopicsAndPaginationState() = runTest {
        val repository = FakeHomeRepository(
            HomePageResult.Success(
                HomePage(listOf(topic(1), topic(2)), page = 0, hasMore = true)
            )
        )
        val viewModel = viewModel(repository=repository)

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
        val viewModel = viewModel(repository=repository)

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
        val viewModel = viewModel(repository=repository)

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
        val viewModel = viewModel(repository=repository)

        viewModel.loadInitial()
        viewModel.loadInitial()
        advanceUntilIdle()

        assertEquals(1, repository.callCount)
    }

    @Test
    fun scroll_past_one_and_a_half_viewports_shows_back_to_top_fab() = runTest {
        val viewModel = viewModel(repository = FakeHomeRepository())

        viewModel.updateFabMode(scrollOffset = 1_501f, viewportHeight = 1_000f)

        assertTrue(viewModel.uiState.value.showBackToTopFab)
    }

    @Test
    fun scrolling_back_inside_hysteresis_hides_back_to_top_fab() = runTest {
        val viewModel = viewModel(repository = FakeHomeRepository())

        viewModel.updateFabMode(scrollOffset = 1_501f, viewportHeight = 1_000f)
        viewModel.updateFabMode(scrollOffset = 1_199f, viewportHeight = 1_000f)

        assertFalse(viewModel.uiState.value.showBackToTopFab)
    }

    @Test
    fun openDrawer_copiesQueryAndExpandsParent() = runTest {
        val parent = HomeCategory(id = 1L, name = "开发")
        val child = HomeCategory(id = 2L, name = "Android", parentCategoryId = 1L)
        val categories = FakeCategoryRepository(CategoryLoadResult.Success(listOf(parent, child)))
        val viewModel = viewModel(categories = categories)
        viewModel.ensureCategoriesLoaded()
        advanceUntilIdle()
        viewModel.setQuery(HomeQuery(feed = HomeFeed.HOT, categoryId = 2L, categoryName = "Android"))
        advanceUntilIdle()

        viewModel.openDrawer(isLoggedIn = true)

        val state = viewModel.uiState.value
        assertTrue(state.drawerOpen)
        assertEquals(2L, state.draftQuery.categoryId)
        assertEquals(HomeFeed.HOT, state.draftQuery.feed)
        assertEquals(listOf(1L), state.drawerExpandedIds)
    }

    @Test
    fun openDrawer_loadsSidebarPrefsOncePerSession() = runTest {
        val sidebar = FakeSidebarPrefsSource(
            SidebarPrefs(
                categoryIds = listOf(4L, 9L),
                tags = listOf(HomeTag(name = "纯水", id = 1461L))
            )
        )
        val session = FakeSessionVersionSource()
        val viewModel = viewModel(sidebar = sidebar, session = session)

        viewModel.openDrawer(isLoggedIn = true)
        advanceUntilIdle()
        assertEquals(listOf(4L, 9L), viewModel.uiState.value.myCategoryIds)
        assertEquals(1, sidebar.callCount)

        // 再开一次不重复拉：侧栏自选不在每次开抽屉时变。
        viewModel.closeDrawer()
        viewModel.openDrawer(isLoggedIn = true)
        advanceUntilIdle()
        assertEquals(1, sidebar.callCount)

        // 会话变了（登录/登出/换号）→ 置回未拉取，下次开抽屉重新取，并先清空旧账号的那组。
        session.emit(1L)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.myCategoryIds.isEmpty())
        viewModel.openDrawer(isLoggedIn = true)
        advanceUntilIdle()
        assertEquals(2, sidebar.callCount)
    }

    @Test
    fun blankTagQueryWithNoCategoryUsesTopTagsWithoutHittingTheNetwork() = runTest {
        val categories = FakeCategoryRepository(
            CategoryLoadResult.Success(
                categories = listOf(HomeCategory(id = 42L, name = "Android")),
                topTags = listOf(TagSuggestion(id = 1461L, name = "纯水", text = "纯水"))
            )
        )
        val viewModel = viewModel(categories = categories)
        viewModel.ensureCategoriesLoaded()
        advanceUntilIdle()
        viewModel.openDrawer(isLoggedIn = true)

        viewModel.loadHotTagsIfNeeded()
        advanceUntilIdle()

        assertEquals(listOf("纯水"), viewModel.uiState.value.tagResults.map(TagSuggestion::name))
        assertTrue(categories.tagCalls.isEmpty())
    }

    @Test
    fun tagSuggestionsAreScopedToTheDraftCategory() = runTest {
        val categories = FakeCategoryRepository()
        val viewModel = viewModel(categories = categories)
        viewModel.openDrawer(isLoggedIn = true)
        viewModel.updateDraft(
            HomeQuery(categoryId = 42L, categoryName = "Android"),
            isLoggedIn = true
        )

        viewModel.onTagQueryChanged("安")
        advanceUntilIdle()

        assertEquals(listOf("安" to 42L), categories.tagCalls)
    }

    @Test
    fun applyDraft_reloadsWhenQueryChanges() = runTest {
        val repository = FakeHomeRepository()
        val viewModel = viewModel(repository = repository)
        viewModel.loadInitial()
        advanceUntilIdle()
        viewModel.openDrawer(isLoggedIn = true)
        viewModel.updateDraft(HomeQuery(feed = HomeFeed.HOT), isLoggedIn = true)

        viewModel.applyDraft()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.drawerOpen)
        assertEquals(HomeFeed.HOT, viewModel.uiState.value.query.feed)
        assertEquals(2, repository.calls.size)
    }

    @Test
    fun applyDraft_sameQueryDoesNotReload() = runTest {
        val repository = FakeHomeRepository()
        val viewModel = viewModel(repository = repository)
        viewModel.loadInitial()
        advanceUntilIdle()
        viewModel.openDrawer(isLoggedIn = true)

        viewModel.applyDraft()
        advanceUntilIdle()

        assertEquals(1, repository.calls.size)
    }

    @Test
    fun loggedOutUnreadDraftFallsBackToLatest() = runTest {
        val viewModel = viewModel()
        viewModel.openDrawer(isLoggedIn = false)
        viewModel.updateDraft(HomeQuery(feed = HomeFeed.UNREAD), isLoggedIn = false)

        assertEquals(HomeFeed.LATEST, viewModel.uiState.value.draftQuery.feed)
    }

    @Test
    fun setQuery_reloads_using_the_complete_legacy_scope() = runTest {
        val repository = FakeHomeRepository(
            HomePageResult.Success(HomePage(listOf(topic(1)), page = 0, hasMore = false)),
            HomePageResult.Success(HomePage(listOf(topic(2)), page = 0, hasMore = false))
        )
        val viewModel = viewModel(repository=repository)

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
        val viewModel = viewModel(repository = FakeHomeRepository())

        viewModel.addIncomingTopic(11L)
        viewModel.addIncomingTopic(11L)
        viewModel.addIncomingTopic(12L)

        assertEquals(listOf(11L, 12L), viewModel.uiState.value.incomingTopicIds)
        assertTrue(viewModel.uiState.value.incomingPromptVisible)
    }

    @Test
    fun dismissIncomingTopics_clears_prompt_and_pending_insert() = runTest {
        val viewModel = viewModel(repository = FakeHomeRepository())

        viewModel.addIncomingTopic(11L)
        viewModel.dismissIncomingTopics()

        assertTrue(viewModel.uiState.value.incomingTopicIds.isEmpty())
        assertFalse(viewModel.uiState.value.incomingPromptVisible)
        assertTrue(viewModel.uiState.value.incomingInsertIds.isEmpty())
    }

    @Test
    fun loadIncomingTopics_moves_updated_rows_to_the_top_and_keeps_fresh_data() = runTest {
        val updated = topic(id = 1L, title = "Topic 1 updated")
        val repository = FakeHomeRepository(
            HomePageResult.Success(HomePage(listOf(topic(1), topic(2), topic(3)), page = 0, hasMore = true)),
            byIds = listOf(updated, topic(4), updated)
        )
        val viewModel = viewModel(repository = repository)

        viewModel.loadInitial()
        advanceUntilIdle()
        viewModel.addIncomingTopic(1L)
        viewModel.addIncomingTopic(4L)
        viewModel.loadIncomingTopics()
        advanceUntilIdle()

        val topics = viewModel.uiState.value.topics
        assertEquals(listOf(1L, 4L, 2L, 3L), topics.map(HomeTopic::id))
        assertEquals("Topic 1 updated", topics.first().title)
        assertEquals(listOf(1L, 4L), viewModel.uiState.value.incomingInsertIds)
        assertEquals(listOf(1L, 4L), viewModel.uiState.value.incomingHighlightIds)
        assertTrue(viewModel.uiState.value.incomingHighlightUntil > System.currentTimeMillis())
        assertTrue(viewModel.uiState.value.isLoadingIncoming)
        val epoch = viewModel.uiState.value.incomingInsertEpoch
        viewModel.finishIncomingInsert(epoch)
        assertTrue(viewModel.uiState.value.incomingInsertIds.isEmpty())
        assertEquals(listOf(1L, 4L), viewModel.uiState.value.incomingHighlightIds)
        viewModel.finishIncomingHighlight()
        assertTrue(viewModel.uiState.value.incomingHighlightIds.isEmpty())
        assertEquals(0L, viewModel.uiState.value.incomingHighlightUntil)
        assertTrue(viewModel.uiState.value.incomingTopicIds.isEmpty())
        assertFalse(viewModel.uiState.value.incomingPromptVisible)
        assertFalse(viewModel.uiState.value.isLoadingIncoming)
    }

    @Test
    fun loadIncomingTopics_keeps_the_prompt_when_fetch_fails_or_returns_empty() = runTest {
        val failed = OutcomeHomeRepository(HomePageResult.Failure("加载新话题失败，请重试"))
        val failedViewModel = viewModel(repository = failed)
        failedViewModel.addIncomingTopic(9L)
        failedViewModel.loadIncomingTopics()
        advanceUntilIdle()

        assertEquals(listOf(9L), failedViewModel.uiState.value.incomingTopicIds)
        assertTrue(failedViewModel.uiState.value.incomingPromptVisible)
        assertEquals("加载新话题失败，请重试", failedViewModel.uiState.value.error)
        assertFalse(failedViewModel.uiState.value.isLoadingIncoming)

        val empty = OutcomeHomeRepository(
            HomePageResult.Success(HomePage(emptyList(), page = 0, hasMore = false))
        )
        val emptyViewModel = viewModel(repository = empty)
        emptyViewModel.addIncomingTopic(9L)
        emptyViewModel.loadIncomingTopics()
        advanceUntilIdle()

        assertEquals(listOf(9L), emptyViewModel.uiState.value.incomingTopicIds)
        assertTrue(emptyViewModel.uiState.value.incomingPromptVisible)
        assertEquals("暂未获取到新话题，请稍后重试", emptyViewModel.uiState.value.error)
    }

    @Test
    fun loadIncomingTopics_prepends_only_topics_missing_from_current_list() = runTest {
        val repository = FakeHomeRepository(
            HomePageResult.Success(HomePage(listOf(topic(1)), page = 0, hasMore = true)),
            byIds = listOf(topic(1), topic(2))
        )
        val viewModel = viewModel(repository=repository)

        viewModel.loadInitial()
        advanceUntilIdle()
        viewModel.addIncomingTopic(1L)
        viewModel.addIncomingTopic(2L)
        viewModel.loadIncomingTopics()
        advanceUntilIdle()

        assertEquals(listOf(1L, 2L), viewModel.uiState.value.topics.map(HomeTopic::id))
        assertEquals(listOf(1L, 2L), repository.byIdsCalls)
        assertEquals(listOf(1L, 2L), viewModel.uiState.value.incomingInsertIds)
        assertEquals(listOf(1L, 2L), viewModel.uiState.value.incomingHighlightIds)
        assertTrue(viewModel.uiState.value.isLoadingIncoming)
        val epoch = viewModel.uiState.value.incomingInsertEpoch
        viewModel.finishIncomingInsert(epoch)
        assertTrue(viewModel.uiState.value.incomingInsertIds.isEmpty())
        assertEquals(listOf(1L, 2L), viewModel.uiState.value.incomingHighlightIds)
        assertTrue(viewModel.uiState.value.incomingHighlightUntil > 0L)
        assertTrue(viewModel.uiState.value.incomingTopicIds.isEmpty())
        assertFalse(viewModel.uiState.value.incomingPromptVisible)
        assertFalse(viewModel.uiState.value.isLoadingIncoming)
    }

    @Test
    fun incomingTopics_wait_for_current_list_request_to_finish() = runTest {
        val repository = DeferredHomeRepository()
        val viewModel = viewModel(repository=repository)

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
        val viewModel = viewModel(repository=repository)

        viewModel.loadInitial()
        advanceUntilIdle()
        viewModel.addIncomingTopic(2L)
        viewModel.refresh()

        assertTrue(viewModel.uiState.value.incomingTopicIds.isEmpty())
        assertFalse(viewModel.uiState.value.incomingPromptVisible)
    }

    @Test
    fun session_version_change_reloads_the_current_list() = runTest {
        val repository = FakeHomeRepository(
            HomePageResult.Success(HomePage(listOf(topic(1)), page = 0, hasMore = false)),
            HomePageResult.Success(HomePage(listOf(topic(5)), page = 0, hasMore = false))
        )
        val session = FakeSessionVersionSource()
        val viewModel = viewModel(repository = repository, session = session)

        viewModel.loadInitial()
        advanceUntilIdle()
        session.emit(1L)
        advanceUntilIdle()

        assertEquals(listOf(5L), viewModel.uiState.value.topics.map(HomeTopic::id))
        assertEquals(2, repository.calls.size)
    }

    @Test
    fun session_version_change_queues_while_a_list_request_is_in_flight() = runTest {
        val repository = DeferredHomeRepository()
        val session = FakeSessionVersionSource()
        val viewModel = viewModel(repository = repository, session = session)

        viewModel.loadInitial()
        session.emit(1L)

        assertTrue(viewModel.uiState.value.pendingSessionRefresh)
        assertEquals(0, repository.loadCalls)

        repository.initial.complete(HomePageResult.Success(HomePage(listOf(topic(1)), 0, false)))
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.pendingSessionRefresh)
        assertEquals(2, repository.loadCalls)
        assertEquals(listOf(7L), viewModel.uiState.value.topics.map(HomeTopic::id))
    }

    @Test
    fun session_version_already_seen_does_not_reload() = runTest {
        val repository = FakeHomeRepository(
            HomePageResult.Success(HomePage(listOf(topic(1)), page = 0, hasMore = false))
        )
        val session = FakeSessionVersionSource(current = 3L)
        val viewModel = viewModel(repository = repository, session = session)

        viewModel.loadInitial()
        advanceUntilIdle()
        session.emit(3L)
        advanceUntilIdle()

        assertEquals(1, repository.calls.size)
    }

    @Test
    fun read_state_update_clears_unseen_and_recomputes_watermark() = runTest {
        val repository = FakeHomeRepository(
            HomePageResult.Success(
                HomePage(
                    listOf(
                        topic(1).copy(
                            unseen = true,
                            unreadPosts = 5,
                            lastReadPostNumber = 2,
                            highestPostNumber = 20
                        ),
                        topic(2)
                    ),
                    page = 0,
                    hasMore = false
                )
            )
        )
        val readState = DefaultTopicReadStateSource()
        val viewModel = viewModel(repository = repository, readState = readState)
        viewModel.loadInitial()
        advanceUntilIdle()

        // 详情页离开结算：蓝点消掉，未读改 3，水位同步成 最高楼20 − 剩余未读3 = 17。
        readState.publish(TopicReadStateUpdate(topicId = 1, unseen = false, unreadPosts = 3))

        val patched = viewModel.uiState.value.topics.first { it.id == 1L }
        assertEquals(false, patched.unseen)
        assertEquals(3, patched.unreadPosts)
        assertEquals(17, patched.lastReadPostNumber)
        // 无关行不动。
        assertEquals(false, viewModel.uiState.value.topics.first { it.id == 2L }.unseen)
    }

    @Test
    fun read_state_update_ignores_topic_not_in_list() = runTest {
        val repository = FakeHomeRepository(
            HomePageResult.Success(HomePage(listOf(topic(1)), page = 0, hasMore = false))
        )
        val readState = DefaultTopicReadStateSource()
        val viewModel = viewModel(repository = repository, readState = readState)
        viewModel.loadInitial()
        advanceUntilIdle()

        readState.publish(TopicReadStateUpdate(topicId = 999L, unseen = false, unreadPosts = 0))

        assertEquals(listOf(1L), viewModel.uiState.value.topics.map(HomeTopic::id))
        val row = viewModel.uiState.value.topics.single()
        assertEquals(topic(1).unseen, row.unseen)
    }

    @Test
    fun read_state_source_replays_current_value_to_late_subscribers() {
        // 源语义：订阅时先吐当前值（对齐鸿蒙「先取当前已知值，再订阅后续变化」）；
        // 退订后不再收到。
        val source = DefaultTopicReadStateSource()
        val receivedFirst = mutableListOf<TopicReadStateUpdate>()
        val receivedSecond = mutableListOf<TopicReadStateUpdate>()

        source.publish(TopicReadStateUpdate(topicId = 1, unseen = false, unreadPosts = 2))
        val unsubscribeFirst = source.subscribe { receivedFirst += it }
        source.subscribe { receivedSecond += it }

        source.publish(TopicReadStateUpdate(topicId = 1, unseen = false, unreadPosts = 0))
        unsubscribeFirst()
        source.publish(TopicReadStateUpdate(topicId = 1, unseen = false, unreadPosts = 0))

        // 首个订阅者先吃到当前值，再吃到一次发布，退订后不再收。
        assertEquals(2, receivedFirst.size)
        // 后来的订阅者同样先吃到当前值重放，再吃到两次发布（退订只作用于第一个订阅者）。
        assertEquals(3, receivedSecond.size)
        assertEquals(0, source.current()?.unreadPosts)
    }
}

private fun topic(id: Long, title: String = "Topic $id") = HomeTopic(
    id = id,
    title = title,
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

private class OutcomeHomeRepository(
    private val result: HomePageResult
) : HomeRepository {
    override suspend fun load(query: HomeQuery, page: Int): HomePageResult = result

    override suspend fun loadByIds(topicIds: List<Long>): HomePageResult = result
}

private class DeferredHomeRepository : HomeRepository {
    val initial = kotlinx.coroutines.CompletableDeferred<HomePageResult>()
    val byIdsCalls = mutableListOf<Long>()
    var loadCalls = 0

    override suspend fun load(query: HomeQuery, page: Int): HomePageResult {
        loadCalls += 1
        return if (loadCalls == 1) {
            initial.await()
        } else {
            HomePageResult.Success(HomePage(listOf(topic(7)), page = 0, hasMore = false))
        }
    }

    override suspend fun loadByIds(topicIds: List<Long>): HomePageResult {
        byIdsCalls += topicIds
        return HomePageResult.Success(HomePage(listOf(topic(2)), page = 0, hasMore = false))
    }
}

private class FakeSessionVersionSource(current: Long = 0L) : SessionVersionSource {
    private var version = current
    private val listeners = mutableListOf<(Long) -> Unit>()

    override fun currentVersion(): Long = version

    override fun subscribe(listener: (Long) -> Unit): () -> Unit {
        listeners += listener
        return { listeners.remove(listener) }
    }

    fun emit(next: Long) {
        version = next
        listeners.toList().forEach { it(next) }
    }
}

private class FakeCategoryRepository(
    private val result: CategoryLoadResult = CategoryLoadResult.Success(
        listOf(HomeCategory(id = 42L, name = "Android", parentCategoryId = null))
    )
) : CategoryRepository {
    var callCount = 0
    val tagCalls = mutableListOf<Pair<String, Long?>>()

    override suspend fun load(): CategoryLoadResult {
        callCount += 1
        return result
    }

    override suspend fun suggestTags(
        query: String,
        categoryId: Long?,
        limit: Int
    ): List<TagSuggestion> {
        tagCalls += query to categoryId
        return emptyList()
    }
}

private class FakeSidebarPrefsSource(
    private val prefs: SidebarPrefs = SidebarPrefs.Empty
) : SidebarPrefsSource {
    var callCount = 0
    override suspend fun load(): SidebarPrefs {
        callCount += 1
        return prefs
    }
}
