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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.arkdo.android.services.network.WebViewDohInterceptor
import com.arkdo.android.services.web.WebSessionManager
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
//  · 加载进度条:brand 色 2dp,顶部;加载完成前显示(鸿蒙 Progress Linear)。
//  · WebView:JS/DOM/DB/cache 全开 + 混合内容允许 + DoH 拦截(linux.do 走 OkHttp)。
//  · 错误页:主框架加载失败时盖层(重试 / 用浏览器打开 / 错误详情)。
//  · 菜单:刷新 / 复制链接 / 用浏览器打开(鸿蒙标题栏 menu 三项)。
//  · 返回:网页可回退时先退一级,无历史才退出本页。
// ==========================================================================

private const val LOG_TAG = "[ArkDO][WebViewPage]"

@Composable
fun WebViewPage(
    url: String,
    pageTitle: String = "",
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
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

                            override fun onPageFinished(view: WebView, requestUrl: String) {
                                super.onPageFinished(view, requestUrl)
                                isLoading = false
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
                }
            )

            // 主框架加载失败:盖一层错误页,否则白屏像 App 坏了(对齐鸿蒙 ErrorView)。
            if (hasError) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface),
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
