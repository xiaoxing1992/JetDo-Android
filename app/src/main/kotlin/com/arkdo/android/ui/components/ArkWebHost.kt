package com.arkdo.android.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.arkdo.android.services.network.ArkWebNetworkBridge
import kotlinx.coroutines.delay
import timber.log.Timber

/**
 * ArkWebHost - WebView 桥接组件
 *
 * 对应 HarmonyOS 版 views/components/ArkWebHost.ets。
 * 创建一个隐藏的 WebView，用于执行 fetch 请求并携带 Cookie。
 *
 * 职责：
 * - 初始化 ArkWebNetworkBridge 的 WebView
 * - 加载 HOST_BOOTSTRAP_URL (https://linux.do/session/csrf.json)
 * - 监听页面加载完成，调用 onReady 回调
 * - 处理加载失败，调用 onLoadFailed 回调
 */
@Composable
fun ArkWebHost(
    onReady: () -> Unit = {},
    onLoadFailed: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var hasNotifiedReady by remember { mutableStateOf(false) }
    var hasNotifiedFailed by remember { mutableStateOf(false) }
    var checkCount by remember { mutableStateOf(0) }

    DisposableEffect(Unit) {
        Timber.i("[ArkDO][ArkWebHost] ========== WebView Host Init Start ==========")
        Timber.i("[ArkDO][ArkWebHost] stage=hostInitStart ts=${System.currentTimeMillis()}")

        onDispose {
            Timber.i("[ArkDO][ArkWebHost] disposed")
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            Timber.i("[ArkDO][ArkWebHost] factory: creating WebView via attachHost")
            Timber.i("[ArkDO][ArkWebHost] context=${ctx.javaClass.simpleName}")
            Timber.i("[ArkDO][ArkWebHost] thread=${Thread.currentThread().name}")

            try {
                // 使用 ArkWebNetworkBridge 的 attachHost 方法创建 WebView
                // attachHost 内部会：
                // 1. 创建 WebView
                // 2. 配置 settings (JS enabled, DOM storage, etc.)
                // 3. 添加 JavascriptInterface
                // 4. 设置 WebViewClient (监听 onPageFinished)
                // 5. loadUrl 会在 post {} 中延迟到窗口附着后执行
                val webView = ArkWebNetworkBridge.attachHost(ctx)

                // 关键：添加 onAttachedToWindow 监听，确保 WebView 真正附着到窗口后才开始加载
                // 这对应鸿蒙版本的 .onControllerAttached() 回调
                webView.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: android.view.View) {
                        Timber.i("[ArkDO][ArkWebHost] ✅ WebView attached to window")
                        Timber.i("[ArkDO][ArkWebHost] width=${v.width} height=${v.height}")
                        Timber.i("[ArkDO][ArkWebHost] isAttachedToWindow=${v.isAttachedToWindow}")
                    }

                    override fun onViewDetachedFromWindow(v: android.view.View) {
                        Timber.i("[ArkDO][ArkWebHost] WebView detached from window")
                    }
                })

                Timber.i("[ArkDO][ArkWebHost] WebView created successfully")
                Timber.i("[ArkDO][ArkWebHost] WebView.url=${webView.url}")
                Timber.i("[ArkDO][ArkWebHost] WebView.isAttachedToWindow=${webView.isAttachedToWindow}")
                Timber.i("[ArkDO][ArkWebHost] waiting for onPageFinished callback...")

                webView
            } catch (e: Exception) {
                Timber.e(e, "[ArkDO][ArkWebHost] FATAL: Failed to create WebView")
                throw e
            }
        },
        update = { webView ->
            // 确保 WebView 有足够的尺寸来完成 Cloudflare Turnstile 验证
            // Turnstile 需要至少 300x65 像素才能正常工作
            if (webView.width == 0 || webView.height == 0) {
                Timber.w("[ArkDO][ArkWebHost] update: WebView has zero size, forcing measure")
                webView.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(300, android.view.View.MeasureSpec.EXACTLY)
                )
                webView.layout(0, 0, 400, 300)
                Timber.i("[ArkDO][ArkWebHost] update: after layout width=${webView.width} height=${webView.height}")
            }
        }
    )

    // 监听 Bridge 就绪状态（轮询检查）
    LaunchedEffect(Unit) {
        Timber.i("[ArkDO][ArkWebHost] LaunchedEffect: start polling canFetch()")

        while (!hasNotifiedReady && !hasNotifiedFailed) {
            delay(500)
            checkCount++

            val canFetch = ArkWebNetworkBridge.canFetch()
            Timber.d("[ArkDO][ArkWebHost] poll #$checkCount: canFetch=$canFetch")

            if (canFetch) {
                hasNotifiedReady = true
                Timber.i("[ArkDO][ArkWebHost] ✅ Bridge ready! Notifying onReady()")
                Timber.i("[ArkDO][ArkWebHost] stage=hostPageEndReady ts=${System.currentTimeMillis()}")
                onReady()
                break
            }

            // 超时检测：30 秒后仍未就绪，认为失败
            if (checkCount >= 60) { // 60 * 500ms = 30s
                hasNotifiedFailed = true
                Timber.e("[ArkDO][ArkWebHost] ❌ Timeout: canFetch() still false after 30s")
                Timber.e("[ArkDO][ArkWebHost] Possible reasons:")
                Timber.e("[ArkDO][ArkWebHost]   1. Network error (cannot reach linux.do)")
                Timber.e("[ArkDO][ArkWebHost]   2. WebView onPageFinished not fired")
                Timber.e("[ArkDO][ArkWebHost]   3. Cloudflare blocking the request")
                onLoadFailed()
                break
            }
        }
    }
}
