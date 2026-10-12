package com.jetdo.android.ui.qr

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jetdo.android.R
import com.jetdo.android.services.auth.QrLoginFlow
import com.jetdo.android.services.auth.QrLoginPayload
import com.jetdo.android.ui.theme.jetdoColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

// ==========================================================================
// 扫码登录·出示端弹窗：创建一枚一次性分享 key → 画成二维码给另一台 JetDo 扫。
//
// 两段式流程（对齐 FluxDO lib/widgets/auth/qr_login_sheet.dart）：
//   1) CONFIG —— 打开弹窗只显示安全说明 + 「生成登录二维码」按钮，**不签发任何东西**。
//      以前是「打开即生成」，用户只是想看看这个入口是干嘛的，账号上就多了一枚 User API Key。
//   2) 二次确认 —— 「确认生成登录二维码？」。key 一签发就落在账号上，不该由「点开入口」
//      这个动作顺带完成，必须用户显式点头。
//   3) QR —— 二维码 + 提示 + 有效期读数 + 账号 + 「重新生成」。
//
// - 3 秒轮询分享 key 存活：key 消失 = 对方已登录（扫入端兑换成功后自焚）→ 显示已完成
// - 关闭弹窗：撤销未被消费的 key（服务端 key 无过期机制，只能主动 revoke）
// - 「重新生成」：先把旧 key 撤掉再签发新的，兑现确认框里「重新生成会使此前分享的二维码
//   立即失效」的承诺
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
    val context = LocalContext.current

    // 两段式的状态机是纯函数（见 QrShowState.kt），这里只负责喂事件。
    var ui by remember(username) { mutableStateOf(QrShowUi()) }
    val consumed = ui.consumed
    var payload by remember { mutableStateOf<QrLoginPayload?>(null) }
    var qrPainter by remember { mutableStateOf<BitmapPainter?>(null) }
    var failed by remember { mutableStateOf(false) }
    // 失败原因留一份给 UI。以前所有失败都压成一句「二维码生成失败」，用户报障只能复述
    // 「失败了」；而失败可能出在宿主机未就绪 / HTTP 状态码被拒 / payload 解不开 / nonce
    // 对不上——层级完全不同，修法也完全不同。把原因显示出来，一句话就能定性。
    var failReason by remember { mutableStateOf<String?>(null) }
    var remainingSec by remember { mutableStateOf(OTP_WINDOW_SEC) }
    val pollInFlight = remember { AtomicBoolean(false) }

    // 创建分享 key → 生成二维码位图。attempt 变化 = 用户点了「重试/重新生成」，整条重来。
    //
    // 关键：CONFIG 段直接返回。这是本次改造的核心 —— 打开弹窗不再顺带签发一枚 User API Key。
    LaunchedEffect(username, ui.attempt, ui.stage) {
        if (!ui.shouldIssueShareKey()) return@LaunchedEffect
        ui = ui.copy(consumed = false)
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
    //
    // `consumed` 必须进 key：Compose 的 LaunchedEffect 只在 key 变化时重启，协程闭包捕获的是
    // **启动那一刻**的局部值。只 key 在 payload 上的话，`consumed` 翻真之后这两条循环还在用
    // 捕获到的旧 false 继续跑 —— 倒计时不收、轮询每 3 秒继续打 /u/{name}.json 直到窗口耗尽。
    // 被扫成功后还在轮询，等于对着一个已经有结论的弹窗白刷 10 分钟网络。
    LaunchedEffect(payload, consumed) {
        if (payload == null || consumed) return@LaunchedEffect
        while (isActive && remainingSec > 0) {
            delay(1000)
            remainingSec -= 1
        }
    }

    // 3 秒轮询 key 存活：消失 = 扫码端兑换成功并自焚 → 显示已完成。
    LaunchedEffect(payload, consumed) {
        val shared = payload ?: return@LaunchedEffect
        if (consumed) return@LaunchedEffect
        while (isActive && remainingSec > 0) {
            delay(ALIVE_POLL_INTERVAL_MS)
            if (!pollInFlight.compareAndSet(false, true)) continue
            try {
                if (QrLoginFlow.isShareKeyAlive(shared.username) == false) {
                    ui = ui.reduce(QrShowAction.MarkConsumed)
                }
            } finally {
                pollInFlight.set(false)
            }
        }
    }

    // 关闭的唯一出口：未消费就撤掉分享 key。
    // ⚠️ 「关闭」按钮此前直接调 onDismiss()，绕过了 onDismissRequest 里的撤销 —— 点「关闭」
    // 关掉的二维码，key 会**永久留在账号上**（服务端 user-api-key 没有过期机制，唯一回收
    // 手段就是这次撤销）。窗口外更要撤。CONFIG 段 payload 为 null，走这里也安全。
    //
    // ⚠️⚠️ 撤销走 QrLoginFlow.revokeSharedKeyDetached 而不是这里的 rememberCoroutineScope：
    // onDismiss() 会立刻把本弹窗移出组合树，remember 出来的 scope 随即被 cancel，协程在
    // 真正调度前就死了。真机日志实证：`revoke share key failed (ignored)
    // msg=rememberCoroutineScope left the composition`。
    val dismiss: () -> Unit = {
        ui.revocableKey(payload?.apiKey)?.let(QrLoginFlow::revokeSharedKeyDetached)
        onDismiss()
    }

    // 「重新生成」：先撤旧 key，再触发新一轮。失败态下 payload 为 null，等价于「重试」。
    val regenerate: () -> Unit = {
        ui.revocableKey(payload?.apiKey)?.let(QrLoginFlow::revokeSharedKeyDetached)
        ui = ui.reduce(QrShowAction.Regenerate)
    }

    if (ui.stage == QrShowStage.CONFIG) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { DialogTitle(stringResource(R.string.auth_qr_show_title)) },
            text = {
                Column {
                    SecurityNote()
                    Spacer(modifier = Modifier.height(14.dp))
                    AccountChip(username)
                }
            },
            confirmButton = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.common_cancel), color = colors.textSecondary)
                    }
                    TextButton(onClick = { ui = ui.reduce(QrShowAction.OpenConfirm) }) {
                        Text(stringResource(R.string.auth_qr_show_generate), color = colors.ldAccent)
                    }
                }
            }
        )
    } else {
        AlertDialog(
            onDismissRequest = dismiss,
            title = { DialogTitle(stringResource(R.string.auth_qr_show_title)) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    when {
                        qrPainter != null -> {
                            val expired = remainingSec <= 0
                            // 被扫掉之后**保留二维码**，改成压暗 + 加盖遮罩，而不是把码整个换成
                            // 一段文字（对齐 FluxDO 的 `_QrCard(dimmed:) + _buildConsumedOverlay`）。
                            // 被消耗掉的是「这一张码」，码本身从画面上消失，用户就再也看不出
                            // 刚刚到底哪张码被用掉了。
                            Box(contentAlignment = Alignment.Center) {
                                Image(
                                    painter = qrPainter!!,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(240.dp)
                                        .alpha(if (expired || consumed) 0.4f else 1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color.White)
                                        .padding(8.dp)
                                )
                                if (consumed) ConsumedOverlay()
                            }
                            if (consumed) {
                                Text(
                                    text = stringResource(R.string.auth_qr_show_consumed_body),
                                    fontSize = 12.5.sp,
                                    lineHeight = 18.sp,
                                    color = colors.textSecondary,
                                    modifier = Modifier.padding(top = 12.dp)
                                )
                            } else {
                                // 「用另一台 JetDo 扫这个码」这句在被扫之后必须撤掉：指令已经完成，
                                // 留着会和遮罩上的结论自相矛盾。
                                Text(
                                    text = stringResource(R.string.auth_qr_show_hint),
                                    fontSize = 13.sp,
                                    color = colors.textSecondary,
                                    modifier = Modifier.padding(top = 12.dp)
                                )
                                Text(
                                    text = if (expired) stringResource(R.string.auth_qr_show_expired)
                                    else stringResource(
                                        R.string.auth_qr_show_remaining,
                                        formatRemaining(remainingSec)
                                    ),
                                    fontSize = 12.sp,
                                    color = if (expired) colors.ldDanger else colors.textSecondary,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            AccountChip(
                                username = username,
                                modifier = Modifier.padding(top = 12.dp)
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
                                    // 被 Cloudflare 挑战：QrLoginFlow 已广播过盾信号、协调器正在跑
                                    // 恢复，这不是"出错了"，是"环境正在自动刷新"。
                                    reason.contains(QrLoginFlow.SHARE_KEY_CHALLENGE_TAG) ->
                                        stringResource(R.string.auth_qr_show_failed_challenge)
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
                        TextButton(onClick = regenerate) {
                            Text(stringResource(R.string.auth_qr_retry), color = colors.ldAccent)
                        }
                    }
                    // 码还在（含已过期）给「重新生成」，省得关掉再从头点一遍。
                    if (qrPainter != null && !consumed) {
                        TextButton(onClick = regenerate) {
                            Text(stringResource(R.string.auth_qr_show_regenerate), color = colors.textSecondary)
                        }
                    }
                    TextButton(onClick = dismiss) {
                        // 被扫之后主按钮换成「完成」（对齐 FluxDO）。此时不该再给「重新生成」
                        // 并排 —— 用户在已经完成的弹窗上再签一枚 key，等于又留一把凭据。
                        Text(
                            stringResource(
                                if (consumed) R.string.common_done else R.string.common_close
                            ),
                            color = if (failed && !consumed) colors.textSecondary else colors.ldAccent
                        )
                    }
                }
            }
        )
    }

    // 二次确认：只有点了「确认生成」才真的签发 key。
    if (ui.confirmOpen) {
        AlertDialog(
            onDismissRequest = { ui = ui.reduce(QrShowAction.CancelConfirm) },
            title = {
                Text(
                    text = stringResource(R.string.auth_qr_show_confirm_title),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.auth_qr_show_confirm_message),
                    fontSize = 14.sp,
                    color = colors.textSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = { ui = ui.reduce(QrShowAction.ConfirmGenerate) }) {
                    Text(stringResource(R.string.auth_qr_show_confirm_action), color = colors.ldAccent)
                }
            },
            dismissButton = {
                TextButton(onClick = { ui = ui.reduce(QrShowAction.CancelConfirm) }) {
                    Text(stringResource(R.string.common_cancel), color = colors.textSecondary)
                }
            }
        )
    }
}

/**
 * 被扫成功后的遮罩：对勾 + 结论，盖在压暗的二维码上。
 *
 * 底色用黑色半透明 scrim 而不是主题色：这张遮罩压在**白底黑码**上，码本身永远是白底、
 * 不随主题走，只有暗底白字才在浅色/深色两种主题下都读得清。
 *
 * （FluxDO 这层还挂了 `BackdropFilter blur(6)`；Compose 的等价物 `Modifier.blur` 需要
 * API 31，本 App minSdk 26，低版本会静默失效、反而在不同机型上长得不一样，所以统一用
 * 「压暗 + scrim」表达同一个语义。）
 */
@Composable
private fun ConsumedOverlay() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .size(240.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.45f))
    ) {
        Icon(
            imageVector = Icons.Rounded.CheckCircle,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(40.dp)
        )
        Text(
            text = stringResource(R.string.auth_qr_show_consumed),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp, start = 16.dp, end = 16.dp)
        )
    }
}

