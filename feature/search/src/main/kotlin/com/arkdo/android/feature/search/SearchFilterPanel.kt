package com.arkdo.android.feature.search

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkdo.android.core.designsystem.FaIcon
import com.arkdo.android.core.model.HomeCategory
import com.arkdo.android.core.model.SearchFilterOption
import com.arkdo.android.core.model.SearchFilters
import com.arkdo.android.core.model.SearchFiltersOptions
import com.arkdo.android.core.model.TagSuggestion
import java.util.Calendar
import java.util.TimeZone

/**
 * 高级筛选面板(对齐鸿蒙 SearchFilterPanel)。
 *
 * 面板内改的是一份草稿,点「应用」才回吐给 SearchOverlay 去拼 q、发请求;
 * 中途取消不留痕。分区顺序、控件规格、文案逐项照搬鸿蒙:
 * 分类(可展开树) → 标签(联想 + 任一/全部) → 发帖人 → 话题状态 → 发布时间
 * (晚于/早于 + 日期选择 + 快捷区间) → 帖子数/浏览量 → 搜索范围/与我相关 → 排序。
 *
 * chip 规格同鸿蒙 ChoiceChip compact 档:高 30、圆角 15、字号 13,
 * 选中 = brand 字色压 info_soft_bg(不是白字压实底),未选 = 正文色压 surface_muted。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchFilterPanel(
    initial: SearchFilters,
    categories: List<HomeCategory>,
    tokens: SearchVisualTokens,
    tagQuery: String,
    tagSuggestions: List<TagSuggestion>,
    isTagLoading: Boolean,
    onTagQueryChange: (String) -> Unit,
    onResetTags: () -> Unit,
    onApply: (SearchFilters) -> Unit,
    onDismiss: () -> Unit
) {
    // 草稿:所有编辑都落在这份拷贝上,点「应用」才提交(对齐鸿蒙 draft)。
    var draft by remember(initial) { mutableStateOf(initial) }
    var categoryExpanded by remember { mutableStateOf(false) }
    var expandedParentId by remember { mutableStateOf(-1L) }
    var showDatePicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 鸿蒙 sheet 定高 78%:顶上留一截背景透出来,不做成整页那样闷。
            .fillMaxHeight(0.78f)
            .padding(horizontal = 16.dp)
    ) {
        // 头部:标题 + 「重置」描边胶囊(纯文字链接太轻,不像可点的)。
        Column(modifier = Modifier.padding(top = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(
                    text = "高级筛选",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = tokens.textPrimary,
                    modifier = Modifier.weight(1f)
                )

                val hasAny = draft.hasAny()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .height(30.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(if (hasAny) tokens.infoSoftBg else tokens.surfaceMuted)
                        .border(
                            width = 0.5.dp,
                            color = if (hasAny) tokens.ldAccent else tokens.border,
                            shape = RoundedCornerShape(15.dp)
                        )
                        .clickable {
                            if (!hasAny) return@clickable
                            draft = SearchFilters()
                            // 对齐鸿蒙:重置同时清掉标签搜索词、回到热门标签。
                            onResetTags()
                        }
                        .padding(horizontal = 12.dp)
                ) {
                    FaIcon(
                        "arrow-rotate-left",
                        if (hasAny) tokens.ldAccent else tokens.textFaint,
                        12.dp
                    )
                    Text(
                        text = "重置",
                        fontSize = 13.sp,
                        color = if (hasAny) tokens.ldAccent else tokens.textFaint,
                        modifier = Modifier.padding(start = 5.dp)
                    )
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = tokens.border)
        }

        // 中段滚动区。
        Column(
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(top = 4.dp, bottom = 20.dp)
        ) {
            // ===== 分类:折叠树(摘要行 + 所有分类 + 顶层/子层,选中即收起) =====
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("分类", tokens)

                val chevronAngle by animateFloatAsState(
                    targetValue = if (categoryExpanded) 180f else 0f,
                    animationSpec = tween(220, easing = FastOutSlowInEasing),
                    label = "categoryChevron"
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(tokens.surfaceMuted)
                        .clickable { categoryExpanded = !categoryExpanded }
                        .padding(horizontal = 12.dp)
                ) {
                    Text(
                        text = if (draft.categoryId >= 0) draft.categoryName else "所有分类",
                        fontSize = 15.sp,
                        color = if (draft.categoryId >= 0) tokens.textPrimary else tokens.textFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    FaIcon("chevron-down", tokens.textFaint, 14.dp, Modifier.rotate(chevronAngle))
                }

                if (categoryExpanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(tokens.surfaceMuted)
                            .animateContentSize(tween(220, easing = FastOutSlowInEasing))
                    ) {
                        CategoryTreeRow(
                            label = "所有分类",
                            selected = draft.categoryId < 0,
                            tokens = tokens,
                            contentPaddingStart = 12.dp,
                            onSelect = {
                                draft = draft.copy(
                                    categoryId = -1,
                                    categorySlug = "",
                                    categoryParentSlug = "",
                                    categoryName = ""
                                )
                                categoryExpanded = false
                            }
                        )

                        val topLevel = categories.filter { it.parentCategoryId == null }
                        topLevel.forEach { top ->
                            val hasChildren = categories.any { it.parentCategoryId == top.id }
                            CategoryRow(
                                category = top,
                                tokens = tokens,
                                isChild = false,
                                hasChildren = hasChildren,
                                selected = draft.categoryId == top.id,
                                parentExpanded = expandedParentId == top.id,
                                onSelect = {
                                    draft = draft.copy(
                                        categoryId = top.id,
                                        categorySlug = top.slug.orEmpty(),
                                        categoryParentSlug = "",
                                        categoryName = top.name
                                    )
                                    categoryExpanded = false
                                },
                                onToggleChildren = {
                                    expandedParentId = if (expandedParentId == top.id) -1L else top.id
                                }
                            )
                            if (expandedParentId == top.id) {
                                categories.filter { it.parentCategoryId == top.id }.forEach { child ->
                                    CategoryRow(
                                        category = child,
                                        tokens = tokens,
                                        isChild = true,
                                        hasChildren = false,
                                        selected = draft.categoryId == child.id,
                                        parentExpanded = false,
                                        onSelect = {
                                            draft = draft.copy(
                                                categoryId = child.id,
                                                categorySlug = child.slug.orEmpty(),
                                                categoryParentSlug = top.slug.orEmpty(),
                                                categoryName = child.name
                                            )
                                            categoryExpanded = false
                                        },
                                        onToggleChildren = {}
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ===== 标签:联想搜索 + 已选 chip + 任一/全部 =====
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("标签", tokens, Modifier.weight(1f))
                    if (draft.tags.size > 1) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            MiniToggle("含任一", !draft.tagsMatchAll, tokens) {
                                draft = draft.copy(tagsMatchAll = false)
                            }
                            MiniToggle("全都含", draft.tagsMatchAll, tokens) {
                                draft = draft.copy(tagsMatchAll = true)
                            }
                        }
                    }
                }

                if (draft.tags.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        draft.tags.forEach { tag ->
                            RemovableTagChip(tag, tokens) {
                                val next = draft.tags - tag
                                // 少于两个标签时「任一/全部」之分不存在,一并复位。
                                draft = draft.copy(
                                    tags = next,
                                    tagsMatchAll = if (next.size < 2) false else draft.tagsMatchAll
                                )
                            }
                        }
                    }
                }

                // 标签搜索框:空 q 即热门标签前几个;联想走 /tags/filter/search。
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(tokens.surfaceMuted)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    FaIcon("magnifying-glass", tokens.textFaint, 14.dp)
                    BasicTextField(
                        value = tagQuery,
                        onValueChange = onTagQueryChange,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        textStyle = TextStyle(fontSize = 14.sp, color = tokens.textPrimary),
                        cursorBrush = SolidColor(tokens.ldAccent),
                        singleLine = true,
                        decorationBox = { innerField ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (tagQuery.isEmpty()) {
                                    Text("搜索标签", fontSize = 14.sp, color = tokens.textFaint, maxLines = 1)
                                }
                                innerField()
                            }
                        }
                    )
                    if (isTagLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = tokens.ldAccent,
                            strokeWidth = 2.dp
                        )
                    }
                }

                if (tagSuggestions.isEmpty() && !isTagLoading) {
                    Text(
                        text = if (tagQuery.trim().isNotEmpty()) "没有匹配的标签" else "暂无可选标签",
                        fontSize = 13.sp,
                        color = tokens.textFaint,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 14.dp)
                    )
                } else if (tagSuggestions.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(tokens.surfaceMuted)
                    ) {
                        tagSuggestions.forEach { tag ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (tag.name !in draft.tags) {
                                            draft = draft.copy(tags = draft.tags + tag.name)
                                        }
                                        // 选完回到默认候选列表,方便接着选第二个(对齐鸿蒙 addTag)。
                                        onResetTags()
                                    }
                                    .padding(horizontal = 12.dp, vertical = 9.dp)
                            ) {
                                Text(
                                    text = tag.name,
                                    fontSize = 14.sp,
                                    color = tokens.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(text = "×${tag.count}", fontSize = 12.sp, color = tokens.textFaint)
                            }
                        }
                    }
                }
            }

            // ===== 发帖人:@ 是一个整 token,掐掉空白与前导 @ =====
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("发帖人", tokens)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(tokens.surfaceMuted)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(text = "@", fontSize = 15.sp, color = tokens.textFaint)
                    BasicTextField(
                        value = draft.username,
                        onValueChange = { raw ->
                            draft = draft.copy(
                                username = raw.trim().replace(Regex("^@+"), "").replace(Regex("\\s+"), "")
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 4.dp),
                        textStyle = TextStyle(fontSize = 15.sp, color = tokens.textPrimary),
                        cursorBrush = SolidColor(tokens.ldAccent),
                        singleLine = true,
                        decorationBox = { innerField ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (draft.username.isEmpty()) {
                                    Text("用户名", fontSize = 15.sp, color = tokens.textFaint, maxLines = 1)
                                }
                                innerField()
                            }
                        }
                    )
                }
            }

            // ===== 话题状态:单选,再点一次取消 =====
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("话题状态", tokens)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SearchFiltersOptions.STATUS.forEach { option ->
                        FilterChip(
                            label = option.label,
                            selected = draft.status == option.value,
                            tokens = tokens
                        ) {
                            draft = draft.copy(
                                status = if (draft.status == option.value) "" else option.value
                            )
                        }
                    }
                }
            }

            // ===== 发布时间:晚于/早于 + 日期选择 + 快捷区间(折算成绝对日期拼 after:) =====
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("发布时间", tokens)

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MiniToggle("晚于", draft.timeWhen == SearchFiltersOptions.TIME_AFTER, tokens) {
                        draft = draft.copy(timeWhen = SearchFiltersOptions.TIME_AFTER)
                    }
                    MiniToggle("早于", draft.timeWhen == SearchFiltersOptions.TIME_BEFORE, tokens) {
                        draft = draft.copy(timeWhen = SearchFiltersOptions.TIME_BEFORE)
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(tokens.surfaceMuted)
                            .clickable { showDatePicker = true }
                            .padding(horizontal = 10.dp)
                    ) {
                        FaIcon("calendar-days", tokens.textFaint, 13.dp)
                        Text(
                            text = draft.timeDate.ifEmpty { "选择日期" },
                            fontSize = 14.sp,
                            color = if (draft.timeDate.isNotEmpty()) tokens.textPrimary else tokens.textFaint,
                            maxLines = 1
                        )
                    }

                    if (draft.timeDate.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clickable { draft = draft.copy(timeDate = "") },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = "✕", fontSize = 13.sp, color = tokens.textFaint)
                        }
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SearchFiltersOptions.TIME_PRESETS.forEach { preset ->
                        FilterChip(
                            label = preset.label,
                            selected = isTimePresetActive(draft, preset),
                            tokens = tokens
                        ) {
                            val date = SearchFiltersOptions.daysAgoToSearchDate(preset.value.toInt())
                            draft = if (
                                draft.timeWhen == SearchFiltersOptions.TIME_AFTER &&
                                draft.timeDate == date
                            ) {
                                // 再点一次已生效的快捷区间 = 取消。
                                draft.copy(timeDate = "")
                            } else {
                                draft.copy(timeWhen = SearchFiltersOptions.TIME_AFTER, timeDate = date)
                            }
                        }
                    }
                }
            }

            // ===== 帖子数 / 浏览量:只收数字(服务端正则 \d+) =====
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("帖子数", tokens)
                RangeRow(
                    min = draft.minPosts,
                    max = draft.maxPosts,
                    tokens = tokens,
                    onMinChange = { draft = draft.copy(minPosts = it) },
                    onMaxChange = { draft = draft.copy(maxPosts = it) }
                )
                SectionTitle("浏览量", tokens)
                RangeRow(
                    min = draft.minViews,
                    max = draft.maxViews,
                    tokens = tokens,
                    onMinChange = { draft = draft.copy(minViews = it) },
                    onMaxChange = { draft = draft.copy(maxViews = it) }
                )
            }

            // ===== 搜索范围 / 与我相关(in: 与 with:) =====
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("搜索范围", tokens)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SearchFiltersOptions.IN_POST.forEach { option ->
                        FilterChip(
                            label = option.label,
                            selected = option.value in draft.inFilters,
                            tokens = tokens
                        ) {
                            draft = draft.copy(inFilters = draft.inFilters.toggle(option.value))
                        }
                    }
                }

                SectionTitle("与我相关", tokens)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SearchFiltersOptions.IN_USER.forEach { option ->
                        FilterChip(
                            label = option.label,
                            selected = option.value in draft.inFilters,
                            tokens = tokens
                        ) {
                            draft = draft.copy(inFilters = draft.inFilters.toggle(option.value))
                        }
                    }
                }

                Text(
                    text = "「与我相关」需要登录后才有结果。",
                    fontSize = 12.sp,
                    color = tokens.textFaint
                )
            }

            // ===== 排序:空值 = 相关性(不拼 order token) =====
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("排序", tokens)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SearchFiltersOptions.ORDER.forEach { option ->
                        FilterChip(
                            label = option.label,
                            selected = draft.order == option.value && option.value.isNotEmpty(),
                            tokens = tokens
                        ) {
                            draft = draft.copy(order = option.value)
                        }
                    }
                }
            }
        }

        // 底部:取消 + 应用(带生效条数)。手势条留白让按钮不贴屏幕下沿。
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 28.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(tokens.surfaceMuted)
                    .clickable(onClick = onDismiss)
            ) {
                Text(text = "取消", fontSize = 15.sp, color = tokens.textSecondary)
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(2f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(tokens.ldAccent)
                    .clickable { onApply(draft) }
            ) {
                val count = draft.activeCount()
                Text(
                    text = if (count > 0) "应用 ($count)" else "应用",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
            }
        }
    }

    if (showDatePicker) {
        val todayYear = Calendar.getInstance().get(Calendar.YEAR)
        val todayUtc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_MONTH, 1)
        }.timeInMillis
        val initialMillis = parseSearchDateToUtcMillis(draft.timeDate)
            ?: System.currentTimeMillis()

        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = initialMillis,
            yearRange = 2013..todayYear,
            selectableDates = object : androidx.compose.material3.SelectableDates {
                // 对齐鸿蒙:起 2013-01-01,止今天。
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis < todayUtc
            }
        )

        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                            timeInMillis = millis
                        }
                        draft = draft.copy(timeDate = SearchFiltersOptions.formatSearchDate(cal))
                    }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }
}

// ========== 分类树行 ==========

/** 普通行(「所有分类」):无前导图标,名称 + 选中勾。 */
@Composable
private fun CategoryTreeRow(
    label: String,
    selected: Boolean,
    tokens: SearchVisualTokens,
    contentPaddingStart: androidx.compose.ui.unit.Dp,
    onSelect: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clickable(onClick = onSelect)
            .padding(start = contentPaddingStart, end = 12.dp)
    ) {
        Text(
            text = label,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) tokens.ldAccent else tokens.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            androidx.compose.material3.Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = tokens.ldAccent
            )
        }
    }
}

