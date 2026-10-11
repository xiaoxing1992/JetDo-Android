package com.jetdo.android.ui.components

import androidx.compose.ui.res.stringResource
import com.jetdo.android.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jetdo.android.core.designsystem.FaIcon
import com.jetdo.android.data.models.Poll
import com.jetdo.android.services.topic.PollMutation
import com.jetdo.android.ui.theme.jetdoColors

// 已投项进度条填充:品牌蓝(强调色,明暗一致)。
private val BarFillChosen = Color(0xFF2563EB)
private val CardRadius = 4.dp
private val ButtonRadius = 4.dp

/**
 * discourse-poll:一个投票的可交互卡片(对齐鸿蒙 `views/components/PollView.ets`)。
 *
 * 两态:
 * - **投票态**:选项圈(可点)+ 信息行(公开/保密 | N 投票人)+ 按钮行(结果 / 撤销投票 / 多选提交);
 * - **结果态**:百分比进度条 + 底部「< 返回 | N 投票人」。
 *
 * 状态归属:`poll` 是唯一数据源(由上层持有,详见详情页 VM)。组件只额外持有两个
 * **纯 UI 态** —— [showResults] 与多选未提交的 [pending]。
 *
 * 乐观更新由上层做:`onVote`/`onRemoveVote` 收的是「用户意图」,上层先改自己的 poll
 * 副本(立即重渲染),再发请求,回来用服务端真值校正、失败回滚。组件不自己留快照,
 * 避免出现"组件内改了、上层不知道"的第二份真源。
 */
@Composable
fun PollView(
    poll: Poll,
    onVote: (optionIds: List<String>) -> Unit,
    onRemoveVote: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = jetdoColors
    var showResults by remember(poll.name) { mutableStateOf(false) }
    // 多选提交前的本地选中集合(regular 点击即投,不用此字段)。
    var pending by remember(poll.name) { mutableStateOf<List<String>>(emptyList()) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, colors.border, RoundedCornerShape(CardRadius))
            .padding(14.dp)
    ) {
        val title = poll.title
        if (!title.isNullOrEmpty()) {
            Text(
                text = title,
                fontSize = 16.sp,
                color = colors.textPrimary,
                modifier = Modifier.fillMaxWidth()
            )
            HorizontalDivider(
                thickness = 1.dp,
                color = colors.border,
                modifier = Modifier.padding(top = 12.dp)
            )
        }

        if (showResults) {
            ResultsList(poll)
            HorizontalDivider(
                thickness = 1.dp,
                color = colors.border,
                modifier = Modifier.padding(top = 4.dp)
            )
            ResultsFooter(voters = poll.voters, onBack = { showResults = false })
        } else {
            OptionsList(
                poll = poll,
                pending = pending,
                onOptionTap = { optionId ->
                    if (!isVotable(poll)) return@OptionsList
                    if (isMultiple(poll)) {
                        // 多选:先攒本地选中,点「投票」统一提交。
                        pending = toggle(pending, optionId)
                    } else {
                        // regular:点击即投并切结果态。
                        showResults = true
                        onVote(listOf(optionId))
                    }
                }
            )
            HorizontalDivider(
                thickness = 1.dp,
                color = colors.border,
                modifier = Modifier.padding(top = 4.dp)
            )
            InfoRow(isPublic = poll.isPublic, voters = poll.voters)
            ButtonsRow(
                poll = poll,
                pending = pending,
                onShowResults = { showResults = true },
                onRemove = {
                    showResults = false
                    pending = emptyList()
                    onRemoveVote()
                },
                onCommitMultiple = {
                    val ids = pending.toList()
                    showResults = true
                    pending = emptyList()
                    onVote(ids)
                }
            )
        }
    }
}

@Composable
private fun OptionsList(
    poll: Poll,
    pending: List<String>,
    onOptionTap: (String) -> Unit
) {
    val colors = jetdoColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    ) {
        poll.options.forEach { option ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOptionTap(option.id) }
                    .padding(vertical = 9.dp)
            ) {
                OptionCircle(marked = isMarked(poll, pending, option.id))
                Text(
                    text = option.label,
                    fontSize = 16.sp,
                    color = colors.textPrimary,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun OptionCircle(marked: Boolean) {
    val colors = jetdoColors
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(if (marked) colors.textPrimary else Color.Transparent)
            .border(1.5.dp, colors.textPrimary, CircleShape)
    )
}

@Composable
private fun ResultsList(poll: Poll) {
    val colors = jetdoColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
    ) {
        poll.options.forEachIndexed { index, option ->
            val chosen = option.id in poll.chosenOptionIds
            val percent = PollMutation.percentOf(poll, option)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (index > 0) 14.dp else 0.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = option.label,
                        fontSize = 15.sp,
                        fontWeight = if (chosen) FontWeight.Medium else FontWeight.Normal,
                        color = colors.textPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Text("$percent%", fontSize = 14.sp, color = colors.textSecondary)
                    Text(stringResource(R.string.poll_option_votes, option.votes), fontSize = 13.sp, color = colors.textSecondary)
                }
                // 进度条:灰底 + 按占比宽度的填充(已投项高亮蓝)。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(colors.topicDetailPageBg)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction = percent / 100f)
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (chosen) BarFillChosen else colors.textTertiary)
                    )
                }
            }
        }
    }
}

