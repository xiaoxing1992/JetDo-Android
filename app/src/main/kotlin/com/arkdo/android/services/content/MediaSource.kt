package com.arkdo.android.services.content

import com.arkdo.android.common.constants.AppConstants

// 帖子媒体源分流。linux.do 自家上传件(/uploads/short-url/*.xz)被 CF 挡且 302 到跨源 CDN,
// 原生播放器直连拿不到字节,唯有 host webview 下载委托能过盾,须先下到沙箱再播;
// 其余外站绝对直链(第三方文件床等)是普通公开文件,播放器直接喂 URL 流式播放即可,
// 不必也不该走下载委托(会因跨源无上下文而挂死)。对齐鸿蒙 MediaSource.ets。
object MediaSource {

    // 该媒体 URL 是否需要走 host webview 下载委托过盾。判定:属于 linux.do 源(与 BASE_URL 同域)才需要;
    // 其它绝对 http(s) URL 直连。空串或相对路径 / 其它 scheme 一律按需要过盾处理——相对路径几乎只可能是
    // linux.do 自家资源,保守走下载委托更安全。
    fun needsDownloadBridge(url: String): Boolean {
        val src = url.trim()
        if (src.isEmpty()) {
            return true
        }
        val lower = src.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return hostOf(lower) == hostOf(AppConstants.BASE_URL.lowercase())
        }
        return true
    }

    // 从绝对 URL 抽出主机名(去 scheme、路径与端口),用于同域判定。
    private fun hostOf(url: String): String {
        val schemeEnd = url.indexOf("://")
        val start = if (schemeEnd >= 0) schemeEnd + 3 else 0
        var end = url.length
        val slash = url.indexOf('/', start)
        if (slash >= 0) {
            end = slash
        }
        val authority = url.substring(start, end)
        val colon = authority.indexOf(':')
        return if (colon >= 0) authority.substring(0, colon) else authority
    }
}
