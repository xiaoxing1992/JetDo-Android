package com.arkdo.android.ui.level

import androidx.compose.ui.res.stringResource
import com.arkdo.android.core.data.AppContextHolder
import com.arkdo.android.R
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkdo.android.common.constants.AppConstants
import com.arkdo.android.core.designsystem.FaIcon
import com.arkdo.android.data.models.ConnectBarItem
import com.arkdo.android.data.models.ConnectLevelData
import com.arkdo.android.data.models.ConnectQuotaItem
import com.arkdo.android.data.models.ConnectRingItem
import com.arkdo.android.data.models.ConnectVetoItem
import com.arkdo.android.services.connect.CONNECT_EXTRACT_SCRIPT
import com.arkdo.android.services.connect.ConnectLevelParser
import com.arkdo.android.ui.theme.arkdoColors
import com.arkdo.android.ui.theme.isJetDoInDarkTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.roundToInt

// 对齐鸿蒙 LevelProgressPage:两侧留白 12,卡片圆角/内距 16。
private val PageMargin = 12.dp
private val CardRadius = 16.dp
private val CardPadding = 16.dp
private val SectionTop = 20.dp
private val SectionBottom = 16.dp
private const val READY_TIMEOUT_MS = 20_000L

private enum class LevelPhase { LOADING, READY, ERROR, ANON }

private val BorderSuccess = Color(0x3322C55E)
private val BorderDanger = Color(0x33EF4444)
private val IconBgSuccess = Color(0x2622C55E)
private val IconBgDanger = Color(0x26EF4444)
private val BarMet = listOf(Color(0xFF22C55E), Color(0xFF4ADE80))
private val BarUnmet = listOf(Color(0xFFF59E0B), Color(0xFFFBBF24))

private data class ConnectColors(
    val success: Color,
    val danger: Color,
    val warning: Color,
    val successBg: Color,
    val dangerBg: Color,
    val track: Color,
    val card: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color
)

private fun connectColors(dark: Boolean) = if (dark) {
    ConnectColors(
        success = Color(0xFF22C55E), danger = Color(0xFFEF4444), warning = Color(0xFFF59E0B),
        successBg = Color(0xFF0A2E14), dangerBg = Color(0xFF2E0A0A), track = Color(0xFF262626),
        card = Color(0xFF191919), border = Color(0xFF2A2A2A), textPrimary = Color(0xFFF0F0F0),
        textSecondary = Color(0xFFA0A0A0), textTertiary = Color(0xFF666666)
    )
} else {
    ConnectColors(
        success = Color(0xFF22C55E), danger = Color(0xFFEF4444), warning = Color(0xFFF59E0B),
        successBg = Color(0xFFF0FDF4), dangerBg = Color(0xFFFEF2F2), track = Color(0xFFEBEBEB),
        card = Color(0xFFFFFFFF), border = Color(0xFFE0E0E0), textPrimary = Color(0xFF111111),
        textSecondary = Color(0xFF555555), textTertiary = Color(0xFF888888)
    )
}

/**
 * 等级进度页。connect.linux.do 服务端渲染,数值从隐藏 WebView 的 DOM 抠出后按 connect 配色重画。
 */
@Composable
fun LevelProgressScreen(
    isLoggedIn: Boolean,
    onNavigateBack: () -> Unit,
    onOpenWeb: (String, String) -> Unit
) {
    val colors = arkdoColors
    val connect = connectColors(isJetDoInDarkTheme())
    var phase by remember { mutableStateOf(if (isLoggedIn) LevelPhase.LOADING else LevelPhase.ANON) }
    var data by remember { mutableStateOf<ConnectLevelData?>(null) }
    var reloadToken by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.pageBgGray)
    ) {
        if (isLoggedIn) {
            ConnectWebHost(
                url = AppConstants.CONNECT_URL,
                reloadToken = reloadToken,
                onPageEnd = { web ->
                    if (phase == LevelPhase.READY) return@ConnectWebHost
                    scope.launch {
                        extractLevel(web)?.let { parsed ->
                            data = parsed
                            phase = LevelPhase.READY
                            return@launch
                        }
                        delay(600)
                        if (phase == LevelPhase.READY) return@launch
                        extractLevel(web)?.let { parsed ->
                            data = parsed
                            phase = LevelPhase.READY
                            return@launch
                        }
                        delay(900)
                        if (phase == LevelPhase.READY) return@launch
                        extractLevel(web)?.let { parsed ->
                            data = parsed
                            phase = LevelPhase.READY
                        }
                    }
                },
                onMainFrameError = {
                    if (phase != LevelPhase.READY && reloadToken < 2) {
                        scope.launch {
                            delay(1200)
                            if (phase != LevelPhase.READY) reloadToken += 1
                        }
                    } else if (phase != LevelPhase.READY) {
                        phase = LevelPhase.ERROR
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
        Column(modifier = Modifier.fillMaxSize()) {
            LevelTopBar(onNavigateBack)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = PageMargin)
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                val ready = data
                when {
                    phase == LevelPhase.READY && ready != null -> {
                        LevelCard(ready, connect)
                        PoweredByFooter(connect)
                    }
                    phase == LevelPhase.LOADING -> LevelLoading()
                    phase == LevelPhase.ANON -> LevelMessage(stringResource(R.string.login_required), stringResource(R.string.user_trust_login_required), false, onRetry = {}, onOpenWeb = onOpenWeb)
                    else -> LevelMessage(
                        stringResource(R.string.doh_unknown_state),
                        stringResource(R.string.challenge_maybe_verify),
                        true,
                        onRetry = {
                            data = null
                            phase = LevelPhase.LOADING
                            reloadToken = 0
                        },
                        onOpenWeb = onOpenWeb
                    )
                }
            }
        }
    }

    if (isLoggedIn && phase == LevelPhase.LOADING) {
        LaunchedEffect(reloadToken) {
            delay(READY_TIMEOUT_MS)
            if (phase == LevelPhase.LOADING) phase = LevelPhase.ERROR
        }
    }
}

private suspend fun extractLevel(web: WebView): ConnectLevelData? =
    suspendCancellableCoroutine { continuation ->
        web.evaluateJavascript(CONNECT_EXTRACT_SCRIPT) { raw ->
            if (continuation.isActive) continuation.resume(ConnectLevelParser.parse(raw))
        }
    }

@Composable
private fun LevelTopBar(onBack: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(56.dp)
            .padding(horizontal = 4.dp)
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
        }
        Text(stringResource(R.string.user_trust_progress), fontSize = 17.sp, fontWeight = FontWeight.Medium, color = arkdoColors.textPrimary)
    }
}

