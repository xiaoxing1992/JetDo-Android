package com.arkdo.android.services.site

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 站点上传设置的解析纯函数。判据来自 Linux.do 实测值
 * （`jpg|jpeg|png|gif|heic|heif|webp|avif|svg|txt|pdf|doc|docx|csv|zip|7z|gz|xz|jxl|md`）。
 */
class SiteSettingsServiceTest {

    @Test
    fun `扩展名串按竖线切分并小写归一`() {
        assertEquals(
            listOf("jpg", "jpeg", "png"),
            SiteSettingsService.splitExtensions("jpg|jpeg|png")
        )
        assertEquals(
            listOf("zip", "xz"),
            SiteSettingsService.splitExtensions(" ZIP | XZ ")
        )
    }

    @Test
    fun `丢掉通配项与空段`() {
        // 服务端允许 `*` 表示「任意后缀」，那条另有 authorizesAllExtensions 判据；
        // 混进白名单会让选择器过滤失效。
        assertEquals(
            listOf("png"),
            SiteSettingsService.splitExtensions("*|.*|png|")
        )
    }

    @Test
    fun `空串或 null 给空表`() {
        assertTrue(SiteSettingsService.splitExtensions("").isEmpty())
        assertTrue(SiteSettingsService.splitExtensions(null).isEmpty())
    }

    @Test
    fun `出厂默认白名单与图片判定表是两张表`() {
        val fallback = defaultSiteUploadSettings()
        // 默认允许上传的后缀里没有 ico，而 IMAGE_EXTENSIONS 有 —— 两者语义不同，不能互替。
        assertFalse(fallback.authorizedExtensions.contains("ico"))
        assertTrue(IMAGE_EXTENSIONS.contains("ico"))
        assertTrue(fallback.authorizedExtensions.contains("jpg"))
    }

    @Test
    fun `音视频不算图片扩展名`() {
        // 这决定走 max_image_size_kb 还是 max_attachment_size_kb，抄错就会用错限额。
        for (extension in listOf("mp4", "mov", "webm", "mp3", "m4a")) {
            assertFalse(extension, IMAGE_EXTENSIONS.contains(extension))
        }
        for (extension in listOf("jpg", "png", "webp", "heic", "jxl")) {
            assertTrue(extension, IMAGE_EXTENSIONS.contains(extension))
        }
    }
}
