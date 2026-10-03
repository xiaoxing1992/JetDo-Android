package com.arkdo.android.services.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.arkdo.android.common.TestStrings
import org.junit.Before

/**
 * 转码决策的纯函数：与鸿蒙 VideoTranscoder 逐条对齐。
 *
 * 只测「不需要编解码器」的那部分 —— 真正跑一遍 Transformer 要设备/Codec，属于实机验证的
 * 范畴；这几条覆盖的是「降到几档」「目标高度算多少」「时长上限是多少」这些一算错就
 * 白压一遍甚至压出横向拉伸画面（旋转视频把编码方向当播放方向）的地方。
 */
class VideoTranscoderTest {

    @Before
    fun installStrings() = TestStrings.install()


    private val mib = 1024L * 1024L

    // ---- 阶梯选档 ----

    @Test
    fun `码率够得上原分辨率时留在最高档`() {
        // 1080p 短边 1080，2.5 Mbps 正好是 1080 档的下限。
        assertEquals(0, VideoTranscoder.tierIndexFor(1080, 2_500_000.0))
    }

    @Test
    fun `码率不够时降到撑得住的那一档`() {
        // 1.0 Mbps 站不住 1080(2.5M) 也站不住 720(1.2M)，但够 540(0.7M)。
        assertEquals(2, VideoTranscoder.tierIndexFor(1080, 1_000_000.0))
    }

    @Test
    fun `比原片还大的档会被跳过`() {
        // 源短边只有 480：1080/720/540 三档都该跳过，落在 480 档。
        assertEquals(3, VideoTranscoder.tierIndexFor(480, 1_000_000.0))
    }

    @Test
    fun `码率低到没有任何档站得住时落到最低档`() {
        // 这是「时长超限」的前哨：真正调用前 fit() 已经用 floorBps 拦掉，这里只保证不越界。
        assertEquals(VideoTranscoder.LADDER.size - 1, VideoTranscoder.tierIndexFor(1080, 100_000.0))
    }

    // ---- 目标高度 ----

    @Test
    fun `横屏视频按高度直接缩`() {
        // 1920x1080 → 短边 1080，目标 720 → 输出高 720。
        val probe = VideoProbe(durationSeconds = 10.0, width = 1920, height = 1080, rotation = 0)
        assertEquals(720, VideoTranscoder.outputHeightFor(probe, 720))
    }

    @Test
    fun `旋转 90 度的竖屏视频按播放方向算高度`() {
        // 编码方向 1920x1080 + rotate 90 → 播放方向 1080x1920。
        // 目标短边 720 落在**宽**上，输出高度应是 1280 —— 拿编码方向的 1080 当基准算成
        // 720 的话，出来会是一张横向压扁的图。
        val probe = VideoProbe(durationSeconds = 10.0, width = 1920, height = 1080, rotation = 90)
        assertEquals(1280, VideoTranscoder.outputHeightFor(probe, 720))
    }

    @Test
    fun `不需要缩小时不给目标尺寸`() {
        val probe = VideoProbe(durationSeconds = 10.0, width = 640, height = 480, rotation = 0)
        assertNull(VideoTranscoder.outputHeightFor(probe, 720))
    }

    @Test
    fun `偶数化保证 H264 宽高为偶数`() {
        assertEquals(720, VideoTranscoder.evenize(720.4))
        assertEquals(720, VideoTranscoder.evenize(721.0))
        assertEquals(718, VideoTranscoder.evenize(719.0))
        // 极小值不能退化成 0（H.264 不接受 0/1 的边长）。
        assertEquals(2, VideoTranscoder.evenize(1.0))
    }

    // ---- 播放方向 ----

    @Test
    fun `旋转 270 度同样互换宽高`() {
        val probe = VideoProbe(durationSeconds = 1.0, width = 1920, height = 1080, rotation = 270)
        assertEquals(VideoSize(1080, 1920), VideoTranscoder.displaySize(probe))
    }

    @Test
    fun `旋转 180 度不互换宽高`() {
        val probe = VideoProbe(durationSeconds = 1.0, width = 1920, height = 1080, rotation = 180)
        assertEquals(VideoSize(1920, 1080), VideoTranscoder.displaySize(probe))
    }

    // ---- 时长上限 ----

    @Test
    fun `时长上限由最低档反推`() {
        // floorBps = 150k + 64k = 214k；4 MiB * 8 * 0.88 / 214k = 137.98... → 取整 137 秒。
        // 这个数就是「多长的视频再怎么压也压不进 4 MB」的答案，改阶梯必须同步改这里。
        assertEquals(137, VideoTranscoder.maxDurationSeconds(4 * mib))
    }

    @Test
    fun `体积翻倍时长上限近似翻倍`() {
        // 除法的取整不参与线性，所以只能断言「按比例算出来的精确值」而不是 2 倍关系：
        // 8 MiB * 8 * 0.88 / 214k = 275.96... → 275（不是 137 * 2 = 274）。
        assertEquals(275, VideoTranscoder.maxDurationSeconds(8 * mib))
    }

    // ---- 文案 ----

    @Test
    fun `时长文案按分秒拆分`() {
        assertEquals("30 秒", VideoTranscoder.readableDuration(30.0))
        assertEquals("1 分钟", VideoTranscoder.readableDuration(60.0))
        assertEquals("1 分 30 秒", VideoTranscoder.readableDuration(90.0))
        assertEquals("3 分钟", VideoTranscoder.readableDuration(180.0))
    }

    @Test
    fun `体积文案与原实现同格式`() {
        assertEquals("4.0 MB", VideoTranscoder.readableSize(4 * mib))
    }
}
