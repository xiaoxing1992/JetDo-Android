# 头像与徽章增强 - 1:1 对齐鸿蒙版

## 概述

完成了用户头像和标签徽章的视觉增强，严格对齐鸿蒙版 `UserAvatar.ets` 和 `TagBadge.ets` 的实现逻辑。

## 已完成的功能

### 1. 头像形状差异化 (UserAvatar.kt)

**对齐源码**: `UserAvatar.ets:352-358`

```kotlin
// ID=1 的用户使用方形圆角(10%), 其余用户使用圆形头像(50%)
val isSquare = user?.id == 1L
val shape = if (isSquare) {
    RoundedCornerShape(avatarSize * 0.1f)  // 方形：10% 圆角
} else {
    CircleShape  // 圆形：50% 圆角
}
```

**视觉效果**:
- ID=1 用户：方形头像，圆角半径 = 头像尺寸 × 10%
- 其他用户：圆形头像（传统样式）

### 2. 动态头像支持

**对齐源码**: `UserAvatar.ets:374-379`

优先级顺序：
1. **animatedAvatar** (GIF 动态头像) - 优先显示
2. **avatarTemplate** (静态头像) - 回退方案
3. **首字母占位符** - 加载失败兜底

```kotlin
private fun buildAvatarUrl(user: User?, size: Int): String? {
    // 优先使用动态头像
    if (user.animatedAvatar != null) {
        return buildFullUrl(user.animatedAvatar)
    }
    // 回退到静态头像模板
    if (user.avatarTemplate != null) {
        return buildFullUrl(user.avatarTemplate.replace("{size}", targetSize))
    }
    return null
}
```

### 3. 徽章标识覆盖 (FlairBadge)

**对齐源码**: `UserAvatar.ets:398-433`

徽章设计规则（严格对齐鸿蒙）：
- **尺寸**: `max(14dp, avatarSize × 0.42)`
- **位置**: 右下角溢出 `badgeSize × 0.24`
- **形状**: 有底色=圆形，无底色=方形(18% 圆角)
- **内缩**: 有底色时 padding = `badgeSize × 0.16`

```kotlin
@Composable
private fun FlairBadge(
    flairUrl: String,
    flairBgColor: String?,      // 底色 (#RRGGBB)
    flairColor: String?,        // 前景色（未使用）
    avatarSize: Dp
) {
    val flairBadgeSize = maxOf(14.dp, avatarSize * 0.42f)
    val flairOverhang = flairBadgeSize * 0.24f
    val hasFlairBg = !flairBgColor.isNullOrBlank()
    
    val shape = if (hasFlairBg) CircleShape else RoundedCornerShape(flairBadgeSize * 0.18f)
    val padding = if (hasFlairBg) (flairBadgeSize * 0.16f) else 0.dp
    
    Box(
        modifier = Modifier
            .size(flairBadgeSize)
            .offset(x = flairOverhang, y = flairOverhang)
            .clip(shape)
            .background(parseColor(flairBgColor))
            .border(1.dp, MaterialTheme.colorScheme.surface, shape)
    ) {
        AsyncImage(
            model = fullUrl,
            modifier = Modifier.size(flairBadgeSize - padding * 2),
            contentScale = if (hasFlairBg) ContentScale.Fit else ContentScale.Crop
        )
    }
}
```

**显示条件**:
- `showFlair = true` (参数控制)
- `avatarSize >= 32dp` (尺寸阈值)
- `user.flair != null` (数据存在)

### 4. 标签徽章图标 (TagIconRegistry + TagBadge)

**对齐源码**: `TagBadge.ets:84-132`

新增 `TagIconRegistry` 组件，支持：
- 从 `tag-icons.json` 加载图标映射表
- 图标 ID → Material Icon 转换
- 颜色解析（#RRGGBB 格式）

```kotlin
object TagIconRegistry {
    private val iconMap: Map<String, TagIconStyle> by lazy {
        loadTagIconsFromAssets()
    }
    
    fun getTagIconStyle(tagName: String): TagIconStyle? {
        return iconMap[tagName]
    }
    
    fun mapIconIdToMaterialIcon(iconId: String): ImageVector? {
        return when (iconId) {
            "star" -> Icons.Default.Star
            "code" -> Icons.Default.Code
            "lightbulb" -> Icons.Default.Lightbulb
            // ... 60+ 图标映射
        }
    }
}
```

