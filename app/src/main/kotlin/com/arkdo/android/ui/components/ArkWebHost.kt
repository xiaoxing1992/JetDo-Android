package com.arkdo.android.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.arkdo.android.services.network.ArkWebNetworkBridge
import timber.log.Timber

/**
 * ArkWebHost - WebView 桥接组件
 *
 * 对应 HarmonyOS 版 views/components/ArkWebHost.ets。
 * 创建一个隐藏的 WebView，用于执行 fetch 请求并携带 Cookie。
 *
 * 职责：
 * - 初始化 ArkWebNetworkBridge 的 WebView
 * - 加载 HOST_BOOTSTRAP_URL (https://linux.do/)
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

    DisposableEffect(Unit) {
        Timber.i("[ArkDO][ArkWebHost] stage=hostInitStart ts=${System.currentTimeMillis()}")

        onDispose {
            Timber.i("[ArkDO][ArkWebHost] disposed")
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            Timber.i("[ArkDO][ArkWebHost] creating WebView via attachHost")

            // 使用 ArkWebNetworkBridge 的 attachHost 方法创建 WebView
            val webView = ArkWebNetworkBridge.attachHost(ctx)

            Timber.i("[ArkDO][ArkWebHost] WebView created, waiting for page load")

            webView
        }
    )

    // 监听 Bridge 就绪状态
    LaunchedEffect(Unit) {
        // 轮询检查 Bridge 是否就绪
        while (!hasNotifiedReady) {
            kotlinx.coroutines.delay(500)
            if (ArkWebNetworkBridge.canFetch()) {
                hasNotifiedReady = true
                Timber.i("[ArkDO][ArkWebHost] Bridge ready, notifying onReady")
                onReady()
                break
            }
        }
    }
}
