package com.arkdo.android.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.arkdo.android.navigation.Routes

/**
 * 个人中心屏幕
 *
 * 对齐 HarmonyOS 版本的 ProfilePage.ets 功能：
 * - 用户头像、昵称、等级
 * - 统计数据（话题数、回复数、点赞数）
 * - 功能入口（书签、草稿、等级进度、徽章墙）
 * - 设置入口
 * - 登录/登出
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的") },
                actions = {
                    IconButton(onClick = {
                        navController.navigate(Routes.SETTINGS)
                    }) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                }
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "个人中心正在开发中...\n\n待实现功能：\n" +
                        "• 用户信息展示\n" +
                        "• 登录/登出\n" +
                        "• 我的话题/回复/点赞\n" +
                        "• 书签/草稿管理\n" +
                        "• 等级进度与徽章墙\n" +
                        "• 设置入口",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
