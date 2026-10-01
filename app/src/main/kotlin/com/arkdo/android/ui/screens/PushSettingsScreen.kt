package com.arkdo.android.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.arkdo.android.services.notification.NotificationPermission
import com.arkdo.android.services.notification.NotificationPermissionState
import com.arkdo.android.services.notification.SystemNotifier
import com.arkdo.android.ui.theme.arkdoColors

// ==========================================================================
// 「通知」子页，对齐鸿蒙 views/pages/PushSettingsPage.ets。
//
// 鸿蒙该页有四块：系统推送开关、发送测试推送、推送中继地址、首启同意告知。
// Android 侧**只做第一块** —— 后三块依赖 P0-1 的推送链路（HarmonyPushTokenProvider /
// PushRelayClient / PushSubscriptionService 那一整套），尚未迁移。这里不摆空壳充数：
// 中继地址填了没处用、测试推送没有中继可打，做出来只会让人以为功能已经好了。
//
// 与鸿蒙的一处必要分歧：鸿蒙的推送开关是「App 自己维护的状态」，关掉就是关掉；
// Android 的通知权限开关**归系统所有**，App 能请求、但不能撤销。所以这里的开关
// 拨到「开」是申请权限，拨到「关」只能把用户送去系统设置 —— 否则会出现
// 「开关显示已关、通知却照收」的假象。
// ==========================================================================

private val PageMargin = 12.dp
private val CardRadius = 16.dp
private val CardPadding = 16.dp
private val CardGap = 12.dp

@Composable
fun PushSettingsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // shouldShowRequestPermissionRationale 是 Activity 的方法；Compose 的 LocalContext
    // 在 Activity 宿主下就是它本身，但经过 ContextWrapper 包装过，所以要走 findActivity。
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current

    var state by remember(context, activity) { mutableStateOf(resolveNotificationState(context, activity)) }
    var sending by remember { mutableStateOf(false) }

    // 用户可能跳去系统设置改了开关再回来 —— 本页组合是复用的（没被销毁），
    // remember 不会重跑，只能在 RESUMED 时重算。
    DisposableEffect(lifecycleOwner, context, activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                state = resolveNotificationState(context, activity)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        state = resolveNotificationState(context, activity)
        when {
            granted -> toast(context, "通知权限已开启")
            // 已走到永久拒绝：系统不会再弹框，只能去系统设置。
            state == NotificationPermissionState.DENIED_PERMANENT ->
                toast(context, "系统已不再询问，请在系统设置中开启")
            else -> toast(context, "未授予通知权限")
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(arkdoColors.pageBgGray)
    ) {
        PushSettingsTopBar(onNavigateBack)
        HorizontalDivider(color = arkdoColors.border, thickness = 0.5.dp)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PageMargin)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            Column(
                modifier = Modifier
                    .padding(top = CardGap)
                    .fillMaxWidth()
                    .background(arkdoColors.surfaceCard, RoundedCornerShape(CardRadius))
            ) {
                NotificationPermissionRow(
                    state = state,
                    onToggle = { wantOn ->
                        when {
                            // 已开启时拨「开」：状态没变，什么都不用做。
                            wantOn && state == NotificationPermissionState.GRANTED -> Unit

                            // 永久拒绝：再 launch 也弹不出框，直接送用户去系统设置。
                            wantOn && state == NotificationPermissionState.DENIED_PERMANENT -> {
                                toast(context, "请在系统设置中开启通知")
                                NotificationPermission.openSystemSettings(context)
                            }

                            wantOn -> {
                                // 必须在 launch 之前打标记：canRequestAgain 靠它区分
                                // 「从未请求」与「已永久拒绝」。
                                NotificationPermission.markRequested(context)
                                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }

                            else -> {
                                // App 无权撤销自己的通知权限，这个方向的开关只能由系统设置完成。
                                toast(context, "请在系统设置中关闭")
                                NotificationPermission.openSystemSettings(context)
                            }
                        }
                    }
                )

                // 测试通知只在已开启时露出：权限没到手时它必然失败，摆在那里只会制造噪音。
                if (state == NotificationPermissionState.GRANTED) {
                    Hairline()
                    TestNotificationRow(
                        sending = sending,
                        onClick = {
                            if (!sending) {
                                sending = true
                                val ok = SystemNotifier.notifyTest(context)
                                sending = false
                                toast(
                                    context,
                                    if (ok) "已发送，请下拉通知栏查看" else "发送失败，请检查系统设置中的通知开关"
                                )
                            }
                        }
                    )
                }

                // 永久拒绝时给一条明路。放在卡片里而不是弹窗，是因为用户可能反复回到这页。
                if (state == NotificationPermissionState.DENIED_PERMANENT) {
                    Hairline()
                    OpenSettingsRow(
                        onClick = { NotificationPermission.openSystemSettings(context) }
                    )
                }
            }

            Text(
                text = "通知权限由系统管理。关闭后，论坛的回复、私信与提及都不会出现在通知栏，" +
                    "但应用内通知页仍会正常累积未读。",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = arkdoColors.textFaint,
                modifier = Modifier.padding(start = CardPadding, end = CardPadding, top = 12.dp)
            )

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

@Composable
private fun PushSettingsTopBar(onNavigateBack: () -> Unit) {
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
            text = "通知",
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = arkdoColors.textPrimary
        )
    }
}