/**
 * 分类行。有子分类的父行:右侧箭头是 44dp 独立热区(点箭头展开,点名字选中父分类),
 * 子分类缩进 26(对齐鸿蒙 CategoryRow)。
 */
@Composable
private fun CategoryRow(
    category: HomeCategory,
    tokens: SearchVisualTokens,
    isChild: Boolean,
    hasChildren: Boolean,
    selected: Boolean,
    parentExpanded: Boolean,
    onSelect: () -> Unit,
    onToggleChildren: () -> Unit
) {
    val arrowAngle by animateFloatAsState(
        targetValue = if (parentExpanded) 90f else 0f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "parentArrow"
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clickable(onClick = onSelect)
            .padding(
                start = if (isChild) 26.dp else 12.dp,
                end = if (!isChild && hasChildren) 0.dp else 12.dp
            )
    ) {
        val categoryColor = parseHexColor(category.color)
        Box(
            modifier = Modifier.width(18.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(categoryColor)
            )
        }
        Text(
            text = category.name,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) tokens.ldAccent else tokens.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp)
        )
        if (selected) {
            androidx.compose.material3.Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = tokens.ldAccent
            )
        }
        if (!isChild && hasChildren) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .width(44.dp)
                    .height(44.dp)
                    .clickable(onClick = onToggleChildren)
            ) {
                FaIcon(
                    "chevron-right",
                    tokens.textFaint,
                    13.dp,
                    Modifier.rotate(arrowAngle)
                )
            }
        }
    }
}

