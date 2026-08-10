package com.arkdo.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.arkdo.android.navigation.ArkDoNavHost
import com.arkdo.android.ui.theme.ArkDoTheme
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

/**
 * 主 Activity
 *
 * 单 Activity 架构，所有页面通过 Jetpack Compose Navigation 管理。
 *
 * 启动流程：
 * 1. 安装 Splash Screen（Android 12+ 自动适配）
 * 2. 启用 Edge-to-Edge 沉浸式布局
 * 3. 加载主题（跟随系统/强制浅色/强制深色）
 * 4. 处理推送通知深链接
 * 5. 渲染 RootNavHost（Tab + Navigation Stack）
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Splash Screen：启动图标在系统启动画面显示，避免白屏
        installSplashScreen()

        super.onCreate(savedInstanceState)

        Timber.i("[ArkDO][MainActivity] onCreate ts=%d", System.currentTimeMillis())

        // Edge-to-Edge：沉浸式状态栏和导航栏
        enableEdgeToEdge()

        setContent {
            ArkDoTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ArkDoNavHost()
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
