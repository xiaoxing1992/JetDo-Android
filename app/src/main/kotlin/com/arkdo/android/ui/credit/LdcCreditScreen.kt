package com.arkdo.android.ui.credit

import androidx.compose.ui.res.stringResource
import com.arkdo.android.core.data.AppContextHolder
import com.arkdo.android.R
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkdo.android.common.constants.AppConstants
import com.arkdo.android.core.designsystem.FaIcon
import com.arkdo.android.data.models.LdcDailyStat
import com.arkdo.android.data.models.LdcUserInfo
import com.arkdo.android.services.credit.LdcCreditEventBus
import com.arkdo.android.services.credit.LdcCreditParser
import com.arkdo.android.services.credit.LdcCreditSession
import com.arkdo.android.services.credit.fetchCredit
import com.arkdo.android.services.credit.formatLdcAmount
import com.arkdo.android.services.credit.ldcAmountValue
import com.arkdo.android.services.credit.ldcDayLabel
import com.arkdo.android.services.network.ArkWebJsonExecutor
import com.arkdo.android.services.web.WebSessionManager
import com.arkdo.android.ui.theme.arkdoColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// 对齐鸿蒙 LdcCreditPage。最近 7 天,登录靠轮询 linux_do_credit_session_id。
private val PageMargin = 12.dp
private val CardRadius = 16.dp
private val CardPadding = 16.dp
private val CardGap = 12.dp
private const val RECENT_DAYS = 7
private const val LOGIN_POLL_MS = 1_000L
private const val MAX_LOGIN_POLLS = 180
private const val BAR_MIN_PERCENT = 4.0
private val LdcIncome = Color(0xFF00C950)

private enum class LdcPhase { LOADING, READY, ANON, ERROR, LOGGING_IN }

/**
 * LINUX DO Credit 页。credit.linux.do 与 linux.do 不同源,取数走本页自己的同源 WebView。
 */
