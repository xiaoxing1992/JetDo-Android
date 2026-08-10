package com.arkdo.android.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController

/**
 * 通知中心屏幕
 *
 * 对齐 HarmonyOS 版本的 NotificationPage.ets 功能：
 * - 通知列表（回复、@、点赞、Boost、Follow 等 30+ 种类型）
 * - 下拉刷新
 * - 点击跳转到对应话题/楼层
 * - 角标未读数同步
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationScreen(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("通知") }
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
                text = "通知列表正在开发中...\n\n待实现功能：\n" +
                        "• 通知类型适配（30+ 种）\n" +
                        "• 未读角标管理\n" +
                        "• 点击跳转深链接\n" +
                        "• 推送通知标记已读",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
