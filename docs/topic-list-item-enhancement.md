# TopicListItem 卡片增强实现总结

## 任务概述

**任务 #6**：增强 TopicListItem 卡片，1:1 对齐鸿蒙版 `TopicListItem.ets` 的完整功能。

## 实现内容

### 1. 核心组件

#### UserAvatar.kt - 用户头像组件
- ✅ 支持圆形头像显示
- ✅ 自动处理头像 URL（相对路径自动添加 baseUrl）
- ✅ 智能尺寸选择（24/32/48/96/144/240px）
- ✅ 失败回退：加载失败时显示首字母占位符
- ✅ 使用 Coil3 进行异步图片加载

#### TimeUtils.kt - 相对时间工具
- ✅ `formatRelativeTime()` - 将时间戳转为友好格式
  - 刚刚（< 1分钟）
  - N 分钟前、N 小时前
  - N 天前、N 周前、N 月前
  - 超过1年显示年月日

#### TopicMetadataBadge.kt - 话题元数据徽章
- ✅ 分类徽章（带颜色标识）
- ✅ 标签徽章（灰色背景）
- ✅ 支持点击回调

#### EnhancedTopicListItem.kt - 增强版话题卡片
完整实现鸿蒙版的所有功能：

**布局结构**
```
┌─────────────────────────────────────────┐
│ [头像] 用户名 · 相对时间               │
│                                         │
│ 话题标题（多行，最多3行）              │
│                                         │
│ [分类] [标签1] [标签2]                 │
│                                         │
│ 👍 点赞数 · 💬 回复数 · 👁 浏览数    │
└─────────────────────────────────────────┘
```

**详细功能**
- ✅ 用户信息行
  - 40dp 圆形头像（支持真实头像/首字母占位）
  - 用户名（中等字重）
  - 相对时间（次要文字颜色）
  
- ✅ 话题标题
  - 16sp 字号
  - 最多显示 3 行
  - 超出显示省略号

- ✅ 元数据徽章行
  - 分类徽章（带颜色，可点击）
  - 标签列表（灰色背景，可点击）
  - 横向滚动支持

- ✅ 统计信息行
  - 点赞数（❤️ 图标）
  - 回复数（💬 图标）
  - 浏览数（👁 图标）
  - 次要文字颜色

- ✅ 交互功能
  - 整卡可点击（进入话题详情）
  - 分类可点击（筛选该分类）
  - 标签可点击（筛选该标签）
  - 涟漪效果反馈

### 2. HomeScreen.kt 集成

- ✅ 移除旧的简化版 `TopicListItem`
- ✅ `TopicList` 使用新的 `EnhancedTopicListItem`
- ✅ 传递完整的点击回调（话题/分类/标签）

## 技术细节

### 头像 URL 处理

```kotlin
private fun buildAvatarUrl(template: String, size: Int): String {
    val targetSize = when {
        size <= 24 -> 24
        size <= 32 -> 32
        size <= 48 -> 48
        size <= 96 -> 96
        size <= 144 -> 144
        else -> 240
    }

    val path = template.replace("{size}", targetSize.toString())

    // 如果是相对路径，添加 baseUrl
    return if (path.startsWith("http")) {
        path
    } else {
        "${AppConstants.BASE_URL}$path"
    }
}
```

### 失败回退机制

使用层叠显示策略：
1. 底层始终显示首字母占位符（背景色 + 文字）
2. 顶层异步加载真实头像（成功则覆盖占位符）
3. 加载失败时自动显示底层占位符

### 相对时间算法

```kotlin
fun formatRelativeTime(timestamp: String): String {
    val now = System.currentTimeMillis()
    val time = Instant.parse(timestamp).toEpochMilli()
    val diff = now - time

    return when {
        diff < 60_000 -> "刚刚"
        diff < 3600_000 -> "${diff / 60_000} 分钟前"
        diff < 86400_000 -> "${diff / 3600_000} 小时前"
        diff < 604800_000 -> "${diff / 86400_000} 天前"
        diff < 2592000_000 -> "${diff / 604800_000} 周前"
        diff < 31536000_000 -> "${diff / 2592000_000} 月前"
        else -> {
            val date = Instant.ofEpochMilli(time)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
            "${date.year}年${date.monthValue}月${date.dayOfMonth}日"
        }
    }
}
```

## 修改的文件

1. **新增组件**
   - `app/src/main/kotlin/com/jetdo/android/ui/components/UserAvatar.kt`
   - `app/src/main/kotlin/com/jetdo/android/ui/components/TopicMetadataBadge.kt`
   - `app/src/main/kotlin/com/jetdo/android/ui/components/EnhancedTopicListItem.kt`
   - `app/src/main/kotlin/com/jetdo/android/utils/TimeUtils.kt`

2. **修改文件**
   - `app/src/main/kotlin/com/jetdo/android/ui/home/HomeScreen.kt` - 集成新组件

## 依赖项

- ✅ Coil 3 图片加载库（已配置）
  - `coil3.compose.AsyncImage`
  - 支持网络图片、缓存、错误处理

## 测试验证

- ✅ 编译通过：`./gradlew compileDebugKotlin`
- ✅ 头像 URL 正确拼接（baseUrl + 相对路径）
- ✅ 头像加载失败时显示首字母占位符
- ✅ 相对时间格式化正确
- ✅ 所有交互回调正常传递

## 视觉对齐度

与鸿蒙版 `TopicListItem.ets` 的对齐情况：

| 功能 | 鸿蒙版 | Android 版 | 状态 |
|-----|--------|-----------|------|
| 用户头像 | ✅ 圆形 + 占位符 | ✅ 圆形 + 占位符 | ✅ 完全对齐 |
| 用户名显示 | ✅ 中等字重 | ✅ 中等字重 | ✅ 完全对齐 |
| 相对时间 | ✅ 次要颜色 | ✅ 次要颜色 | ✅ 完全对齐 |
| 话题标题 | ✅ 3行截断 | ✅ 3行截断 | ✅ 完全对齐 |
| 分类徽章 | ✅ 带颜色 | ✅ 带颜色 | ✅ 完全对齐 |
| 标签徽章 | ✅ 灰色背景 | ✅ 灰色背景 | ✅ 完全对齐 |
| 统计信息 | ✅ 点赞/回复/浏览 | ✅ 点赞/回复/浏览 | ✅ 完全对齐 |
| 交互功能 | ✅ 多点击回调 | ✅ 多点击回调 | ✅ 完全对齐 |

## 下一步建议

1. **任务 #2**：实现版块筛选抽屉（核心功能）
2. **任务 #3**：实现实时新话题提醒（体验增强）
3. **任务 #4**：实现右下角智能 FAB（已有 ViewModel 支持）

## 相关文档

- [[home-ui-alignment.md]] - 首页核心架构对齐
- 鸿蒙参考：`harmonyos/entry/src/main/ets/pages/home/components/TopicListItem.ets`
