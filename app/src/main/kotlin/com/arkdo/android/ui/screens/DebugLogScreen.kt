package com.arkdo.android.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkdo.android.BuildConfig
import com.arkdo.android.services.logging.DebugLogStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 调试日志页。
 *
 * 背景：测试手机没数据线，logcat 抓不到。闪退时进程直接没了，所以
 * DebugLogStore 在崩溃瞬间把缓冲落盘、下次启动回读 —— 进这个页面就能看到
 * 上一次闪退的堆栈。一键复制/分享文件后粘给开发者即可。
 *
 * 刷新用 500ms 轮询：日志从任意线程写入，轮询比每次写入发信号更省也更稳。
 * 配色对齐 logcat：崩溃红/错误红/警告琥珀/信息绿/调试灰，堆栈行继承所属级别。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugLogScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var logs by remember { mutableStateOf(DebugLogStore.snapshot()) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val listState = rememberLazyListState()

    // 只有停在底部才跟随滚动，翻旧日志时不打扰。
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            info.visibleItemsInfo.lastOrNull()?.index == info.totalItemsCount - 1
        }
    }

    LaunchedEffect(Unit) {
        while (isActive) {
            delay(500)
            val next = DebugLogStore.snapshot()
            if (next != logs) logs = next
        }
    }

    LaunchedEffect(logs.size) {
        if (atBottom && logs.isNotEmpty()) {
            listState.scrollToItem(logs.lastIndex)
        }
    }

    fun shareLogFile() {
        val file = DebugLogStore.dumpToFile(context)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            android.content.Intent.createChooser(intent, "分享日志文件")
        )
    }

    fun copyAll() {
        val text = DebugLogStore.dumpText()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("arkdo-debug-log", text))
        scope.launch { snackbar.showSnackbar("已复制 ${logs.size} 行日志") }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("调试日志") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { copyAll() }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "复制全部日志")
                    }
                    IconButton(onClick = { shareLogFile() }) {
                        Icon(Icons.Default.Share, contentDescription = "分享日志文件")
                    }
                    IconButton(onClick = {
                        DebugLogStore.clear()
                        logs = emptyList()
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "清空日志")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        modifier = modifier
    ) { innerPadding ->
        if (logs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp)
            ) {
                Text(
                    text = "暂无日志。闪退后的堆栈会在下次启动时出现在这里。",
                    fontSize = 13.sp
                )
            }
        } else {
            // SelectionContainer：日志文本可长按选择、逐行复制。
            SelectionContainer(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // 构建标识：一眼确认设备上跑的是哪次 APK（排查"改了没生效"类问题）。
                    item(key = "build") {
                        Text(
                            text = "构建 ${BuildConfig.VERSION_NAME} (v${BuildConfig.VERSION_CODE}) · ${BuildConfig.BUILD_TIME}",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 4.dp)
                        )
                    }
                    itemsIndexed(logs) { _, line ->
                        // 崩溃条目高亮：红字加粗 + 淡红底，一眼定位闪退段。
                        val isCrash = line.startsWith("[C/") || line.contains("未捕获异常")
                        Text(
                            text = line,
                            fontSize = 10.sp,
                            lineHeight = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (isCrash) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (isCrash) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isCrash) MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                                    else Color.Transparent
                                )
                        )
                    }
                }
            }
        }
    }
}