@Composable
private fun NotificationPermissionRow(
    state: NotificationPermissionState,
    onToggle: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = CardPadding, vertical = 12.dp)
    ) {
        Icon(
            Icons.Outlined.Notifications,
            contentDescription = null,
            tint = arkdoColors.textSecondary,
            modifier = Modifier.size(20.dp)
        )
        Column(
            modifier = Modifier
                .padding(start = 14.dp, end = 12.dp)
                .weight(1f)
        ) {
            Text("系统通知权限", fontSize = 15.sp, color = arkdoColors.textPrimary)
            Text(
                text = when (state) {
                    NotificationPermissionState.GRANTED -> "已开启，可接收系统通知"
                    NotificationPermissionState.DENIED_CAN_ASK -> "未开启，打开开关即可授权"
                    NotificationPermissionState.DENIED_PERMANENT -> "已被系统关闭，需前往系统设置开启"
                },
                fontSize = 12.sp,
                color = arkdoColors.textFaint,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Switch(
            checked = state == NotificationPermissionState.GRANTED,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(checkedTrackColor = arkdoColors.ldAccent)
        )
    }
}

@Composable
private fun TestNotificationRow(sending: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !sending, onClick = onClick)
            .padding(horizontal = CardPadding, vertical = 12.dp)
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.Send,
            contentDescription = null,
            tint = arkdoColors.textSecondary,
            modifier = Modifier.size(20.dp)
        )
        Column(
            modifier = Modifier
                .padding(start = 14.dp, end = 12.dp)
                .weight(1f)
        ) {
            Text("发送测试通知", fontSize = 15.sp, color = arkdoColors.textPrimary)
            Text(
                text = "验证通知能否真正抵达通知栏",
                fontSize = 12.sp,
                color = arkdoColors.textFaint,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = arkdoColors.textFaint,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun OpenSettingsRow(onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = CardPadding, vertical = 12.dp)
    ) {
        Icon(
            Icons.Outlined.Settings,
            contentDescription = null,
            tint = arkdoColors.textSecondary,
            modifier = Modifier.size(20.dp)
        )
        Column(
            modifier = Modifier
                .padding(start = 14.dp, end = 12.dp)
                .weight(1f)
        ) {
            Text("前往系统设置", fontSize = 15.sp, color = arkdoColors.textPrimary)
            Text(
                text = "在系统的通知设置里重新打开",
                fontSize = 12.sp,
                color = arkdoColors.textFaint,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = arkdoColors.textFaint,
            modifier = Modifier.size(20.dp)
        )
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

/** 取当前状态。activity 为 null（预览等场景）时按最保守的永久拒绝处理，不显示"可申请"。 */
private fun resolveNotificationState(
    context: Context,
    activity: Activity?
): NotificationPermissionState = activity
    ?.let { NotificationPermission.state(context, NotificationPermission.canRequestAgain(context, it)) }
    ?: NotificationPermissionState.DENIED_PERMANENT

/** LocalContext 在 Activity 宿主下可能是被包装过的 Context，逐层拆到 Activity。 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
