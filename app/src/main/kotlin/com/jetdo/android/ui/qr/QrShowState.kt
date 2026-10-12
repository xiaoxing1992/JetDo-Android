package com.jetdo.android.ui.qr

/**
 * 出示端弹窗的纯状态机（对齐 FluxDO `QrLoginSheet` 的 CONFIG → 确认 → QR 两段式）。
 *
 * 为什么抽出来：这次改造唯一的硬约束是「**打开弹窗不得签发 User API Key**」——
 * 以前是 LaunchedEffect 一跑就 createSharePayload，用户只想看看这个入口就白落一枚 key。
 * 约束的正确性靠肉眼看 Compose 代码很容易被下一次改动推翻，所以把「什么状态才允许签发」
 * 落成一个纯函数，用单测钉住（见 QrShowStateTest）。
 */
internal enum class QrShowStage {
    /** 只显示安全说明 + 「生成登录二维码」按钮，不持有任何 payload。 */
    CONFIG,

    /** 已确认，正在/已经持有 payload。 */
    QR
}

/**
 * 弹窗的 UI 状态。
 *
 * @param attempt 递增计数，作为「重新签发」的触发键（Compose 侧喂给 LaunchedEffect）。
 */
internal data class QrShowUi(
    val stage: QrShowStage = QrShowStage.CONFIG,
    val confirmOpen: Boolean = false,
    val attempt: Int = 0,
    val consumed: Boolean = false
)

internal sealed interface QrShowAction {
    /** 点「生成登录二维码」：只弹二次确认，**不改 stage**。 */
    data object OpenConfirm : QrShowAction

    /** 二次确认里点「取消」：退回配置态。 */
    data object CancelConfirm : QrShowAction

    /** 二次确认里点「确认生成」：进 QR 段。 */
    data object ConfirmGenerate : QrShowAction

    /** 点「重新生成」/失败后点「重试」：留在 QR 段，换一轮。 */
    data object Regenerate : QrShowAction

    /** 轮询发现分享 key 消失 = 对方已登录成功。 */
    data object MarkConsumed : QrShowAction
}

internal fun QrShowUi.reduce(action: QrShowAction): QrShowUi = when (action) {
    QrShowAction.OpenConfirm -> copy(confirmOpen = true)
    QrShowAction.CancelConfirm -> copy(confirmOpen = false)
    QrShowAction.ConfirmGenerate -> copy(confirmOpen = false, stage = QrShowStage.QR)
    QrShowAction.Regenerate -> copy(attempt = attempt + 1)
    QrShowAction.MarkConsumed -> copy(consumed = true)
}

/**
 * 本轮是否该真的去签发 key。只有进到 QR 段才为真 —— 这是「打开弹窗不签发」的唯一实现点。
 */
internal fun QrShowUi.shouldIssueShareKey(): Boolean = stage == QrShowStage.QR

/**
 * 关闭/重新生成时该撤销哪把 key；null 表示没有需要回收的。
 *
 * 两条都不能松：CONFIG 段 payload 本来就是 null（没什么可撤），**已消费的 key 不能撤**
 * —— 那是对方刚兑换成功、正在用的那把，撤掉等于把人踢下线。
 */
internal fun QrShowUi.revocableKey(apiKey: String?): String? =
    if (stage == QrShowStage.QR && !consumed) apiKey else null
