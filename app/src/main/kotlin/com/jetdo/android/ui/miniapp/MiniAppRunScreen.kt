package com.jetdo.android.ui.miniapp

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jetdo.android.services.miniapp.MiniAppHostRuntime
import com.jetdo.android.services.web.WebSessionManager
import com.jetdo.android.ui.theme.jetdoColors
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 微应用运行页。
 *
 * 承载宿主 WebView（assets/miniapp/host.html）。宿主页面内部再开一层
 * sandbox iframe 跑微应用本体 —— 外层同源（能加载解密 Worker、能带 Cookie），
 * 内层无同源（微应用拿不到宿主 DOM 与 Cookie）。
 */
@Composable
fun MiniAppRunScreen(
    appId: String,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MiniAppViewModel = hiltViewModel()
) {
    val state by viewModel.runState.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    val runtime = remember {
        MiniAppHostRuntime(
            context.applicationContext,
            WebView(context),
            viewModel.repository
        )
    }

    LaunchedEffect(appId) {
        viewModel.loadLaunch(appId)
    }

    // 拿到启动信息后交给宿主：设置会话并让宿主页面开始拉包。
    LaunchedEffect(state.launch) {
        val launch = state.launch ?: return@LaunchedEffect
        val (query, hash) = viewModel.launchParameters()
        runtime.setSession(launch, query, hash)
        runtime.start(appId, query, hash)
    }

    DisposableEffect(Unit) {
        onDispose {
            runtime.release()
        }
    }
    // 全屏运行：微应用本身是完整画面（自带顶栏/摇杆），垫上下 inset 会在
    // 状态栏和手势条处露出两条页面底色、把画面割裂成三段。这里让 WebView
    // 铺满整屏，系统栏浮在画面上方（Edge-to-Edge）；iframe 仍按 host.html 里
    // 的显式像素高度取满全屏，离屏 canvas 不会为 0。
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(jetdoColors.pageBg)
    ) {
        MiniAppWebView(runtime = runtime)

        val error = state.error
        if (error != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(jetdoColors.pageBg),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = error,
                    color = jetdoColors.textTertiary,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(24.dp)
                )
            }
        } else if (state.loading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(jetdoColors.pageBg),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = jetdoColors.ldAccent)
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MiniAppWebView(runtime: MiniAppHostRuntime) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val webView = runtime.webView
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                // 宿主页面最终注入到 linux.do 同源文档里（见 MiniAppHostRuntime），
                // 这两项是为了让注入前的引导页阶段也能正常工作。
                allowFileAccess = true
                allowContentAccess = true
                mediaPlaybackRequiresUserGesture = false
                setSupportMultipleWindows(false)
            }
            WebSessionManager.configureCookieAccess(webView, "MiniAppHost")
            // WebViewClient 由 MiniAppHostRuntime 设置（它要监听引导页加载完成来注入宿主页），
            // 这里不要再设，否则会覆盖掉，宿主页面永远注不进去。
            //
            // attach 监听同理关键：WebView 在 attach 到窗口之前调 loadUrl 会失败，
            // 所以真正加载引导页要等 attach 之后（由运行时触发）。
            webView.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: android.view.View) {
                    runtime.onWebViewAttached()
                }

                override fun onViewDetachedFromWindow(v: android.view.View) = Unit
            })
            webView
        }
    )
}