// ========== 通用小控件 ==========

@Composable
private fun SectionTitle(
    title: String,
    tokens: SearchVisualTokens,
    modifier: Modifier = Modifier
) {
    Text(
        text = title,
        fontSize = 13.sp,
        color = tokens.textSecondary,
        modifier = modifier
    )
}

/** 规格同鸿蒙 ChoiceChip compact 档:高 30 / 圆角 15 / 字 13,选中 brand 字压 info_soft_bg。 */
@Composable
private fun FilterChip(
    label: String,
    selected: Boolean,
    tokens: SearchVisualTokens,
    onClick: () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(if (selected) tokens.infoSoftBg else tokens.surfaceMuted)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp)
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) tokens.ldAccent else tokens.textPrimary,
            maxLines = 1
        )
    }
}

/** 已选标签 chip:恒为选中态 + 尾部 ✕(对齐鸿蒙 removable 档,右内边距收一点)。 */
@Composable
private fun RemovableTagChip(
    tag: String,
    tokens: SearchVisualTokens,
    onRemove: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(tokens.infoSoftBg)
            .clickable(onClick = onRemove)
            .padding(start = 12.dp, end = 9.dp)
    ) {
        Text(
            text = tag,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = tokens.ldAccent,
            maxLines = 1
        )
        Text(text = " ✕", fontSize = 10.sp, color = tokens.ldAccent)
    }
}

