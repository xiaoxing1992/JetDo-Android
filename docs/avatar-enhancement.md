# 用户头像增强实现总结

## 问题描述

初始实现的头像组件只支持：
- ✅ 圆形头像
- ✅ 静态头像
- ❌ 无法加载（缺少 baseUrl）

鸿蒙版的头像支持更丰富的特性：
- 圆形头像 / 方形头像
- 静态头像 / 动态头像（GIF）
- 徽章标识覆盖显示

## 实现方案

### 1. 数据模型支持

User 模型已包含完整字段：

```kotlin
data class User(
    // 基础信息
    @SerialName("id") val id: Long,
    @SerialName("username") val username: String = "",
    @SerialName("name") val name: String? = null,
    
    // 头像
    @SerialName("avatar_template") val avatarTemplate: String? = null,
    @SerialName("animated_avatar") val animatedAvatar: String? = null,  // 动态头像 GIF
    
    // 徽章标识
    @SerialName("flair_url") val flairUrl: String? = null,
    @SerialName("flair_bg_color") val flairBgColor: String? = null,
    @SerialName("flair_color") val flairColor: String? = null,
    @SerialName("flair_name") val flairName: String? = null,
    
    // 其他
    @SerialName("badge_ids") val badgeIds: List<Long>? = null
)
```

### 2. UserAvatar 组件增强

#### 核心功能

**头像形状支持**
- `isSquare = false` → 圆形头像（CircleShape）
- `isSquare = true` → 圆角方形头像（RoundedCornerShape(8.dp)）

**动态头像优先**
```kotlin
// 1. 优先使用 animatedAvatar（GIF 动画头像）
if (user.animatedAvatar != null) {
    return buildFullUrl(user.animatedAvatar)
}

// 2. 回退到 avatarTemplate（静态头像模板）
if (user.avatarTemplate != null) {
    val path = user.avatarTemplate.replace("{size}", targetSize.toString())
    return buildFullUrl(path)
}
```

**智能尺寸选择**
```kotlin
val targetSize = when {
    size <= 24 -> 24
    size <= 32 -> 32
    size <= 48 -> 48
    size <= 96 -> 96
    size <= 144 -> 144
    else -> 240
}
```

选择最接近的预设尺寸，避免服务器重复缩放，提升加载速度。

**URL 自动补全**
```kotlin
return if (path.startsWith("http")) {
    path  // 已是完整 URL
} else {
    "${AppConstants.BASE_URL}$path"  // 补全 baseUrl
}
```

**失败回退机制**
```kotlin
Box {
    // 底层：首字母占位符（始终显示）
    Text(initial, ...)
    
    // 顶层：异步加载真实头像（成功则覆盖）
    AsyncImage(model = avatarUrl, ...)
}
```

采用层叠策略，底层始终显示占位符，顶层异步加载图片。加载成功则覆盖，失败则自动显示底层。

#### 徽章标识显示

**FlairBadge 组件**
```kotlin
@Composable
private fun FlairBadge(
    flairUrl: String,
    flairBgColor: String?,
    size: Dp,
    modifier: Modifier
)
```

特性：
- 位置：头像右下角（`Align.BottomEnd`）
- 尺寸：头像尺寸的 40%（`avatarSize * 0.4f`）
- 偏移：轻微溢出（`offset(x=2.dp, y=2.dp)`）
- 背景：解析 `flairBgColor`（支持 `#RRGGBB` 和 `RRGGBB` 格式）
- 边框：1dp 白色边框，与背景分离
- 图标：70% 尺寸居中显示

**颜色解析**
```kotlin
private fun parseColor(colorString: String?): Color? {
    val hex = colorString?.removePrefix("#") ?: return null
    
    if (hex.length == 6) {
        val r = hex.substring(0, 2).toInt(16)
        val g = hex.substring(2, 4).toInt(16)
        val b = hex.substring(4, 6).toInt(16)
        return Color(r, g, b)
    }
    
    return null
}
```

### 3. 头像形状判断逻辑

**shouldUseSquareAvatar()**

```kotlin
private fun shouldUseSquareAvatar(user: User?): Boolean {
    if (user == null) return false

    // 策略 1：有 flair 标识 → 方形（通常代表官方/认证身份）
    if (!user.flairName.isNullOrEmpty()) {
        return true
    }

    // 策略 2：有特定徽章 ID → 方形（管理员、版主等）
    // TODO: 根据实际数据调整具体徽章 ID
    
    // 默认：圆形头像
    return false
}
```

当前判断策略：
- ✅ **有 flairName** → 方形头像（官方账号、认证用户）
- ✅ **普通用户** → 圆形头像
- ⏳ **特定徽章 ID** → 待根据实际数据调整

### 4. TopicListItem 集成

```kotlin
UserAvatar(
    user = topic.author,
    avatarSize = 40.dp,
    isSquare = shouldUseSquareAvatar(topic.author),  // 智能判断形状
    showFlair = true  // 显示徽章标识
)
```

## 视觉效果

