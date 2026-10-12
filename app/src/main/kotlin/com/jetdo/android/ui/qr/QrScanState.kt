package com.jetdo.android.ui.qr

import androidx.annotation.StringRes
import com.jetdo.android.R
import com.jetdo.android.services.auth.QrLoginPayload
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * 扫入端页面的纯状态机（对齐 FluxDO `QrLoginScanPage` 的 `_handling` / `_statusMessage`）。
 *
 * 为什么要有这个文件：用户报的第一条是「扫到码之后页面什么提示都没有」——
 * 命中二维码时扫入端会立刻停掉解码（`handled`），而**相机预览还活着**，
 * 界面上看不出任何变化；真正的反馈（Toast）要等 OTP 兑换 + 会话复查整条网络链路跑完
 * 才出现，窗口最高 45s。于是用户看到的是「码扫到了，但 App 像没反应」。
 *
 * 这里的约束是「命中即进处理态、处理完必须回到可重扫态」，肉眼看 Compose 很容易被下次
 * 改动掀掉，所以抽成纯函数用单测钉住（见 QrScanStateTest）。
 */
internal enum class QrScanPhase {
    /** 正在等码。 */
    SCANNING,

    /** 已命中，正在兑换登录。此期间不再接受新的命中。 */
    HANDLING
}

/**
 * @param statusRes 遮罩上要显示的状态文案；null = 只有转圈没有文字（对齐 FluxDO 的 `_statusMessage != null` 分支）。
 */
internal data class QrScanUi(
    val phase: QrScanPhase = QrScanPhase.SCANNING,
    @StringRes val statusRes: Int? = null
)

internal sealed interface QrScanAction {
    /** 解出一枚本 App 的登录码。 */
    data object Detected : QrScanAction

    /** 处理结束（成功后会离开页面）。 */
    data object Finished : QrScanAction

    /** 处理失败：必须回到可重扫态，否则用户只能退出页面重进。 */
    data object Failed : QrScanAction
}

internal fun QrScanUi.reduce(action: QrScanAction): QrScanUi = when (action) {
    // 幂等：相机线程每 150ms 一帧，屏蔽闸门在分析器的 AtomicBoolean 上，这里是第二道。
    QrScanAction.Detected ->
        if (phase == QrScanPhase.HANDLING) this
        else copy(phase = QrScanPhase.HANDLING, statusRes = R.string.auth_qr_logging_in)

    QrScanAction.Finished -> copy(phase = QrScanPhase.SCANNING, statusRes = null)
    QrScanAction.Failed -> copy(phase = QrScanPhase.SCANNING, statusRes = null)
}

/** 是否该画处理中遮罩。 */
internal fun QrScanUi.showOverlay(): Boolean =
    phase == QrScanPhase.HANDLING || statusRes != null

/**
 * 扫到的内容是什么。
 *
 * 三种必须分开，混成一种都出过事故：
 * - 混成「一律当登录码」→ 扫到一张无关的码就弹「二维码不是 JetDo 登录码」（用户要的
 *   其实是「继续扫」）；
 * - 混成「一律静默」→ 版本不认识的本 App 码被无声吞掉，用户看到的就是「扫了没反应」，
 *   正是这次要修的同一类现象。
 */
internal enum class ScannedQr {
    /** 与本 App 无关（WiFi 码、海报码、别的 App 的码）。静默忽略，继续扫。 */
    NOT_OURS,

    /** 本 App 的登录码，本端认识。 */
    SUPPORTED,

    /** 形状是本 App 的登录码（带 k / o 字段），但版本或字段是本端不认识的。要明确报错。 */
    UNSUPPORTED
}

/** 不需要用户知道、也不该进登录流程的码。 */
internal fun ScannedQr.shouldHandle(): Boolean = this != ScannedQr.NOT_OURS

/**
 * 分类扫到的原始字符串。
 *
 * [ScannedQr.UNSUPPORTED] 的判据不是「像 JSON」而是「确实带 k 与 o 两个字段」——
 * 随便一段 JSON 都可能带 `o`，只有 k+o 同时出现才够格说「这是我们家的码」。
 */
internal fun classifyScannedQr(raw: String): ScannedQr {
    if (QrLoginPayload.parse(raw) != null) return ScannedQr.SUPPORTED
    val obj = runCatching {
        Json.parseToJsonElement(raw.trim()).jsonObject
    }.getOrNull() ?: return ScannedQr.NOT_OURS
    return if (obj.containsKey("k") && obj.containsKey("o")) {
        ScannedQr.UNSUPPORTED
    } else {
        ScannedQr.NOT_OURS
    }
}
