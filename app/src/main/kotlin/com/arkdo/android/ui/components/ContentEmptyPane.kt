package com.arkdo.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkdo.android.ui.theme.arkdoColors

/**
 * 双栏右栏的**空占位**。
 *
 * 宽屏下右上角没有内容时用它兜底，而不是让系统露出空白 —— 空白会被读成「页面没加载出来」，
 * 品牌空态才读得出「这里在等你从左边选一个」。
 *
 * 对齐鸿蒙 `RootTabPage` 的 `PLACEHOLDER` NavDestination（品牌名 + 一行提示，居中）。
 */
@Composable
fun ContentEmptyPane(modifier: Modifier = Modifier) {
    val colors = arkdoColors
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.pageBg)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "ArkDO",
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            color = colors.textSecondary
        )
        Text(
            text = "从左侧选择一个帖子开始阅读",
            fontSize = 15.sp,
            color = colors.textTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp)
        )
    }
}
