package com.jetdo.android.services.notification

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 通知权限状态机的分支覆盖。
 *
 * 这些分支对应的设备状态（拒绝一次 / 拒绝两次 / 系统设置里关掉 / API 32 设备）在真机上
 * 极难凑齐，而写错任意一条的后果都不轻：把「已永久拒绝」误判成「可申请」会让用户点了没反应，
 * 反过来则会跳过弹框直接跳系统设置、用户永远等不到授权界面。所以这里逐条钉死。
 */
class NotificationPermissionTest {

    private fun resolve(sdkInt: Int, enabled: Boolean, canRequestAgain: Boolean) =
        resolveNotificationPermissionState(
            sdkInt = sdkInt,
            enabled = enabled,
            canRequestAgain = canRequestAgain
        )

    @Test
    fun `通知可达时一律判定为已授予`() {
        assertEquals(NotificationPermissionState.GRANTED, resolve(SDK_TIRAMISU, true, true))
        assertEquals(NotificationPermissionState.GRANTED, resolve(SDK_TIRAMISU, true, false))
        // enabled 优先于版本：用户自己在系统设置里把开关打开后，低版本设备同样应显示已开启。
        assertEquals(NotificationPermissionState.GRANTED, resolve(26, true, false))
    }

    @Test
    fun `API 33 以下没有运行时权限_关闭即永久拒绝`() {
        // 低版本走到这里只可能是用户在系统设置里关掉了通知：这个场景下没有任何请求框可弹，
        // 即使 canRequestAgain 传 true 也不能给出「可申请」——那会让开关点下去毫无反应。
        assertEquals(NotificationPermissionState.DENIED_PERMANENT, resolve(26, false, true))
        assertEquals(NotificationPermissionState.DENIED_PERMANENT, resolve(32, false, true))
    }

    @Test
    fun `API 33 起未授予但系统仍会弹框时为可申请`() {
        assertEquals(NotificationPermissionState.DENIED_CAN_ASK, resolve(33, false, true))
    }

    @Test
    fun `API 33 起未授予且系统不再弹框时为永久拒绝`() {
        assertEquals(NotificationPermissionState.DENIED_PERMANENT, resolve(33, false, false))
    }

    @Test
    fun `更高的 API 等级不改变判定规则`() {
        assertEquals(NotificationPermissionState.DENIED_CAN_ASK, resolve(36, false, true))
        assertEquals(NotificationPermissionState.DENIED_PERMANENT, resolve(36, false, false))
        assertEquals(NotificationPermissionState.GRANTED, resolve(36, true, false))
    }
}
