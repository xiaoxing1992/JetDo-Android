package com.arkdo.android.ui.screens

import android.content.ClipData
import android.content.Context
import android.os.Build
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.arkdo.android.services.network.WebViewDohInterceptor
import com.arkdo.android.services.web.WebSessionManager
import com.arkdo.android.ui.theme.arkdoColors
import kotlinx.coroutines.launch
import timber.log.Timber

// ==========================================================================
// 通用 WebView 页,对齐鸿蒙 views/pages/WebViewPage.ets。
//
// 承载点击链接后打开的任意网页(外站或站内非帖子页)。复用 App 全局 CookieManager
// (与 host webview 同源),站内链接可带登录态。
//
// 页面元素与鸿蒙逐条对应:
//  · 顶栏:返回 + 标题(mainTitle + 域名副标题),文字用 ✕/chevron 一致风格,域名兜底。
//  · 首屏加载页:首个文档绘制前盖一层带真实进度的加载页,兼作转场可见性的承载体。
//  · 加载进度条:brand 色 2dp,顶部;加载完成前显示(鸿蒙 Progress Linear)。
//  · WebView:JS/DOM/DB/cache 全开 + 混合内容允许 + DoH 拦截(linux.do 走 OkHttp)。
//  · 错误页:主框架加载失败时盖层(重试 / 用浏览器打开 / 错误详情)。
//  · 菜单:刷新 / 复制链接 / 用浏览器打开(鸿蒙标题栏 menu 三项)。
//  · 返回:网页可回退时先退一级,无历史才退出本页。
// ==========================================================================

private const val LOG_TAG = "[ArkDO][WebViewPage]"

/**
 * Box 内叠层的淡入淡出容器。
 *
 * 为什么不在调用点直接写 AnimatedVisibility：那里嵌在外层 Column 的 lambda 里，重载
 * 解析会优先命中 ColumnScope.AnimatedVisibility，而调用点处在 Box 的 lambda 中拿不到
 * 那个隐式接收者，编译直接报「cannot be called in this context with an implicit
 * receiver」。收口成一个非扩展函数后，这里没有隐式接收者，解析到的就是顶层那一支。
 */
@Composable
private fun FadingOverlay(
    visible: Boolean,
    enterMillis: Int = 180,
    exitMillis: Int = 120,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(enterMillis)),
        exit = fadeOut(animationSpec = tween(exitMillis)),
        content = { content() }
    )
}

/**
 * 首屏加载页。
 *
 * 它存在的第一理由是**让转场能被看见**。WebView 首个文档绘制前是整片空白,而空白页与
 * 上一页(详情页/列表页都是浅底)没有色调差 —— 整屏横向滑动时用户只读到「一片浅色移过去」,
 * 和「根本没有动画」无法区分。给转场中的新页一张有内容的画面,位移立刻可辨。
 *
 * 第二个理由是它本身就有用:把 WebView 的真实进度(onProgressChanged)以可见形式呈现,
 * 慢网/离线时用户知道 App 在工作,而不是对着白屏怀疑卡死。
 *
 * 何时撤掉:onPageCommitVisible(文档进入绘制)即淡出,刻意早于 onPageFinished —— 后者要等
 * 所有子资源就绪,重页面会白等好几秒,比 WebView 原本的渐进渲染还慢。
 */
@Composable
private fun BrowserLoadingPane(progress: Int) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(arkdoColors.topicDetailPageBg)
    ) {
        // 进度为 0(还没等到 onProgressChanged)时走不定态转圈:确定态画在 0 处是个不动的空环,
        // 反而像卡住。拿到真实进度后再切成确定态。
        if (progress <= 0) {
            CircularProgressIndicator(
                modifier = Modifier.size(40.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeWidth = 3.dp
            )
        } else {
            CircularProgressIndicator(
                progress = { progress.coerceIn(0, 100) / 100f },
                modifier = Modifier.size(40.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeWidth = 3.dp
            )
        }
        Text(
            text = "正在加载",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 18.dp)
        )
    }
}

