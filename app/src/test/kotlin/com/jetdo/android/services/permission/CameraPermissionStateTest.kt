package com.jetdo.android.services.permission

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CameraPermission 三态判定的纯函数单测。
 *
 * 这四条分支对应的设备状态（从未请求 / 拒绝一次 / 拒绝两次不再询问 / 已授权）
 * 在真机上凑齐既慢又难复现，而它们恰恰是最容易写错的地方 ——
 * 尤其「从未请求」与「永久拒绝」在 shouldShowRequestPermissionRationale 上都返回 false，
 * 只能靠 hasRequested 标记区分。纯函数让这四条路径在 JVM 上一次跑完。
 */
class CameraPermissionStateTest {

    @Test
    fun `已授权时为 GRANTED`() {
        assertEquals(
            CameraPermissionState.GRANTED,
            resolveCameraPermissionState(granted = true, hasRequested = false, canRequestAgain = true)
        )
    }

    @Test
    fun `已授权时忽略其余入参`() {
        // 权限位是硬真源：即便标记显示「问过且不能再问」，只要已授权就是 GRANTED。
        assertEquals(
            CameraPermissionState.GRANTED,
            resolveCameraPermissionState(granted = true, hasRequested = true, canRequestAgain = false)
        )
    }

    @Test
    fun `从未请求过时判定为可继续弹框`() {
        // 关键回归点：首次进入时 shouldShowRequestPermissionRationale 也是 false，
        // 若先判 canRequestAgain 就会误判成永久拒绝、直接跳系统设置，用户永远等不到弹框。
        assertEquals(
            CameraPermissionState.DENIED_CAN_ASK,
            resolveCameraPermissionState(granted = false, hasRequested = false, canRequestAgain = false)
        )
    }

    @Test
    fun `拒绝过一次且系统仍会弹框时为可继续弹框`() {
        assertEquals(
            CameraPermissionState.DENIED_CAN_ASK,
            resolveCameraPermissionState(granted = false, hasRequested = true, canRequestAgain = true)
        )
    }

    @Test
    fun `拒绝后系统不再弹框时为永久拒绝`() {
        assertEquals(
            CameraPermissionState.DENIED_PERMANENT,
            resolveCameraPermissionState(granted = false, hasRequested = true, canRequestAgain = false)
        )
    }
}
