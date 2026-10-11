package com.arkdo.android.services.challenge

import com.arkdo.android.services.network.ArkWebNetworkBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

// ==========================================================================
// 与 HarmonyOS 版 services/challenge/ChallengeRecoveryCoordinator.ets 逐字段对齐。
//
// 收到挑战信号后，优先在后台重载 host webview 让 CF 挑战 JS 静默跑通拿 cf_clearance，
// 用接口探测判定放行；两次重载仍不通才升级到交互弹窗。状态机吞掉恢复中/冷却中的重复信号。
//
// 常量：PROBE_INTERVAL_MS=2000、RELOAD_RETRY_AT_POLL=3、MAX_POLL=6、
//       COOLDOWN_MS=30000、DISMISS_HOLDOFF_MS=60000
// ==========================================================================

class ChallengeRecoveryCoordinator(
    private val onRecovered: () -> Unit,
    private val onEscalateToSheet: () -> Unit,
    private val onWindowBegin: (() -> Unit)? = null,
    private val onWindowEnd: (() -> Unit)? = null
) {
    companion object {
        private const val PROBE_INTERVAL_MS = 2_000L
        private const val RELOAD_RETRY_AT_POLL = 3
        private const val MAX_POLL = 6
        private const val COOLDOWN_MS = 30_000L
        private const val DISMISS_HOLDOFF_MS = 60_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var state = ChallengeRecoveryState.IDLE
    private val probe = ChallengeResolutionProbe(ArkWebNetworkBridge)
    private var windowActive = false
    private var pollJob: Job? = null
    private var cooldownJob: Job? = null
    private var pollCount = 0
    private var probing = false

    fun getState(): ChallengeRecoveryState = state

    private fun setState(s: ChallengeRecoveryState) {
        state = s
        ChallengeRecoveryEventBus.notifyState(s)
    }

    /** 收到挑战信号：仅 IDLE 开始一轮恢复；其余状态一律吞掉。 */
    fun handleChallengeSignal(source: String) {
        if (state != ChallengeRecoveryState.IDLE) {
            Timber.i("[JetDo][Challenge] handleChallengeSignal ignored state=%s source=%s", state, source)
            return
        }
        startRecovery(source)
    }

    private fun startRecovery(source: String) {
        Timber.i("[JetDo][Challenge] startRecovery source=%s", source)
        setState(ChallengeRecoveryState.RESOLVING)
        pollCount = 0
        beginWindow()
        ArkWebNetworkBridge.reloadHost()
        startPolling()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                delay(PROBE_INTERVAL_MS)
                poll()
            }
        }
    }

    /** 每 2s 探测一次。注意 await 期间状态可能被 sheet/dismiss 改掉，返回后重新校验 RESOLVING。 */
    private suspend fun poll() {
        if (probing || state != ChallengeRecoveryState.RESOLVING) return
        probing = true
        try {
            val resolved = probe.canAccessLatestJson()
            if (state != ChallengeRecoveryState.RESOLVING) {
                return
            }
            if (resolved) {
                resolvedSilently()
                return
            }
            pollCount++
            Timber.i("[JetDo][Challenge] probe pollCount=%d not resolved", pollCount)
            if (pollCount == RELOAD_RETRY_AT_POLL) {
                Timber.i("[JetDo][Challenge] reloadHost attempt2")
                ArkWebNetworkBridge.reloadHost()
            } else if (pollCount >= MAX_POLL) {
                escalate()
            }
        } finally {
            probing = false
        }
    }

    private fun resolvedSilently() {
        Timber.i("[JetDo][Challenge] resolved silently")
        pollJob?.cancel()
        pollJob = null
        onRecovered()
        enterCooldown()
    }

    /** 升级到交互弹窗。 */
    private fun escalate() {
        Timber.i("[JetDo][Challenge] escalate")
        pollJob?.cancel()
        pollJob = null
        cooldownJob?.cancel()
        cooldownJob = null
        beginWindow()  // 幂等：从 DISMISSED/IDLE 纯手动升级时补开
        setState(ChallengeRecoveryState.AWAITING_SHEET)
        onEscalateToSheet()
    }

    private fun beginWindow() {
        if (!windowActive) {
            windowActive = true
            onWindowBegin?.invoke()
        }
    }

    private fun endWindow() {
        if (windowActive) {
            windowActive = false
            onWindowEnd?.invoke()
        }
    }

    private fun enterCooldown() {
        endWindow()
        setState(ChallengeRecoveryState.COOLDOWN)
        cooldownJob?.cancel()
        cooldownJob = scope.launch {
            delay(COOLDOWN_MS)
            setState(ChallengeRecoveryState.IDLE)
        }
    }

    /** 弹窗验证成功：onRecovered + enterCooldown。 */
    fun notifySheetResolved() {
        if (state != ChallengeRecoveryState.AWAITING_SHEET) return
        Timber.i("[JetDo][Challenge] sheet resolved")
        onRecovered()
        enterCooldown()
    }

    /**
     * 用户关弹窗未解决：AWAITING_SHEET→DISMISSED，60s 后回 IDLE。
     * 回 IDLE 会让紧随的 429 立刻又触发整轮后台静默探测，故进 DISMISSED 吞掉自动信号、停在终态手动入口。
     */
    fun notifySheetDismissed() {
        if (state != ChallengeRecoveryState.AWAITING_SHEET) return
        Timber.i("[JetDo][Challenge] sheet dismissed")
        endWindow()
        pollJob?.cancel()
        pollJob = null
        cooldownJob?.cancel()
        cooldownJob = null
        setState(ChallengeRecoveryState.DISMISSED)
        cooldownJob = scope.launch {
            delay(DISMISS_HOLDOFF_MS)
            setState(ChallengeRecoveryState.IDLE)
        }
    }

    /** 首页手动验证：停后台轮询直接升级弹窗。COOLDOWN/AWAITING_SHEET 拒绝。 */
    fun forceEscalate(): Boolean {
        if (state == ChallengeRecoveryState.COOLDOWN || state == ChallengeRecoveryState.AWAITING_SHEET) {
            Timber.i("[JetDo][Challenge] forceEscalate rejected state=%s", state)
            return false
        }
        escalate()
        return true
    }

    /** 放弃/销毁也算窗口结束，防窗口泄漏。 */
    fun dispose() {
        Timber.i("[JetDo][Challenge] dispose")
        pollJob?.cancel()
        pollJob = null
        cooldownJob?.cancel()
        cooldownJob = null
        endWindow()
        setState(ChallengeRecoveryState.IDLE)
        scope.coroutineContext[Job]?.cancel()
    }
}
