package com.arkdo.android.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkdo.android.core.model.HomeCategory
import com.arkdo.android.core.model.SearchFilterOption
import com.arkdo.android.core.model.SearchFilters
import com.arkdo.android.core.model.SearchFiltersOptions

/**
 * 高级筛选面板(对齐鸿蒙 SearchFilterPanel)。
 *
 * 面板里改的是**草稿**,点「应用」才整体换新实例回传 —— 中途取消不留痕。
 * 各条件的 q 串拼法见 core:model 的 `buildSearchQuery`(逐条对齐 Discourse 官方语法)。
 */
@Composable
fun SearchFilterPanel(
    initial: SearchFilters,
    categories: List<HomeCategory>,
    tokens: SearchVisualTokens,
    onApply: (SearchFilters) -> Unit,
    onDismiss: () -> Unit
) {
    // 草稿:所有编辑都落在这份拷贝上,点「应用」才提交。
    var draft by remember(initial) { mutableStateOf(initial) }

    Column(modifier = Modifier.fillMaxWidth()) {
        // 头部:标题 + 重置/应用。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Text("高级筛选", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = tokens.textPrimary)
            Spacer(modifier = Modifier.weight(1f))
            Text(
                "重置",
                fontSize = 14.sp,
                color = tokens.textSecondary,
                modifier = Modifier
                    .clickable { draft = SearchFilters() }
                    .padding(8.dp)
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(tokens.ldAccent)
                    .clickable { onApply(draft) }
                    .padding(horizontal = 18.dp, vertical = 7.dp)
            ) {
                Text("应用", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White)
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = tokens.border)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // 分类:单选,再点一次取消。
            SectionTitle("版块", tokens)
            if (categories.isEmpty()) {
                HintText("暂无版块数据", tokens)
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    categories.forEach { category ->
                        FilterChip(
                            label = category.name,
                            selected = draft.categoryId == category.id,
                            tokens = tokens,
                            onClick = {
                                draft = if (draft.categoryId == category.id) {
                                    draft.copy(
                                        categoryId = -1,
                                        categorySlug = "",
                                        categoryParentSlug = "",
                                        categoryName = ""
                                    )
                                } else {
                                    val parent = categories.firstOrNull { it.id == category.parentCategoryId }
                                    draft.copy(
                                        categoryId = category.id,
                                        categorySlug = category.slug.orEmpty(),
                                        categoryParentSlug = parent?.slug.orEmpty(),
                                        categoryName = category.name
                                    )
                                }
                            }
                        )
                    }
                }
            }

            // 标签:输入后回车添加(按名字匹配,tags: 过滤器的值就是名字)。
            SectionTitle("标签", tokens)
            TagInput(
                tags = draft.tags,
                tokens = tokens,
                onAdd = { tag -> if (tag !in draft.tags) draft = draft.copy(tags = draft.tags + tag) },
                onRemove = { tag -> draft = draft.copy(tags = draft.tags - tag) }
            )
            if (draft.tags.size > 1) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    FilterChip(
                        label = "同时包含全部",
                        selected = draft.tagsMatchAll,
                        tokens = tokens,
                        onClick = { draft = draft.copy(tagsMatchAll = !draft.tagsMatchAll) }
                    )
                }
            }

            // 发帖人。
            SectionTitle("发帖人", tokens)
            PlainInput(
                value = draft.username,
                placeholder = "用户名（不带 @）",
                tokens = tokens,
                onValueChange = { draft = draft.copy(username = it.trim()) }
            )

            // 搜索范围:需要登录的两组与所有人可用的那组合并展示。
            SectionTitle("范围", tokens)
            MultiChips(
                options = SearchFiltersOptions.IN_USER,
                selected = draft.inFilters,
                tokens = tokens,
                onToggle = { value -> draft = draft.copy(inFilters = draft.inFilters.toggle(value)) }
            )
            MultiChips(
                options = SearchFiltersOptions.IN_POST,
                selected = draft.inFilters,
                tokens = tokens,
                onToggle = { value -> draft = draft.copy(inFilters = draft.inFilters.toggle(value)) }
            )

            // 状态:单选。
            SectionTitle("状态", tokens)
            SingleChips(
                options = SearchFiltersOptions.STATUS,
                selected = draft.status,
                tokens = tokens,
                onSelect = { value -> draft = draft.copy(status = if (draft.status == value) "" else value) }
            )

            // 时间:快捷区间(折算成 after:绝对日期)。
            SectionTitle("发布时间", tokens)
            SingleChips(
                options = SearchFiltersOptions.TIME_PRESETS,
                selected = "",
                tokens = tokens,
                onSelect = { days ->
                    draft = draft.copy(
                        timeWhen = SearchFiltersOptions.TIME_AFTER,
                        timeDate = SearchFiltersOptions.daysAgoToSearchDate(days.toInt())
                    )
                }
            )
            if (draft.timeDate.isNotEmpty()) {
                HintText("当前：${draft.timeWhen}:${draft.timeDate}", tokens)
            }

            // 帖数 / 浏览量区间,只收数字(服务端正则 \d+)。
            SectionTitle("帖数", tokens)
            RangeInputs(
                min = draft.minPosts,
                max = draft.maxPosts,
                tokens = tokens,
                onMinChange = { draft = draft.copy(minPosts = it) },
                onMaxChange = { draft = draft.copy(maxPosts = it) }
            )

            SectionTitle("浏览量", tokens)
            RangeInputs(
                min = draft.minViews,
                max = draft.maxViews,
                tokens = tokens,
                onMinChange = { draft = draft.copy(minViews = it) },
                onMaxChange = { draft = draft.copy(maxViews = it) }
            )

            // 排序:空值 = 相关性(不拼 order token)。
            SectionTitle("排序", tokens)
            SingleChips(
                options = SearchFiltersOptions.ORDER,
                selected = draft.order,
                tokens = tokens,
                onSelect = { value -> draft = draft.copy(order = value) }
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

private fun List<String>.toggle(value: String): List<String> =
    if (value in this) this - value else this + value

@Composable
private fun SectionTitle(title: String, tokens: SearchVisualTokens) {
    Text(
        text = title,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        color = tokens.textPrimary,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
    )
}

@Composable
private fun HintText(text: String, tokens: SearchVisualTokens) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = tokens.textFaint,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun FilterChip(
    label: String,
    selected: Boolean,
    tokens: SearchVisualTokens,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) tokens.ldAccent else tokens.fillSubtle)
            .border(
                width = 0.5.dp,
                color = if (selected) tokens.ldAccent else tokens.border,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = if (selected) Color.White else tokens.textPrimary
        )
    }
}

