package com.arkdo.android.services.challenge

import com.arkdo.android.services.network.ChallengeState
import timber.log.Timber

// ==========================================================================
// 与 HarmonyOS 版 services/challenge/ChallengeEventBus.ets 逐字段对齐。
// 「撞盾信号」总线：bridge 判定 challengeState!==NONE 时广播，RootTabPage 订阅后
// 交给 ChallengeRecoveryCoordinator 处理。带单调版本号去重。
// ==========================================================================

data class ChallengeRequiredEvent(
    val version: Long,
    val source: String,
    val statusCode: Int,
    val challengeState: ChallengeState
)

typealias ChallengeRequiredListener = (ChallengeRequiredEvent) -> Unit

object ChallengeEventBus {
    private val listeners = mutableMapOf<Long, ChallengeRequiredListener>()
    private var nextListenerId = 0L
    private var currentVersion = 0L

    /** 订阅，返回退订闭包。 */
    fun subscribe(listener: ChallengeRequiredListener): () -> Unit {
        val id = nextListenerId++
        listeners[id] = listener
        return { listeners.remove(id) }
    }

    /** 广播，返回新 version。单个 listener 异常不影响其它。 */
    fun notifyChallengeRequired(source: String, statusCode: Int, challengeState: ChallengeState): Long {
        currentVersion++
        val event = ChallengeRequiredEvent(currentVersion, source, statusCode, challengeState)
        Timber.i("[JetDo][Challenge] notify source=%s status=%d state=%s version=%d",
            source, statusCode, challengeState, currentVersion)
        listeners.values.toList().forEach { l ->
            runCatching { l(event) }.onFailure {
                Timber.e(it, "[JetDo][Challenge] listener threw")
            }
        }
        return currentVersion
    }

    fun getCurrentVersion(): Long = currentVersion
}
