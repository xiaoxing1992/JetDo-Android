package com.arkdo.android.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.TextFormat
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkdo.android.BuildConfig
import com.arkdo.android.common.constants.AppConstants
import com.arkdo.android.common.theme.AppThemeMode
import com.arkdo.android.common.theme.ThemeModeStore
import com.arkdo.android.core.designsystem.FAB_GLASS_RADIUS_MAX
import com.arkdo.android.core.designsystem.FAB_GLASS_RADIUS_MIN
import com.arkdo.android.core.designsystem.FabGlassStore
import com.arkdo.android.ui.theme.arkdoColors

// 设置页,对齐鸿蒙 SettingsPage:灰底无描边卡片。
// 外观三档会立刻改主题。关于里的反馈、版本、免责声明、诊断日志是真入口。
// 「网络与连接」已迁(内有 DoH 配置);「通知」已迁(系统通知权限 + 发送测试通知)。
// 论坛 / 我的站点在 Android 上还没有子页,点了只提示,不造一个空页面冒充已经做好。
// 实验性功能只放了首页新话题提醒。

private val PageMargin = 12.dp
private val CardRadius = 16.dp
private val CardPadding = 16.dp
private val CardGap = 12.dp
private const val OFFICIAL_SITE_URL = "https://arkdo.app"

private const val DISCLAIMER = "本应用是第三方论坛客户端，不提供、不托管、不审核任何内容。\n\n" +
    "应用内显示的全部内容均来自你自行添加的第三方站点，由该站点及其运营方负责。" +
    "你选择访问哪些站点，完全由你自己决定。\n\n" +
    "本应用与任何论坛站点及其运营方之间，不存在隶属、合作或授权关系。"

@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onOpenFeedback: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenExperimental: () -> Unit,
    onOpenTopicListStyle: () -> Unit,
    onOpenNetworkSettings: () -> Unit = {},
    onOpenPushSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val themeMode by ThemeModeStore.mode.collectAsState()
    val fabGlassEnabled by FabGlassStore.enabled.collectAsState()
    val fabGlassRadius by FabGlassStore.radius.collectAsState()
    var showAbout by remember { mutableStateOf(false) }
    var showDisclaimer by remember { mutableStateOf(false) }
    val versionLabel = "v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})"

    if (showAbout) {
        AboutDialog(
            versionLabel = versionLabel,
            onOpenSite = {
                showAbout = false
                openOfficialSite(context)
            },
            onDismiss = { showAbout = false }
        )
    }
    if (showDisclaimer) {
        DisclaimerDialog(onDismiss = { showDisclaimer = false })
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(arkdoColors.pageBgGray)
    ) {
        SettingsTopBar(onNavigateBack)
        HorizontalDivider(color = arkdoColors.border, thickness = 0.5.dp)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PageMargin)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            SettingsCard(modifier = Modifier.padding(top = CardGap)) {
                AppearanceRow(themeMode)
                Hairline()
                SettingRow(
                    icon = Icons.Outlined.TextFormat,
                    label = "帖子列表样式",
                    onClick = onOpenTopicListStyle
                )
                Hairline()
                FabGlassRow(enabled = fabGlassEnabled)
                if (fabGlassEnabled) {
                    Hairline()
                    FabGlassRadiusRow(radius = fabGlassRadius)
                }
            }

            SettingsCard {
                SettingRow(
                    icon = Icons.Outlined.Notifications,
                    label = "通知",
                    onClick = onOpenPushSettings
                )
                Hairline()
                SettingRow(
                    icon = Icons.Outlined.Wifi,
                    label = "网络与连接",
                    onClick = onOpenNetworkSettings
                )
                Hairline()
                SettingRow(
                    icon = Icons.Outlined.Science,
                    label = "实验性功能",
                    onClick = onOpenExperimental
                )
            }

            Text(
                text = "关于",
                fontSize = 13.sp,
                color = arkdoColors.textFaint,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = CardPadding, end = CardPadding, top = 18.dp, bottom = 8.dp)
            )
            SettingsCard(modifier = Modifier.padding(top = 0.dp)) {
                SettingRow(
                    icon = Icons.Outlined.ChatBubbleOutline,
                    label = "反馈与建议",
                    onClick = onOpenFeedback
                )
                Hairline()
                SettingRow(
                    icon = Icons.Outlined.Info,
                    label = "关于 ArkDO",
                    value = versionLabel,
                    onClick = { showAbout = true }
                )
                Hairline()
                SettingRow(
                    icon = Icons.Outlined.Description,
                    label = "免责声明",
                    onClick = { showDisclaimer = true }
                )
                Hairline()
                SettingRow(
                    icon = Icons.Outlined.BugReport,
                    label = "诊断日志",
                    onClick = onOpenDiagnostics
                )
            }
            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

@Composable
private fun SettingsTopBar(onNavigateBack: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(56.dp)
            .padding(horizontal = 4.dp)
    ) {
        IconButton(onClick = onNavigateBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = arkdoColors.textPrimary)
        }
        Text(
            text = "设置",
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = arkdoColors.textPrimary
        )
    }
}

