package com.arkdo.android.ui.bookmarks

import androidx.compose.ui.res.stringResource
import com.arkdo.android.R
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.arkdo.android.core.data.bookmark.BookmarkItem
import com.arkdo.android.core.data.bookmark.bookmarkTimeLabel
import com.arkdo.android.core.designsystem.FaIcon
import com.arkdo.android.core.model.HomeUser
import com.arkdo.android.data.models.TopicCategory
import com.arkdo.android.data.models.TopicTag
import com.arkdo.android.data.models.User
import com.arkdo.android.services.site.CategoryIndex
import com.arkdo.android.ui.components.TopicMetadataBadges
import com.arkdo.android.ui.components.UserAvatar
import com.arkdo.android.ui.theme.arkdoColors
import com.arkdo.android.ui.theme.isJetDoInDarkTheme

private val PagePadding = 16.dp

@Composable
fun BookmarksScreen(
    username: String,
    onNavigateBack: () -> Unit,
    onOpenTopic: (Long, String, Int?) -> Unit,
    viewModel: BookmarksViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val colors = arkdoColors
    var pendingDelete by remember { mutableStateOf<BookmarkItem?>(null) }

    LaunchedEffect(username) { viewModel.loadInitial(username) }
    LaunchedEffect(state.toast) {
        val message = state.toast ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        viewModel.consumeToast()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.pageBg)
    ) {
        val title = if (state.items.isNotEmpty()) stringResource(R.string.bookmarks_count, state.items.size) else stringResource(R.string.bookmarks_bookmark)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(56.dp)
                .padding(horizontal = 4.dp)
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = colors.textPrimary)
            }
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
        }
        HorizontalDivider(color = colors.border, thickness = 0.5.dp)

        when {
            state.items.isEmpty() && state.initialLoading -> Centered {
                CircularProgressIndicator(color = colors.ldAccent)
            }
            state.items.isEmpty() && state.error != null -> Centered {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.error ?: stringResource(R.string.bookmarks_load_failed), color = colors.textSecondary)
                    Text(
                        stringResource(R.string.common_retry),
                        color = colors.ldAccent,
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .clickable { viewModel.loadInitial(username) }
                    )
                }
            }
            state.items.isEmpty() -> Centered {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FaIcon("bookmark", colors.textFaint, 36.dp)
                    Text(stringResource(R.string.bookmarks_empty), fontSize = 16.sp, color = colors.textPrimary, modifier = Modifier.padding(top = 12.dp))
                    Text(
                        stringResource(R.string.bookmarks_hint),
                        fontSize = 13.sp,
                        color = colors.textFaint,
                        modifier = Modifier.padding(top = 6.dp, start = 32.dp, end = 32.dp)
                    )
                }
            }
            else -> PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = { viewModel.loadInitial(username) },
                modifier = Modifier.fillMaxSize()
            ) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.items, key = { "${it.id}_${it.pinned}" }) { item ->
                        BookmarkRow(
                            item = item,
                            categories = state.categories,
                            onOpen = {
                                onOpenTopic(
                                    item.topicId,
                                    item.title,
                                    item.linkedPostNumber.takeIf { it > 1 }
                                )
                            },
                            onTogglePin = { viewModel.togglePin(item, context) },
                            onDelete = { pendingDelete = item }
                        )
                        HorizontalDivider(color = colors.border, thickness = 1.dp)
                    }
                    item(key = "footer") {
                        LaunchedEffect(state.items.size, state.hasMore) {
                            if (!state.loadingMore && state.hasMore) viewModel.loadMore()
                        }
                        ListFooter(state.loadingMore, state.hasMore)
                    }
                }
            }
        }
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.bookmarks_delete)) },
            text = { Text(stringResource(R.string.bookmarks_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    viewModel.delete(item, context)
                }) { Text(stringResource(R.string.common_delete), color = arkdoColors.ldDanger) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

@Composable
private fun BookmarkRow(
    item: BookmarkItem,
    categories: List<TopicCategory>,
    onOpen: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = arkdoColors
    val pinnedBg = if (isJetDoInDarkTheme()) Color(0xFF101114) else Color(0xFFF6F6F6)
    val gold = if (isJetDoInDarkTheme()) Color(0xFFE0B84C) else Color(0xFFD4A017)
    var menuOpen by remember(item.id, item.pinned) { mutableStateOf(false) }
    val category = CategoryIndex.find(categories, item.categoryId)
    val parent = CategoryIndex.findParent(categories, item.categoryId)
    val time = bookmarkTimeLabel(item.createdAt)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (item.pinned) pinnedBg else colors.pageBg)
            .clickable(onClick = onOpen)
            .padding(start = PagePadding, end = PagePadding, top = 12.dp, bottom = 13.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            UserAvatar(user = item.author?.toAppUser(), avatarSize = 46.dp, modifier = Modifier.padding(end = 10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Top) {
                    if (item.pinned) {
                        FaIcon("bookmark", gold, 13.dp, Modifier.padding(end = 4.dp, top = 2.dp))
                    }
                    Text(
                        text = item.title,
                        fontSize = 17.sp,
                        color = colors.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (category != null || item.tags.isNotEmpty()) {
                    TopicMetadataBadges(
                        category = category,
                        parentCategory = parent,
                        tags = item.tags.map { TopicTag(id = it.id, name = it.name, slug = it.slug) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    )
                }
            }
        }
        if (item.excerpt.isNotEmpty()) {
            Text(
                text = buildAnnotatedString {
                    if (item.linkedPostNumber > 1) {
                        withStyle(SpanStyle(fontSize = 13.sp, color = colors.textFaint)) {
                            append("#${item.linkedPostNumber} · ")
                        }
                    }
                    withStyle(SpanStyle(fontSize = 14.sp, color = colors.textSecondary)) {
                        append(item.excerpt)
                    }
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 21.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
        ) {
            Text(stringResource(R.string.poll_reply_count, item.replyCount), fontSize = 13.sp, color = colors.textSecondary)
            Spacer(Modifier.weight(1f))
            if (time != null) {
                Text(time, fontSize = 13.sp, color = colors.textFaint)
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(28.dp)) {
                    FaIcon("ellipsis", colors.textFaint, 16.dp)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(if (item.pinned) stringResource(R.string.common_unpin) else stringResource(R.string.common_pin)) },
                        onClick = {
                            menuOpen = false
                            onTogglePin()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_delete), color = colors.ldDanger) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ListFooter(loadingMore: Boolean, hasMore: Boolean) {
    val colors = arkdoColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        when {
            loadingMore -> CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = colors.ldAccent,
                strokeWidth = 2.dp
            )
            !hasMore -> Text(stringResource(R.string.common_no_more), fontSize = 13.sp, color = colors.textFaint)
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

private fun HomeUser.toAppUser(): User = User(
    id = id,
    username = username,
    name = name,
    avatarTemplate = avatarTemplate,
    animatedAvatar = animatedAvatar
)
