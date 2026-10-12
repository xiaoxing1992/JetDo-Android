package com.jetdo.android.ui.qr

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
// - 关闭弹窗：撤销未被消费的 key（服务端 key 无过期机制，只能主动 revoke）
// - 过期时间已烘进二维码载荷（QrLoginFlow.OTP_WINDOW_MS），扫入端会自己拒收过期码，
//   这里的倒计时只是给用户看的读数 + 到点停轮询；出示端被杀也拦得住旧码
// - 二维码内容是明文 key+OTP（一次性、兑换即焚），窗口内别截图外传
// ==========================================================================

private const val LOG_TAG = "[JetDo][QrShow]"
private val OTP_WINDOW_SEC = (QrLoginFlow.OTP_WINDOW_MS / 1000).toInt()
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
    // 失败原因留一份给 UI。以前所有失败都压成一句「二维码生成失败」，用户报障只能复述
    // 「失败了」；而失败可能出在宿主机未就绪 / HTTP 状态码被拒 / payload 解不开 / nonce
    // 对不上——层级完全不同，修法也完全不同。把原因显示出来，一句话就能定性。
    var failReason by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    var consumed by remember { mutableStateOf(false) }
    var remainingSec by remember { mutableStateOf(OTP_WINDOW_SEC) }
    val pollInFlight = remember { AtomicBoolean(false) }

    // 创建分享 key → 生成二维码位图。attempt 变化 = 用户点了「重试」，整条重来。
    LaunchedEffect(username, attempt) {
        payload = null
        qrPainter = null
        failed = false
        failReason = null
        remainingSec = OTP_WINDOW_SEC
        QrLoginFlow.createSharePayload(context, username)
            .onSuccess { created ->
                payload = created
                runCatching {
                    qrPainter = BitmapPainter(qrBitmap(QrLoginPayload.encode(created), 640).asImageBitmap())
                }.onFailure {
                    Timber.w("%s qr render failed msg=%s", LOG_TAG, it.message)
                    failed = true
                    failReason = "qr render: ${it.message}"
                }
            }
            .onFailure {
                Timber.w("%s create share payload failed msg=%s", LOG_TAG, it.message)
                failed = true
                failReason = it.message
            }
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

    // 关闭的唯一出口：未消费就撤掉分享 key。
    // ⚠️ 「关闭」按钮此前直接调 onDismiss()，绕过了 onDismissRequest 里的撤销 —— 点「关闭」
    // 关掉的二维码，key 会**永久留在账号上**（服务端 user-api-key 没有过期机制，唯一回收
    // 手段就是这次撤销）。窗口外更要撤。
    val dismiss: () -> Unit = {
        val shared = payload
        if (shared != null && !consumed) {
            scope.launch {
                QrLoginFlow.revokeSharedKey(shared.apiKey)
            }
        }
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = dismiss,
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
                    failed -> {
                        val reason = failReason
                        Text(
                            // 宿主还没就绪（冷启动刚进 App 就点）是暂时性的，给一句可行动的
                            // 提示；其余原因原样透出，用户复述一句就够我们定位。
                            text = when {
                                reason == null -> stringResource(R.string.auth_qr_show_failed)
                                reason.contains(HOST_NOT_READY) ->
                                    stringResource(R.string.auth_qr_show_failed_host)
                                // 超时是这一步最常见的失败：脚本内部两次串行 fetch（取 CSRF +
                                // 发 POST），宿主还在过盾时很容易吃满窗口。它不该显示成
                                // 「生成失败：share key timeout」这种内部标记，给一句人说人话的。
                                reason.contains(QrLoginFlow.SHARE_KEY_TIMEOUT_TAG) ->
                                    stringResource(R.string.auth_qr_show_failed_timeout)
                                else -> stringResource(
                                    R.string.auth_qr_show_failed_reason,
                                    reason.take(120)
                                )
                            },
                            fontSize = 14.sp,
                            color = colors.textSecondary,
                            modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
                        )
                    }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 失败时给「重试」：宿主未就绪这类失败是暂时的，关掉重开太别扭。
                if (failed) {
                    TextButton(onClick = { attempt += 1 }) {
                        Text(stringResource(R.string.auth_qr_retry), color = colors.ldAccent)
                    }
                }
                TextButton(onClick = dismiss) {
                    Text(
                        stringResource(R.string.common_close),
                        color = if (failed) colors.textSecondary else colors.ldAccent
                    )
                }
            }
        }
    )
}

/** 「宿主未就绪」标记，来自 ArkWebNetworkBridge.runRawScript 的抛错文案。 */
private const val HOST_NOT_READY = "host not ready"

private fun formatRemaining(totalSec: Int): String {
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}
