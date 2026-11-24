package com.jetdo.android.ui.miniapp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jetdo.android.core.data.miniapp.MiniAppRepository
import com.jetdo.android.core.data.miniapp.MiniAppResult
import com.jetdo.android.core.model.MiniAppLaunch
import com.jetdo.android.core.model.MiniAppSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 微应用广场页状态。 */
data class MiniAppSquareUiState(
    val loading: Boolean = false,
    val apps: List<MiniAppSummary> = emptyList(),
    val error: String? = null,
    /** 是否有更多页可加载（站点 total/per_page 判定）。 */
    val hasMore: Boolean = false,
    /** 正在加载下一页（与首次 loading 区分，避免列表整体被转圈替换）。 */
    val isLoadingMore: Boolean = false,
    /** 下拉刷新中：保留旧列表，仅显示顶部刷新指示。 */
    val refreshing: Boolean = false,
    /** 已加载到的页码（0-based）。 */
    val page: Int = 0,
    val total: Int = 0
)

/** 微应用运行页状态。 */
data class MiniAppRunUiState(
    val loading: Boolean = true,
    val appName: String = "",
    val message: String = "",
    val error: String? = null,
    /** 已就绪的启动信息，交给宿主运行时挂载。 */
    val launch: MiniAppLaunch? = null
)

/**
 * 微应用 ViewModel。
 *
 * 广场页与运行页共用一个 ViewModel：广场点进某应用后写入 pendingLaunch，
 * 运行页消费它并调启动接口。
 */
@HiltViewModel
class MiniAppViewModel @Inject constructor(
    val repository: MiniAppRepository
) : ViewModel() {

    private val _squareState = MutableStateFlow(MiniAppSquareUiState())
    val squareState: StateFlow<MiniAppSquareUiState> = _squareState.asStateFlow()

    private val _runState = MutableStateFlow(MiniAppRunUiState())
    val runState: StateFlow<MiniAppRunUiState> = _runState.asStateFlow()

    /** 已选中的微应用 ID（从广场跳转过来时带上）。 */
    private var pendingAppId: String? = null
    private var pendingQuery: Map<String, List<String>> = emptyMap()
    private var pendingHash: String = ""

    /**
     * 加载广场第一页。
     * - 首次（列表为空）：loading=true 整页转圈。
     * - 下拉刷新（force 且已有内容）：refreshing=true 保留旧列表，成功后整体替换为新第一页。
     * 无论哪种都重置分页游标到 page=0，避免刷新后从旧页码续翻。
     */
    fun loadSquare(force: Boolean = false) {
        val cur = _squareState.value
        if (cur.loading || cur.refreshing) return
        if (!force && cur.apps.isNotEmpty()) return
        val isRefresh = force && cur.apps.isNotEmpty()
        _squareState.update {
            it.copy(
                loading = !isRefresh,
                refreshing = isRefresh,
                error = null
            )
        }
        viewModelScope.launch {
            when (val result = repository.listApps(0)) {
                is MiniAppResult.Success -> _squareState.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        apps = result.data.apps,
                        error = null,
                        hasMore = result.data.hasMore,
                        page = result.data.page,
                        total = result.data.total,
                        isLoadingMore = false
                    )
                }
                is MiniAppResult.Failure -> _squareState.update {
                    // 刷新失败保留旧列表（refreshing 收起），只在首次加载失败时显示错误页。
                    it.copy(loading = false, refreshing = false, error = result.message)
                }
            }
        }
    }

    /** 加载下一页并去重追加（站点滚动期间可能有应用上下架导致 id 重复）。 */
    fun loadMore() {
        val cur = _squareState.value
        if (cur.loading || cur.isLoadingMore || !cur.hasMore) return
        val nextPage = cur.page + 1
        _squareState.update { it.copy(isLoadingMore = true) }
        viewModelScope.launch {
            when (val result = repository.listApps(nextPage)) {
                is MiniAppResult.Success -> _squareState.update {
                    val existing = it.apps.map { app -> app.id }.toSet()
                    val fresh = result.data.apps.filterNot { app -> app.id in existing }
                    it.copy(
                        isLoadingMore = false,
                        apps = it.apps + fresh,
                        hasMore = result.data.hasMore,
                        page = result.data.page,
                        total = result.data.total
                    )
                }
                is MiniAppResult.Failure -> _squareState.update {
                    // 翻页失败保留已加载的列表，收起 hasMore 避免 footer 反复触发同一失败页。
                    it.copy(isLoadingMore = false, hasMore = false)
                }
            }
        }
    }

    /** 从广场选中一个应用，准备进入运行页。 */
    fun selectApp(appId: String) {
        pendingAppId = appId
        pendingQuery = emptyMap()
        pendingHash = ""
        _runState.value = MiniAppRunUiState(loading = true, message = "正在启动…")
    }

    /** 运行页调用：拉启动信息。 */
    fun loadLaunch(appId: String = pendingAppId.orEmpty()) {
        if (appId.isBlank()) {
            _runState.update { it.copy(loading = false, error = "缺少微应用 ID") }
            return
        }
        _runState.update { it.copy(loading = true, error = null, message = "正在获取应用信息…") }
        viewModelScope.launch {
            when (val result = repository.launch(appId)) {
                is MiniAppResult.Success -> _runState.update {
                    it.copy(
                        loading = false,
                        appName = result.data.release.name,
                        message = "",
                        launch = result.data
                    )
                }
                is MiniAppResult.Failure -> _runState.update {
                    it.copy(loading = false, error = result.message)
                }
            }
        }
    }

    /** 启动参数（query / hash），供宿主运行时构造 launch.getParameters 的返回值。 */
    fun launchParameters(): Pair<Map<String, List<String>>, String> = pendingQuery to pendingHash
}
