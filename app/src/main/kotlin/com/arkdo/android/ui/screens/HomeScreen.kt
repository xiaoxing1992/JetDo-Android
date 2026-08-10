package com.arkdo.android.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController

/**
 * 首页屏幕
 *
 * 对齐 HarmonyOS 版本的 HomePage.ets 功能：
 * - 顶部 Tab（最新、热门、未读等，可在设置中配置）
 * - 话题列表（虚拟滚动、下拉刷新）
 * - 版块筛选抽屉
 * - 搜索入口
 * - Cloudflare 挑战处理
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("最新", "热门", "未读")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LINUX DO") },
                actions = {
                    IconButton(onClick = { /* TODO: 打开搜索 */ }) {
                        Icon(Icons.Default.Search, contentDescription = "搜索")
                    }
                    IconButton(onClick = { /* TODO: 手动刷新 */ }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                }
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Tab 切换栏
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }

            // 话题列表占位
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "话题列表正在加载...\n\n核心功能待实现：\n" +
                            "• WebView 网络桥接\n" +
                            "• Discourse API 适配\n" +
                            "• 话题卡片组件\n" +
                            "• 下拉刷新\n" +
                            "• 无限滚动加载",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
