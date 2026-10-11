package com.jetdo.android.ui.screens

import androidx.compose.ui.res.stringResource
import com.jetdo.android.core.data.AppContextHolder
import com.jetdo.android.R
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
import com.jetdo.android.BuildConfig
import com.jetdo.android.common.constants.AppConstants
import com.jetdo.android.common.theme.AppThemeMode
import com.jetdo.android.common.theme.ThemeModeStore
import com.jetdo.android.core.designsystem.FAB_GLASS_RADIUS_MAX
import com.jetdo.android.core.designsystem.FAB_GLASS_RADIUS_MIN
import com.jetdo.android.core.designsystem.FabGlassStore
import com.jetdo.android.ui.theme.jetdoColors

// 设置页,对齐鸿蒙 SettingsPage:灰底无描边卡片。
// 外观三档会立刻改主题。关于里的反馈、版本、免责声明、诊断日志是真入口。
// 「网络与连接」已迁(内有 DoH 配置);「通知」已迁(系统通知权限 + 发送测试通知)。
// 论坛 / 我的站点在 Android 上还没有子页,点了只提示,不造一个空页面冒充已经做好。
// 实验性功能只放了首页新话题提醒。

private val PageMargin = 12.dp
private val CardRadius = 16.dp
private val CardPadding = 16.dp
private val CardGap = 12.dp
private const val OFFICIAL_SITE_URL = "https://jetdo.app"

private val DISCLAIMER: String get() = AppContextHolder.getString(R.string.about_disclaimer1b) +
    AppContextHolder.getString(R.string.about_disclaimer3) +
    AppContextHolder.getString(R.string.about_disclaimer4b) +
    AppContextHolder.getString(R.string.about_disclaimer2)

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
            .background(jetdoColors.pageBgGray)
    ) {
        SettingsTopBar(onNavigateBack)
        HorizontalDivider(color = jetdoColors.border, thickness = 0.5.dp)
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
                    label = stringResource(R.string.settings_topic_list_style),
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
                    label = stringResource(R.string.home_notifications),
                    onClick = onOpenPushSettings
                )
                Hairline()
                SettingRow(
                    icon = Icons.Outlined.Wifi,
                    label = stringResource(R.string.settings_network),
                    onClick = onOpenNetworkSettings
                )
                Hairline()
                SettingRow(
                    icon = Icons.Outlined.Science,
                    label = stringResource(R.string.settings_experimental),
                    onClick = onOpenExperimental
                )
            }

            Text(
                text = stringResource(R.string.settings_about),
                fontSize = 13.sp,
                color = jetdoColors.textFaint,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = CardPadding, end = CardPadding, top = 18.dp, bottom = 8.dp)
            )
            SettingsCard(modifier = Modifier.padding(top = 0.dp)) {
                SettingRow(
                    icon = Icons.Outlined.ChatBubbleOutline,
                    label = stringResource(R.string.settings_feedback),
                    onClick = onOpenFeedback
                )
                Hairline()
                SettingRow(
                    icon = Icons.Outlined.Info,
                    label = stringResource(R.string.about_jetdo),
                    value = versionLabel,
                    onClick = { showAbout = true }
                )
                Hairline()
                SettingRow(
                    icon = Icons.Outlined.Description,
                    label = stringResource(R.string.about_disclaimer),
                    onClick = { showDisclaimer = true }
                )
                Hairline()
                SettingRow(
                    icon = Icons.Outlined.BugReport,
                    label = stringResource(R.string.debug_diagnostics),
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
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = jetdoColors.textPrimary)
        }
        Text(
            text = stringResource(R.string.common_settings),
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = jetdoColors.textPrimary
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
            .background(jetdoColors.surfaceCard, RoundedCornerShape(CardRadius))
    ) {
        content()
    }
}

@Composable
private fun Hairline() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = CardPadding),
        thickness = 0.5.dp,
        color = jetdoColors.border
    )
}

