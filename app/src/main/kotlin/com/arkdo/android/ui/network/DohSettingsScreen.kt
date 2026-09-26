package com.arkdo.android.ui.network

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkdo.android.services.network.DohManager
import com.arkdo.android.services.network.DohResolveMode
import com.arkdo.android.services.network.DohServer
import com.arkdo.android.services.network.DohSpeedTester
import com.arkdo.android.ui.theme.arkdoColors
import kotlinx.coroutines.launch

// ==========================================================================
// DoH 配置页，对齐鸿蒙 views/pages/DohSettingsPage.ets。
//
// 后端（DohManager / DohService / WebViewDohInterceptor）早就是完整的、且默认
// SECURE_ONLY 开启，缺的一直只是这个页面 —— 用户没有任何入口改服务器或看状态。
//
// 卡片分组与设置页同款：灰底 + 无描边圆角卡片。
// ==========================================================================

private val PageMargin = 12.dp
private val CardRadius = 16.dp
private val CardPadding = 16.dp
private val CardGap = 12.dp

/** 测速状态机：每行独立驱动显示。 */
private enum class RowTestState { IDLE, TESTING, OK, FAIL }

private data class DohRow(
    val server: DohServer,
    val state: RowTestState = RowTestState.IDLE,
    val latencyMs: Int = -1,
    val message: String = ""
)