@Composable
private fun LevelCard(data: ConnectLevelData, connect: ConnectColors) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = PageMargin)
            .clip(RoundedCornerShape(CardRadius))
            .background(connect.card)
            .border(1.dp, connect.border, RoundedCornerShape(CardRadius))
            .padding(CardPadding)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = data.title.ifEmpty { stringResource(R.string.user_trust_reqs) },
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = connect.textPrimary,
                modifier = Modifier.weight(1f)
            )
            if (data.statusLabel.isNotEmpty()) {
                val statusColor = if (data.statusIsMet) connect.success else connect.danger
                val statusBg = if (data.statusIsMet) connect.successBg else connect.dangerBg
                Text(
                    text = data.statusLabel,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = statusColor,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(statusBg)
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                )
            }
        }
        if (data.subtitle.isNotEmpty()) {
            Text(
                text = data.subtitle,
                fontSize = 13.sp,
                color = connect.textTertiary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            )
        }
        if (data.rings.isNotEmpty()) {
            SectionTitle(stringResource(R.string.trust_activity), first = true, connect = connect)
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = SectionBottom)) {
                data.rings.forEach { RingItem(it, connect, Modifier.weight(1f)) }
            }
        }
        if (data.bars.isNotEmpty()) {
            SectionTitle(stringResource(R.string.user_engagement), first = data.rings.isEmpty(), connect = connect)
            data.bars.forEachIndexed { index, bar -> BarItem(bar, spaced = index > 0, connect = connect) }
        }
        if (data.quotas.isNotEmpty() || data.vetos.isNotEmpty()) {
            SectionTitle(stringResource(R.string.user_compliance_records), first = data.rings.isEmpty() && data.bars.isEmpty(), connect = connect)
            data.quotas.forEachIndexed { index, quota -> QuotaCard(quota, spaced = index > 0, connect = connect) }
            data.vetos.forEachIndexed { index, veto ->
                VetoCard(veto, spaced = index > 0 || data.quotas.isNotEmpty(), connect = connect)
            }
        }
        HorizontalDivider(color = connect.border, thickness = 1.dp, modifier = Modifier.padding(top = SectionTop, bottom = 14.dp))
        if (data.hint.isNotEmpty()) {
            Text(data.hint, fontSize = 12.sp, color = connect.textTertiary, modifier = Modifier.fillMaxWidth())
        }
        if (data.conclusion.isNotEmpty()) {
            Text(
                text = data.conclusion,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (data.conclusionIsMet) connect.success else connect.danger,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun SectionTitle(title: String, first: Boolean, connect: ConnectColors) {
    Text(
        text = title,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = connect.textTertiary,
        letterSpacing = 0.5.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (first) SectionBottom else SectionTop, bottom = SectionBottom)
    )
}

@Composable
private fun RingItem(ring: ConnectRingItem, connect: ConnectColors, modifier: Modifier) {
    val color = if (ring.met) connect.success else connect.warning
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(74.dp)) {
            CircularProgressIndicator(
                progress = { ringPercent(ring) / 100f },
                modifier = Modifier.size(74.dp),
                color = color,
                trackColor = connect.track,
                strokeWidth = 6.dp,
                gapSize = 0.dp
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(ring.current, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = color)
                Text(ring.target, fontSize = 10.sp, color = connect.textTertiary)
            }
        }
        Text(ring.label, fontSize = 12.sp, color = connect.textSecondary, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun BarItem(bar: ConnectBarItem, spaced: Boolean, connect: ConnectColors) {
    val color = if (bar.met) connect.success else connect.warning
    Column(modifier = Modifier.fillMaxWidth().padding(top = if (spaced) 14.dp else 0.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(bar.label, fontSize = 13.sp, color = connect.textSecondary, modifier = Modifier.weight(1f))
            Text(bar.nums, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = color)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(connect.track)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(barFraction(bar))
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Brush.horizontalGradient(if (bar.met) BarMet else BarUnmet))
            )
        }
    }
}

