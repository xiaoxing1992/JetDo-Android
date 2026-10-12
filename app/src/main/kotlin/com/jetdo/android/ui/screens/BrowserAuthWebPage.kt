package com.jetdo.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jetdo.android.R
import com.jetdo.android.services.auth.BrowserAuthLoginFlow
import com.jetdo.android.services.auth.BrowserAuthResult
import com.jetdo.android.services.auth.UserApiKeyLoginManager
import com.jetdo.android.ui.theme.jetdoColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

// ==========================================================================
// 浏览器授权登录 · App 内承载版。
//
// 与 [BrowserAuthLoginFlow] 走的是同一条协议（User API Key：明文公钥换加密 payload，
// 随行 OTP 换 _t），差别只在**授权页开在哪里**：
//  · 原来：Custom Tabs 拉起系统浏览器 → 那台浏览器自己得能通（实测华为机上被
//    安全浏览云策略拦 + 无 DoH 解析失败，点「授权」直接 403，深链永远不回）。
//  · 现在：复用 App 内的通用 [WebViewPage]（挂 WebViewDohInterceptor，站内请求走
//    OkHttp/DoH）→ 与「立即登录」同一条网络链路，站内会话、过盾、Cookie 落库全部同源。
//
// 回程深链 discourse://auth_redirect 由 WebViewPage 的 onSchemeUrl 钩子接住 --
// WebView 无法加载该 scheme，不接就会被丢给系统且毫无反馈（旧版「授权完没反应」的观感）。
//
// 关闭语义：授权成功即 onDone() 退回上一页；失败给遮罩 + 重试/关闭，不再让用户对着
// 一个不动的授权页猜发生了什么。
// ==========================================================================

private const val LOG_TAG = "[JetDo][BrowserAuthWeb]"

/** 成功态至少停留这么久再退页，否则「登录成功」一闪而过跟没提示一样。 */
private const val SUCCESS_HOLD_MS = 900L

/**
 * 授权页的处理阶段。
 *
 * 深链被接住之后是一串**挂起**动作（过盾 + 兑换 one-time-password + 会话复查），
 * 慢的时候好几秒。这段静默以前完全没有任何反馈 —— 用户看到的就是「点了没反应，
 * 过几秒页面自己消失了」，正是「点击授权没反应」这条反馈的来源。
 */
private enum class AuthWebPhase { IDLE, FINISHING, DONE }

