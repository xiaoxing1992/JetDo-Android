package com.jetdo.android.ui.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 出示端弹窗状态机护栏。
 *
 * 核心待钉住的一条：**打开弹窗不得签发 User API Key**。改造前是「弹窗一组合就
 * createSharePayload」，用户只是想看看这个入口是干嘛的，账号上就白落一枚永不自动过期的
 * key。现在签发与否完全由 [QrShowUi.shouldIssueShareKey] 决定，把它锁死在「只有 QR 段」。
 *
 * 第二条：**已消费的 key 不能撤销**。那是对方刚兑换成功、正在用的那把，撤掉等于把人踢下线。
 */
class QrShowStateTest {

    @Test
    fun `initial state is config and would not issue a share key`() {
        val ui = QrShowUi()
        assertEquals(QrShowStage.CONFIG, ui.stage)
        assertFalse(ui.confirmOpen)
        assertFalse("打开弹窗就签发 key 是本次改造要消灭的行为", ui.shouldIssueShareKey())
    }

    @Test
    fun `opening the confirm dialog keeps us in config`() {
        val ui = QrShowUi().reduce(QrShowAction.OpenConfirm)
        assertEquals(QrShowStage.CONFIG, ui.stage)
        assertTrue(ui.confirmOpen)
        assertFalse("点「生成」只是弹确认，此时仍不该签发", ui.shouldIssueShareKey())
    }

    @Test
    fun `cancelling the confirm dialog returns to config without issuing`() {
        val ui = QrShowUi()
            .reduce(QrShowAction.OpenConfirm)
            .reduce(QrShowAction.CancelConfirm)
        assertEquals(QrShowStage.CONFIG, ui.stage)
        assertFalse(ui.confirmOpen)
        assertFalse(ui.shouldIssueShareKey())
    }

    @Test
    fun `confirming moves to qr and only then issues`() {
        val ui = QrShowUi()
            .reduce(QrShowAction.OpenConfirm)
            .reduce(QrShowAction.ConfirmGenerate)
        assertEquals(QrShowStage.QR, ui.stage)
        assertFalse("确认框要关掉，不能盖在二维码上", ui.confirmOpen)
        assertTrue(ui.shouldIssueShareKey())
    }

    @Test
    fun `regenerate bumps attempt so the effect re-runs`() {
        val ui = QrShowUi()
            .reduce(QrShowAction.ConfirmGenerate)
            .reduce(QrShowAction.Regenerate)
        assertEquals(1, ui.attempt)
        assertEquals(QrShowStage.QR, ui.stage)
        assertTrue(ui.shouldIssueShareKey())
    }

    @Test
    fun `regenerate alone cannot escape the config stage`() {
        // Regenerate 只 +attempt，不动 stage。万一将来有人在配置态误接了「重新生成」，
        // 也不至于绕过二次确认直接签发。
        val ui = QrShowUi().reduce(QrShowAction.Regenerate)
        assertEquals(QrShowStage.CONFIG, ui.stage)
        assertFalse(ui.shouldIssueShareKey())
    }

    @Test
    fun `mark consumed flips the flag and nothing else`() {
        val ui = QrShowUi()
            .reduce(QrShowAction.ConfirmGenerate)
            .reduce(QrShowAction.MarkConsumed)
        assertTrue(ui.consumed)
        assertEquals(QrShowStage.QR, ui.stage)
        assertEquals(0, ui.attempt)
    }

    @Test
    fun `config stage has no key to revoke`() {
        assertNull(
            "配置态不该持有 payload；真拿到 key 说明状态泄漏了，这里也不该撤",
            QrShowUi().revocableKey("sk-whatever")
        )
        assertNull(QrShowUi().reduce(QrShowAction.OpenConfirm).revocableKey("sk-whatever"))
    }

    @Test
    fun `qr stage revokes the outstanding key when closing`() {
        val ui = QrShowUi().reduce(QrShowAction.ConfirmGenerate)
        assertEquals("sk-whatever", ui.revocableKey("sk-whatever"))
    }

    @Test
    fun `consumed key is never revoked`() {
        val ui = QrShowUi()
            .reduce(QrShowAction.ConfirmGenerate)
            .reduce(QrShowAction.MarkConsumed)
        assertNull(
            "对方刚用这把 key 登录成功，撤掉等于把人踢下线",
            ui.revocableKey("sk-whatever")
        )
    }

    @Test
    fun `qr stage with no payload yields nothing to revoke`() {
        val ui = QrShowUi().reduce(QrShowAction.ConfirmGenerate)
        assertNull(ui.revocableKey(null))
    }
}
