package com.arkdo.android.data.models

import kotlinx.serialization.Serializable

// ==========================================================================
// 与 HarmonyOS 版 models/ConnectLevel.ets 逐字段对齐。
// 来源：connect.linux.do「信任级别要求」页 DOM 解析（服务端渲染）。
// ==========================================================================

/** 活跃程度环形项：current/target 为展示文本，val/max 驱动环形填充。 */
@Serializable
data class ConnectRingItem(
    val label: String,
    val current: String,
    val target: String,
    val val_: Double,
    val max: Double,
    val met: Boolean
)

/** 互动参与进度条项：nums 为展示文本，val/max 驱动填充宽度。 */
@Serializable
data class ConnectBarItem(
    val label: String,
    val nums: String,
    val val_: Double,
    val max: Double,
    val met: Boolean
)

/** 合规配额卡：slots 为总格数，used 为已占用（违规）格数。 */
@Serializable
data class ConnectQuotaItem(
    val label: String,
    val nums: String,
    val slots: Int,
    val used: Int,
    val met: Boolean
)

/** 否决项：value 为次数文本，met=true 达标（0 次）。 */
@Serializable
data class ConnectVetoItem(
    val label: String,
    val desc: String,
    val value: String,
    val met: Boolean
)

@Serializable
data class ConnectLevelData(
    val title: String,
    val statusLabel: String,
    val statusIsMet: Boolean,
    val subtitle: String,
    val rings: List<ConnectRingItem>,
    val bars: List<ConnectBarItem>,
    val quotas: List<ConnectQuotaItem>,
    val vetos: List<ConnectVetoItem>,
    val hint: String,
    val conclusion: String,
    val conclusionIsMet: Boolean
)
