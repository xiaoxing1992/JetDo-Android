package com.jetdo.android.ui.screens

// ==========================================================================
// WebView 导航分流判定：一条 URL 该由 WebView 自己加载，还是交回宿主处理。
//
// 为什么值得单独成文件并被测试钉住：授权回程深链 discourse://auth_redirect 就靠这条
// 判定分流。一旦把它判成「WebView 能加载」，302 的落点就被当成普通导航，停在一个
// WebView 根本不支持的 scheme 上 —— 页面不动、深链不回程，用户侧表现成
// 「点了授权什么也没发生」。这类故障在真机上只能读到「没反应」，单元测试是唯一
// 能便宜复现它的地方。
// ==========================================================================

/**
 * true = http/https，交给 WebView 自己加载（与不覆写 shouldOverrideUrlLoading 时等价）。
 * false = 其余 scheme（discourse://、mailto:、intent:、javascript: …）一律交宿主：
 *         WebView 没有加载它们的能力，返回 false 只会把 URL 丢给系统去找处理器，
 *         找不到时静默失败 —— 正是要避免的那种「没反应」。
 *
 * 先裁首尾空白：HTML 里 href 带换行/空格是常态，不裁会把 https 误判成自定义 scheme。
 */
fun isWebViewLoadable(link: String): Boolean {
    val lower = link.trim().lowercase()
    return lower.startsWith("http://") || lower.startsWith("https://")
}