@Composable
private fun SingleChips(
    options: List<SearchFilterOption>,
    selected: String,
    tokens: SearchVisualTokens,
    onSelect: (String) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        options.forEach { option ->
            FilterChip(
                label = option.label,
                selected = selected == option.value && option.value.isNotEmpty(),
                tokens = tokens,
                onClick = { onSelect(option.value) }
            )
        }
    }
}

@Composable
private fun MultiChips(
    options: List<SearchFilterOption>,
    selected: List<String>,
    tokens: SearchVisualTokens,
    onToggle: (String) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    ) {
        options.forEach { option ->
            FilterChip(
                label = option.label,
                selected = option.value in selected,
                tokens = tokens,
                onClick = { onToggle(option.value) }
            )
        }
    }
}

@Composable
private fun TagInput(
    tags: List<String>,
    tokens: SearchVisualTokens,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }

    PlainInput(
        value = text,
        placeholder = "输入标签后回车添加",
        tokens = tokens,
        onValueChange = { text = it },
        onDone = {
            val trimmed = text.trim()
            if (trimmed.isNotEmpty()) {
                onAdd(trimmed)
                text = ""
            }
        }
    )

    if (tags.isNotEmpty()) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        ) {
            tags.forEach { tag ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(tokens.ldAccent)
                        .clickable { onRemove(tag) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(tag, fontSize = 13.sp, color = Color.White)
                    Text(" ×", fontSize = 13.sp, color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun RangeInputs(
    min: String,
    max: String,
    tokens: SearchVisualTokens,
    onMinChange: (String) -> Unit,
    onMaxChange: (String) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(modifier = Modifier.weight(1f)) {
            PlainInput(
                value = min,
                placeholder = "最小",
                tokens = tokens,
                numeric = true,
                onValueChange = { onMinChange(SearchFiltersOptions.digitsOnly(it)) }
            )
        }
        Text(
            "—",
            fontSize = 14.sp,
            color = tokens.textFaint,
            modifier = Modifier.padding(horizontal = 10.dp)
        )
        Box(modifier = Modifier.weight(1f)) {
            PlainInput(
                value = max,
                placeholder = "最大",
                tokens = tokens,
                numeric = true,
                onValueChange = { onMaxChange(SearchFiltersOptions.digitsOnly(it)) }
            )
        }
    }
}

@Composable
private fun PlainInput(
    value: String,
    placeholder: String,
    tokens: SearchVisualTokens,
    onValueChange: (String) -> Unit,
    numeric: Boolean = false,
    onDone: (() -> Unit)? = null
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, fontSize = 13.sp, color = tokens.textFaint) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
            imeAction = if (onDone != null) androidx.compose.ui.text.input.ImeAction.Done
            else androidx.compose.ui.text.input.ImeAction.Next
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
            onDone = { onDone?.invoke() }
        ),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = tokens.fillSubtle,
            unfocusedContainerColor = tokens.fillSubtle,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedTextColor = tokens.textPrimary,
            unfocusedTextColor = tokens.textPrimary
        ),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
    )
}