### 圆形头像（普通用户）
```
┌────────┐
│  ╭──╮  │
│  │头│  │  普通用户
│  │像│  │  无徽章标识
│  ╰──╯  │
└────────┘
```

### 方形头像 + 徽章（认证用户）
```
┌────────┐
│ ┌────┐ │
│ │头像│ │  认证用户
│ │  🏅│ │  右下角徽章
│ └────┘ │
└────────┘
```

### 动态头像（GIF）
```
┌────────┐
│  ╭──╮  │
│  │🎬│  │  GIF 动画
│  │头│  │  自动播放
│  ╰──╯  │
└────────┘
```

## 技术细节

### Coil3 图片加载

```kotlin
AsyncImage(
    model = avatarUrl,
    contentDescription = "用户头像",
    modifier = Modifier.size(avatarSize).clip(shape),
    contentScale = ContentScale.Crop
)
```

特性：
- ✅ 自动缓存（内存 + 磁盘）
- ✅ 支持 GIF 动画
- ✅ 失败时不显示（底层占位符自动显现）
- ✅ 异步加载，不阻塞 UI

### 性能优化

**尺寸预选**
- 避免请求任意尺寸（例如 39px、41px）
- 统一请求预设尺寸（24/32/48/96/144/240）
- 服务器可预生成缓存，提升响应速度

**层叠渲染**
- 占位符和真实头像同时渲染
- 无需等待加载完成判断是否显示占位符
- 减少布局抖动

**URL 缓存**
- baseUrl 在编译时确定
- URL 拼接逻辑纯函数，可被 Compose 记忆化

## 对齐情况

| 功能 | 鸿蒙版 | Android 版 | 状态 |
|-----|--------|-----------|------|
| 圆形头像 | ✅ | ✅ | ✅ 完全对齐 |
| 方形头像 | ✅ | ✅ | ✅ 完全对齐 |
| 动态头像（GIF） | ✅ | ✅ | ✅ 完全对齐 |
| 徽章标识显示 | ✅ | ✅ | ✅ 完全对齐 |
| 失败回退占位符 | ✅ | ✅ | ✅ 完全对齐 |
| URL 自动补全 | ✅ | ✅ | ✅ 完全对齐 |
| 智能尺寸选择 | ✅ | ✅ | ✅ 完全对齐 |

## 后续优化方向

### 1. 精确的角色判断

目前通过 `flairName` 判断是否使用方形头像。未来可以根据实际数据调整：

```kotlin
// 方案 A：通过特定徽章 ID 判断
val OFFICIAL_BADGE_IDS = setOf(100L, 101L, 102L)
if (user.badgeIds?.any { it in OFFICIAL_BADGE_IDS } == true) {
    return true
}

// 方案 B：通过用户名前缀判断
if (user.username.startsWith("official_") || user.username.startsWith("bot_")) {
    return true
}

// 方案 C：从 API 获取用户角色（需要后端支持）
if (user.role in setOf("admin", "moderator", "official")) {
    return true
}
```

### 2. 更丰富的徽章样式

当前只支持单个圆形徽章。未来可以支持：
- 多个徽章（最多显示 3 个）
- 不同形状的徽章（圆形、方形、六边形）
- 徽章动画（闪烁、旋转）

### 3. 头像加载状态

```kotlin
AsyncImage(
    model = avatarUrl,
    loading = {
        CircularProgressIndicator(...)  // 加载中
    },
    error = {
        // 保持占位符（当前实现）
    }
)
```

### 4. 头像点击交互

```kotlin
UserAvatar(
    user = user,
    onClick = { 
        // 跳转到用户主页
        navController.navigate("user/${user.id}")
    }
)
```

## 修改的文件

**增强**
- `app/src/main/kotlin/com/jetdo/android/ui/components/UserAvatar.kt`
  - 新增 `isSquare` 参数（形状控制）
  - 新增 `showFlair` 参数（徽章显示）
  - 新增 `FlairBadge` 组件（徽章渲染）
  - 优先使用 `animatedAvatar`（动态头像）
  - 新增 `parseColor()` 辅助函数

- `app/src/main/kotlin/com/jetdo/android/ui/components/TopicListItem.kt`
  - 新增 `shouldUseSquareAvatar()` 判断逻辑
  - UserAvatar 调用传递 `isSquare` 和 `showFlair`

**文档**
- `docs/avatar-enhancement.md` - 本文档

## 测试验证

- ✅ 编译通过：`./gradlew compileDebugKotlin`
- ✅ 圆形头像正常显示
- ✅ 方形头像正常显示（isSquare=true）
- ✅ 动态头像 GIF 自动播放
- ✅ 徽章标识正确覆盖显示
- ✅ 失败回退到首字母占位符
- ✅ URL 正确补全 baseUrl

## 相关文档

- [[topic-list-item-enhancement.md]] - TopicListItem 卡片增强
- [[home-ui-alignment.md]] - 首页核心架构对齐
- 鸿蒙参考：`harmonyos/entry/src/main/ets/components/UserAvatar.ets`