@Composable
fun DohSettingsScreen(
    onNavigateBack: () -> Unit,
    // 切换/开关/删除导致生效变更后回调：由上层重载宿主 WebView，让新 DNS 立即用于解析。
    onDohApplied: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var rows by remember { mutableStateOf(emptyList<DohRow>()) }
    var enabled by remember { mutableStateOf(true) }
    var activeId by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(DohResolveMode.SECURE_ONLY) }
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<DohServer?>(null) }

    fun reload() {
        val servers = DohManager.getServers(context)
        // 保留已有测速结果：按 id 归并旧行状态，新增项为 IDLE。
        val previous = rows.associateBy { it.server.id }
        rows = servers.map { server ->
            val old = previous[server.id]
            DohRow(
                server = server,
                state = old?.state ?: RowTestState.IDLE,
                latencyMs = old?.latencyMs ?: -1,
                message = old?.message.orEmpty()
            )
        }
        enabled = DohManager.isEnabled(context)
        activeId = DohManager.getActiveId(context)
        mode = DohManager.getMode(context)
    }

    fun updateRow(id: String, state: RowTestState, latencyMs: Int, message: String) {
        rows = rows.map {
            if (it.server.id == id) it.copy(state = state, latencyMs = latencyMs, message = message) else it
        }
    }

    fun testOne(id: String) {
        val target = rows.firstOrNull { it.server.id == id } ?: return
        if (target.state == RowTestState.TESTING) return
        updateRow(id, RowTestState.TESTING, -1, "")
        scope.launch {
            val result = DohSpeedTester.test(target.server.url)
            updateRow(
                id,
                if (result.ok) RowTestState.OK else RowTestState.FAIL,
                result.latencyMs,
                result.message
            )
        }
    }

    LaunchedEffect(Unit) { reload() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(arkdoColors.pageBgGray)
    ) {
        // 顶栏：返回 + 标题 + 右上角「＋」添加自定义 DoH。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(56.dp)
                .padding(horizontal = 4.dp)
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = arkdoColors.textPrimary
                )
            }
            Text(
                text = "DoH 配置",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = arkdoColors.textPrimary,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "添加 DoH", tint = arkdoColors.textPrimary)
            }
        }
        HorizontalDivider(color = arkdoColors.border, thickness = 0.5.dp)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PageMargin)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            // 卡片 1：总开关。
            DohCard(modifier = Modifier.padding(top = CardGap)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = CardPadding, vertical = 12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("启用 DoH", fontSize = 15.sp, color = arkdoColors.textPrimary)
                        Text(
                            "关闭后走系统 DNS（可能被污染）",
                            fontSize = 12.sp,
                            color = arkdoColors.textFaint,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { on ->
                            enabled = on
                            DohManager.setEnabled(context, on)
                            onDohApplied()
                            Toast.makeText(
                                context,
                                if (on) "DoH 已启用" else "DoH 已关闭",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = arkdoColors.ldAccent)
                    )
                }
            }

            // 卡片 2：服务器列表（单选生效 + 逐条测速 + 自定义项删除）。
            DohCard(modifier = Modifier.padding(top = CardGap)) {
                rows.forEachIndexed { index, row ->
                    if (index > 0) Hairline()
                    ServerRow(
                        row = row,
                        isActive = row.server.id == activeId,
                        onSelect = {
                            if (activeId != row.server.id) {
                                activeId = row.server.id
                                DohManager.setActive(context, row.server.id)
                                // DoH 关闭时只记录选择（下次开启才生效），不重载，避免误导。
                                if (enabled) onDohApplied()
                                Toast.makeText(context, "已切换 DoH", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onTest = { testOne(row.server.id) },
                        onDelete = { pendingDelete = row.server }
                    )
                }
            }

            // 一键测速全部。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = CardGap)
                    .height(44.dp)
                    .clip(RoundedCornerShape(CardRadius))
                    .background(arkdoColors.surfaceCard)
                    .clickable { rows.forEach { testOne(it.server.id) } },
                contentAlignment = Alignment.Center
            ) {
                Text("一键测速全部", fontSize = 15.sp, color = arkdoColors.ldAccent)
            }

            // 卡片 3：解析模式。
            Text(
                text = "解析模式",
                fontSize = 13.sp,
                color = arkdoColors.textFaint,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = CardPadding, end = CardPadding, top = 18.dp, bottom = 8.dp)
            )
            ModeCard(
                mode = mode,
                onSelect = { next ->
                    mode = next
                    DohManager.setMode(context, next)
                    // 同上：关闭态改模式不生效，不触发重载。
                    if (enabled) onDohApplied()
                }
            )

            Text(
                text = "DoH 用加密的 HTTPS 通道解析域名，绕过被污染的本地 DNS。\n" +
                    "生效范围与鸿蒙版略有差异：Android 无法给 WebView 指定全局 DoH，" +
                    "改由每个请求逐条接管，因此只覆盖 GET 请求。",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = arkdoColors.textFaint,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = CardPadding, end = CardPadding, top = 14.dp)
            )
            Spacer(modifier = Modifier.height(40.dp))
        }
    }

    if (showAddDialog) {
        AddDohDialog(
            onConfirm = { name, url ->
                showAddDialog = false
                val created = DohManager.addServer(context, name, url)
                if (created == null) {
                    Toast.makeText(context, "添加失败", Toast.LENGTH_SHORT).show()
                } else {
                    reload()
                    Toast.makeText(context, "已添加", Toast.LENGTH_SHORT).show()
                }
            },
            onInvalid = { reason ->
                showAddDialog = false
                Toast.makeText(context, reason, Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showAddDialog = false }
        )
    }

    pendingDelete?.let { server ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除 DoH", color = arkdoColors.textPrimary) },
            text = { Text("确定删除「${server.name}」吗？", color = arkdoColors.textSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    val wasActive = activeId == server.id
                    pendingDelete = null
                    DohManager.removeServer(context, server.id)
                    reload()
                    // 删的是当前项：已回退内置默认并重新下发，需重载宿主（仅启用态）。
                    if (wasActive && enabled) onDohApplied()
                    Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show()
                }) {
                    Text("删除", color = arkdoColors.ldDanger)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("取消", color = arkdoColors.textSecondary)
                }
            }
        )
    }
}

// ==================== 子组件 ====================

@Composable
private fun DohCard(
    modifier: Modifier = Modifier.padding(top = CardGap),
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(arkdoColors.surfaceCard, RoundedCornerShape(CardRadius))
    ) {
        content()
    }
}

@Composable
private fun Hairline() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = CardPadding),
        thickness = 0.5.dp,
        color = arkdoColors.border
    )
}

