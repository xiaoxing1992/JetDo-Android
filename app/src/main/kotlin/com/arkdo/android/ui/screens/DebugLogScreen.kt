package com.arkdo.android.ui.screens

import androidx.compose.ui.res.stringResource
import com.arkdo.android.R
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import com.arkdo.android.services.logging.FileLoggerDaemon
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 调试日志页。
 *
 * 背景：测试手机没数据线，logcat 抓不到。日志留两条腿：
 *  · 内存环形缓冲（DebugLogStore）：崩溃瞬间落盘，下次启动回读 —— 本页的列表就是它。
 *  · 沙箱文件（FileLoggerDaemon）：持续落盘、1MB 轮转、保留 3 份 —— 「分享」导出的是它。
 * 前者负责「崩了当场看一眼」，后者负责「脱机跑了一天回头看」。
 *
 * 刷新用 500ms 轮询：日志从任意线程写入，轮询比每次写入发信号更省也更稳。
 * 配色对齐 logcat：崩溃红/错误红/警告琥珀/信息绿/调试灰，堆栈行继承所属级别。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugLogScreen(
    onNavigateBack: () -> Unit,
    onOpenUrl: (String, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    var logs by remember { mutableStateOf(DebugLogStore.snapshot()) }
    var confirmClear by remember { mutableStateOf(false) }
    var fileLogEnabled by remember { mutableStateOf(FileLoggerDaemon.isEnabled()) }
    var fileLogVerbose by remember { mutableStateOf(FileLoggerDaemon.isVerbose()) }
    var fileLogBytes by remember { mutableStateOf(FileLoggerDaemon.totalSizeBytes()) }
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
            // 占用大小只在真的变了才写回：mutableStateOf 按值比较，值不变就不会触发重组，
            // 否则这一页会每 500ms 白重组一次。
            val size = FileLoggerDaemon.totalSizeBytes()
            if (size != fileLogBytes) fileLogBytes = size
        }
    }

    LaunchedEffect(logs.size) {
        if (atBottom && logs.isNotEmpty()) {
            listState.scrollToItem(logs.lastIndex)
        }
    }

    fun shareLogFile() {
        // 分享的是一整份文件日志（归档 + 当前合并），而不是内存里那 2000 行环形缓冲：
        // 用户脱机跑一天的现场在文件里，内存缓冲只留得住最近一小段。
        val file = FileLoggerDaemon.exportToFile(context)
        if (file == null) {
            scope.launch { snackbar.showSnackbar(context.getString(R.string.debug_export_failed)) }
            return
        }
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
            android.content.Intent.createChooser(intent, context.getString(R.string.debug_share_log_file))
        )
    }

    fun copyAll() {
        val text = DebugLogStore.dumpText()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("arkdo-debug-log", text))
        scope.launch { snackbar.showSnackbar(context.getString(R.string.debug_copied_lines, logs.size)) }
    }

    // 清空是「内存缓冲 + 沙箱文件（当前 + 2 份归档）」一起删，一次误点就是一天现场，
    // 所以必须二次确认。文案把后果说清楚，别只问「确定吗」。
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.debug_clear_confirm)) },
            text = {
                Text(
                    stringResource(R.string.debug_clear_warning) +
                        stringResource(R.string.debug_export_first)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    DebugLogStore.clear()
                    FileLoggerDaemon.clear()
                    logs = emptyList()
                    fileLogBytes = 0L
                    confirmClear = false
                }) { Text(stringResource(R.string.common_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.debug_debug_log)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    // 人机验证渲染自测:在同一套 WebView 配置下打开 hCaptcha 官方演示页。
                    // 这里能画出复选框 → 内核没问题,是站点弹层/布局的事;这里也空白 → 内核层问题。
                    IconButton(onClick = { onOpenUrl("https://accounts.hcaptcha.com/demo", context.getString(R.string.challenge_hcaptcha_selftest)) }) {
                        Icon(Icons.Default.BugReport, contentDescription = stringResource(R.string.challenge_hcaptcha_render_test))
                    }
                    IconButton(onClick = { copyAll() }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.debug_copy_all))
                    }
                    IconButton(onClick = { shareLogFile() }) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.debug_share_log_file))
                    }
                    IconButton(onClick = { confirmClear = true }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.debug_clear_log))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            FileLogControlRow(
                enabled = fileLogEnabled,
                verbose = fileLogVerbose,
                sizeBytes = fileLogBytes,
                onEnabledChange = { value ->
                    fileLogEnabled = value
                    FileLoggerDaemon.setEnabled(value)
                    fileLogBytes = FileLoggerDaemon.totalSizeBytes()
                },
                onVerboseChange = { value ->
                    fileLogVerbose = value
                    FileLoggerDaemon.setVerbose(value)
                }
            )

            if (logs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.debug_no_logs),
                        fontSize = 13.sp
                    )
                }
            } else {
                // SelectionContainer：日志文本可长按选择、逐行复制。
                SelectionContainer(modifier = Modifier.fillMaxSize()) {
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
                                text = stringResource(R.string.about_build, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.BUILD_TIME),
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
}

/**
 * 文件日志的开关与占用。
 *
 * 放在列表之外而不是 LazyColumn 的 header 里：日志为空时也要能看见、能关掉 ——
 * 而「日志为空」恰恰是新装包 / 刚清空之后的常态。
 *
 * 开关不接 UI 就等于「用户无法关闭的隐形写盘」，所以这一行不是装饰，是必需件。
 */
@Composable
private fun FileLogControlRow(
    enabled: Boolean,
    verbose: Boolean,
    sizeBytes: Long,
    onEnabledChange: (Boolean) -> Unit,
    onVerboseChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.debug_log_to_disk), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    text = stringResource(R.string.debug_log_usage, formatLogSize(sizeBytes)),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.debug_verbose_log), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    text = stringResource(R.string.debug_verbose_hint),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 落盘关掉时详细日志没有意义（没有文件可写），跟着禁用而不是留一个能拨动的空开关。
            Switch(checked = verbose, enabled = enabled, onCheckedChange = onVerboseChange)
        }
    }
}

private fun formatLogSize(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
