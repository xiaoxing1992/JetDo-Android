package com.arkdo.android.feature.search

import com.arkdo.android.feature.search.R
import com.arkdo.android.core.data.AppContextHolder
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkdo.android.core.data.search.SearchAuthGate
import com.arkdo.android.core.data.search.SearchRepository
import com.arkdo.android.core.model.HomeUser
import com.arkdo.android.core.model.SearchFilters
import com.arkdo.android.core.model.SearchHit
import com.arkdo.android.core.model.SearchPageResult
import com.arkdo.android.core.model.SearchType
import com.arkdo.android.core.model.TagSuggestion
import com.arkdo.android.core.model.buildSearchQuery
import com.arkdo.android.core.model.parseSearchHighlights
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 输入停顿多久才真正发起搜索(边打字边搜,避免每次击键都请求)。 */
internal const val SEARCH_DEBOUNCE_MS = 350L

/**
 * 少于该字数不发起搜索。linux.do 单个汉字也能搜出结果(站点把 `min_search_term_length`
 * 调低了),所以这里只挡空串;真正过短由服务端 `filter_short_terms` 兜底。
 */
internal const val MIN_TERM_LEN = 1

/** Discourse 整页搜索分页硬上限。 */
internal const val MAX_SEARCH_PAGE = 10

/** 标签联想防抖(对齐鸿蒙 TAG_DEBOUNCE_MS,与搜索框同一量级)。 */
internal const val TAG_DEBOUNCE_MS = 300L

internal data class SearchUiState(
    val query: String = "",
    val searchType: SearchType = SearchType.TOPICS,
    /** 帖子结果。 */
    val hits: List<SearchHit> = emptyList(),
    /** 「用户」类型的结果(帖子结果仍在 [hits] 里)。 */
    val users: List<HomeUser> = emptyList(),
    val filters: SearchFilters = SearchFilters(),
    /** 拆出的普通高亮词(已去掉 `in:` / `#版块` / `@用户` 等高级语法)。 */
    val highlights: List<String> = emptyList(),
    val history: List<String> = emptyList(),
    val isSearching: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val hasSearched: Boolean = false,
    val error: String? = null,
    val requiresChallenge: Boolean = false,
    val showFilterPanel: Boolean = false,
    /** WebView 宿主(注入式 fetch 桥)是否就绪;未就绪时不发请求,由宿主页面注入。 */
    val isHostReady: Boolean = false,
    /** 标签联想:面板搜索框的输入词(空 = 热门标签)。 */
    val tagQuery: String = "",
    /** 标签联想候选(已剔除本次筛选里选中的标签)。 */
    val tagSuggestions: List<TagSuggestion> = emptyList(),
    val isTagLoading: Boolean = false,
    /** 分类树由宿主注入(结果项渲染分类徽标用),feature 层不自己拉站点元数据。 */
    val categories: List<com.arkdo.android.core.model.HomeCategory> = emptyList()
) {
    /** 关键词或筛选条件任一非空即可搜(筛选条件单独也能收窄,但仍要求有关键词见 [canSearch])。 */
    val canSearch: Boolean get() = query.trim().length >= MIN_TERM_LEN

    /** 结果为空且已搜过且没在加载 → 空态。 */
    val isEmpty: Boolean
        get() = hasSearched && !isSearching && error == null &&
            if (searchType == SearchType.USERS) users.isEmpty() else hits.isEmpty()

    val activeFilterCount: Int get() = filters.activeCount()
}

