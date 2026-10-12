package com.jetdo.android.ui.qr

import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * zxing 旋转能力护栏。
 *
 * 扫码页曾对 [PlanarYUVLuminanceSource] 链式调用 `rotateCounterClockwise()` 把相机帧转正，
 * 结果 zxing 3.5.3 的这个类**根本没有覆写**该方法（javap 实证），调用落到基类的默认实现：
 * `throw UnsupportedOperationException("This luminance source does not support rotation by
 * 90 degrees.")`。手机竖屏 rotationDegrees 恒为 90/270，于是每帧都炸、永远扫不出码。
 *
 * 修复方式是不旋转（QR 码自带寻像图形，识别器自己定向）。这个测试把结论固化：
 * declaringClass 若不再是 LuminanceSource，说明 zxing 升级后实现了旋转，可以重新评估，
 * 但注意 QR 识别本身并不需要旋转。
 */
class ZxingRotationGuardTest {

    @Test
    fun `planar yuv source does not implement rotation`() {
        val method = PlanarYUVLuminanceSource::class.java.getMethod("rotateCounterClockwise")
        assertEquals(
            "PlanarYUVLuminanceSource 覆写了 rotateCounterClockwise —— 请重新评估 QrScanScreen 的旋转策略",
            LuminanceSource::class.java,
            method.declaringClass
        )
    }
}