@Composable
fun BrowserAuthWebPage(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 授权 URL 由 RSA 公钥 + 本次 nonce 拼成，构建要读/建密钥对（Default 线程），
    // 不能阻塞组合：先亮加载态，拿到 URL 再起 WebView。
    var authorizeUrl by remember { mutableStateOf<String?>(null) }
    var buildFailed by remember { mutableStateOf(false) }
    // 重试信号。WebViewPage 不响应 url 参数变化（AndroidView 的 factory 只跑一次），
    // 让「重试」真的重新发一次请求，只能靠 key 换掉整个 WebView。
    // 不重建 URL：nonce 仍在待处理集合里，同一张授权页再点一次「授权」依然能被认出。
    var attempt by remember { mutableIntStateOf(0) }
    var failure by remember { mutableStateOf<String?>(null) }
    var phase by remember { mutableStateOf(AuthWebPhase.IDLE) }

    LaunchedEffect(Unit) {
        authorizeUrl = runCatching { UserApiKeyLoginManager.buildAuthorizeUrl(context) }
            .onFailure { Timber.w("%s build authorize url failed msg=%s", LOG_TAG, it.message) }
            .getOrNull()
        buildFailed = authorizeUrl == null
    }

    val authorize = authorizeUrl
    if (authorize == null) {
        // URL 还没拼好（或在拼的当口就失败了）。这一屏不挂 WebView，避免先闪一个空壳。
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .background(jetdoColors.topicDetailPageBg)
        ) {
            if (buildFailed) {
                Text(
                    text = stringResource(R.string.auth_browser_launch_failed),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 40.dp)
                )
                Box(
                    modifier = Modifier
                        .padding(top = 20.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(onClick = onDone)
                        .padding(horizontal = 32.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.common_close),
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(40.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeWidth = 3.dp
                )
                Text(
                    text = stringResource(R.string.common_loading),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 18.dp)
                )
            }
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        key(attempt) {
            WebViewPage(
                url = authorize,
                pageTitle = stringResource(R.string.auth_browser_web_title),
                onNavigateBack = onDone,
                onSchemeUrl = { schemeUrl ->
                    // 回调发生在主线程；兑换是挂起动作（要过 ArkWebNetworkBridge），扔进 scope。
                    // 立刻返回 true：本次导航到此为止，WebView 停在授权页，不往深链上走。
                    // 先把遮罩立起来再去兑换 —— 兑换全程几秒，没有它这段就是「点了没反应」。
                    phase = AuthWebPhase.FINISHING
                    scope.launch {
                        when (val result = BrowserAuthLoginFlow.handleCallback(context, schemeUrl)) {
                            // 兑换成功：Cookie 已落库、会话总线已广播，给个成功态再退回上一页。
                            BrowserAuthResult.Success -> {
                                phase = AuthWebPhase.DONE
                                BrowserAuthLoginFlow.toastResult(context, result)
                                delay(SUCCESS_HOLD_MS)
                                onDone()
                            }
                            // 残留回调重放（nonce 对不上）：静默，用户什么都没点不该被打扰。
                            BrowserAuthResult.Ignored -> phase = AuthWebPhase.IDLE
                            is BrowserAuthResult.Failed -> {
                                phase = AuthWebPhase.IDLE
                                BrowserAuthLoginFlow.toastResult(context, result)
                                failure = context.getString(result.reasonRes)
                            }
                        }
                    }
                    true
                }
            )
        }
        failure?.let { message ->
            AuthFailureOverlay(
                message = message,
                onRetry = { failure = null; attempt++ },
                onClose = onDone
            )
        }
        // 处理中/成功态遮罩：同时挡住手势，避免兑换进行中用户在授权页上又点一次。
        if (failure == null && phase != AuthWebPhase.IDLE) {
            AuthStatusOverlay(
                message = stringResource(
                    if (phase == AuthWebPhase.DONE) R.string.auth_login_success
                    else R.string.auth_browser_finishing
                ),
                done = phase == AuthWebPhase.DONE
            )
        }
    }
}

/** 进行中/成功遮罩：压暗 + 居中卡片（转圈或对勾）。失败态另有 [AuthFailureOverlay]。 */
@Composable
private fun AuthStatusOverlay(message: String, done: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            // 无涟漪的点击拦截：遮罩期间手势不该穿到底下的 WebView。
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 30.dp, vertical = 22.dp)
        ) {
            if (done) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(38.dp)
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(38.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeWidth = 3.dp
                )
            }
            Text(
                text = message,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 14.dp)
            )
        }
    }
}

/** 授权失败遮罩：压暗授权页 + 居中卡片。失败必须可见，否则又回到「点了没反应」。 */
@Composable
private fun AuthFailureOverlay(message: String, onRetry: () -> Unit, onClose: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            // 无涟漪的点击拦截：遮罩期间手势不该穿到底下的 WebView（授权页上有按钮，
            // 误触会发出没人认领的请求）。indication = null 即「只吃事件、不画反馈」。
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(horizontal = 40.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 24.dp, vertical = 22.dp)
        ) {
            Text(
                text = message,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(top = 18.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(onClick = onRetry)
                        .padding(horizontal = 26.dp, vertical = 9.dp)
                ) {
                    Text(
                        text = stringResource(R.string.common_retry),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(onClick = onClose)
                        .padding(horizontal = 22.dp, vertical = 9.dp)
                ) {
                    Text(
                        text = stringResource(R.string.common_close),
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
