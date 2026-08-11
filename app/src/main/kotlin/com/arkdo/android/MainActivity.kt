package com.arkdo.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.arkdo.android.navigation.ArkDoNavHost
import com.arkdo.android.services.network.ArkWebNetworkBridge
import com.arkdo.android.services.session.AppStateEventBus
import com.arkdo.android.services.session.observeLifecycle
import com.arkdo.android.ui.components.ArkWebHost
import com.arkdo.android.ui.theme.ArkDoTheme
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

/**
 * 主 Activity
 *
 * 单 Activity 架构,所有页面通过 Jetpack Compose Navigation 管理。
 *
 * 启动流程：
 * 1. 安装 Splash Screen（Android 12+ 自动适配）
 * 2. 启用 Edge-to-Edge 沉浸式布局
 * 3. 监听前后台状态（AppStateEventBus）
 * 4. 加载主题（跟随系统/强制浅色/强制深色）
 * 5. 处理推送通知深链接
 * 6. 渲染 RootNavHost（Tab + Navigation Stack）
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Timber.i("[ArkDO][MainActivity] onCreate ts=%d", System.currentTimeMillis())

        // 监听前后台状态（onStart → 前台，onStop → 后台）
        AppStateEventBus.observeLifecycle(this)

        // 设置 WebView 下载上下文
        ArkWebNetworkBridge.setDownloadContext(this)

        // 清理媒体缓存（冷启动）
        ArkWebNetworkBridge.clearMediaCacheDir(this)

        // Edge-to-Edge：沉浸式状态栏和导航栏
        enableEdgeToEdge()

        setContent {
            ArkDoTheme {
                var isWebViewReady by remember { mutableStateOf(false) }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box {
                        // 主界面（在上层）
                        ArkDoNavHost()

                        // 隐藏的 WebView 宿主（在底层，1x1 像素，近透明）
                        ArkWebHost(
                            onReady = {
                                Timber.i("[ArkDO][MainActivity] WebView bridge ready")
                                isWebViewReady = true
                            },
                            onLoadFailed = {
                                Timber.e("[ArkDO][MainActivity] WebView bridge load failed")
                                // TODO: 显示降级 UI，引导用户开启 DoH
                            }
                        )
                    }
                }
            }
        }

        // 处理推送通知深链接（如果是从通知点击启动）
        handleNotificationIntent()
    }

    private fun handleNotificationIntent() {
        val data = intent?.data
        if (data?.scheme == "arkdo" && data.host == "notification") {
            // 解析并导航到对应页面（Topic / Notification）
            val topicId = data.getQueryParameter("topicId")
            val postNumber = data.getQueryParameter("postNumber")
            Timber.i("[ArkDO][MainActivity] notification intent topicId=$topicId postNumber=$postNumber")
            // TODO: 通过 Navigation 跳转到详情页
        }
    }
}