**TagBadge 增强**:
```kotlin
@Composable
private fun TagBadge(tag: TopicTag, ...) {
    val iconStyle = TagIconRegistry.getTagIconStyle(tag.name)
    val icon = iconStyle?.let { TagIconRegistry.mapIconIdToMaterialIcon(it.iconId) }
    val color = iconStyle?.let { TagIconRegistry.parseColor(it.color) }
    
    Row(...) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                modifier = Modifier.size(14.dp),
                tint = color ?: MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(text = tag.name, ...)
    }
}
```

## 数据模型扩展

### User 模型 (User.kt)

新增字段（对齐鸿蒙 `BasicUser` 接口）：

```kotlin
@Serializable
data class User(
    // 现有字段...
    val id: Long,
    val username: String,
    val displayName: String,
    val avatarTemplate: String,
    
    // 新增字段
    @SerialName("animated_avatar") val animatedAvatar: String? = null,  // GIF 动态头像
    @SerialName("flair_url") val flair: String? = null,                 // 徽章图标 URL
    @SerialName("flair_bg_color") val flairBgColor: String? = null,     // 徽章底色
    @SerialName("flair_color") val flairColor: String? = null           // 徽章前景色
)
```

## 文件清单

### 新增文件
- `app/src/main/kotlin/com/jetdo/android/ui/components/TagIconRegistry.kt` - 标签图标注册表
- `app/src/main/assets/tag-icons.json` - 图标映射配置（60+ 标签）
- `docs/avatar-badge-enhancement.md` - 本文档

### 修改文件
- `app/src/main/kotlin/com/jetdo/android/ui/components/UserAvatar.kt`
  - 添加形状差异化逻辑
  - 添加 FlairBadge 组件
  - 支持动态头像优先级
- `app/src/main/kotlin/com/jetdo/android/ui/components/TopicMetadataBadges.kt`
  - TagBadge 支持图标和颜色
- `app/src/main/kotlin/com/jetdo/android/data/models/User.kt`
  - 扩展 4 个新字段

## 使用示例

### 基础用法（自动显示徽章）
```kotlin
UserAvatar(
    user = user,
    avatarSize = 40.dp
)
```

### 自定义尺寸 + 禁用徽章
```kotlin
UserAvatar(
    user = user,
    avatarSize = 24.dp,
    showFlair = false  // 小尺寸时禁用徽章
)
```

## 视觉对比

| 特性 | 鸿蒙版 | Android 版 | 状态 |
|-----|-------|-----------|------|
| ID=1 方形头像 | ✅ 10% 圆角 | ✅ 10% 圆角 | ✅ 已对齐 |
| 其他圆形头像 | ✅ 50% 圆角 | ✅ CircleShape | ✅ 已对齐 |
| 动态头像优先 | ✅ animatedAvatar | ✅ animatedAvatar | ✅ 已对齐 |
| 徽章尺寸 | ✅ 0.42×size | ✅ 0.42×size | ✅ 已对齐 |
| 徽章位置 | ✅ 右下溢出 0.24 | ✅ 右下溢出 0.24 | ✅ 已对齐 |
| 徽章形状 | ✅ 有底色=圆/无底色=方 | ✅ 有底色=圆/无底色=方 | ✅ 已对齐 |
| 标签图标 | ✅ 60+ 图标 | ✅ 60+ Material Icons | ✅ 已对齐 |
| 标签颜色 | ✅ 自定义色 | ✅ 解析 #RRGGBB | ✅ 已对齐 |

## 编译状态

✅ **编译通过** (仅 3 个弃用警告，不影响功能)

```bash
./gradlew compileDebugKotlin
# BUILD SUCCESSFUL in 4s

./gradlew assembleDebug
# BUILD SUCCESSFUL in 7s
```

## 下一步

1. **验证 APK 效果**: 安装 `app/build/outputs/apk/debug/app-debug.apk` 查看视觉变化
2. **测试数据**: 确认 API 返回的用户数据包含新字段（animatedAvatar, flair, flairBgColor）
3. **继续任务 #2**: 实现版块筛选抽屉（核心交互功能）

## 关键参考

- 鸿蒙源码: `entry/src/main/ets/components/user/UserAvatar.ets`
- 鸿蒙源码: `entry/src/main/ets/components/topic/TagBadge.ets`
- Material 3 设计规范: 头像组件最佳实践
