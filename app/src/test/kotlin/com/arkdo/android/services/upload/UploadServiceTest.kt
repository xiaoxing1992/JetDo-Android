package com.arkdo.android.services.upload

import com.arkdo.android.services.session.LoginGate
import com.arkdo.android.services.site.SiteUploadSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.arkdo.android.common.TestStrings
import org.junit.Before

/**
 * 上传工具纯函数：与鸿蒙 UploadService 逐条对齐，边界值按服务端行为定。
 */
class UploadServiceTest {

    @Before
    fun installStrings() = TestStrings.install()


    private fun settings(
        authorized: List<String> = listOf("jpg", "png", "zip", "xz"),
        staff: List<String> = emptyList(),
    ) = SiteUploadSettings(
        maxImageSizeKb = 4096,
        maxAttachmentSizeKb = 4096,
        authorizedExtensions = authorized,
        authorizedExtensionsForStaff = staff,
        newuserMaxAttachments = 0,
        newuserMaxEmbeddedMedia = 5,
    )

    // ==================== 附件 markdown ====================

    @Test
    fun `附件 markdown 带上 attachment 标记与可读体积`() {
        val markdown = UploadService.buildAttachmentMarkdown(
            fileName = "release.zip",
            humanFileSize = "38.3 KB",
            shortUrl = "upload://abc123.zip",
        )
        // `|attachment` 是服务端 cook 成 <a class="attachment"> 的依据，少它就不算附件。
        assertEquals("[release.zip|attachment](upload://abc123.zip) (38.3 KB)", markdown)
    }

    @Test
    fun `附件 markdown 剔除会破坏语法的方括号与竖线`() {
        val markdown = UploadService.buildAttachmentMarkdown(
            fileName = "a[b]|c.zip",
            humanFileSize = "",
            shortUrl = "upload://x.zip",
        )
        assertEquals("[abc.zip|attachment](upload://x.zip)", markdown)
    }

    @Test
    fun `文件名为空时退回 file`() {
        // 名字里全是非法字符 → 剔除后为空 → 用兜底名，不能拼出 `[|attachment]`。
        val markdown = UploadService.buildAttachmentMarkdown("[]|", "1 KB", "upload://x.zip")
        assertEquals("[file|attachment](upload://x.zip) (1 KB)", markdown)
    }

    // ==================== 视频标签 ====================

    @Test
    fun `视频标签用 shortPath 而不是 upload 短码`() {
        val html = UploadService.buildVideoHtml(
            shortPath = "https://linux.do/uploads/short-url/xyz.mp4",
            url = "https://linux.do/uploads/default/original/3X/x/xyz.mp4",
            width = 1920,
            height = 1080,
            mediaMimeType = "video/mp4",
        )
        // upload:// 短码只在 markdown 的链接/图片 token 里被改写，写进裸 HTML 会是死链。
        assertTrue(html.contains("src=\"https://linux.do/uploads/short-url/xyz.mp4\""))
        assertTrue(html.contains("type=\"video/mp4\""))
        assertTrue(html.contains("width=\"640\""))
        assertTrue(html.contains("height=\"360\""))
        assertTrue(html.contains("controls"))
    }

    @Test
    fun `shortPath 缺失时退回真实 url`() {
        val html = UploadService.buildVideoHtml("", "https://x/y.mp4", 640, 360, "video/mp4")
        assertTrue(html.contains("src=\"https://x/y.mp4\""))
    }

    @Test
    fun `横向视频等比缩到 640x360`() {
        assertEquals(VideoBox(640, 360), UploadService.videoBox(1920, 1080))
    }

    @Test
    fun `竖屏视频摆正成 360x640 而不是写死横向框`() {
        // 站方主题组件写死 640x360，竖屏会被摆歪 —— 这正是自研渲染器要按真实比例算的原因。
        assertEquals(VideoBox(360, 640), UploadService.videoBox(1080, 1920))
    }

    @Test
    fun `小于长边基准的尺寸原样保留`() {
        assertEquals(VideoBox(320, 240), UploadService.videoBox(320, 240))
        assertEquals(VideoBox(640, 480), UploadService.videoBox(640, 480))
    }

    @Test
    fun `量不到尺寸时退回 640x360`() {
        assertEquals(VideoBox(640, 360), UploadService.videoBox(0, 0))
        assertEquals(VideoBox(640, 360), UploadService.videoBox(-1, 200))
    }

    // ==================== 体积上限 ====================