@Composable
fun WebViewPage(
    url: String,
    pageTitle: String = "",
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // WebView 的默认背景是纯白。网页的 html/body 背景不会覆盖视口的每一寸 ——
    // 页面顶部常有 safe-area / padding 空档,文档背景也可能晚于 WebView 首帧才铺满,
    // 那些空隙露出的就是 WebView 自己的白底。日间模式下白底对网页浅底看不出来,
    // 暗色模式下就是网页顶上刺眼的一道白。
    // 设成当前主题的 surface:暗色下是 #17191D 与页面底色同值(修掉那道白),
    // 亮色下是 #FFFFFF —— 与 WebView 原本的默认值相同,所以日间观感零变化。
    val webBackgroundArgb = MaterialTheme.colorScheme.surface.toArgb()

    fun hostOf(raw: String): String {
        val match = Regex("^https?://([^/?#]+)", RegexOption.IGNORE_CASE).find(raw.trim())
        return match?.groupValues?.get(1) ?: raw
    }

    // 页面加载状态(对齐鸿蒙 @State)。
    var displayTitle by remember { mutableStateOf(pageTitle.ifEmpty { hostOf(url) }) }
    var progress by remember { mutableStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }
    var errorDetail by remember { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }
    // WebView 引用:返回/刷新/取当前地址都要用它。
    var webRef by remember { mutableStateOf<WebView?>(null) }
    // 首屏揭幕:WebView 首帧是空白的,先盖一层底色,等文档开始绘制再淡出,避免
    // 「空白啪一下变网页」。只做首个文档 —— 之后页面内跳转不重置此标志,旧页保持可见
    // 直到新页接管;若每次跳转都重置,点链接就会先闪一下空白,反而更难用。
    // 用盖层而不是给 WebView 挂 alpha:AndroidView 上做 alpha 会让 WebView 走离屏合成,
    // 部分机型上会闪。
    var firstDocumentRevealed by remember { mutableStateOf(false) }

    fun currentUrl(): String {
        val current = runCatching { webRef?.url.orEmpty() }.getOrDefault("")
        val lower = current.lowercase()
        return if (lower.startsWith("http://") || lower.startsWith("https://")) current else url
    }

    fun isBrowsable(link: String): Boolean {
        val lower = link.lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://")
    }

    fun copyToClipboard(link: String, okMessage: String) {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("arkdo-webview-link", link))
            android.widget.Toast.makeText(context, okMessage, android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Timber.i("$LOG_TAG copy link failed ${e.message}")
            android.widget.Toast.makeText(context, "复制失败", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun reload() {
        hasError = false
        errorDetail = ""
        isLoading = true
        progress = 0
        val web = webRef
        if (web != null) {
            runCatching { web.reload() }.onFailure {
                // 首次加载就失败时无历史可刷,退回按原 url 重新载入(对齐鸿蒙 refresh→loadUrl 兜底)。
                Timber.i("$LOG_TAG reload failed, fallback loadUrl ${it.message}")
                runCatching { web.loadUrl(url) }
            }
        }
    }

    fun openInBrowser() {
        val link = currentUrl()
        if (!isBrowsable(link)) {
            android.widget.Toast.makeText(context, "当前页面地址无法用浏览器打开", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link))
            context.startActivity(intent)
        } catch (e: Exception) {
            Timber.i("$LOG_TAG open in browser failed ${e.message}")
            copyToClipboard(link, "未找到可用浏览器,链接已复制")
        }
    }

    // 系统返回键:网页内可回退先退一级,否则退出本页(对齐鸿蒙 handleBack)。
    BackHandler {
        val web = webRef
        val canGoBack = web != null && runCatching { web.canGoBack() }.getOrDefault(false)
        if (canGoBack) {
            web?.goBack()
        } else {
            onNavigateBack()
        }
    }

    // 背景先铺满再垫导航栏/键盘 inset:Edge-to-Edge 下 WebView 靠 imePadding 缩高,
    // 聚焦的输入框才会被 Chromium 滚进可视区(私信输入等场景)。
    //
    // 底色用主题里的详情页灰底(亮 #F2F3F5)而非 surface(纯白):鸿蒙 WebViewPage 的
    // staticBackgroundColor 就是 topic_detail_page_bg,它源码注释写明了理由 ——
    // 「通用页面背景在浅色模式为 #F2F3F5,避免标题栏叠在白底上后看起来接近纯白」。
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(arkdoColors.topicDetailPageBg)
            .navigationBarsPadding()
            .imePadding()
    ) {
        // 顶栏:返回 + 标题(副标题域名)+ ⋯ 菜单。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(56.dp)
                .padding(horizontal = 4.dp)
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayTitle,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = hostOf(currentUrl()),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("刷新") },
                        onClick = { showMenu = false; reload() }
                    )
                    DropdownMenuItem(
                        text = { Text("复制链接") },
                        onClick = { showMenu = false; copyToClipboard(currentUrl(), "链接已复制") }
                    )
                    DropdownMenuItem(
                        text = { Text("用浏览器打开") },
                        onClick = { showMenu = false; openInBrowser() }
                    )
                }
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

        // 加载进度条(对齐鸿蒙 Progress Linear,brand 色 2dp)。
        if (isLoading && progress < 100) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = MaterialTheme.colorScheme.primary
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        // 别用系统默认的纯白底:暗色下会在网页顶部露白(见上方 webBackgroundArgb 注释)。
                        setBackgroundColor(webBackgroundArgb)
                        // 对齐 Web(...) 的 javaScriptAccess/domStorageAccess/databaseAccess/mixedMode/cacheMode。
                        WebSessionManager.configureCookieAccess(this, "WebViewPage")
                        WebSessionManager.applyUserAgent(this, "WebViewPage")
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.cacheMode = WebSettings.LOAD_DEFAULT

                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? =
                                WebViewDohInterceptor.intercept(request)
                                    ?: super.shouldInterceptRequest(view, request)

                            // 新一轮导航开始:揭掉上一次错误页(重试成功靠它去掉错误页)。
                            override fun onPageStarted(view: WebView?, requestUrl: String?, favicon: android.graphics.Bitmap?) {
                                super.onPageStarted(view, requestUrl, favicon)
                                hasError = false
                                errorDetail = ""
                            }

                            // 文档开始绘制:首屏揭幕。刻意早于 onPageFinished —— 后者要等所有
                            // 子资源就绪,重页面会白等好几秒,比 WebView 原来的渐进渲染还慢。
                            override fun onPageCommitVisible(view: WebView?, url: String?) {
                                super.onPageCommitVisible(view, url)
                                firstDocumentRevealed = true
                            }

                            override fun onPageFinished(view: WebView, requestUrl: String) {
                                super.onPageFinished(view, requestUrl)
                                isLoading = false
                                // 兜底:极少数文档(204 / 无可见内容)不触发 onPageCommitVisible,
                                // 靠完成回调保证揭幕层一定会撤掉,否则页面会被永久盖住。
                                firstDocumentRevealed = true
                                scope.launch { WebSessionManager.saveCookies("WebViewPage") }
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                super.onReceivedError(view, request, error)
                                // 只认主框架错误:子资源失败不整页报错。
                                if (request?.isForMainFrame != true) return
                                isLoading = false
                                errorDetail = error?.description?.toString().orEmpty()
                                hasError = true
                                Timber.i("$LOG_TAG main frame error host=${hostOf(currentUrl())} info=$errorDetail")
                            }
                        }

                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress
                            }

                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                // 仅当外部未指定标题时,用网页自身 title 兜底。
                                if (pageTitle.isEmpty() && !title.isNullOrEmpty()) {
                                    displayTitle = title
                                }
                            }
                        }

                        webRef = this
                        loadUrl(url)
                    }
                },
                // 在本页内切换明暗时 WebView 不会重建、factory 不再执行,靠 update 重刷底色。
                update = { web -> runCatching { web.setBackgroundColor(webBackgroundArgb) } }
            )

            // 首屏加载页:文档一进入绘制就淡出。退场动画走完即离开组合,不常驻挡 WebView。
            FadingOverlay(
                visible = !firstDocumentRevealed,
                enterMillis = 0,
                exitMillis = 240
            ) {
                BrowserLoadingPane(progress = progress)
            }

            // 主框架加载失败:盖一层错误页,否则白屏像 App 坏了(对齐鸿蒙 ErrorView)。
            // 走 FadingOverlay 而非 if:错误页出现、重试成功后消失都不该是硬切。
            FadingOverlay(visible = hasError) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(arkdoColors.topicDetailPageBg),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("无法打开此页面", fontSize = 17.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        text = hostOf(currentUrl()),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Text(
                        text = "若站点被封锁或需要代理,可以试试用系统浏览器打开。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 12.dp, start = 32.dp, end = 32.dp)
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(top = 22.dp)
                    ) {
                        // 重试
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(MaterialTheme.colorScheme.primary)
                                .clickable(onClick = { reload() })
                                .padding(horizontal = 28.dp, vertical = 10.dp)
                        ) {
                            Text("重试", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
                        }
                        // 用浏览器打开
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable(onClick = { openInBrowser() })
                                .padding(horizontal = 20.dp, vertical = 10.dp)
                        ) {
                            Text("用浏览器打开", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    if (errorDetail.isNotEmpty()) {
                        Text(
                            text = errorDetail,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 18.dp, start = 32.dp, end = 32.dp)
                        )
                    }
                }
            }
        }
    }
}