@Composable
private fun AppearanceRow(mode: AppThemeMode) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        SettingRow(
            icon = Icons.Outlined.DarkMode,
            label = stringResource(R.string.settings_appearance),
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
            tint = jetdoColors.textSecondary,
            modifier = Modifier.size(20.dp)
        )
        Column(
            modifier = Modifier
                .padding(start = 14.dp, end = 12.dp)
                .weight(1f)
        ) {
            Text(stringResource(R.string.settings_fab_glass), fontSize = 15.sp, color = jetdoColors.textPrimary)
            Text(
                stringResource(R.string.settings_fab_glass_desc),
                fontSize = 12.sp,
                color = jetdoColors.textFaint,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = FabGlassStore::setEnabled,
            colors = SwitchDefaults.colors(checkedTrackColor = jetdoColors.ldAccent)
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
            Text(stringResource(R.string.settings_frost_intensity), fontSize = 15.sp, color = jetdoColors.textPrimary, modifier = Modifier.weight(1f))
            Text(radius.toString(), fontSize = 13.sp, color = jetdoColors.textFaint)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(FAB_GLASS_RADIUS_MIN.toString(), fontSize = 11.sp, color = jetdoColors.textFaint)
            Slider(
                value = radius.toFloat(),
                onValueChange = { FabGlassStore.setRadius(it) },
                onValueChangeFinished = FabGlassStore::flushRadius,
                valueRange = FAB_GLASS_RADIUS_MIN.toFloat()..FAB_GLASS_RADIUS_MAX.toFloat(),
                steps = FAB_GLASS_RADIUS_MAX - FAB_GLASS_RADIUS_MIN - 1,
                colors = SliderDefaults.colors(
                    thumbColor = jetdoColors.ldAccent,
                    activeTrackColor = jetdoColors.ldAccent,
                    inactiveTrackColor = jetdoColors.surfaceMuted
                ),
                modifier = Modifier.weight(1f)
            )
            Text(FAB_GLASS_RADIUS_MAX.toString(), fontSize = 11.sp, color = jetdoColors.textFaint)
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
        Icon(icon, contentDescription = null, tint = jetdoColors.textSecondary, modifier = Modifier.size(20.dp))
        Text(
            text = label,
            fontSize = 15.sp,
            color = jetdoColors.textPrimary,
            modifier = Modifier
                .padding(start = 14.dp)
                .weight(1f)
        )
        if (value.isNotEmpty()) {
            Text(
                text = value,
                fontSize = 13.sp,
                color = jetdoColors.textFaint,
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
            tint = jetdoColors.textFaint,
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
        containerColor = jetdoColors.surfaceCard,
        title = {
            Text(
                "JetDo",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = jetdoColors.textPrimary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.about_discourse_desc),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = jetdoColors.textSecondary,
                    textAlign = TextAlign.Center
                )
                Text(
                    stringResource(R.string.about_version, versionLabel),
                    fontSize = 13.sp,
                    color = jetdoColors.textFaint,
                    modifier = Modifier.padding(top = 14.dp)
                )
                Row(modifier = Modifier.padding(top = 6.dp)) {
                    Text(stringResource(R.string.about_website), fontSize = 14.sp, color = jetdoColors.textSecondary)
                    Text(
                        OFFICIAL_SITE_URL,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = jetdoColors.ldAccent,
                        modifier = Modifier.clickable(onClick = onOpenSite)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_close), color = jetdoColors.textPrimary, fontWeight = FontWeight.Medium)
            }
        }
    )
}

@Composable
private fun DisclaimerDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = jetdoColors.surfaceCard,
        title = {
            Text(
                stringResource(R.string.about_disclaimer),
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = jetdoColors.textPrimary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        },
        text = {
            Text(
                DISCLAIMER,
                fontSize = 14.sp,
                lineHeight = 22.sp,
                color = jetdoColors.textSecondary
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_got_it), color = jetdoColors.ldAccent, fontWeight = FontWeight.Medium)
            }
        }
    )
}

@Composable
private fun AppThemeMode.label(): String = when (this) {
    AppThemeMode.SYSTEM -> stringResource(R.string.settings_follow_system)
    AppThemeMode.LIGHT -> stringResource(R.string.settings_light)
    AppThemeMode.DARK -> stringResource(R.string.settings_dark)
}

private fun openOfficialSite(context: android.content.Context) {
    val opened = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(OFFICIAL_SITE_URL)))
    }.isSuccess
    if (opened) return
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("jetdo", OFFICIAL_SITE_URL))
    Toast.makeText(context, context.getString(R.string.about_browser_failed), Toast.LENGTH_SHORT).show()
}