@Composable
private fun ServerRow(
    row: DohRow,
    isActive: Boolean,
    onSelect: () -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = CardPadding, end = CardPadding, top = 12.dp, bottom = 12.dp)
    ) {
        // 左侧：选中圆点 + 名称 + 地址，整块点击即选为当前生效。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onSelect)
        ) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(if (isActive) arkdoColors.ldAccent else Color.Transparent)
                    .border(
                        width = 1.5.dp,
                        color = if (isActive) arkdoColors.ldAccent else arkdoColors.textFaint,
                        shape = CircleShape
                    )
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            ) {
                Text(
                    text = row.server.name,
                    fontSize = 15.sp,
                    color = arkdoColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = shortUrl(row.server.url),
                    fontSize = 12.sp,
                    color = arkdoColors.textFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }

        // 右侧：测速状态 / 触发测速。
        LatencyBadge(row = row, onTest = onTest)

        // 删除（仅自定义项；内置项由 DohManager 拒删，这里也不给入口）。
        if (!row.server.builtin) {
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除",
                    tint = arkdoColors.ldDanger,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun LatencyBadge(row: DohRow, onTest: () -> Unit) {
    if (row.state == RowTestState.TESTING) {
        Box(
            modifier = Modifier.size(30.dp),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = arkdoColors.ldAccent
            )
        }
        return
    }
    // 实体药丸按钮：点击热区足够大，不是裸文字那种小热区。
    Box(
        modifier = Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(arkdoColors.surfaceMuted)
            .clickable(onClick = onTest)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = badgeLabel(row), fontSize = 13.sp, color = badgeColor(row))
    }
}

private fun badgeLabel(row: DohRow): String = when (row.state) {
    RowTestState.OK -> "${row.latencyMs}ms"
    RowTestState.FAIL -> if (row.message.isNotEmpty()) row.message else "重试"
    else -> "测速"
}

/** 药丸文字色：idle/fail 走主题色（硬编码会在深色模式下露白或发暗）。 */
@Composable
private fun badgeColor(row: DohRow): Color = when (row.state) {
    RowTestState.OK -> latencyColor(row.latencyMs)
    RowTestState.FAIL -> arkdoColors.textFaint
    else -> arkdoColors.ldAccent
}

/** 延迟色板与鸿蒙 DohSettingsPage.latencyColor 同值：快绿 / 中黄 / 慢橙。 */
private fun latencyColor(ms: Int): Color = when {
    ms < 150 -> Color(0xFF22A06B)
    ms < 400 -> Color(0xFFD99400)
    else -> Color(0xFFD1622B)
}

@Composable
private fun ModeCard(mode: DohResolveMode, onSelect: (DohResolveMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(CardRadius))
                .background(arkdoColors.surfaceCard)
                .clickable { expanded = true }
                .padding(start = CardPadding, end = CardPadding, top = 14.dp, bottom = 14.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("DNS 解析模式", fontSize = 15.sp, color = arkdoColors.textPrimary)
                Text(
                    text = if (mode == DohResolveMode.SECURE_ONLY) {
                        "只用 DoH，解析失败即加载失败"
                    } else {
                        "DoH 失败时回退系统 DNS"
                    },
                    fontSize = 12.sp,
                    color = arkdoColors.textFaint,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Text(
                text = if (mode == DohResolveMode.SECURE_ONLY) "仅 DoH" else "自动",
                fontSize = 13.sp,
                color = arkdoColors.textFaint,
                modifier = Modifier.padding(end = 6.dp)
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = arkdoColors.textFaint,
                modifier = Modifier.size(18.dp)
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("自动（推荐）") },
                onClick = {
                    expanded = false
                    onSelect(DohResolveMode.AUTO)
                }
            )
            DropdownMenuItem(
                text = { Text("仅 DoH") },
                onClick = {
                    expanded = false
                    onSelect(DohResolveMode.SECURE_ONLY)
                }
            )
        }
    }
}

@Composable
private fun AddDohDialog(
    onConfirm: (name: String, url: String) -> Unit,
    onInvalid: (reason: String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = arkdoColors.surfaceCard,
        title = {
            Text(
                text = "添加 DoH",
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = arkdoColors.textPrimary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("名称（可留空，默认取域名）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    placeholder = { Text("https://.../dns-query") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val trimmedUrl = url.trim()
                if (!trimmedUrl.startsWith("https://") || trimmedUrl.length < 12) {
                    onInvalid("请填写有效的 https DoH 地址")
                    return@TextButton
                }
                val trimmedName = name.trim()
                onConfirm(if (trimmedName.isNotEmpty()) trimmedName else hostOf(trimmedUrl), trimmedUrl)
            }) {
                Text("添加", color = arkdoColors.ldAccent, fontWeight = FontWeight.Medium)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = arkdoColors.textSecondary)
            }
        }
    )
}

private fun shortUrl(url: String): String = url.removePrefix("https://")

private fun hostOf(url: String): String {
    val withoutScheme = url.removePrefix("https://")
    val slash = withoutScheme.indexOf('/')
    return if (slash >= 0) withoutScheme.substring(0, slash) else withoutScheme
}
