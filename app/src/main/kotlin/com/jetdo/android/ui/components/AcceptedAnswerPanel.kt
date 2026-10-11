package com.jetdo.android.ui.components

import androidx.compose.ui.res.stringResource
import com.jetdo.android.core.data.AppContextHolder
import com.jetdo.android.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jetdo.android.data.models.AcceptedAnswer
import com.jetdo.android.data.models.User
import com.jetdo.android.core.model.RelativeTime

// ==========================================================================
// discourse-solved 采纳答案面板（对齐鸿蒙 AcceptedAnswerPanel.ets + Card）。
//
// 一楼正文下方渲染「已解决」框：整框一条绿色标题（多解附「· N 个方案」），
// 下面铺多张精简卡（作者条 + 正文 + 阅读更多），卡间发丝线分隔。
// 首个方案默认展开、其余默认收起。
// ==========================================================================

private val CONTENT_MAX_HEIGHT = 260.dp
private val FADE_HEIGHT = 56.dp

/** 绿色主色：#009900 亮 / #4CAF50 暗（对齐鸿蒙 ld_success）。 */
internal val AcceptedSuccess = Color(0xFF009900)

@Composable
fun AcceptedAnswerPanel(
    answers: List<AcceptedAnswer>,
    onJumpToPost: (Int) -> Unit = {},
    onOpenImages: (List<ViewerImage>, Int) -> Unit = { _, _ -> },
    onOpenLink: (String) -> Unit = {},
    // 采纳答案正文里也可能带音频:透传媒体解析回调(同 PostRichContent)。
    onResolveMedia: suspend (String) -> String = { error(AppContextHolder.getString(R.string.common_media_download_not_ready)) },
    // 引用卡：采纳答案里的引用卡也要能展开全文 / 跳转（对齐鸿蒙 AcceptedAnswerPanel 透传同名回调）。
    onJumpToQuote: (topicId: Long, postNumber: Int, fallbackUrl: String?) -> Unit = { _, _, _ -> },
    onResolveQuotedPost: suspend (topicId: Long, postNumber: Int) -> String = { _, _ -> "" },
    modifier: Modifier = Modifier
) {
    if (answers.isEmpty()) return
    val colors = com.jetdo.android.ui.theme.jetdoColors
    val isDark = com.jetdo.android.ui.theme.isJetDoInDarkTheme()
    val success = if (isDark) Color(0xFF4CAF50) else AcceptedSuccess

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
    ) {
        // 绿色「已解决」标题条。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(success)
                .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 10.dp)
        ) {
            Text(
                text = "✔",
                fontSize = 14.sp,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.detail_solved),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White
            )
            if (answers.size > 1) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.solution_count_suffix, answers.size),
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }
        }

        answers.forEachIndexed { index, answer ->
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(colors.border)
                )
            }
            AcceptedAnswerCard(
                answer = answer,
                initiallyCollapsed = index > 0,
                onJumpToPost = onJumpToPost,
                onOpenImages = onOpenImages,
                onOpenLink = onOpenLink,
                onResolveMedia = onResolveMedia,
                onJumpToQuote = onJumpToQuote,
                onResolveQuotedPost = onResolveQuotedPost
            )
        }
    }
}

@Composable
private fun AcceptedAnswerCard(
    answer: AcceptedAnswer,
    initiallyCollapsed: Boolean,
    onJumpToPost: (Int) -> Unit,
    onOpenImages: (List<ViewerImage>, Int) -> Unit,
    onOpenLink: (String) -> Unit,
    onResolveMedia: suspend (String) -> String,
    onJumpToQuote: (topicId: Long, postNumber: Int, fallbackUrl: String?) -> Unit,
    onResolveQuotedPost: suspend (topicId: Long, postNumber: Int) -> String
) {
    val colors = com.jetdo.android.ui.theme.jetdoColors
    var collapsed by remember(answer.postNumber) { mutableStateOf(initiallyCollapsed) }
    var jumping by remember(answer.postNumber) { mutableStateOf(false) }
    val rotateAngle by animateFloatAsState(
        targetValue = if (collapsed) 180f else 0f,
        animationSpec = tween(250, easing = EaseInOut),
        label = "answerChevron"
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        // 作者条：整条可点切换折叠，右侧箭头随态翻转。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { collapsed = !collapsed }
                .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp)
        ) {
            UserAvatar(
                user = User(
                    id = 0,
                    username = answer.username,
                    avatarTemplate = answer.avatarTemplate.takeIf { it.isNotEmpty() }
                ),
                avatarSize = 32.dp,
                showFlair = false,
                modifier = Modifier.padding(end = 10.dp)
            )
            Text(
                text = answer.username,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (answer.createdAt.isNotEmpty()) {
                Text(
                    text = "·",
                    fontSize = 13.sp,
                    color = colors.textTertiary,
                    modifier = Modifier.padding(start = 8.dp, end = 8.dp)
                )
                Text(
                    text = RelativeTime.fromNow(answer.createdAt),
                    fontSize = 13.sp,
                    color = colors.textTertiary
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "⌃",
                fontSize = 14.sp,
                color = colors.textFaint,
                modifier = Modifier.rotate(rotateAngle)
            )
        }

        AnimatedVisibility(
            visible = !collapsed,
            enter = fadeIn(tween(250, easing = EaseInOut)) +
                slideInVertically(tween(250, easing = EaseInOut)) { -8 },
            exit = fadeOut(tween(250, easing = EaseInOut)) +
                slideOutVertically(tween(250, easing = EaseInOut)) { -8 }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, bottom = 14.dp)
            ) {
                // 正文：高度上限裁剪 + 底部渐隐。
                val isDark = com.jetdo.android.ui.theme.isJetDoInDarkTheme()
                Box(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = CONTENT_MAX_HEIGHT)
                            .clipToBounds()
                    ) {
                        PostRichContent(
                            html = answer.cookedHtml,
                            onOpenImages = onOpenImages,
                            onLinkClick = onOpenLink,
                            onResolveMedia = onResolveMedia,
                            // 楼层上下文用被采纳答案自己的楼号（对齐鸿蒙 AcceptedAnswerPanel.ets:161）。
                            currentPostNumber = answer.postNumber,
                            onJumpToQuote = onJumpToQuote,
                            onResolveQuotedPost = onResolveQuotedPost,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    // 底部渐隐：同色系零透明度 → 卡底色。
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(FADE_HEIGHT)
                            .background(
                                Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    1f to (if (isDark) Color(0xFF1F2228) else Color.White)
                                )
                            )
                    )
                }
                // 阅读更多 → 跳楼。
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .clickable(enabled = !jumping) {
                            if (jumping) return@clickable
                            jumping = true
                            onJumpToPost(answer.postNumber)
                            jumping = false
                        }
                ) {
                    if (jumping) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), color = colors.ldAccent, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = stringResource(R.string.common_read_more),
                        fontSize = 15.sp,
                        color = colors.ldAccent
                    )
                }
            }
        }
    }
}
