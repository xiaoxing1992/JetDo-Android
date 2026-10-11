# 标签徽章不显示问题 - 调试指南

## 问题描述

首页话题卡片中的标签徽章没有显示。

## 已添加的调试日志

### 1. TopicService (数据源层) - 第 439-445 行
检查 API 返回的原始数据是否包含标签：
```kotlin
if (source.tags != null && source.tags.isNotEmpty()) {
    android.util.Log.d("TopicService", "Topic ${source.id}: Raw tags from API = ${source.tags.map { "${it.name}(${it.id})" }}")
    android.util.Log.d("TopicService", "Topic ${source.id}: Mapped tags = ${tags.map { it.name }}")
} else {
    android.util.Log.d("TopicService", "Topic ${source.id}: No tags in API response")
}
```

### 2. TopicMetadataBadges (组件层) - 第 47-52 行
检查标签是否传递到 UI 组件，以及图标配置是否加载：
```kotlin
if (tags.isNotEmpty()) {
    val loadedCount = TagIconRegistry.debugGetLoadedCount(context)
    android.util.Log.d("TopicMetadataBadges", "Tags: ${tags.map { it.name }}, Loaded icons: $loadedCount")
}
```

### 3. TagBadge (渲染层) - 第 147 行
检查每个标签的图标样式是否匹配：
```kotlin
android.util.Log.d("TagBadge", "Tag: ${tag.name}, IconStyle: $iconStyle, Icon: $icon")
```

## 排查步骤

### 步骤 1：安装应用并清空日志
```bash
./gradlew installDebug
adb logcat -c
```

### 步骤 2：启动应用并查看日志
```bash
adb logcat | grep -E "TopicService|TopicMetadataBadges|TagBadge"
```

### 步骤 3：分析日志输出

**场景 A：没有任何日志输出**
→ 应用可能没有正常运行，或者日志过滤条件不对

**场景 B：只看到 "No tags in API response"**
```
TopicService: Topic 12345: No tags in API response
```
→ **根本原因：API 返回的数据中没有 tags 字段**
→ 解决方案：检查 LinuxDo API 端点，确认是否需要额外参数才能返回标签

**场景 C：看到 "Raw tags from API" 但没有 "TopicMetadataBadges" 日志**
```
TopicService: Topic 12345: Raw tags from API = [病友(1), 抽奖(2)]
TopicService: Topic 12345: Mapped tags = [病友, 抽奖]
```
→ **根本原因：标签数据没有传递到 UI 层**
→ 解决方案：检查 HomeScreen 是否正确传递 `topic.tags` 给 TopicListItem

**场景 D：看到 "TopicMetadataBadges" 但 "Loaded icons: 0"**
```
TopicMetadataBadges: Tags: [病友, 抽奖], Loaded icons: 0
```
→ **根本原因：JSON 配置文件加载失败**
→ 解决方案：
  1. 检查 `res/raw/linuxdo_tag_icons.json` 文件是否存在
  2. 检查 JSON 格式是否正确
  3. 检查资源 ID `R.raw.linuxdo_tag_icons` 是否正确

**场景 E：看到 "TagBadge" 但 "IconStyle: null"**
```
TopicMetadataBadges: Tags: [patient], Loaded icons: 10
TagBadge: Tag: patient, IconStyle: null, Icon: null
```
→ **根本原因：标签名称不匹配 JSON 配置**
→ JSON 配置中是 `"病友"`，但 API 返回的是 `"patient"`
→ 解决方案：更新 JSON 配置文件，使用 API 返回的实际标签名称

**场景 F：一切正常但仍不显示**
```
TopicMetadataBadges: Tags: [病友], Loaded icons: 10
TagBadge: Tag: 病友, IconStyle: TagIconStyle(...), Icon: 🏥
```
→ 检查 UI 渲染逻辑，可能是布局或样式问题

## 可能的解决方案

### 方案 1：API 端点问题
如果 API 不返回标签，需要：
1. 检查鸿蒙版使用的具体 API 端点
2. 对比 Android 版和鸿蒙版的网络请求
3. 可能需要添加查询参数如 `include_tags=true`

### 方案 2：标签名称映射
如果标签名称不匹配（API 返回英文，配置文件用中文）：

**选项 A：** 使用 `tag.slug` 而不是 `tag.name`
```kotlin
// 在 TagIconRegistry.kt 中
fun getIconStyle(tagSlug: String): TagIconStyle? {
    return tagIconsCache[tagSlug]
}
```

**选项 B：** 创建双语映射表
```json
{
  "tags": [
    {
      "tagName": "病友",
      "tagSlug": "patient",
      "emoji": "🏥",
      "color": "#FF6B6B"
    }
  ]
}
```

### 方案 3：添加降级显示
如果图标配置找不到，显示纯文本标签：
```kotlin
if (iconStyle != null) {
    // 显示图标 + 名称
} else {
    // 降级：只显示标签名称（无图标）
    Text(
        text = tag.name,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}
```

## 下一步行动

1. **运行上述调试命令**，收集实际日志输出
2. **根据日志输出**，确定具体是哪个环节出了问题
3. **应用对应的解决方案**
4. **移除调试日志**，清理代码

## 相关文件

- `/app/src/main/kotlin/com/jetdo/android/services/topic/TopicService.kt:439-445` - 数据层日志
- `/app/src/main/kotlin/com/jetdo/android/ui/home/components/TopicMetadataBadges.kt:47-52` - 组件层日志
- `/app/src/main/kotlin/com/jetdo/android/ui/home/components/TopicMetadataBadges.kt:147` - 渲染层日志
- `/app/src/main/res/raw/linuxdo_tag_icons.json` - 图标配置文件
