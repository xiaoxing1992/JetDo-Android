package com.arkdo.android.ui.components

import androidx.compose.ui.res.stringResource
import com.arkdo.android.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.arkdo.android.data.models.PostReaction
import com.arkdo.android.data.models.ReactionUser
import com.arkdo.android.data.models.User
import com.arkdo.android.ui.theme.arkdoColors

// 帖子「谁回应了」底部弹层内容,对齐鸿蒙 ReactionUsersSheet.ets:
// 顶部分类 chips(「所有」+ 按表情,客户端过滤不重发请求) + 用户名单(跨页累积) + 五态 footer。
// 请求与分页状态由 TopicDetailViewModel 持有,这里只渲染与发出交互事件。

/** chips 上展示的分类:count>0 且本地有对应表情图(对齐鸿蒙 visibleReactions 的过滤)。 */
private fun visibleReactions(reactions: List<PostReaction>): List<PostReaction> =
    reactions.filter { it.count > 0 && reactionEmojiAsset(it.id) != null }

@Composable
fun ReactionUsersSheetContent(
    reactions: List<PostReaction>,
    totalRows: Int,
    users: List<ReactionUser>,
    selectedReaction: String,
    initialLoading: Boolean,
    loadMoreLoading: Boolean,
    hasMore: Boolean,
    initialError: String?,
    loadMoreError: String?,
    onSelectReaction: (String) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onOpenUser: (User) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = arkdoColors
    val filters = remember(reactions) { visibleReactions(reactions) }
    val filteredUsers = remember(users, selectedReaction) {
        if (selectedReaction.isEmpty()) users else users.filter { it.reaction == selectedReaction }
    }
    // 弹层内容限高:不给上限的话 LazyColumn 处于无限高约束,全部条目一次性展开,
    // 滚动的是 ModalBottomSheet 外壳而非列表自身 → 触底信号永不触发(实测踩坑)。
    val sheetMaxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.72f

    Column(modifier = modifier.fillMaxWidth().heightIn(max = sheetMaxHeight)) {
        Text(
            text = stringResource(R.string.detail_reactions),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp)
        )

        // 分类 chips:横向滚动,「所有」在最前(计数为该楼总人数)。
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 12.dp)
        ) {
            ReactionFilterChip(
                label = stringResource(R.string.common_all),
                iconAsset = null,
                count = totalRows,
                selected = selectedReaction.isEmpty(),
                onSelect = { onSelectReaction("") }
            )
            filters.forEach { reaction ->
                ReactionFilterChip(
                    label = reaction.id,
                    iconAsset = reactionEmojiAsset(reaction.id),
                    count = reaction.count,
                    selected = selectedReaction == reaction.id,
                    onSelect = { onSelectReaction(reaction.id) }
                )
            }
        }

        HorizontalDivider(color = colors.border, thickness = 1.dp)

        when {
            initialLoading && users.isEmpty() -> SheetStateBlock {
                CircularProgressIndicator(color = colors.brand, modifier = Modifier.size(24.dp))
                Text(
                    text = stringResource(R.string.detail_loading_reactions),
                    fontSize = 14.sp,
                    color = colors.textTertiary,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
            initialError != null && users.isEmpty() -> SheetStateBlock {
                Text(
                    text = initialError,
                    fontSize = 14.sp,
                    color = colors.textSecondary,
                    maxLines = 2,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                SheetTextButton(stringResource(R.string.common_retry), onRetry, Modifier.padding(top = 12.dp))
            }
            users.isEmpty() && !hasMore -> SheetStateBlock {
                Text(
                    text = stringResource(R.string.detail_no_reactions),
                    fontSize = 14.sp,
                    color = colors.textTertiary,
                    modifier = Modifier.padding(top = 36.dp)
                )
            }
            filteredUsers.isEmpty() -> SheetStateBlock {
                Text(
                    text = stringResource(R.string.detail_reaction_not_loaded),
                    fontSize = 14.sp,
                    color = colors.textTertiary
                )
                if (hasMore) {
                    SheetTextButton(stringResource(R.string.common_continue_loading), onLoadMore, Modifier.padding(top = 12.dp))
                }
            }
            else -> {
                val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                // 触底自动翻页(对齐鸿蒙 Scroll.onReachEnd):倒数第 2 项(末位用户或 footer)
                // 进入视口即加载;「继续加载」按钮只作过滤空态/手动兜底。
                LaunchedEffect(listState, filteredUsers.size, hasMore, loadMoreLoading) {
                    snapshotFlow {
                        val info = listState.layoutInfo
                        (info.visibleItemsInfo.lastOrNull()?.index ?: -1) to info.totalItemsCount
                    }.collect { (lastVisible, total) ->
                        timber.log.Timber.d(
                            "[ArkDO][ReactionSheet] scroll last=%d total=%d hasMore=%s loading=%s",
                            lastVisible, total, hasMore, loadMoreLoading
                        )
                        if (hasMore && !loadMoreLoading && total > 0 && lastVisible >= total - 2) {
                            onLoadMore()
                        }
                    }
                }
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    itemsIndexed(filteredUsers, key = { _, u -> "${u.user.id}:${u.reaction}" }) { _, user ->
                        ReactionUserRow(user = user, onOpenUser = onOpenUser)
                    }
                    item {
                        ReactionLoadFooter(
                            loadMoreLoading = loadMoreLoading,
                            loadMoreError = loadMoreError,
                            hasMore = hasMore,
                            onLoadMore = onLoadMore,
                            onRetry = onRetry
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReactionFilterChip(
    label: String,
    iconAsset: String?,
    count: Int,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val colors = arkdoColors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(if (selected) colors.brand else colors.surfaceCard)
            .border(
                width = 1.dp,
                color = if (selected) colors.brand else colors.border,
                shape = RoundedCornerShape(17.dp)
            )
            .clickable(onClick = onSelect)
            .padding(horizontal = 12.dp)
    ) {
        if (iconAsset != null) {
            AsyncImage(
                model = "file:///android_asset/$iconAsset",
                contentDescription = label,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(5.dp))
        } else {
            Text(
                text = label,
                fontSize = 14.sp,
                color = if (selected) androidx.compose.ui.graphics.Color.White else colors.textSecondary
            )
            Spacer(modifier = Modifier.width(5.dp))
        }
        Text(
            text = "$count",
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) androidx.compose.ui.graphics.Color.White else colors.textSecondary
        )
    }
}

@Composable
private fun ReactionUserRow(user: ReactionUser, onOpenUser: (User) -> Unit) {
    val colors = arkdoColors
    val iconAsset = reactionEmojiAsset(user.reaction)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp)
            .clickable { onOpenUser(user.user) }
            .padding(horizontal = 20.dp)
    ) {
        UserAvatar(user = user.user, avatarSize = 42.dp, showFlair = false)
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = user.user.name?.takeIf { it.isNotEmpty() } ?: user.user.username,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "@${user.user.username}",
                fontSize = 13.sp,
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (iconAsset != null) {
            AsyncImage(
                model = "file:///android_asset/$iconAsset",
                contentDescription = user.reaction,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/** footer 四态:加载中 / 加载失败重试 / 继续加载 / 已加载全部(对齐鸿蒙 LoadFooter)。 */
@Composable
private fun ReactionLoadFooter(
    loadMoreLoading: Boolean,
    loadMoreError: String?,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit
) {
    val colors = arkdoColors
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxWidth().height(54.dp)
    ) {
        when {
            loadMoreLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = colors.brand, modifier = Modifier.size(16.dp))
                Text(
                    text = stringResource(R.string.common_loading_more),
                    fontSize = 13.sp,
                    color = colors.textTertiary,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            loadMoreError != null -> SheetTextButton(stringResource(R.string.common_load_failed_tap), onRetry, Modifier.fillMaxWidth())
            hasMore -> SheetTextButton(stringResource(R.string.common_continue_loading), onLoadMore, Modifier.fillMaxWidth())
            else -> Text(
                text = stringResource(R.string.detail_reactions_loaded),
                fontSize = 13.sp,
                color = colors.textTertiary
            )
        }
    }
}

@Composable
private fun SheetTextButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .heightIn(min = 34.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, arkdoColors.border, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(text = label, fontSize = 13.sp, color = arkdoColors.brand)
    }
}

/** 状态占位居中块(初始加载/错误/空态共用)。 */
@Composable
private fun SheetStateBlock(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp)
    ) {
        content()
    }
}