/** 弹窗标题：二维码图标 + 文案，对齐 FluxDO 的标题行。 */
@Composable
private fun DialogTitle(text: String) {
    val colors = jetdoColors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Rounded.QrCode2,
            contentDescription = null,
            tint = colors.ldAccent,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = text,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            color = colors.textPrimary,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

/** 风险提示条：这张码里是明文 key + 一次性令牌。 */
@Composable
private fun SecurityNote() {
    val colors = jetdoColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.ldDanger.copy(alpha = 0.09f))
            .padding(12.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.WarningAmber,
            contentDescription = null,
            tint = colors.ldDanger,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = stringResource(R.string.auth_qr_show_security_warning),
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
            color = colors.textSecondary,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

/** 「账号：xxx」胶囊，对齐 FluxDO 的 _InfoChip。 */
@Composable
private fun AccountChip(username: String, modifier: Modifier = Modifier) {
    val colors = jetdoColors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surfaceMuted)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.Person,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(13.dp)
        )
        Text(
            text = stringResource(R.string.auth_qr_show_account, username),
            fontSize = 12.sp,
            color = colors.textSecondary,
            modifier = Modifier.padding(start = 5.dp)
        )
    }
}

/** 「宿主未就绪」标记，来自 ArkWebNetworkBridge.runRawScript 的抛错文案。 */
private const val HOST_NOT_READY = "host not ready"

private fun formatRemaining(totalSec: Int): String {
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}