    @Test
    fun `生效上限取站点上限与客户端硬上限的较小者`() {
        assertEquals(4096L * 1024, UploadService.effectiveLimitBytes(4096))
        // 站点配到 1 GB 也不能照走，否则峰值内存会把 App 撑爆。
        assertEquals(CLIENT_MAX_UPLOAD_BYTES, UploadService.effectiveLimitBytes(1024 * 1024))
    }

    @Test
    fun `没超限不产生文案`() {
        assertEquals("", UploadService.checkSize(4L * 1024 * 1024, 4096, "附件"))
    }

    @Test
    fun `超站点上限时点到本站的限额`() {
        val message = UploadService.checkSize(5L * 1024 * 1024, 4096, "附件")
        assertTrue(message.startsWith("附件 5.0 MB 超过本站上限"))
        assertTrue(message.contains("4.0 MB"))
    }

    @Test
    fun `站点上限高于客户端硬上限时改说 App 上限`() {
        // 40000 KB ≈ 39 MB > 32 MB：用户真正撞的是 App 那道，报站点上限会让人去找站长。
        val message = UploadService.checkSize(40L * 1024 * 1024, 40000, "视频")
        assertTrue(message.contains("App 单次上传上限"))
    }

    @Test
    fun `humanSize 三档`() {
        assertEquals("512 B", UploadService.humanSize(512))
        assertEquals("1.0 KB", UploadService.humanSize(1024))
        assertEquals("38.3 KB", UploadService.humanSize((38.3 * 1024).toLong()))
        assertEquals("1.5 MB", UploadService.humanSize((1.5 * 1024 * 1024).toLong()))
    }

    // ==================== 文件名与 MIME ====================

    @Test
    fun `extensionOf 取小写后缀`() {
        assertEquals("mp4", UploadService.extensionOf("clip.MP4"))
        assertEquals("", UploadService.extensionOf("README"))
        assertEquals("", UploadService.extensionOf("trailing."))
        assertEquals("xz", UploadService.extensionOf("a.b.xz"))
    }

    @Test
    fun `baseNameOf 不吞掉以点开头的名字`() {
        assertEquals("clip", UploadService.baseNameOf("clip.mp4"))
        // dot == 0 时原样返回，否则 ".gitignore" 会被削成空串。
        assertEquals(".gitignore", UploadService.baseNameOf(".gitignore"))
        assertEquals("noext", UploadService.baseNameOf("noext"))
    }

    @Test
    fun `音视频上传改名成 xz`() {
        assertEquals("clip.xz", UploadService.disguisedName("clip.mp4"))
        assertEquals("我的视频.xz", UploadService.disguisedName("我的视频.mov"))
        // 无扩展名也补上 .xz，否则白名单那一关直接过不去。
        assertEquals("raw.xz", UploadService.disguisedName("raw"))
    }

    @Test
    fun `mimeFor 认不出给 octet-stream`() {
        assertEquals("video/mp4", UploadService.mimeFor("mp4"))
        assertEquals(DISGUISE_MIME, UploadService.mimeFor("xz"))
        assertEquals("application/octet-stream", UploadService.mimeFor("unknownext"))
    }

    // ==================== 白名单 ====================

    @Test
    fun `非图片扩展名过滤掉图片`() {
        LoginGate.clearUserPrivileges() // 身份未知 → 按 staff 合并，行为见下一个用例
        val extensions = UploadService.nonImageExtensions(settings())
        assertEquals(listOf("zip", "xz"), extensions)
    }

    @Test
    fun `身份未知时并入 staff 白名单而不是把入口藏掉`() {
        LoginGate.clearUserPrivileges()
        val merged = UploadService.allowedExtensions(settings(authorized = listOf("zip"), staff = listOf("tar")))
        assertTrue(merged.contains("tar"))
    }

    @Test
    fun `普通用户看不到 staff 专有后缀`() {
        LoginGate.setUserPrivileges(trustLevel = 2, staff = false)
        val allowed = UploadService.allowedExtensions(settings(authorized = listOf("zip"), staff = listOf("tar")))
        assertFalse(allowed.contains("tar"))
        LoginGate.clearUserPrivileges()
    }

    @Test
    fun `站点只允许图片时附件入口没有可选项`() {
        LoginGate.clearUserPrivileges()
        // 这正是「一个非图片后缀都没有 → 藏掉附件钮」的判据。
        assertTrue(UploadService.nonImageExtensions(settings(authorized = listOf("jpg", "png"))).isEmpty())
    }

    @Test
    fun `isImageExtension 大小写不敏感`() {
        assertTrue(UploadService.isImageExtension("PNG"))
        assertFalse(UploadService.isImageExtension("mp4"))
    }
}
