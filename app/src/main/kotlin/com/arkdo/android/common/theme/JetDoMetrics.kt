package com.arkdo.android.common.theme

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 布局度量 token —— 与 HarmonyOS 版 common/theme/Metrics.ets 对齐。
 */
object JetDoMetrics {
    // 卡片四件套
    val PageMargin = 12.dp
    val CardRadius = 16.dp
    val CardPadding = 16.dp
    val CardGap = 12.dp
}

/**
 * 字号 token（全项目 392 处 fontSize 归纳出的九档标准）
 */
object JetDoFontSizes {
    val Title = 20.sp          // 大标题
    val Subtitle = 18.sp       // 小节标题
    val BodyLarge = 17.sp      // 强调正文
    val Body = 15.sp           // 正文（最常用）
    val BodySmall = 14.sp      // 次要正文、按钮
    val Caption = 13.sp        // 说明文字、元信息（第二常用）
    val Footnote = 12.sp       // 辅助文字、角标
    val Micro = 11.sp          // 最小可读字号
}