/**
 * 搜索 ViewModel。
 *
 * 关键行为(对齐鸿蒙 SearchOverlay):
 * - **边打字边搜**:输入后 debounce [SEARCH_DEBOUNCE_MS] 才发请求;
 * - **过期响应丢弃**:每次请求带自增序号,响应回来时序号不匹配就丢,避免慢响应覆盖新结果;
 * - **两种类型两个接口**:帖子走 /search.json(可分页),用户走 /u/search/users(一次性);
 * - **历史只在「有效搜索」时记**:点开某条结果才算一次,不是每次击键都写。
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: SearchRepository,
    private val historyStore: SearchHistoryStore,
    private val authGate: SearchAuthGate
) : ViewModel() {

    /** WebView 宿主未就绪时的提示（运行期取值，产出与比对同源）。 */
    private val hostNotReadyMessage: String
        get() = AppContextHolder.getString(R.string.composer_env_not_ready)

    /** 未登录时搜索被服务端拦截的提示。 */
    private val loginRequiredMessage: String
        get() = AppContextHolder.getString(R.string.search_login_required)


    private val _uiState = MutableStateFlow(SearchUiState())
    internal val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var debounceJob: Job? = null
    /** 请求序号:边打字边搜时丢弃过期响应(只认最后一次)。 */
    private var searchSeq = 0
    /** 标签联想的请求序号与防抖,与主搜索相互独立。 */
    private var tagSeq = 0
    private var tagDebounceJob: Job? = null
    private var currentPage = 1
    private val seenTopicIds = HashSet<Long>()

    init {
        _uiState.update { it.copy(history = historyStore.load()) }
    }

    fun setQuery(query: String) {
        if (_uiState.value.query == query) return
        _uiState.update { it.copy(query = query) }
        scheduleSearch(reset = true)
    }

    fun setSearchType(type: SearchType) {
        if (_uiState.value.searchType == type) return
        // 换类型等于换接口,清掉旧结果再搜,避免"用户"页里残留上次的帖子。
        _uiState.update {
            it.copy(
                searchType = type,
                hits = emptyList(),
                users = emptyList(),
                hasMore = false,
                error = null,
                hasSearched = false
            )
        }
        currentPage = 1
        seenTopicIds.clear()
        // 对齐鸿蒙 switchSearchType:取消防抖、立即按新类型重搜(不是再等 350ms)。
        debounceJob?.cancel()
        if (_uiState.value.canSearch) {
            runSearch(reset = true)
        }
    }

    /**
     * 提交当前输入(软键盘搜索键 / 点历史词)。立即搜,不等 debounce;
     * 主动提交记入历史(对齐鸿蒙 handleSubmit:回车即留痕,边打字边搜不写)。
     */
    fun submit() {
        debounceJob?.cancel()
        if (!_uiState.value.canSearch) return
        onResultOpened()
        runSearch(reset = true)
    }

    fun applyHistoryTerm(term: String) {
        _uiState.update { it.copy(query = term) }
        submit()
    }

    /** 筛选面板点「应用」:整体换成新条件并立即重搜(不记历史;没关键词就不搜,对齐鸿蒙 applyFilters)。 */
    fun applyFilters(filters: SearchFilters) {
        _uiState.update { it.copy(filters = filters, showFilterPanel = false) }
        if (_uiState.value.canSearch) {
            debounceJob?.cancel()
            runSearch(reset = true)
        }
    }

    fun openFilterPanel() {
        _uiState.update { it.copy(showFilterPanel = true) }
        // 面板一打开就有候选可点(对齐鸿蒙 aboutToAppear → loadTagSuggestions(''));
        // 按当前词刷新,顺带把刚选中的标签从候选里剔除。
        if (_uiState.value.tagQuery.isBlank()) {
            loadTagSuggestions("")
        } else {
            loadTagSuggestions(_uiState.value.tagQuery)
        }
    }

    fun dismissFilterPanel() {
        _uiState.update { it.copy(showFilterPanel = false) }
    }

    fun loadMore() {
        val state = _uiState.value
        // 只有帖子搜索分页;用户搜索是一次性返回。
        if (state.searchType != SearchType.TOPICS) return
        if (state.isSearching || state.isLoadingMore || !state.hasMore) return
        if (state.hits.isEmpty()) return
        runSearch(reset = false)
    }

    fun retry() {
        submit()
    }

    /** 点开结果算一次有效搜索,记入历史。 */
    fun onResultOpened() {
        val term = _uiState.value.query.trim()
        if (term.isEmpty()) return
        _uiState.update { it.copy(history = historyStore.add(term)) }
    }

    fun removeHistory(term: String) {
        _uiState.update { it.copy(history = historyStore.remove(term)) }
    }

    fun clearHistory() {
        historyStore.clear()
        _uiState.update { it.copy(history = emptyList()) }
    }

    /** 宿主注入分类树(结果项渲染分类徽标用)。 */
    fun setCategories(categories: List<com.arkdo.android.core.model.HomeCategory>) {
        _uiState.update { it.copy(categories = categories) }
    }

    /**
     * 宿主注入 WebView 就绪状态(对齐鸿蒙 isArkWebReady)。
     * 就绪瞬间若停在「正在准备请求环境」的错误态,自动补发一次被拦下的搜索。
     */
    fun setHostReady(ready: Boolean) {
        val wasReady = _uiState.value.isHostReady
        _uiState.update { it.copy(isHostReady = ready) }
        if (ready && !wasReady && _uiState.value.error == hostNotReadyMessage) {
            submit()
        }
    }

    // ---------- 标签联想(对齐鸿蒙 SearchFilterPanel 的 /tags/filter/search 联想) ----------

    /**
     * 面板标签搜索框输入:防抖 300ms 后联想;清空输入不是「没有候选」,
     * 而是回到默认的热门标签列表(对齐鸿蒙 handleTagQueryChanged)。
     */
    fun onTagQueryChange(query: String) {
        _uiState.update { it.copy(tagQuery = query) }
        tagDebounceJob?.cancel()
        tagDebounceJob = viewModelScope.launch {
            delay(TAG_DEBOUNCE_MS)
            loadTagSuggestions(query)
        }
    }

    /** 重置标签搜索:清词并回到热门标签(对齐鸿蒙 addTag 后与「重置」后的行为)。 */
    fun resetTagSuggestions() {
        tagDebounceJob?.cancel()
        _uiState.update { it.copy(tagQuery = "") }
        loadTagSuggestions("")
    }

    private fun loadTagSuggestions(query: String) {
        if (!_uiState.value.isHostReady) return
        val seq = ++tagSeq
        _uiState.update { it.copy(isTagLoading = true) }
        viewModelScope.launch {
            val suggestions = repository.suggestTags(query.trim(), SearchRepository.TAG_SUGGEST_LIMIT)
            if (seq != tagSeq) return@launch
            val selected = _uiState.value.filters.tags
            _uiState.update {
                it.copy(
                    isTagLoading = false,
                    // 已选中的标签不再出现在候选里(对齐鸿蒙)。
                    tagSuggestions = suggestions.filter { tag -> tag.name !in selected }
                )
            }
        }
    }

    private fun scheduleSearch(reset: Boolean) {
        debounceJob?.cancel()
        if (!_uiState.value.canSearch) {
            // 输入被清空:直接回空态,不残留上次结果(对齐鸿蒙 resetResults)。
            searchSeq++
            currentPage = 1
            seenTopicIds.clear()
            _uiState.update {
                it.copy(
                    hits = emptyList(),
                    users = emptyList(),
                    hasMore = false,
                    isSearching = false,
                    isLoadingMore = false,
                    hasSearched = false,
                    error = null
                )
            }
            return
        }
        debounceJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            runSearch(reset)
        }
    }

    private fun runSearch(reset: Boolean) {
        val state = _uiState.value
        if (!state.canSearch) return
        // linux.do 搜索接口要求登录（未登录返回 403 not_logged_in），
        // 前置登录门：未登录直接请求登录弹层，不发注定失败的请求（对齐鸿蒙 runSearch 的 isArkWebReady 前置门）。
        if (!authGate.ensureLoggedIn()) {
            _uiState.update {
                it.copy(
                    isSearching = false,
                    isLoadingMore = false,
                    hasSearched = true,
                    error = loginRequiredMessage
                )
            }
            return
        }
        // 对齐鸿蒙 runSearch 的 isArkWebReady 前置门:宿主 WebView 没挂好之前
        // fetch 桥必失败,先把话说明白,别让用户看到一句含糊的「网络错误」。
        if (!state.isHostReady) {
            _uiState.update {
                it.copy(
                    isSearching = false,
                    isLoadingMore = false,
                    hasSearched = true,
                    error = hostNotReadyMessage
                )
            }
            return
        }
        if (state.searchType == SearchType.USERS) {
            runUserSearch()
            return
        }
        runTopicSearch(reset)
    }

    private fun runUserSearch() {
        val term = _uiState.value.query.trim()
        val seq = ++searchSeq
        _uiState.update {
            it.copy(isSearching = true, error = null, hasSearched = true, requiresChallenge = false)
        }
        viewModelScope.launch {
            // 显式带上限 20(对齐鸿蒙 USER_SEARCH_LIMIT;官方整页搜索也是 20)。
            val result = repository.searchUsers(term, SearchRepository.USER_SEARCH_LIMIT)
            if (seq != searchSeq) return@launch
            _uiState.update {
                if (result.isSuccess) {
                    it.copy(
                        isSearching = false,
                        users = result.users,
                        error = null,
                        // 用户搜索无分页。
                        hasMore = false
                    )
                } else {
                    it.copy(
                        isSearching = false,
                        users = emptyList(),
                        requiresChallenge = result.requiresChallenge,
                        // 与帖子搜索同理:过盾统一文案,其余透传具体原因。
                        error = if (result.requiresChallenge) AppContextHolder.getString(R.string.search_challenge) else result.error
                    )
                }
            }
        }
    }

    private fun runTopicSearch(reset: Boolean) {
        val state = _uiState.value
        val term = state.query.trim()
        // 关键词与筛选条件拼成最终 q(语法对齐 Discourse 官方,见 core:model buildSearchQuery)。
        val queryString = buildSearchQuery(term, state.filters)

        val page = if (reset) 1 else currentPage + 1
        val seq = ++searchSeq
        if (reset) currentPage = 1

        _uiState.update {
            if (reset) {
                it.copy(
                    isSearching = true,
                    isLoadingMore = false,
                    error = null,
                    requiresChallenge = false,
                    hasSearched = true,
                    // 高亮词按本次搜索词定格(不随后续输入漂移;高级语法会被过滤掉)。
                    highlights = parseSearchHighlights(term)
                )
            } else {
                it.copy(isLoadingMore = true)
            }
        }

        viewModelScope.launch {
            val result = repository.searchTopics(queryString, page)
            // 过期响应(用户已改词/换类型)直接丢弃。
            if (seq != searchSeq) return@launch

            when (result) {
                is SearchPageResult.Success -> _uiState.update { current ->
                    currentPage = page
                    if (reset) {
                        // 新一轮:重置去重集后按响应顺序收全部结果。
                        seenTopicIds.clear()
                        val fresh = result.page.hits.filter { seenTopicIds.add(it.topic.id) }
                        current.copy(
                            isSearching = false,
                            isLoadingMore = false,
                            hits = fresh,
                            hasMore = result.page.hasMore && page < MAX_SEARCH_PAGE,
                            error = null
                        )
                    } else {
                        val incoming = result.page.hits.filter { seenTopicIds.add(it.topic.id) }
                        current.copy(
                            isSearching = false,
                            isLoadingMore = false,
                            hits = current.hits + incoming,
                            hasMore = result.page.hasMore && page < MAX_SEARCH_PAGE,
                            error = null
                        )
                    }
                }

                is SearchPageResult.Failure -> _uiState.update {
                    if (reset) {
                        // 对齐鸿蒙:失败不清已有结果(错误提示只在结果为空时上屏);
                        // hasMore 收掉,避免底部"上拉加载更多"对着一次失败的搜索续翻。
                        it.copy(
                            isSearching = false,
                            isLoadingMore = false,
                            hasMore = false,
                            requiresChallenge = result.requiresChallenge,
                            // 过盾给统一文案;其余原样透传仓储层带回来的具体原因
                            // (HTTP 状态码 / 宿主未就绪 / 超时),不再吞成一句通用文案。
                            error = if (result.requiresChallenge) AppContextHolder.getString(R.string.search_challenge) else result.message
                        )
                    } else {
                        // 翻页失败保留已有结果,只把加载态收掉。
                        it.copy(isLoadingMore = false)
                    }
                }
            }
        }
    }
}