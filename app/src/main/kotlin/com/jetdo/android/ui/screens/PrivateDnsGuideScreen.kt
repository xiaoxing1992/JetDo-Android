package com.jetdo.android.ui.screens

import androidx.compose.ui.res.stringResource
import com.jetdo.android.R
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jetdo.android.services.network.NetworkAccessibilityChecker
import kotlinx.coroutines.launch

/**
 * Private DNS 配置引导页面
 *
 * 引导用户配置系统 Private DNS (DoH) 以绕过 DNS 污染访问 linux.do
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivateDnsGuideScreen(
    onDismiss: () -> Unit,
    onConfigured: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var canAccess by remember { mutableStateOf<Boolean?>(null) }

    fun checkAccess() {
        checking = true
        scope.launch {
            canAccess = NetworkAccessibilityChecker.canAccessLinuxDo()
            checking = false
            if (canAccess == true) {
                onConfigured()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.pdns_guide_title)) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 状态指示
            when {
                checking -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.pdns_checking))
                }
                canAccess == true -> {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(stringResource(R.string.pdns_ok))
                        }
                    }
                }
                canAccess == false -> {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Column {
                                Text(
                                    stringResource(R.string.pdns_fail),
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    stringResource(R.string.pdns_fail_reason),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 说明文字
            Text(
                stringResource(R.string.pdns_why),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.pdns_polluted) +
                        stringResource(R.string.pdns_intro) +
                        stringResource(R.string.pdns_intro2),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            // 配置步骤
            Text(
                stringResource(R.string.pdns_steps),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            ConfigStep(
                step = "1",
                title = stringResource(R.string.notif_tap_settings),
                description = stringResource(R.string.pdns_jumps_to_system)
            )

            ConfigStep(
                step = "2",
                title = stringResource(R.string.pdns_step_find),
                description = stringResource(R.string.pdns_where)
            )

            ConfigStep(
                step = "3",
                title = stringResource(R.string.pdns_step_select),
                description = stringResource(R.string.pdns_servers2) +
                        stringResource(R.string.pdns_server_ali2) +
                        stringResource(R.string.pdns_server_tencent2) +
                        "• dns.google（Google DNS）"
            )

            ConfigStep(
                step = "4",
                title = stringResource(R.string.challenge_save_and_return),
                description = stringResource(R.string.pdns_verify_hint)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 操作按钮
            Button(
                onClick = {
                    try {
                        val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS)
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        // 降级到通用设置页面
                        val fallbackIntent = Intent(Settings.ACTION_SETTINGS)
                        context.startActivity(fallbackIntent)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Settings, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.common_open_system_settings))
            }

            OutlinedButton(
                onClick = { checkAccess() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !checking
            ) {
                Text(stringResource(R.string.pdns_check))
            }

            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.challenge_later))
            }
        }
    }
}

@Composable
private fun ConfigStep(
    step: String,
    title: String,
    description: String
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(32.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    step,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        Column {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
