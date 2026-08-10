package com.arkdo.android.services.challenge

import timber.log.Timber

// ==========================================================================
// 与 HarmonyOS 版 services/challenge/ChallengeRecoveryEventBus.ets 逐字段对齐。
// 协调器状态机的双向通道：协调器→UI 广播状态迁移；UI→协调器「手动验证」请求接管。
// ==========================================================================

enum class ChallengeRecoveryState {
    IDLE,             // 无恢复进行
    RESOLVING,        // 后台静默探测中
    AWAITING_SHEET,   // 已升级到交互弹窗
    COOLDOWN,         // 恢复成功后 30s 冷却
    DISMISSED         // 用户关掉兜底弹窗且未过盾，60s 保持
}

typealias ChallengeRecoveryStateListener = (ChallengeRecoveryState) -> Unit
typealias ManualChallengeListener = () -> Unit

object ChallengeRecoveryEventBus {
    private val stateListeners = mutableMapOf<Long, ChallengeRecoveryStateListener>()
    private var nextStateListenerId = 0L
    private var currentState = ChallengeRecoveryState.IDLE

    private val manualListeners = mutableMapOf<Long, ManualChallengeListener>()
    private var nextManualListenerId = 0L

    fun subscribeState(listener: ChallengeRecoveryStateListener): () -> Unit {
        val id = nextStateListenerId++
        stateListeners[id] = listener
        return { stateListeners.remove(id) }
    }

    fun notifyState(state: ChallengeRecoveryState) {
        currentState = state
        Timber.i("[ArkDO][Challenge] recovery state=%s", state)
        stateListeners.values.toList().forEach { l ->
            runCatching { l(state) }.onFailure {
                Timber.e(it, "[ArkDO][Challenge] state listener threw")
            }
        }
    }

    fun getCurrentState(): ChallengeRecoveryState = currentState

    fun subscribeManual(listener: ManualChallengeListener): () -> Unit {
        val id = nextManualListenerId++
        manualListeners[id] = listener
        return { manualListeners.remove(id) }
    }

    fun requestManual() {
        Timber.i("[ArkDO][Challenge] manual request")
        manualListeners.values.toList().forEach { l ->
            runCatching { l() }.onFailure {
                Timber.e(it, "[ArkDO][Challenge] manual listener threw")
            }
        }
    }
}