/** 二态小切换(晚于/早于、含任一/全都含):13sp / 高 34 / 圆角 8。 */
@Composable
private fun MiniToggle(
    label: String,
    selected: Boolean,
    tokens: SearchVisualTokens,
    onTap: () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) tokens.infoSoftBg else tokens.surfaceMuted)
            .clickable(onClick = onTap)
            .padding(horizontal = 12.dp)
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) tokens.ldAccent else tokens.textSecondary
        )
    }
}

/** 最小—最大数字输入(高 38 / 圆角 8 / placeholder 最小值、最大值)。 */
@Composable
private fun RangeRow(
    min: String,
    max: String,
    tokens: SearchVisualTokens,
    onMinChange: (String) -> Unit,
    onMaxChange: (String) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        NumberField(value = min, placeholder = "最小值", tokens = tokens, onValue = onMinChange, modifier = Modifier.weight(1f))
        Text(text = "—", fontSize = 13.sp, color = tokens.textFaint)
        NumberField(value = max, placeholder = "最大值", tokens = tokens, onValue = onMaxChange, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun NumberField(
    value: String,
    placeholder: String,
    tokens: SearchVisualTokens,
    onValue: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    BasicTextField(
        value = value,
        onValueChange = { raw -> onValue(SearchFiltersOptions.digitsOnly(raw)) },
        modifier = modifier,
        textStyle = TextStyle(fontSize = 14.sp, color = tokens.textPrimary),
        cursorBrush = SolidColor(tokens.ldAccent),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        decorationBox = { innerField ->
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(tokens.surfaceMuted)
                    .padding(horizontal = 12.dp)
            ) {
                if (value.isEmpty()) {
                    Text(placeholder, fontSize = 14.sp, color = tokens.textFaint, maxLines = 1)
                }
                innerField()
            }
        }
    )
}

// ========== 工具 ==========

private fun List<String>.toggle(value: String): List<String> =
    if (value in this) this - value else this + value

/** 分类色:`RRGGBB`(无 # 前缀)→ Color;解析失败回退中性灰。 */
private fun parseHexColor(hex: String): Color = runCatching {
    Color(android.graphics.Color.parseColor("#${hex.removePrefix("#")}"))
}.getOrDefault(Color(0xFF6B7280))

/** 快捷区间是否正生效:after: + 同一天(对齐鸿蒙 isTimePresetActive)。 */
private fun isTimePresetActive(draft: SearchFilters, preset: SearchFilterOption): Boolean =
    draft.timeWhen == SearchFiltersOptions.TIME_AFTER &&
        draft.timeDate == SearchFiltersOptions.daysAgoToSearchDate(preset.value.toInt())

/** `YYYY-MM-DD` → UTC 毫秒(DatePicker 内部用 UTC 日界);解析失败返回 null。 */
private fun parseSearchDateToUtcMillis(date: String): Long? {
    if (date.length != 10) return null
    return runCatching {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(date.substring(0, 4).toInt(), date.substring(5, 7).toInt() - 1, date.substring(8, 10).toInt())
        }
        cal.timeInMillis
    }.getOrNull()
}
