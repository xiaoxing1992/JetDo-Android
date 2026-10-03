package com.arkdo.android.ui.components

import androidx.compose.ui.res.stringResource
import com.arkdo.android.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkdo.android.core.model.RelativeTime
import com.arkdo.android.core.designsystem.FaIcon
import com.arkdo.android.data.models.PendingPost
import com.arkdo.android.data.models.User
import com.arkdo.android.ui.theme.arkdoColors

// 待审核帖子卡片,对齐鸿蒙 PendingPostCard.ets(官方 templates/topic.gjs 的 .pending-posts > .reviewable-item):
// 「需要审批」标签 + 相对时间 / 当前用户头像昵称 / 正文 / 删除按钮。
//
// 三条服务端契约约束(改动前先看清楚):
// 1. TopicPendingPostSerializer 只下发 id/raw/created_at,没有作者字段——按定义只有本人能看到
//    自己的待审帖,作者一律取当前登录用户(官方前端同样直接用 currentUser 画头像昵称)。
// 2. raw 是 Markdown 原文,服务端不给 cooked;按纯文本直出(保留换行)——待审内容基本都是
//    刚发出去的短回复,这个取舍成本极低。
// 3. 它不是楼层:不占楼号、不进 post_stream、不参与已读上报与 scrubber 计数。
@Composable
fun PendingPostCard(
    pending: PendingPost,
    currentUser: User?,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = arkdoColors
    Column(modifier = modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 12.dp, bottom = 12.dp)) {
        // 元信息行:「需要审批」胶囊 + 相对时间。刻意不用 warning 橙——官方不把"排队待审"当告警。
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.lottery_needs_review),
                fontSize = 11.sp,
                color = colors.textSecondary,
                modifier = Modifier
                    .background(colors.fillSubtle, RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
            Text(
                text = RelativeTime.fromNow(pending.createdAt),
                fontSize = 11.sp,
                color = colors.textTertiary,
                modifier = Modifier.padding(start = 10.dp)
            )
        }

        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
        ) {
            UserAvatar(user = currentUser, avatarSize = 40.dp)
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    text = currentUser?.name?.takeIf { it.isNotEmpty() } ?: currentUser?.username ?: "",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // raw 直出:不 trim 掉换行,Markdown 原文里的分段对作者自己是有意义的。
                Text(
                    text = pending.raw,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    color = colors.textBody,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                )
            }
        }

        // 删除(撤回)按钮:对齐官方 .reviewable-actions 的 btn-danger——实心红底、白字、带垃圾桶图标。
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.ldDanger)
                    .clickable(onClick = onDelete)
                    .padding(start = 14.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
            ) {
                FaIcon("trash-can", Color.White, 14.dp)
                Text(
                    text = stringResource(R.string.common_delete),
                    fontSize = 13.sp,
                    color = Color.White,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
        }
    }
}
