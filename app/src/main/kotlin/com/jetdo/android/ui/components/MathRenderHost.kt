package com.jetdo.android.ui.components

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.view.View
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.jetdo.android.services.content.MathRenderService
import timber.log.Timber

private const val LOG_TAG = "[JetDo][MathRenderHost]"

/**
 * 离屏公式引擎的 Web 载体:只加载本地 `mathjax/render.html`,1×1、透明、不参与命中测试。
 *
 * 与 ArkWebNetworkBridge 完全无关 —— 不导航站点、不随 session 重载、不在网络路径上。
 * 仅在含公式的帖子打开时挂载(由 [MathHostGate] 控制)。
 *
 * 为什么用 1×1 而不是满屏:MathJax 出图走 `canvas.toDataURL`,与布局可见性无关,
 * 1×1 几乎不占合成开销。但**必须真实挂载在窗口上**(不能在 `remember` 里建了就丢),
 * 否则 WebView 不加载页面。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MathRenderHost(modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                // 透明背景 + 不参与命中:它是纯离屏引擎,不该被用户看到或点到。
                setBackgroundColor(AndroidColor.TRANSPARENT)
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // 本地 assets 页面不需要网络;关掉文件访问之外的一切,减少攻击面。
                settings.allowFileAccess = true
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true

                addJavascriptInterface(MathRenderService.createBridge(), "JetDoMath")

                webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        Timber.i("$LOG_TAG render page loaded")
                        MathRenderService.attach(this@apply)
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: android.webkit.WebResourceRequest?,
                        error: android.webkit.WebResourceError?
                    ) {
                        Timber.e(
                            "$LOG_TAG page error url=%s code=%s",
                            request?.url, error?.errorCode
                        )
                    }
                }

                MathRenderService.setDensity(density)
                loadUrl("file:///android_asset/mathjax/render.html")
            }
        }
    )
}

/**
 * 引用计数门控:本楼出现公式时 retain,离开时 release。**不挂载** WebView。
 *
 * 与 [MathRenderHostSlot] 分工明确:门控只管引用计数(每个含公式的楼各持一份),
 * 实际 WebView 由 Slot 单点挂载 —— 一篇帖子里多楼含公式时,每楼各挂一个引擎是纯浪费。
 */
@Composable
fun MathHostGate(hasMath: Boolean) {
    if (!hasMath) return

    DisposableEffect(Unit) {
        MathRenderService.retain()
        onDispose { MathRenderService.release() }
    }
}

/**
 * 离屏引擎的**唯一**挂载点:观察 [MathRenderService.active],有引用时挂载 1×1 WebView,
 * 引用归零(含空闲延迟)后卸载。挂在页面级(如详情页),不放楼层里。
 */
@Composable
fun MathRenderHostSlot() {
    val active by MathRenderService.active.collectAsState()
    if (active) {
        MathRenderHost(modifier = Modifier.size(1.dp))
    }
}