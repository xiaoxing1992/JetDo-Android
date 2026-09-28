package com.arkdo.android.services.content

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// 对齐鸿蒙 MediaSource.ets 的分流语义:论坛同源(含相对路径兜底)走下载委托过盾,外站直连流播。
class MediaSourceTest {

    @Test
    fun `论坛同源上传件走下载桥`() {
        assertTrue(MediaSource.needsDownloadBridge("https://linux.do/uploads/short-url/abc.xz"))
        assertTrue(MediaSource.needsDownloadBridge("https://LINUX.DO/uploads/short-url/abc.xz"))
    }

    @Test
    fun `外站绝对直链直接流播`() {
        assertFalse(MediaSource.needsDownloadBridge("https://cdn.example.com/audio/song.mp3"))
        assertFalse(MediaSource.needsDownloadBridge("http://files.somebed.org/a/b.mp3"))
    }

    @Test
    fun `同源判定忽略路径端口与协议大小写`() {
        assertTrue(MediaSource.needsDownloadBridge("https://linux.do:443/uploads/a.mp3"))
        assertFalse(MediaSource.needsDownloadBridge("https://notlinux.do/uploads/a.mp3"))
        assertFalse(MediaSource.needsDownloadBridge("https://linux.do.evil.com/a.mp3"))
    }

    @Test
    fun `空串与相对路径保守走下载桥`() {
        assertTrue(MediaSource.needsDownloadBridge(""))
        assertTrue(MediaSource.needsDownloadBridge("   "))
        assertTrue(MediaSource.needsDownloadBridge("/uploads/short-url/abc.xz"))
        assertTrue(MediaSource.needsDownloadBridge("javascript:void(0)"))
    }
}