@Composable
private fun SettingsCard(
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
private fun AppearanceRow(mode: AppThemeMode) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        SettingRow(
            icon = Icons.Outlined.DarkMode,
            label = "外观",
            value = mode.label(),
            onClick = { expanded = true }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AppThemeMode.entries.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.label()) },
                    onClick = {
                        ThemeModeStore.setMode(item)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun FabGlassRow(enabled: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = CardPadding, vertical = 12.dp)
    ) {
        Icon(
            Icons.Outlined.BlurOn,
            contentDescription = null,
            tint = arkdoColors.textSecondary,
            modifier = Modifier.size(20.dp)
        )
        Column(
            modifier = Modifier
                .padding(start = 14.dp, end = 12.dp)
                .weight(1f)
        ) {
            Text("悬浮按钮玻璃效果", fontSize = 15.sp, color = arkdoColors.textPrimary)
            Text(
                "首页与详情页右下角按钮显示液态玻璃",
                fontSize = 12.sp,
                color = arkdoColors.textFaint,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = FabGlassStore::setEnabled,
            colors = SwitchDefaults.colors(checkedTrackColor = arkdoColors.ldAccent)
        )
    }
}

/** 只在开关打开时露出：关着时这个值不产生任何效果。拖动只改内存，松手才写盘。 */
@Composable
private fun FabGlassRadiusRow(radius: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = CardPadding, end = CardPadding, top = 12.dp, bottom = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("磨砂强度", fontSize = 15.sp, color = arkdoColors.textPrimary, modifier = Modifier.weight(1f))
            Text(radius.toString(), fontSize = 13.sp, color = arkdoColors.textFaint)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(FAB_GLASS_RADIUS_MIN.toString(), fontSize = 11.sp, color = arkdoColors.textFaint)
            Slider(
                value = radius.toFloat(),
                onValueChange = { FabGlassStore.setRadius(it) },
                onValueChangeFinished = FabGlassStore::flushRadius,
                valueRange = FAB_GLASS_RADIUS_MIN.toFloat()..FAB_GLASS_RADIUS_MAX.toFloat(),
                steps = FAB_GLASS_RADIUS_MAX - FAB_GLASS_RADIUS_MIN - 1,
                colors = SliderDefaults.colors(
                    thumbColor = arkdoColors.ldAccent,
                    activeTrackColor = arkdoColors.ldAccent,
                    inactiveTrackColor = arkdoColors.surfaceMuted
                ),
                modifier = Modifier.weight(1f)
            )
            Text(FAB_GLASS_RADIUS_MAX.toString(), fontSize = 11.sp, color = arkdoColors.textFaint)
        }
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    label: String,
    value: String = "",
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = CardPadding, vertical = 14.dp)
    ) {
        Icon(icon, contentDescription = null, tint = arkdoColors.textSecondary, modifier = Modifier.size(20.dp))
        Text(
            text = label,
            fontSize = 15.sp,
            color = arkdoColors.textPrimary,
            modifier = Modifier
                .padding(start = 14.dp)
                .weight(1f)
        )
        if (value.isNotEmpty()) {
            Text(
                text = value,
                fontSize = 13.sp,
                color = arkdoColors.textFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(end = 6.dp)
                    .widthIn(max = 180.dp)
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = arkdoColors.textFaint,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun AboutDialog(
    versionLabel: String,
    onOpenSite: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = arkdoColors.surfaceCard,
        title = {
            Text(
                "ArkDO",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = arkdoColors.textPrimary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "支持基于 Discourse（开源论坛程序）搭建的站点",
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = arkdoColors.textSecondary,
                    textAlign = TextAlign.Center
                )
                Text(
                    "版本 $versionLabel",
                    fontSize = 13.sp,
                    color = arkdoColors.textFaint,
                    modifier = Modifier.padding(top = 14.dp)
                )
                Row(modifier = Modifier.padding(top = 6.dp)) {
                    Text("官网 ", fontSize = 14.sp, color = arkdoColors.textSecondary)
                    Text(
                        OFFICIAL_SITE_URL,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = arkdoColors.ldAccent,
                        modifier = Modifier.clickable(onClick = onOpenSite)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", color = arkdoColors.textPrimary, fontWeight = FontWeight.Medium)
            }
        }
    )
}

@Composable
private fun DisclaimerDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = arkdoColors.surfaceCard,
        title = {
            Text(
                "免责声明",
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = arkdoColors.textPrimary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        },
        text = {
            Text(
                DISCLAIMER,
                fontSize = 14.sp,
                lineHeight = 22.sp,
                color = arkdoColors.textSecondary
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("知道了", color = arkdoColors.ldAccent, fontWeight = FontWeight.Medium)
            }
        }
    )
}

private fun AppThemeMode.label(): String = when (this) {
    AppThemeMode.SYSTEM -> "跟随系统"
    AppThemeMode.LIGHT -> "浅色"
    AppThemeMode.DARK -> "深色"
}

private fun openOfficialSite(context: android.content.Context) {
    val opened = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(OFFICIAL_SITE_URL)))
    }.isSuccess
    if (opened) return
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("arkdo", OFFICIAL_SITE_URL))
    Toast.makeText(context, "打不开浏览器，官网地址已复制", Toast.LENGTH_SHORT).show()
}
