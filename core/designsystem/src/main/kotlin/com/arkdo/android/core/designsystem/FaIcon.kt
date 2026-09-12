package com.arkdo.android.core.designsystem

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/** Small dependency-free fallback for notification type glyphs. */
@Composable
fun FaIcon(iconId: String, color: Color, size: TextUnit = 12.sp, modifier: Modifier = Modifier) {
    Text(iconId.take(1).uppercase(), color = color, fontSize = size, modifier = modifier)
}
