package com.jetdo.android.ui.qr

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jetdo.android.R
import com.jetdo.android.services.auth.QrLoginFlow
import com.jetdo.android.services.auth.QrLoginPayload
import com.jetdo.android.ui.theme.jetdoColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

// ==========================================================================
// 扫码登录·出示端弹窗：创建一枚一次性分享 key → 画成二维码给另一台 JetDo 扫。
// - 3 秒轮询分享 key 存活：key 消失 = 对方已登录（扫入端兑换成功后自焚）→ 显示已完成
// - 关闭弹窗 / 10 分钟窗口过期：撤销未被消费的 key（服务端 key 无过期机制，只能主动 revoke）
// - 二维码内容是明文 key+OTP（一次性、兑换即焚），窗口内别截图外传
// ==========================================================================

private const val LOG_TAG = "[JetDo][QrShow]"
private const val OTP_WINDOW_SEC = 10 * 60
private const val ALIVE_POLL_INTERVAL_MS = 3000L

@Composable
fun QrLoginShowDialog(
    username: String,
    onDismiss: () -> Unit
) {
    val colors = jetdoColors
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var payload by remember { mutableStateOf<QrLoginPayload?>(null) }
    var qrPainter by remember { mutableStateOf<BitmapPainter?>(null) }
    var failed by remember { mutableStateOf(false) }
    var consumed by remember { mutableStateOf(false) }
    var remainingSec by remember { mutableStateOf(OTP_WINDOW_SEC) }
    val pollInFlight = remember { AtomicBoolean(false) }

    // 创建分享 key → 生成二维码位图。
    LaunchedEffect(username) {
        QrLoginFlow.createSharePayload(context, username)
            .onSuccess { created ->
                payload = created
                runCatching {
                    qrPainter = BitmapPainter(qrBitmap(QrLoginPayload.encode(created), 640).asImageBitmap())
                }.onFailure {
                    Timber.w("%s qr render failed msg=%s", LOG_TAG, it.message)
                    failed = true
                }
            }
            .onFailure { failed = true }
    }

    // 10 分钟窗口倒数（服务端 OTP TTL 兜底值；key 本身不过期，过窗主动 revoke）。
    LaunchedEffect(payload) {
        if (payload == null) return@LaunchedEffect
        while (isActive && remainingSec > 0 && !consumed) {
            delay(1000)
            remainingSec -= 1
        }
    }

    // 3 秒轮询 key 存活：消失 = 扫码端兑换成功并自焚 → 显示已完成。
    LaunchedEffect(payload) {
        val shared = payload ?: return@LaunchedEffect
        while (isActive && !consumed && remainingSec > 0) {
            delay(ALIVE_POLL_INTERVAL_MS)
            if (!pollInFlight.compareAndSet(false, true)) continue
            try {
                if (QrLoginFlow.isShareKeyAlive(shared.username) == false) {
                    consumed = true
                }
            } finally {
                pollInFlight.set(false)
            }
        }
    }

    AlertDialog(
        onDismissRequest = {
            val shared = payload
            if (shared != null && !consumed && remainingSec > 0) {
                // 未消费的 key 主动撤销；已在窗口外/已消费则无需（已被对方焚毁或过期）。
                scope.launch {
                    QrLoginFlow.revokeSharedKey(shared.apiKey)
                }
            }
            onDismiss()
        },
        title = {
            Text(
                text = if (consumed) stringResource(R.string.auth_qr_show_consumed)
                else stringResource(R.string.auth_qr_show_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = colors.textPrimary
            )
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                when {
                    consumed -> Text(
                        text = stringResource(R.string.auth_qr_show_consumed_body),
                        fontSize = 14.sp,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                    qrPainter != null -> {
                        Image(
                            painter = qrPainter!!,
                            contentDescription = null,
                            modifier = Modifier
                                .size(240.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White)
                                .padding(8.dp)
                        )
                        Text(
                            text = stringResource(R.string.auth_qr_show_hint),
                            fontSize = 13.sp,
                            color = colors.textSecondary,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                        Text(
                            text = stringResource(R.string.auth_qr_show_remaining, formatRemaining(remainingSec)),
                            fontSize = 12.sp,
                            color = colors.textSecondary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    failed -> Text(
                        text = stringResource(R.string.auth_qr_show_failed),
                        fontSize = 14.sp,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                    else -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            color = colors.ldAccent,
                            strokeWidth = 2.dp
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss() }) {
                Text(stringResource(R.string.common_close), color = colors.ldAccent)
            }
        }
    )
}

private fun formatRemaining(totalSec: Int): String {
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}
