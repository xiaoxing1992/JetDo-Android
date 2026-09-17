package com.arkdo.android.feature.search

import com.arkdo.android.core.data.search.SearchRepository
import com.arkdo.android.core.model.HomeTopic
import com.arkdo.android.core.model.HomeUser
import com.arkdo.android.core.model.SearchFilters
import com.arkdo.android.core.model.SearchHit
import com.arkdo.android.core.model.SearchPage
import com.arkdo.android.core.model.SearchPageResult
import com.arkdo.android.core.model.SearchType
import com.arkdo.android.core.model.SearchUsersResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
class SearchViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun topic(id: Long) = HomeTopic(id = id, title = "Topic $id")

    private fun viewModel(
        repository: SearchRepository = FakeSearchRepository(),
        history: SearchHistoryStore = InMemorySearchHistoryStore()
    ) = SearchViewModel(repository, history)

    @Test
    fun `输入后先 debounce 再发请求`() = runTest {
        val repo = FakeSearchRepository(SearchPageResult.Success(SearchPage(listOf(SearchHit(topic(1))), 1, false)))
        val vm = viewModel(repository = repo)

        vm.setQuery("kotlin")
        // debounce 未到点:不该发请求。
        advanceTimeBy(SEARCH_DEBOUNCE_MS - 1)
        assertTrue(repo.queries.isEmpty())

        advanceUntilIdle()
        assertEquals(1, repo.queries.size)
        assertEquals(listOf(1L), vm.uiState.value.hits.map { it.topic.id })
    }

    @Test
    fun `连续输入只发最后一次请求`() = runTest {
        val repo = FakeSearchRepository(SearchPageResult.Success(SearchPage(emptyList(), 1, false)))
        val vm = viewModel(repository = repo)

        vm.setQuery("k")
        vm.setQuery("ko")
        vm.setQuery("kotlin")
        advanceUntilIdle()

        // 前两次的 debounce 都被取消,只留最后一次。
        assertEquals(1, repo.queries.size)
        assertEquals("kotlin", repo.queries.single())
    }

    @Test
    fun `清空输入回到空态且不发请求`() = runTest {
        val repo = FakeSearchRepository(SearchPageResult.Success(SearchPage(listOf(SearchHit(topic(1))), 1, false)))
        val vm = viewModel(repository = repo)

        vm.setQuery("kotlin")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.hits.isNotEmpty())

        vm.setQuery("")
        advanceUntilIdle()

        assertTrue(vm.uiState.value.hits.isEmpty())
        assertFalse(vm.uiState.value.hasSearched)
        assertEquals(1, repo.queries.size) // 没有新的请求
    }

    @Test
    fun `关键词与筛选条件一起拼进 q 串`() = runTest {
        val repo = FakeSearchRepository(SearchPageResult.Success(SearchPage(emptyList(), 1, false)))
        val vm = viewModel(repository = repo)

        vm.setQuery("kotlin")
        advanceUntilIdle()
        vm.applyFilters(SearchFilters(categoryId = 42, categorySlug = "android", order = "likes"))
        advanceUntilIdle()

        assertEquals("kotlin #android order:likes", repo.queries.last())
    }

    @Test
    fun `高亮词在搜索时定格并过滤高级语法`() = runTest {
        val repo = FakeSearchRepository(SearchPageResult.Success(SearchPage(emptyList(), 1, false)))
        val vm = viewModel(repository = repo)

        vm.setQuery("kotlin in:title #android")
        advanceUntilIdle()

        assertEquals(listOf("kotlin"), vm.uiState.value.highlights)
    }

    @Test
    fun `翻页追加结果并按 id 去重`() = runTest {
        val repo = FakeSearchRepository(
            SearchPageResult.Success(SearchPage(listOf(SearchHit(topic(1)), SearchHit(topic(2))), 1, true)),
            SearchPageResult.Success(SearchPage(listOf(SearchHit(topic(2)), SearchHit(topic(3))), 2, false))
        )
        val vm = viewModel(repository = repo)

        vm.setQuery("kotlin")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.hasMore)

        vm.loadMore()
        advanceUntilIdle()

        assertEquals(listOf(1L, 2L, 3L), vm.uiState.value.hits.map { it.topic.id })
        assertFalse(vm.uiState.value.hasMore)
    }

    @Test
    fun `翻到第 10 页后即使服务端说还有也不再有更多`() = runTest {
        // Discourse 整页搜索硬上限 10 页:服务端 `more_full_page_results` 一直为真时,
        // 也必须自己停在第 10 页,否则会无限翻下去。
        val pages = (1..MAX_SEARCH_PAGE).map { p ->
            SearchPageResult.Success(SearchPage(listOf(SearchHit(topic(p.toLong()))), p, hasMore = true))
        }
        val repo = FakeSearchRepository(*pages.toTypedArray())
        val vm = viewModel(repository = repo)

        vm.setQuery("kotlin")
        advanceUntilIdle()
        // 第 1 页已到,再翻 9 次正好停在第 10 页。
        repeat(MAX_SEARCH_PAGE - 1) {
            vm.loadMore()
            advanceUntilIdle()
        }

        assertEquals(MAX_SEARCH_PAGE, vm.uiState.value.hits.size)
        assertFalse(vm.uiState.value.hasMore)
    }

    @Test
    fun `过期响应被丢弃`() = runTest {
        // 第一次请求慢、第二次快:慢响应回来时序号已过期,不能覆盖新结果。
        val repo = DeferredSearchRepository()
        val vm = viewModel(repository = repo)

        vm.setQuery("old")
        advanceUntilIdle()
        vm.setQuery("new")
        advanceUntilIdle()

        // 先让第二次(新词)的响应回来。
        repo.pending[1].complete(
            SearchPageResult.Success(SearchPage(listOf(SearchHit(topic(99))), 1, false))
        )
        advanceUntilIdle()
        assertEquals(listOf(99L), vm.uiState.value.hits.map { it.topic.id })

        // 再让第一次(旧词)的慢响应回来 —— 必须被丢弃。
        repo.pending[0].complete(
            SearchPageResult.Success(SearchPage(listOf(SearchHit(topic(1))), 1, false))
        )
        advanceUntilIdle()
        assertEquals(listOf(99L), vm.uiState.value.hits.map { it.topic.id })
    }

    @Test
    fun `切换到用户类型走用户接口且无分页`() = runTest {
        val repo = FakeSearchRepository(SearchPageResult.Success(SearchPage(emptyList(), 1, false)))
        repo.userResult = SearchUsersResult(listOf(HomeUser(id = 7, username = "ada")))
        val vm = viewModel(repository = repo)

        vm.setQuery("ada")
        advanceUntilIdle()
        vm.setSearchType(SearchType.USERS)
        advanceUntilIdle()

        assertEquals(listOf("ada"), repo.userTerms)
        assertEquals(listOf(7L), vm.uiState.value.users.map { it.id })
        assertFalse(vm.uiState.value.hasMore)
    }

    @Test
    fun `切换到用户类型清掉旧的帖子结果`() = runTest {
        val repo = FakeSearchRepository(SearchPageResult.Success(SearchPage(listOf(SearchHit(topic(1))), 1, false)))
        repo.userResult = SearchUsersResult(emptyList())
        val vm = viewModel(repository = repo)

        vm.setQuery("x")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.hits.isNotEmpty())

        vm.setSearchType(SearchType.USERS)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.hits.isEmpty())
    }

    @Test
    fun `用户类型下不触发分页`() = runTest {
        val repo = FakeSearchRepository(SearchPageResult.Success(SearchPage(emptyList(), 1, true)))
        repo.userResult = SearchUsersResult(listOf(HomeUser(id = 1, username = "a")))
        val vm = viewModel(repository = repo)

        vm.setSearchType(SearchType.USERS)
        vm.setQuery("a")
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()

        assertEquals(1, repo.userTerms.size)
    }

    @Test
    fun `搜索失败给出提示且不残留结果`() = runTest {
        val repo = FakeSearchRepository(
            SearchPageResult.Failure("boom", requiresChallenge = false)
        )
        val vm = viewModel(repository = repo)

        vm.setQuery("kotlin")
        advanceUntilIdle()

        assertEquals("搜索失败，请检查网络后重试", vm.uiState.value.error)
        assertTrue(vm.uiState.value.hits.isEmpty())
    }

    @Test
    fun `需要过盾时给专门的提示文案`() = runTest {
        val repo = FakeSearchRepository(SearchPageResult.Failure("challenge", requiresChallenge = true))
        val vm = viewModel(repository = repo)

        vm.setQuery("kotlin")
        advanceUntilIdle()

        assertTrue(vm.uiState.value.requiresChallenge)
        assertEquals("搜索遇到访问验证，请稍后重试", vm.uiState.value.error)
    }

    @Test
    fun `翻页失败保留已有结果`() = runTest {
        val repo = FakeSearchRepository(
            SearchPageResult.Success(SearchPage(listOf(SearchHit(topic(1))), 1, true)),
            SearchPageResult.Failure("boom")
        )
        val vm = viewModel(repository = repo)

        vm.setQuery("kotlin")
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()

        assertEquals(listOf(1L), vm.uiState.value.hits.map { it.topic.id })
        assertFalse(vm.uiState.value.isLoadingMore)
    }

    @Test
    fun `点开结果才记入历史`() = runTest {
        val repo = FakeSearchRepository(SearchPageResult.Success(SearchPage(emptyList(), 1, false)))
        val history = InMemorySearchHistoryStore()
        val vm = viewModel(repository = repo, history = history)

        vm.setQuery("kotlin")
        advanceUntilIdle()
        // 只是搜索过,没点结果:不该写历史。
        assertTrue(vm.uiState.value.history.isEmpty())

        vm.onResultOpened()
        assertEquals(listOf("kotlin"), vm.uiState.value.history)
    }

    @Test
    fun `历史去重置顶并封顶十条`() = runTest {
        val history = InMemorySearchHistoryStore((1..10).map { "term$it" })
        val vm = viewModel(history = history)

        vm.applyHistoryTerm("kotlin")
        advanceUntilIdle()
        vm.onResultOpened()

        val loaded = vm.uiState.value.history
        assertEquals(10, loaded.size)
        assertEquals("kotlin", loaded.first())
    }

    @Test
    fun `删一条与清空历史`() = runTest {
        val history = InMemorySearchHistoryStore(listOf("a", "b", "c"))
        val vm = viewModel(history = history)

        vm.removeHistory("b")
        assertEquals(listOf("a", "c"), vm.uiState.value.history)

        vm.clearHistory()
        assertTrue(vm.uiState.value.history.isEmpty())
    }

    @Test
    fun `筛选面板开关`() = runTest {
        val vm = viewModel()

        vm.openFilterPanel()
        assertTrue(vm.uiState.value.showFilterPanel)

        vm.dismissFilterPanel()
        assertFalse(vm.uiState.value.showFilterPanel)
    }

    @Test
    fun `应用筛选条件后面板关闭`() = runTest {
        val vm = viewModel()

        vm.openFilterPanel()
        vm.applyFilters(SearchFilters(status = "solved"))

        assertFalse(vm.uiState.value.showFilterPanel)
        assertEquals(1, vm.uiState.value.activeFilterCount)
    }

    @Test
    fun `空态只在搜过且无结果时成立`() = runTest {
        val repo = FakeSearchRepository(SearchPageResult.Success(SearchPage(emptyList(), 1, false)))
        val vm = viewModel(repository = repo)

        assertFalse(vm.uiState.value.isEmpty)

        vm.setQuery("nothing")
        advanceUntilIdle()

        assertTrue(vm.uiState.value.isEmpty)
    }
}

private class FakeSearchRepository(
    private vararg val pages: SearchPageResult
) : SearchRepository {
    val queries = mutableListOf<String>()
    val userTerms = mutableListOf<String>()
    var userResult: SearchUsersResult = SearchUsersResult(emptyList())
    private var index = 0

    override suspend fun searchTopics(query: String, page: Int): SearchPageResult {
        queries += query
        return pages.getOrElse(index++) { pages.lastOrNull() ?: SearchPageResult.Success(SearchPage(emptyList(), page, false)) }
    }

    override suspend fun searchUsers(term: String, limit: Int): SearchUsersResult {
        userTerms += term
        return userResult
    }
}

/** 手动控制每个请求的完成时机,用于验证过期响应丢弃。 */
private class DeferredSearchRepository : SearchRepository {
    val pending = mutableListOf<kotlinx.coroutines.CompletableDeferred<SearchPageResult>>()

    override suspend fun searchTopics(query: String, page: Int): SearchPageResult {
        val deferred = kotlinx.coroutines.CompletableDeferred<SearchPageResult>()
        pending += deferred
        return deferred.await()
    }

    override suspend fun searchUsers(term: String, limit: Int): SearchUsersResult =
        SearchUsersResult(emptyList())
}