@Composable
fun LdcCreditScreen(
    startLogin: Boolean,
    onNavigateBack: () -> Unit
) {
    val colors = arkdoColors
    var phase by remember {
        mutableStateOf(
            when {
                startLogin -> LdcPhase.LOGGING_IN
                LdcCreditSession.hasSession() -> LdcPhase.LOADING
                else -> LdcPhase.ANON
            }
        )
    }
    var info by remember { mutableStateOf(LdcCreditSession.getCachedInfo()) }
    var daily by remember { mutableStateOf<List<LdcDailyStat>>(emptyList()) }
    var errorMessage by remember { mutableStateOf("") }
    var hostActive by remember { mutableStateOf(phase == LdcPhase.LOADING || phase == LdcPhase.LOGGING_IN) }
    var navTarget by remember { mutableStateOf("") }
    var navSerial by remember { mutableIntStateOf(0) }
    val initialUrl = remember {
        if (startLogin) AppConstants.CREDIT_HOME_URL else AppConstants.CREDIT_BOOTSTRAP_URL
    }
    var executor by remember { mutableStateOf<ArkWebJsonExecutor?>(null) }
    var hostReady by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun loadAll(exec: ArkWebJsonExecutor) {
        scope.launch {
            val user = fetchCredit(exec, "/api/v1/oauth/user-info")
            when {
                user.status == 401 -> {
                    LdcCreditSession.clearCache()
                    LdcCreditEventBus.notifyCreditChanged()
                    info = null
                    phase = LdcPhase.ANON
                }
                user.body != null -> {
                    val parsed = LdcCreditParser.parseUserInfo(user.body)
                    if (parsed != null) {
                        info = parsed
                        phase = LdcPhase.READY
                        LdcCreditSession.setCachedInfo(parsed)
                        LdcCreditEventBus.notifyCreditChanged()
                    } else {
                        errorMessage = AppContextHolder.getString(R.string.err_ldc_parse)
                        phase = LdcPhase.ERROR
                    }
                }
                else -> {
                    errorMessage = user.message.ifEmpty { AppContextHolder.getString(R.string.credit_connect_failed) }
                    phase = LdcPhase.ERROR
                }
            }
            val stats = fetchCredit(exec, "/api/v1/dashboard/stats/daily?days=$RECENT_DAYS")
            LdcCreditParser.parseDailyStats(stats.body)?.let { daily = sortNewestFirst(it) }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.pageBgGray)
    ) {
        if (hostActive) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (phase == LdcPhase.LOGGING_IN) {
                    Spacer(modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars).height(56.dp))
                }
                LdcCreditWebHost(
                    initialUrl = initialUrl,
                    navTarget = if (navSerial == 0) "" else navTarget,
                    interactive = phase == LdcPhase.LOGGING_IN,
                    onReady = {
                        hostReady = true
                        if (phase != LdcPhase.LOGGING_IN) executor?.let { loadAll(it) }
                    },
                    onLoadFailed = {
                        if (phase != LdcPhase.LOGGING_IN && phase != LdcPhase.READY) {
                            errorMessage = AppContextHolder.getString(R.string.credit_connect_failed)
                            phase = LdcPhase.ERROR
                        }
                    },
                    onPageSettled = {
                        if (phase == LdcPhase.LOGGING_IN && LdcCreditSession.hasSession()) {
                            scope.launch { WebSessionManager.saveCookies("LdcCredit") }
                            phase = LdcPhase.LOADING
                            executor?.let { loadAll(it) }
                        }
                    },
                    onExecutor = { executor = it },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (phase == LdcPhase.LOGGING_IN) {
            LoginTopBar(onCancel = {
                if (LdcCreditSession.hasSession()) {
                    phase = LdcPhase.LOADING
                    executor?.let { loadAll(it) }
                } else {
                    phase = LdcPhase.ANON
                }
            })
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                CreditTopBar(onNavigateBack)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = PageMargin)
                ) {
                    Spacer(modifier = Modifier.height(8.dp))
                    val ready = info
                    when {
                        phase == LdcPhase.READY && ready != null -> {
                            BalanceCard(ready)
                            DailyCard(daily)
                            CreditFooter()
                        }
                        phase == LdcPhase.ANON -> CreditMessage(
                            stringResource(R.string.credit_not_logged_in),
                            stringResource(R.string.credit_login_to_view_full),
                            action = stringResource(R.string.login_go),
                            onAction = {
                                phase = LdcPhase.LOGGING_IN
                                hostActive = true
                                navTarget = AppConstants.CREDIT_HOME_URL
                                navSerial += 1
                            }
                        )
                        phase == LdcPhase.ERROR -> CreditMessage(
                            stringResource(R.string.doh_unknown_state),
                            errorMessage.ifEmpty { stringResource(R.string.challenge_maybe_captcha) },
                            action = stringResource(R.string.common_retry),
                            onAction = {
                                errorMessage = ""
                                phase = LdcPhase.LOADING
                                hostActive = true
                                if (hostReady) executor?.let { loadAll(it) }
                            }
                        )
                        phase == LdcPhase.LOADING -> CreditLoading()
                    }
                    Spacer(modifier = Modifier.height(40.dp))
                }
            }
        }
    }

    if (phase == LdcPhase.LOGGING_IN) {
        LaunchedEffect(navSerial) {
            repeat(MAX_LOGIN_POLLS) {
                delay(LOGIN_POLL_MS)
                if (phase != LdcPhase.LOGGING_IN) return@LaunchedEffect
                if (LdcCreditSession.hasSession()) {
                    scope.launch { WebSessionManager.saveCookies("LdcCredit") }
                    phase = LdcPhase.LOADING
                    executor?.let { loadAll(it) }
                    return@LaunchedEffect
                }
            }
        }
    }
}

private fun sortNewestFirst(stats: List<LdcDailyStat>): List<LdcDailyStat> =
    stats.sortedByDescending { it.date }

@Composable
private fun CreditTopBar(onBack: () -> Unit) {
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
        Text("LINUX DO Credit", fontSize = 17.sp, fontWeight = FontWeight.Medium, color = arkdoColors.textPrimary)
    }
}

@Composable
private fun LoginTopBar(onCancel: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(56.dp)
            .background(arkdoColors.pageBg)
            .padding(horizontal = 16.dp)
    ) {
        Text(stringResource(R.string.credit_login), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = arkdoColors.textPrimary, modifier = Modifier.weight(1f))
        TextButton(onClick = onCancel) {
            Text(stringResource(R.string.common_cancel), fontSize = 15.sp, color = arkdoColors.ldAccent)
        }
    }
}

