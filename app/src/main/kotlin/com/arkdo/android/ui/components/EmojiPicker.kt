package com.arkdo.android.ui.components

import androidx.compose.ui.res.stringResource
import com.arkdo.android.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.arkdo.android.services.content.EmojiGroup
import com.arkdo.android.services.content.EmojiItem
import com.arkdo.android.services.content.emojiCatalogRevision
import com.arkdo.android.services.content.getEmojiGroups
import com.arkdo.android.ui.theme.arkdoColors

// ==========================================================================
// 表情选择器，对齐鸿蒙 views/components/EmojiPicker.ets。
//
// 结构：顶行 = 横向滚动的分组 tab + 右侧整段退格键；下面 = 7 列网格（Lazy，
// 表情表可能两千多条，必须惰性）。
//
// 与鸿蒙的一处实现差异：那边在 aboutToAppear 里读一次分组快照，如果面板打开得比
// 表情表拉到得早，就一直是空的、得重开才能看到内容；这里订阅 emojiCatalogRevision，
// 表一到就自动刷新。
// ==========================================================================

/** 网格列数（对齐鸿蒙 EMOJI_COLUMNS = repeat(7, 1fr)）。 */
private const val EMOJI_COLUMNS = 7

private const val TAB_ROW_HEIGHT = 42
private const val TAB_HEIGHT = 34
private const val CELL_HEIGHT = 44
private const val EMOJI_IMAGE_SIZE = 30

/** 分组 key → 中文名。对齐鸿蒙 EmojiPicker.groupLabel；未列出的组直接用 key。 */
@Composable
private fun groupLabel(key: String): String = when (key) {
    "smileys_&_emotion" -> stringResource(R.string.emoji_cat_smileys)
    "people_&_body" -> stringResource(R.string.filter_users)
    "animals_&_nature" -> stringResource(R.string.emoji_cat_nature)
    "food_&_drink" -> stringResource(R.string.emoji_cat_food)
    "travel_&_places" -> stringResource(R.string.emoji_cat_travel)
    "activities" -> stringResource(R.string.user_activity_tab)
    "objects" -> stringResource(R.string.emoji_cat_objects)
    "symbols" -> stringResource(R.string.emoji_cat_symbols)
    "flags" -> stringResource(R.string.emoji_cat_flags)
    else -> key
}

/**
 * @param modifier 由调用方给定高度——面板高度对齐软键盘高度（见 CommentInputOverlay）。
 * @param onSelect 点某个表情：调用方负责把 `:name:` 插到光标处。
 * @param onDelete 点退格：调用方负责按「整段短码优先」删除（见 normalizeEmojiDeletion）。
 */
@Composable
fun EmojiPicker(
    onSelect: (EmojiItem) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = arkdoColors
    val revision by emojiCatalogRevision.collectAsState()
    val groups: List<EmojiGroup> = remember(revision) { getEmojiGroups() }

    var selectedIndex by remember(revision) { mutableIntStateOf(0) }
    // 切站/重载后分组数可能变少，索引会越界（0 组时落到 0，下面按空表分支处理）。
    val activeIndex = selectedIndex.coerceIn(0, (groups.size - 1).coerceAtLeast(0))

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.topicDetailPageBg)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(TAB_ROW_HEIGHT.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(TAB_ROW_HEIGHT.dp)
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                groups.forEachIndexed { index, group ->
                    val selected = index == activeIndex
                    Box(
                        modifier = Modifier
                            .height(TAB_HEIGHT.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (selected) colors.immersiveInputFieldBg else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { selectedIndex = index }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = groupLabel(group.key),
                            fontSize = 13.sp,
                            color = if (selected) colors.ldAccent else colors.textSecondary,
                            maxLines = 1
                        )
                    }
                }
            }

            // 退格：整段删短码（对齐鸿蒙右侧的 ⌫ 键）。用字符而非图标——项目图标集里
            // 没有 delete/backspace 这一类，FaIcon 找不到名字会静默不画。
            Box(
                modifier = Modifier
                    .width(44.dp)
                    .height(TAB_ROW_HEIGHT.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "⌫", fontSize = 22.sp, color = colors.textSecondary)
            }
        }

        HorizontalDivider(thickness = 0.5.dp, color = colors.border)

        if (groups.isEmpty()) {
            // 表还没拉到时给一行说明，而不是一片空白（用户会以为面板坏了）。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Text(
                    text = stringResource(R.string.emoji_loading),
                    fontSize = 13.sp,
                    color = colors.textTertiary,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(EMOJI_COLUMNS),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                val items = groups[activeIndex].items
                items(items = items, key = { "${it.groupKey}-${it.name}" }) { item ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(CELL_HEIGHT.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onSelect(item) },
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = item.url,
                            contentDescription = ":${item.name}:",
                            modifier = Modifier.size(EMOJI_IMAGE_SIZE.dp)
                        )
                    }
                }
            }
        }
    }
}