/** 信息行(投票态):公开/保密 | N 投票人。 */
@Composable
private fun InfoRow(isPublic: Boolean, voters: Int) {
    val colors = jetdoColors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            FaIcon("eye", colors.textSecondary, 15.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (isPublic) stringResource(R.string.poll_public) else stringResource(R.string.poll_secret),
                fontSize = 14.sp,
                color = colors.textSecondary
            )
        }
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(16.dp)
                .background(colors.border)
        )
        Text(
            text = stringResource(R.string.poll_voters, voters),
            fontSize = 14.sp,
            color = colors.textSecondary,
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp)
        )
    }
}

/** 按钮行(投票态):结果 [+ 撤消投票] / 多选提交。 */
@Composable
private fun ButtonsRow(
    poll: Poll,
    pending: List<String>,
    onShowResults: () -> Unit,
    onRemove: () -> Unit,
    onCommitMultiple: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
    ) {
        val voted = poll.chosenOptionIds.isNotEmpty()
        val resultsVisible = canSeeResults(poll)

        if (resultsVisible) {
            PollButton(iconId = "chart-bar", label = stringResource(R.string.common_results), flexible = false, onClick = onShowResults)
        }
        when {
            voted -> {
                if (resultsVisible) Spacer(Modifier.width(10.dp))
                PollButton(
                    iconId = "arrow-rotate-left",
                    label = stringResource(R.string.poll_undo_vote),
                    flexible = true,
                    onClick = onRemove
                )
            }

            isMultiple(poll) && pending.isNotEmpty() -> {
                Spacer(Modifier.width(10.dp))
                PollButton(iconId = "check", label = stringResource(R.string.poll_title), flexible = true, onClick = onCommitMultiple)
            }
        }
    }
}

/** 底部(结果态):< 返回 | N 投票人。 */
@Composable
private fun ResultsFooter(voters: Int, onBack: () -> Unit) {
    val colors = jetdoColors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .border(1.dp, colors.border, RoundedCornerShape(ButtonRadius))
                .clickable { onBack() }
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            FaIcon("angle-left", colors.textSecondary, 16.dp)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.common_back), fontSize = 14.sp, color = colors.textSecondary)
        }
        Spacer(Modifier.weight(1f))
        Text(stringResource(R.string.poll_voters, voters), fontSize = 14.sp, color = colors.textSecondary)
    }
}

/**
 * 按钮。`flexible` 时撑满剩余宽度 —— 必须声明为 [androidx.compose.foundation.layout.RowScope]
 * 扩展才能调用 `weight`(weight 只在 Row 作用域内可见)。
 */
@Composable
private fun androidx.compose.foundation.layout.RowScope.PollButton(
    iconId: String,
    label: String,
    flexible: Boolean,
    onClick: () -> Unit
) {
    val colors = jetdoColors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .then(if (flexible) Modifier.weight(1f) else Modifier)
            .border(1.dp, colors.border, RoundedCornerShape(ButtonRadius))
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 9.dp)
    ) {
        // 按钮文字/图标用正文主体色,与正文一致;非次要 meta 灰。
        FaIcon(iconId, colors.textBody, 15.dp)
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 14.sp, color = colors.textBody)
    }
}

// ==================== 状态判断(逐条对齐鸿蒙 PollView) ====================

private fun isMultiple(poll: Poll): Boolean = poll.type == "multiple"

/** 可投票:投票未关闭且当前用户尚未投过。 */
private fun isVotable(poll: Poll): Boolean =
    poll.status == "open" && poll.chosenOptionIds.isEmpty()

/** 选项圈是否点亮:已投看 chosen;多选未提交看本地 pending。 */
private fun isMarked(poll: Poll, pending: List<String>, optionId: String): Boolean =
    if (poll.chosenOptionIds.isNotEmpty()) optionId in poll.chosenOptionIds
    else isMultiple(poll) && optionId in pending

/** 能否查看结果:results=always、已投、或已关闭。 */
private fun canSeeResults(poll: Poll): Boolean =
    poll.results == "always" || poll.chosenOptionIds.isNotEmpty() || poll.status == "closed"

private fun toggle(list: List<String>, value: String): List<String> =
    if (value in list) list - value else list + value