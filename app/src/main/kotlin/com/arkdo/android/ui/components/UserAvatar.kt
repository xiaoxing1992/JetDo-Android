package com.arkdo.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.arkdo.android.common.constants.AppConstants
import com.arkdo.android.data.models.User
import com.arkdo.android.ui.theme.ArkdoFixedColors
import com.arkdo.android.ui.theme.arkdoColors
import kotlin.math.floor
import kotlin.math.roundToInt

// ==========================================================================
// 对齐鸿蒙版 views/components/UserAvatar.ets。
//
// 形状规则：user.id == 1 走方形（10% 圆角），其余圆形。
// 头像请求尺寸恒取 96 —— 鸿蒙侧把 avatar_template 的 {size} 写死成 '96'，
// 不按显示尺寸分档，这样同一张图在各处能共用一份缓存。
// ==========================================================================

private const val AVATAR_REQUEST_SIZE = "96"
private val FLAIR_MIN_AVATAR_SIZE = 32.dp

@Composable
fun UserAvatar(
    user: User?,
    avatarSize: Dp = 48.dp,
    showFlair: Boolean = true,
    modifier: Modifier = Modifier
) {
    val colors = arkdoColors

    // 鸿蒙取的是 username 首字符，不是 displayName。
    val initial = (user?.username ?: user?.displayName)
        ?.firstOrNull()?.toString()?.uppercase() ?: "?"

    // max(12, floor(size * 0.4))，随头像尺寸缩放。
    val initialFontSize = maxOf(12f, floor(avatarSize.value * 0.4f)).sp

    val shape = if (user?.id == 1L) RoundedCornerShape(avatarSize * 0.1f) else CircleShape

    val avatarUrl = buildAvatarUrl(user)
    // 首字母只在没有候选或加载失败时露出，不是恒在底层垫着。
    var showInitial by remember(avatarUrl) { mutableStateOf(avatarUrl == null) }

    Box(
        // 不裁切：flair 角标要探出头像边界。
        modifier = modifier.size(avatarSize),
        contentAlignment = Alignment.BottomEnd
    ) {
        Box(
            modifier = Modifier
                .size(avatarSize)
                .clip(shape)
                .background(colors.surfaceMuted)
                .border(width = 1.dp, color = ArkdoFixedColors.AvatarBorder, shape = shape),
            contentAlignment = Alignment.Center
        ) {
            if (showInitial) {
                Text(
                    text = initial,
                    fontSize = initialFontSize,
                    fontWeight = FontWeight.Medium,
                    color = colors.avatarFg
                )
            }

            if (avatarUrl != null) {
                AsyncImage(
                    model = avatarUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(avatarSize)
                        .clip(shape),
                    contentScale = ContentScale.Crop,
                    onState = { state ->
                        showInitial = state is AsyncImagePainter.State.Error
                    }
                )
            }
        }

        // 对齐鸿蒙 hasFlair()：除了非空和尺寸门槛，还要求它**确实能画出东西** ——
        // 要么是图片地址，要么是图标集里真有的 FA 名。都不是就整个不画，
        // 而不是画一个彩色圆圈套着加载失败的图。
        val flairUrl = user?.flairUrl.orEmpty()
        val renderableFlair = flairUrl.isNotEmpty() &&
            (isImageFlair(flairUrl) || FaIconSet.has(context, flairUrl))

        if (showFlair && renderableFlair && avatarSize >= FLAIR_MIN_AVATAR_SIZE) {
            FlairBadge(
                flairUrl = flairUrl,
                flairBgColor = user?.flairBgColor,
                flairColor = user?.flairColor,
                avatarSize = avatarSize
            )
        }
    }
}

/**
 * 右下角 flair 角标。
 *
 * 有底色时用圆形 + Contain + 内缩，让底色露成一圈；无底色时小圆角方块 + Cover 铺满。
 * 鸿蒙侧没有描边，这里也不要加 —— 加了在深色底上会多出一圈亮边。
 */
@Composable
private fun FlairBadge(
    flairUrl: String,
    flairBgColor: String?,
    flairColor: String?,
    avatarSize: Dp
) {
    val context = LocalContext.current

    val badgeSize = maxOf(14.dp, avatarSize * 0.42f)
    val overhang = (badgeSize.value * 0.24f).roundToInt().dp
    val hasBg = !flairBgColor.isNullOrBlank()

    val backgroundColor = if (hasBg) {
        parseColor(flairBgColor) ?: ArkdoFixedColors.FlairFallbackBg
    } else {
        Color.Transparent
    }
    val shape = if (hasBg) CircleShape else RoundedCornerShape((badgeSize.value * 0.18f).roundToInt().dp)
    val inset = if (hasBg) (badgeSize.value * 0.16f).roundToInt().dp else 0.dp

    Box(
        modifier = Modifier
            .size(badgeSize)
            .offset(x = overhang, y = overhang)
            .clip(shape)
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        // 判据是 URL 形态，不是"在不在图标集里" —— 对齐鸿蒙 isImageFlair()。
        // 反过来判会让"不在图标集里的图标名"被当成图片地址去请求。
        if (isImageFlair(flairUrl)) {
            AsyncImage(
                model = resolveFlairUrl(flairUrl),
                contentDescription = null,
                modifier = Modifier
                    .size(badgeSize)
                    .padding(inset),
                contentScale = if (hasBg) ContentScale.Fit else ContentScale.Crop
            )
        }
    }
}

/**
 * 头像地址：优先动态头像，其次 avatar_template。
 * 鸿蒙侧是「候选链」逐个失败下移，这里先取第一个可用的，失败后回落首字母。
 */
private fun buildAvatarUrl(user: User?): String? {
    if (user == null) return null

    user.animatedAvatar?.takeIf { it.isNotEmpty() }?.let { return absolutize(it) }

    user.avatarTemplate?.takeIf { it.isNotEmpty() }?.let {
        return absolutize(it.replace("{size}", AVATAR_REQUEST_SIZE))
    }

    return null
}

private fun absolutize(path: String): String =
    if (path.startsWith("http")) path else "${AppConstants.BASE_URL}$path"

private fun resolveFlairUrl(flairUrl: String): String = when {
    flairUrl.startsWith("http") -> flairUrl
    flairUrl.startsWith("//") -> "https:$flairUrl"
    flairUrl.startsWith("/") -> "${AppConstants.BASE_URL}$flairUrl"
    else -> "${AppConstants.BASE_URL}/$flairUrl"
}

private fun parseColor(colorString: String?): Color? {
    if (colorString.isNullOrBlank()) return null
    return runCatching {
        val hex = colorString.removePrefix("#")
        if (hex.length == 6) {
            Color(
                hex.substring(0, 2).toInt(16),
                hex.substring(2, 4).toInt(16),
                hex.substring(4, 6).toInt(16)
            )
        } else {
            null
        }
    }.getOrNull()
}