@Composable
private fun BalanceCard(info: LdcUserInfo) {
    val colors = arkdoColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = CardGap)
            .clip(RoundedCornerShape(CardRadius))
            .background(colors.surfaceCard)
            .padding(CardPadding)
    ) {
        Text(stringResource(R.string.credit_available), fontSize = 13.sp, color = colors.textSecondary)
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(formatLdcAmount(info.availableBalance), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary)
            Text("LDC", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = colors.textFaint, modifier = Modifier.padding(start = 6.dp, bottom = 4.dp))
        }
        HorizontalDivider(color = colors.border, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 14.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            MetricCell(stringResource(R.string.credit_balance_title), formatLdcAmount(info.communityBalance), Modifier.weight(1f))
            MetricCell(stringResource(R.string.credit_pending), formatLdcAmount(info.pendingBalance), Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
            MetricCell(stringResource(R.string.credit_remaining), formatLdcAmount(info.remainQuota), Modifier.weight(1f))
            MetricCell(stringResource(R.string.credit_daily_limit), formatLdcAmount(info.dailyLimit), Modifier.weight(1f))
        }
    }
}

@Composable
private fun MetricCell(label: String, value: String, modifier: Modifier) {
    Column(modifier = modifier) {
        Text(value, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = arkdoColors.textPrimary)
        Text(label, fontSize = 12.sp, color = arkdoColors.textFaint, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun DailyCard(stats: List<LdcDailyStat>) {
    val colors = arkdoColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = CardGap)
            .clip(RoundedCornerShape(CardRadius))
            .background(colors.surfaceCard)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = CardPadding, end = CardPadding, top = 14.dp, bottom = 6.dp)
        ) {
            Text(stringResource(R.string.filter_recent_days, RECENT_DAYS), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.credit_total_income, formatLdcAmount(totalIncome(stats))), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = LdcIncome)
        }
        if (stats.isEmpty()) {
            Text(
                stringResource(R.string.credit_no_records),
                fontSize = 13.sp,
                color = colors.textFaint,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = CardPadding, end = CardPadding, top = 10.dp, bottom = CardPadding)
            )
        } else {
            stats.forEach { DailyRow(it, stats) }
        }
    }
}

@Composable
private fun DailyRow(stat: LdcDailyStat, all: List<LdcDailyStat>) {
    val colors = arkdoColors
    val income = ldcAmountValue(stat.income)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = CardPadding, vertical = 11.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(ldcDayLabel(stat.date), fontSize = 14.sp, color = colors.textPrimary, modifier = Modifier.weight(1f))
            if (ldcAmountValue(stat.expense) > 0) {
                Text(
                    "-${formatLdcAmount(stat.expense)}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.ldDanger,
                    modifier = Modifier.padding(end = 10.dp)
                )
            }
            Text(
                if (income > 0) "+${formatLdcAmount(stat.income)}" else "—",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (income > 0) LdcIncome else colors.textFaint
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .height(5.dp)
                .clip(RoundedCornerShape(2.5.dp))
                .background(colors.surfaceMuted)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(incomeBarFraction(stat, all))
                    .height(5.dp)
                    .clip(RoundedCornerShape(2.5.dp))
                    .background(LdcIncome)
            )
        }
    }
}

@Composable
private fun CreditFooter() {
    Row(
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp, bottom = 10.dp)
    ) {
        Text(stringResource(R.string.about_data_from), fontSize = 12.sp, color = arkdoColors.textFaint)
        Text("credit.linux.do", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = arkdoColors.textSecondary)
    }
}

@Composable
private fun CreditLoading() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth().height(280.dp)
    ) {
        CircularProgressIndicator(modifier = Modifier.size(32.dp), color = arkdoColors.ldAccent, strokeWidth = 3.dp)
        Text(stringResource(R.string.credit_loading), fontSize = 14.sp, color = arkdoColors.textSecondary, modifier = Modifier.padding(top = 14.dp))
    }
}

@Composable
private fun CreditMessage(title: String, subtitle: String, action: String, onAction: () -> Unit) {
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
        TextButton(
            onClick = onAction,
            modifier = Modifier
                .padding(top = 22.dp)
                .height(42.dp)
                .clip(RoundedCornerShape(21.dp))
                .background(colors.ldAccent)
        ) {
            Text(action, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White, modifier = Modifier.padding(horizontal = 28.dp))
        }
    }
}

private fun totalIncome(stats: List<LdcDailyStat>): String {
    val total = stats.sumOf { ldcAmountValue(it.income) }
    return ((total * 100).roundToInt() / 100.0).toString()
}

private fun incomeBarFraction(stat: LdcDailyStat, all: List<LdcDailyStat>): Float {
    val value = ldcAmountValue(stat.income)
    if (value <= 0) return 0f
    val max = all.maxOf { ldcAmountValue(it.income) }
    if (max <= 0) return 0f
    val percent = (value / max) * 100
    val shown = if (percent < BAR_MIN_PERCENT) BAR_MIN_PERCENT else (percent * 10).roundToInt() / 10.0
    return (shown / 100.0).toFloat()
}
