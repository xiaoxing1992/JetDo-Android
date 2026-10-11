package com.jetdo.android.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage

@Composable
fun NotificationAvatar(
    name: String,
    username: String,
    avatarTemplate: String?,
    size: Dp,
    modifier: Modifier = Modifier
) {
    val url = avatarTemplate?.replace("{size}", "80")
    if (url.isNullOrBlank()) {
        Box(
            modifier = modifier.size(size).clip(CircleShape).background(Color(0xFF0088CC)),
            contentAlignment = Alignment.Center
        ) {
            Text(name.take(1).ifEmpty { username.take(1) }.uppercase(), color = Color.White)
        }
    } else {
        AsyncImage(
            model = url,
            contentDescription = name,
            modifier = modifier.size(size).clip(CircleShape)
        )
    }
}
