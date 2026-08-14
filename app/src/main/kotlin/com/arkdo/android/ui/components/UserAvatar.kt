package com.arkdo.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.arkdo.android.common.constants.AppConstants
import com.arkdo.android.data.models.User

/**
 * 用户头像组件 - 1:1 对齐鸿蒙版 UserAvatar.ets
 *
 * 支持功能：
 * - 静态头像 / 动态头像（GIF）
 * - 圆形 / 方形（圆角）
 * - 徽章标识覆盖显示
 * - 失败回退到首字母占位符
 *
 * 对齐鸿蒙逻辑：
 * - ID=1 用户使用方形圆角(10%)，其余用户使用圆形头像(50%)
 * - 徽章尺寸 avatarSize * 0.42f
 * - 徽章位置右下角溢出 0.24f
 *
 * @param user 用户数据（包含头像、徽章等信息）
 * @param avatarSize 头像尺寸（默认 40dp）
 * @param showFlair 是否显示徽章标识（默认显示，需 avatarSize >= 32dp）
 * @param modifier 修饰符
 */
@Composable
fun UserAvatar(
    user: User?,
    avatarSize: Dp = 40.dp,
    showFlair: Boolean = true,
    modifier: Modifier = Modifier
) {
    val displayName = user?.displayName ?: "?"
    val initial = displayName.firstOrNull()?.toString()?.uppercase() ?: "?"

    // 头像形状：严格对齐鸿蒙源码 UserAvatar.ets:352-358
    // ID=1 的用户使用方形圆角(10%), 其余用户使用圆形头像(50%)
    val isSquare = user?.id == 1L
    val shape = if (isSquare) {
        RoundedCornerShape(avatarSize * 0.1f)  // 方形：10% 圆角
    } else {
        CircleShape  // 圆形：50% 圆角
    }

    Box(
        modifier = modifier.size(avatarSize),
        contentAlignment = Alignment.Center
    ) {
        // 主头像
        Box(
            modifier = Modifier
                .size(avatarSize)
                .clip(shape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            // 始终显示首字母作为背景
            Text(
                text = initial,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )

            // 如果有头像 URL，尝试加载图片覆盖在上面
            val avatarUrl = buildAvatarUrl(user, avatarSize.value.toInt())
            if (avatarUrl != null) {
                AsyncImage(
                    model = avatarUrl,
                    contentDescription = "用户头像: ${user?.displayName}",
                    modifier = Modifier
                        .size(avatarSize)
                        .clip(shape),
                    contentScale = ContentScale.Crop
                )
            }
        }

        // 徽章标识（右下角小图标）
        // 对齐鸿蒙版逻辑：
        // - 最小头像尺寸限制 32dp (flairMinAvatarSize)
        // - 徽章尺寸 avatarSize * 0.42f
        // - 轻微溢出头像右下角 0.24f (探出约 1/4)
        val flairMinAvatarSize = 32.dp
        if (showFlair && user?.flairUrl != null && avatarSize >= flairMinAvatarSize) {
            FlairBadge(
                flairUrl = user.flairUrl,
                flairBgColor = user.flairBgColor,
                flairColor = user.flairColor,
                avatarSize = avatarSize,
                modifier = Modifier.align(Alignment.BottomEnd)
            )
        }
    }
}

/**
 * 徽章标识组件（覆盖在头像右下角）
 *
 * 对齐鸿蒙版 UserAvatar.ets:111-151:
 * - 徽章尺寸: avatarSize * 0.42
 * - 轻微溢出: avatarSize * 0.24 (探出约 1/4)
 * - 形状: 有底色=圆形(Contain + 内缩让底色露成圆环), 无底色=方形(Cover 铺满方块)
 * - 内缩比例: 有底色时 padding = badgeSize * 0.16
 * - 圆角: 有底色=50%(圆形), 无底色=18%(小圆角方形)
 */
@Composable
private fun FlairBadge(
    flairUrl: String,
    flairBgColor: String?,
    flairColor: String?,
    avatarSize: Dp,
    modifier: Modifier = Modifier
) {
    // 徽章尺寸计算: max(14, avatarSize * 0.42)
    val flairBadgeSize = maxOf(14.dp, avatarSize * 0.42f)
    // 溢出量: badgeSize * 0.24
    val flairOverhang = flairBadgeSize * 0.24f
    // 是否有底色
    val hasFlairBg = !flairBgColor.isNullOrBlank()
    // 底色
    val backgroundColor = if (hasFlairBg) {
        parseColor(flairBgColor) ?: Color(0xFFBE123C)  // 默认玫红色
    } else {
        Color.Transparent
    }
    // 形状: 有底色=圆形, 无底色=方形(18% 圆角)
    val shape = if (hasFlairBg) {
        CircleShape
    } else {
        RoundedCornerShape(flairBadgeSize * 0.18f)
    }
    // 内缩: 有底色时 padding 16%
    val padding = if (hasFlairBg) (flairBadgeSize * 0.16f) else 0.dp

    Box(
        modifier = modifier
            .size(flairBadgeSize)
            .offset(x = flairOverhang, y = flairOverhang)
            .clip(shape)
            .background(backgroundColor)
            .border(
                width = if (hasFlairBg) 1.dp else 0.dp,
                color = MaterialTheme.colorScheme.surface,
                shape = shape
            ),
        contentAlignment = Alignment.Center
    ) {
        val fullUrl = if (flairUrl.startsWith("http")) {
            flairUrl
        } else if (flairUrl.startsWith("//")) {
            "https:$flairUrl"
        } else if (flairUrl.startsWith("/")) {
            "${AppConstants.BASE_URL}$flairUrl"
        } else {
            "${AppConstants.BASE_URL}/$flairUrl"
        }

        AsyncImage(
            model = fullUrl,
            contentDescription = "徽章标识",
            modifier = Modifier
                .size(flairBadgeSize - padding * 2)
                .then(if (padding > 0.dp) Modifier.offset(0.dp, 0.dp) else Modifier),
            contentScale = if (hasFlairBg) ContentScale.Fit else ContentScale.Crop
        )
    }
}

/**
 * 构建头像 URL
 * - 优先使用 animatedAvatar（动态头像 GIF）
 * - 回退到 avatarTemplate（静态头像）
 * - avatar_template 格式示例: "/letter_avatar_proxy/v4/letter/a/abc123/{size}.png"
 */
private fun buildAvatarUrl(user: User?, size: Int): String? {
    if (user == null) return null

    // 优先使用动态头像
    if (user.animatedAvatar != null) {
        return if (user.animatedAvatar.startsWith("http")) {
            user.animatedAvatar
        } else {
            "${AppConstants.BASE_URL}${user.animatedAvatar}"
        }
    }

    // 回退到静态头像模板
    if (user.avatarTemplate != null) {
        // 选择合适的尺寸（常见尺寸: 24, 32, 48, 96, 144, 240）
        val targetSize = when {
            size <= 24 -> 24
            size <= 32 -> 32
            size <= 48 -> 48
            size <= 96 -> 96
            size <= 144 -> 144
            else -> 240
        }

        val path = user.avatarTemplate.replace("{size}", targetSize.toString())

        // 如果是相对路径，添加 baseUrl
        return if (path.startsWith("http")) {
            path
        } else {
            "${AppConstants.BASE_URL}$path"
        }
    }

    return null
}

/**
 * 解析颜色字符串（#RRGGBB 或 RRGGBB 格式）
 */
private fun parseColor(colorString: String?): Color? {
    if (colorString.isNullOrBlank()) return null

    return try {
        val hex = if (colorString.startsWith("#")) {
            colorString.substring(1)
        } else {
            colorString
        }

        if (hex.length == 6) {
            val r = hex.substring(0, 2).toInt(16)
            val g = hex.substring(2, 4).toInt(16)
            val b = hex.substring(4, 6).toInt(16)
            Color(r, g, b)
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }
}
