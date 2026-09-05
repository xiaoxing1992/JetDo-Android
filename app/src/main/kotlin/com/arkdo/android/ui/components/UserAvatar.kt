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
import timber.log.Timber
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
    val flairContext = LocalContext.current

    // 鸿蒙取的是 username 首字符，不是 displayName。
    val initial = (user?.username ?: user?.displayName)
        ?.firstOrNull()?.toString()?.uppercase() ?: "?"

    // max(12, floor(size * 0.4))，随头像尺寸缩放。
    val initialFontSize = maxOf(12f, floor(avatarSize.value * 0.4f)).sp

    val shape = if (user?.id == 1L) RoundedCornerShape(avatarSize * 0.1f) else CircleShape

    // 鸿蒙是「候选链」逐个失败下移：动图候选加载失败退静态候选，全失败才露首字母。
    val avatarCandidates = remember(user) { avatarCandidates(user) }
    var candidateIndex by remember(avatarCandidates) { mutableStateOf(0) }
    // 首字母只在没有候选或全部候选失败时露出，不是恒在底层垫着。
    var showInitial by remember(avatarCandidates) { mutableStateOf(avatarCandidates.isEmpty()) }
    val avatarUrl = avatarCandidates.getOrNull(candidateIndex)

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
                        // 失败退下一候选（动图→静态），到头才露首字母。对齐鸿蒙 advanceCandidate()。
                        if (state is AsyncImagePainter.State.Error) {
                            if (candidateIndex + 1 < avatarCandidates.size) {
                                Timber.w(
                                    "[UserAvatar] 候选加载失败，退下一候选 failed=%s rest=%s",
                                    avatarUrl,
                                    avatarCandidates.drop(candidateIndex + 1)
                                )
                                candidateIndex += 1
                            } else {
                                Timber.w(
                                    "[UserAvatar] 全部候选失败 url=%s user=%s",
                                    avatarUrl,
                                    user?.username
                                )
                                showInitial = true
                            }
                        }
                    }
                )
            }
        }

        // 对齐鸿蒙 hasFlair()：除了非空和尺寸门槛，还要求它**确实能画出东西** ——
        // 要么是图片地址，要么是图标集里真有的 FA 名。都不是就整个不画，
        // 而不是画一个彩色圆圈套着加载失败的图。
        val flairUrl = user?.flairUrl.orEmpty()
        val kind = flairKind(
            flairUrl = flairUrl,
            avatarSizeDp = avatarSize.value,
            iconExists = FaIconSet.has(flairContext, flairUrl),
            minAvatarSizeDp = FLAIR_MIN_AVATAR_SIZE.value
        )

        if (showFlair && kind != FlairKind.NONE) {
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
        } else {
            // 走到这里说明调用方已经确认过这个名字在图标集里。
            FaIcon(
                iconId = flairUrl,
                color = parseColor(flairColor) ?: Color.White,
                size = (badgeSize.value * 0.58f).roundToInt().dp
            )
        }
    }
}

/** flair 角标该怎么画（或者不画）。 */
internal enum class FlairKind { NONE, IMAGE, ICON }

/**
 * flair 角标的渲染决策，抽出来是为了能在 JVM 上直接测 —— 这条规则踩过坑：
 * 按"在不在图标集里"分支，会把不认识的图标名当成图片地址去请求，
 * 结果画出一个彩色圆圈套着加载失败的图。鸿蒙是按地址形态判，且两边都不成立就不画。
 *
 * @param iconExists 该名字在 FA 图标集里是否存在（调用方查表后传入）
 */
internal fun flairKind(
    flairUrl: String,
    avatarSizeDp: Float,
    iconExists: Boolean,
    minAvatarSizeDp: Float = 32f
): FlairKind {
    if (flairUrl.isEmpty() || avatarSizeDp < minAvatarSizeDp) return FlairKind.NONE
    if (isImageFlair(flairUrl)) return FlairKind.IMAGE
    return if (iconExists) FlairKind.ICON else FlairKind.NONE
}

/** flair 是图片还是图标，按地址形态判。对齐鸿蒙 isImageFlair()。 */
internal fun isImageFlair(flairUrl: String): Boolean =
    flairUrl.startsWith("http://") || flairUrl.startsWith("https://") || flairUrl.startsWith("//")

/** 候选地址列表：动图在前、静态在后，空串一律跳过。抽成纯函数供 JVM 测试。 */
internal fun avatarCandidates(user: User?, requestSize: String = AVATAR_REQUEST_SIZE): List<String> =
    buildList {
        if (user == null) return@buildList

        user.animatedAvatar?.takeIf { it.isNotEmpty() }?.let { add(resolveAvatarUrl(it)) }

        user.avatarTemplate?.takeIf { it.isNotEmpty() }?.let {
            add(resolveAvatarUrl(it.replace("{size}", requestSize)))
        }
    }

/** 对齐鸿蒙 resolveAvatarUrl()：http(s) 直用，// 补 https，/ 拼 BASE_URL，其余补斜杠拼。 */
internal fun resolveAvatarUrl(path: String): String = when {
    path.startsWith("http://") || path.startsWith("https://") -> path
    path.startsWith("//") -> "https:$path"
    path.startsWith("/") -> "${AppConstants.BASE_URL}$path"
    else -> "${AppConstants.BASE_URL}/$path"
}

private fun resolveFlairUrl(flairUrl: String): String = resolveAvatarUrl(flairUrl)

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