@Composable
private fun QuotaCard(quota: ConnectQuotaItem, spaced: Boolean, connect: ConnectColors) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (spaced) 12.dp else 0.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (quota.met) connect.card else connect.dangerBg)
            .border(1.dp, if (quota.met) connect.border else BorderDanger, RoundedCornerShape(10.dp))
            .padding(horizontal = CardPadding, vertical = 12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(quota.label, fontSize = 13.sp, color = connect.textSecondary, modifier = Modifier.weight(1f))
            Text(
                quota.nums,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = if (quota.met) connect.textTertiary else connect.danger
            )
        }
        if (quota.slots > 0) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            ) {
                repeat(quota.slots) { slot ->
                    val used = slot < quota.used
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                (if (used) connect.danger else connect.success).copy(alpha = if (used) 0.8f else 0.25f)
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun VetoCard(veto: ConnectVetoItem, spaced: Boolean, connect: ConnectColors) {
    val color = if (veto.met) connect.success else connect.danger
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (spaced) 12.dp else 0.dp)
            .height(64.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (veto.met) connect.successBg else connect.dangerBg)
            .border(1.dp, if (veto.met) BorderSuccess else BorderDanger, RoundedCornerShape(10.dp))
            .padding(horizontal = CardPadding)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(if (veto.met) IconBgSuccess else IconBgDanger)
        ) {
            FaIcon(if (veto.met) "check" else "xmark", color, 15.dp)
        }
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(veto.label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = connect.textPrimary)
            if (veto.desc.isNotEmpty()) {
                Text(veto.desc, fontSize = 12.sp, color = connect.textSecondary, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Text(veto.value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun PoweredByFooter(connect: ConnectColors) {
    Row(
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp, bottom = 30.dp)
    ) {
        Text("Powered by ", fontSize = 12.sp, color = connect.textTertiary)
        Text("LINUX DO", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = connect.textSecondary)
    }
}

@Composable
private fun LevelLoading() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth().height(280.dp)
    ) {
        CircularProgressIndicator(modifier = Modifier.size(32.dp), color = arkdoColors.ldAccent, strokeWidth = 3.dp)
        Text(stringResource(R.string.user_loading_trust), fontSize = 14.sp, color = arkdoColors.textSecondary, modifier = Modifier.padding(top = 14.dp))
    }
}

@Composable
private fun LevelMessage(
    title: String,
    subtitle: String,
    retryable: Boolean,
    onRetry: () -> Unit,
    onOpenWeb: (String, String) -> Unit
) {
    val colors = arkdoColors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth().height(320.dp)
    ) {
        FaIcon("circle-exclamation", colors.textFaint, 40.dp)
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary, modifier = Modifier.padding(top = 16.dp))
        Text(
            subtitle,
            fontSize = 13.sp,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp, start = 24.dp, end = 24.dp)
        )
        if (retryable) {
            Row(modifier = Modifier.padding(top = 22.dp)) {
                TextButton(
                    onClick = onRetry,
                    modifier = Modifier
                        .height(42.dp)
                        .clip(RoundedCornerShape(21.dp))
                        .background(colors.ldAccent)
                ) {
                    Text(stringResource(R.string.common_retry), fontSize = 15.sp, color = Color.White, modifier = Modifier.padding(horizontal = 16.dp))
                }
                Spacer(modifier = Modifier.width(10.dp))
                TextButton(
                    onClick = { onOpenWeb(AppConstants.CONNECT_URL, AppContextHolder.getString(R.string.user_trust_progress)) },
                    modifier = Modifier
                        .height(42.dp)
                        .clip(RoundedCornerShape(21.dp))
                        .background(colors.surfaceCard)
                ) {
                    Text(stringResource(R.string.common_open_browser), fontSize = 15.sp, color = colors.ldAccent, modifier = Modifier.padding(horizontal = 8.dp))
                }
            }
        }
    }
}

private fun ringPercent(ring: ConnectRingItem): Float {
    if (ring.max > 0) return ((ring.val_ / ring.max) * 100).toFloat().coerceAtMost(100f)
    return if (ring.met) 100f else 0f
}

private fun barFraction(bar: ConnectBarItem): Float {
    val percent = when {
        bar.max > 0 -> ((bar.val_ / bar.max) * 100).coerceAtMost(100.0)
        bar.met -> 100.0
        else -> 0.0
    }
    return ((percent * 10).roundToInt() / 10.0 / 100.0).toFloat()
}
