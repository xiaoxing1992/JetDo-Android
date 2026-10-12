package com.jetdo.android.ui.qr

import com.jetdo.android.R
import com.jetdo.android.services.auth.QrLoginPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扫入端状态机 + 二维码内容分类的护栏。
 *
 * 两条待钉住的约束：
 * 1. **命中即进处理态，处理完必须回到可重扫态。** 用户报的就是「扫到码之后页面毫无反应」——
 *    原因是命中只停了相机解码，界面上的反馈要等整条兑换链路跑完才出现。反过来说，
 *    失败后如果不把处理态撤掉、把解码闸门放开，用户就只能退出页面重进。
 * 2. **无关的二维码必须静默忽略，本 App 但不认识的码必须明确报错。** 两边都是「准确提示」：
 *    前者防止扫到别人家的码弹一句莫名其妙的错误，后者防止真出问题时又变成「扫了没反应」。
 */
class QrScanStateTest {

    // ---------- 状态机 ----------

    @Test
    fun `initial state is scanning with no overlay`() {
        val ui = QrScanUi()
        assertEquals(QrScanPhase.SCANNING, ui.phase)
        assertNull(ui.statusRes)
        assertFalse("还没扫到任何东西就不该盖遮罩", ui.showOverlay())
    }

    @Test
    fun `detected enters handling and shows the signing-in overlay`() {
        val ui = QrScanUi().reduce(QrScanAction.Detected)
        assertEquals(QrScanPhase.HANDLING, ui.phase)
        assertEquals(R.string.auth_qr_logging_in, ui.statusRes)
        assertTrue("扫到之后必须立刻有可见反馈", ui.showOverlay())
    }

    @Test
    fun `repeated detections are idempotent`() {
        // 相机线程 150ms 一帧，同一张码会在闸门生效前的窗口里被反复解出。
        val first = QrScanUi().reduce(QrScanAction.Detected)
        val second = first.reduce(QrScanAction.Detected)
        assertEquals(first, second)
    }

    @Test
    fun `failure returns to a rescanable state and clears the overlay`() {
        val ui = QrScanUi()
            .reduce(QrScanAction.Detected)
            .reduce(QrScanAction.Failed)
        assertEquals(QrScanPhase.SCANNING, ui.phase)
        assertNull("失败后遮罩必须撤掉", ui.statusRes)
        assertFalse(ui.showOverlay())
    }

    @Test
    fun `a failed attempt can be retried`() {
        val ui = QrScanUi()
            .reduce(QrScanAction.Detected)
            .reduce(QrScanAction.Failed)
            .reduce(QrScanAction.Detected)
        assertEquals(QrScanPhase.HANDLING, ui.phase)
        assertEquals(R.string.auth_qr_logging_in, ui.statusRes)
    }

    @Test
    fun `finishing clears the overlay too`() {
        val ui = QrScanUi()
            .reduce(QrScanAction.Detected)
            .reduce(QrScanAction.Finished)
        assertEquals(QrScanPhase.SCANNING, ui.phase)
        assertFalse(ui.showOverlay())
    }

    // ---------- 内容分类 ----------

    @Test
    fun `a real login payload is supported`() {
        val raw = QrLoginPayload.encode(
            QrLoginPayload(apiKey = "key-abc", otp = "deadbeef", username = "petter")
        )
        assertEquals(ScannedQr.SUPPORTED, classifyScannedQr(raw))
        assertTrue(classifyScannedQr(raw).shouldHandle())
    }

    @Test
    fun `a payload with an unknown version is unsupported but not ignored`() {
        // 形状够格说「这是我们家的码」，只是本端不认识 —— 必须报错，不能静默吞掉，
        // 否则用户看到的就是「扫了没反应」，正是这次要修的那类现象。
        val raw = """{"v":99,"k":"key-abc","o":"deadbeef","u":"petter"}"""
        assertEquals(ScannedQr.UNSUPPORTED, classifyScannedQr(raw))
        assertTrue(classifyScannedQr(raw).shouldHandle())
    }

    @Test
    fun `json without both payload fields is not ours`() {
        assertEquals(ScannedQr.NOT_OURS, classifyScannedQr("""{"v":1,"k":"key-abc"}"""))
        assertEquals(ScannedQr.NOT_OURS, classifyScannedQr("""{"v":1,"o":"deadbeef"}"""))
        assertEquals(ScannedQr.NOT_OURS, classifyScannedQr("""{"hello":"world"}"""))
    }

    @Test
    fun `foreign qr codes are silently ignored`() {
        // 这些都不该进登录流程：扫到它们要的是「继续扫」，不是弹一句错误。
        val foreign = listOf(
            "",
            "   ",
            "https://linux.do/t/topic/123",
            "WIFI:S:MyNet;T:WPA;P:secret;;",
            "BEGIN:VCARD\nVERSION:3.0\nFN:Someone\nEND:VCARD"
        )
        foreign.forEach { raw ->
            assertEquals("不该当成登录码: $raw", ScannedQr.NOT_OURS, classifyScannedQr(raw))
            assertFalse("不该进处理流程: $raw", classifyScannedQr(raw).shouldHandle())
        }
    }

    @Test
    fun `only not-ours is filtered out`() {
        assertFalse(ScannedQr.NOT_OURS.shouldHandle())
        assertTrue(ScannedQr.SUPPORTED.shouldHandle())
        assertTrue(ScannedQr.UNSUPPORTED.shouldHandle())
    }
}
