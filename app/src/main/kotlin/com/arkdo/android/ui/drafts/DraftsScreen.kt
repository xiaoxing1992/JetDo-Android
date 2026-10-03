package com.arkdo.android.ui.drafts

import androidx.compose.ui.res.stringResource
import com.arkdo.android.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.widget.Toast
import com.arkdo.android.common.constants.AppConstants
import com.arkdo.android.core.model.RelativeTime
import com.arkdo.android.core.designsystem.FaIcon
import com.arkdo.android.data.models.Draft
import com.arkdo.android.data.models.DraftAction
import com.arkdo.android.services.draft.draftTagsAsSuggestions
import com.arkdo.android.services.draft.isNewTopicDraft
import com.arkdo.android.ui.composer.NewTopicResume
import com.arkdo.android.ui.theme.arkdoColors

/**
 * 我的草稿。新主题续写发帖页，回帖和编辑打开帖子并预填，私信等仍走网页。
 */
@Composable
fun DraftsScreen(
    onNavigateBack: () -> Unit,
    onResumeTopic: (NewTopicResume) -> Unit,
    onResumeReply: (topicId: Long, title: String, resume: DraftResume) -> Unit,
    onResumeEdit: (topicId: Long, title: String, resume: DraftResume) -> Unit,
    onOpenWeb: (String) -> Unit,
    username: String = "",
    viewModel: DraftsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val colors = arkdoColors
    val context = LocalContext.current
    var pendingDelete by remember { mutableStateOf<Draft?>(null) }
    val title = if (state.drafts.isNotEmpty()) stringResource(R.string.drafts_count, state.drafts.size) else stringResource(R.string.drafts_title)

    LaunchedEffect(Unit) { viewModel.loadInitial() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onShown()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        Toast.makeText(context, notice, Toast.LENGTH_SHORT).show()
        viewModel.consumeNotice()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.pageBg)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(56.dp)
                .padding(horizontal = 4.dp)
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
            }
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
        }
        HorizontalDivider(color = colors.border, thickness = 0.5.dp)

        when {
            state.drafts.isEmpty() && state.initialLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.ldAccent)
                }
            }
            state.drafts.isEmpty() && state.error != null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.error ?: stringResource(R.string.drafts_load_failed), color = colors.textSecondary)
                        Text(
                            stringResource(R.string.common_retry),
                            color = colors.ldAccent,
                            modifier = Modifier.padding(top = 12.dp).clickable { viewModel.loadInitial() }
                        )
                    }
                }
            }
            state.drafts.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FaIcon("pen-to-square", colors.textTertiary, 36.dp)
                        Text(
                            stringResource(R.string.drafts_empty),
                            fontSize = 16.sp,
                            color = colors.textPrimary,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                        Text(
                            stringResource(R.string.drafts_hint),
                            fontSize = 13.sp,
                            color = colors.textTertiary,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
            else -> {
                val listState = rememberLazyListState()
                PullToRefreshBox(
                    isRefreshing = state.refreshing,
                    onRefresh = { viewModel.loadInitial() },
                    modifier = Modifier.fillMaxSize()
                ) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(state.drafts, key = { _, item -> item.draftKey }) { index, item ->
                            DraftRow(
                                item = item,
                                deleting = state.deletingKey == item.draftKey,
                                onOpen = {
                                    if (state.deletingKey == item.draftKey) return@DraftRow
                                    openDraft(item, username, onResumeTopic, onResumeReply, onResumeEdit, onOpenWeb)
                                },
                                onDelete = { pendingDelete = item }
                            )
                            if (index < state.drafts.lastIndex) {
                                HorizontalDivider(color = colors.border, thickness = 1.dp)
                            }
                        }
                        item(key = "footer") {
                            LaunchedEffect(state.drafts.size) {
                                if (!state.loadingMore && state.hasMore) viewModel.loadMore()
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                when {
                                    state.loadingMore -> CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = colors.ldAccent,
                                        strokeWidth = 2.dp
                                    )
                                    !state.hasMore -> Text(stringResource(R.string.common_no_more), fontSize = 13.sp, color = colors.textFaint)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { draft ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.drafts_delete)) },
            text = { Text(stringResource(R.string.drafts_delete_confirm, draft.displayTitle)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(draft)
                    pendingDelete = null
                }) { Text(stringResource(R.string.common_delete), color = colors.ldDanger) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

@Composable
private fun DraftRow(
    item: Draft,
    deleting: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = arkdoColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                actionLabel(item.action),
                fontSize = 11.sp,
                color = colors.ldAccent,
                modifier = Modifier
                    .background(colors.infoSoftBg, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
            Text(
                RelativeTime.fromNow(item.createdAt),
                fontSize = 12.sp,
                color = colors.textFaint,
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(start = 8.dp)
            )
            if (deleting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = colors.ldDanger,
                    strokeWidth = 2.dp
                )
            } else {
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    FaIcon("trash-can", colors.ldDanger, 17.dp)
                }
            }
        }
        Text(
            item.displayTitle,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp)
        )
        val excerpt = item.excerpt
        if (excerpt.isNotEmpty()) {
            Text(
                excerpt,
                fontSize = 14.sp,
                color = colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun actionLabel(action: DraftAction): String = when (action) {
    DraftAction.NEW_TOPIC -> stringResource(R.string.filter_topics)
    DraftAction.REPLY -> stringResource(R.string.user_replies_tab)
    DraftAction.PRIVATE_MESSAGE -> stringResource(R.string.user_pm)
    DraftAction.EDIT -> stringResource(R.string.common_edit)
    DraftAction.OTHER -> stringResource(R.string.drafts_draft)
}

/** 点击分流，对齐鸿蒙 DraftsPage.openDraft。 */
private fun openDraft(
    item: Draft,
    username: String,
    onResumeTopic: (NewTopicResume) -> Unit,
    onResumeReply: (topicId: Long, title: String, resume: DraftResume) -> Unit,
    onResumeEdit: (topicId: Long, title: String, resume: DraftResume) -> Unit,
    onOpenWeb: (String) -> Unit
) {
    if (item.isNewTopicDraft()) {
        onResumeTopic(
            NewTopicResume(
                draftKey = item.draftKey,
                sequence = item.sequence,
                title = item.title,
                body = item.reply,
                categoryId = item.categoryId,
                tags = draftTagsAsSuggestions(item.tags)
            )
        )
        return
    }
    val topicId = item.topicId
    if (item.action == DraftAction.EDIT) {
        val postId = item.postId
        val original = item.originalText
        if (topicId != null && postId != null && original != null) {
            onResumeEdit(
                topicId,
                item.displayTitle,
                DraftResume(item.draftKey, item.sequence, item.reply, postId, original)
            )
            return
        }
    } else if (item.action == DraftAction.REPLY && topicId != null) {
        onResumeReply(
            topicId,
            item.displayTitle,
            DraftResume(item.draftKey, item.sequence, item.reply)
        )
        return
    }
    val path = if (username.isNotEmpty()) "/u/$username/activity/drafts" else "/my/activity/drafts"
    onOpenWeb("${AppConstants.BASE_URL}$path")
}
