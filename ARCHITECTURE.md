# ArkDO Android - 项目结构文档

## 概览

ArkDO Android 是 LINUX DO 社区的第三方 Android 原生客户端，基于 HarmonyOS 版本（ArkDO）1:1 还原功能和架构。

- **架构模式**：单 Activity + Jetpack Compose + MVVM
- **依赖注入**：Hilt
- **异步处理**：Kotlin Coroutines + Flow
- **导航**：Jetpack Compose Navigation
- **网络层**：WebView Bridge（绕过 Cloudflare 防护）
- **图片加载**：Coil
- **持久化**：DataStore + Room

## 目录结构

```
app/src/main/kotlin/com/arkdo/android/
├── ArkDoApplication.kt          # 应用入口
├── MainActivity.kt              # 主 Activity
│
├── common/                      # 通用工具和常量
│   ├── constants/              # 常量定义
│   ├── theme/                  # 主题管理（深色模式切换）
│   ├── utils/                  # 工具类（日期格式化、断点适配等）
│   └── ui/                     # 通用 UI 组件（加载态、空态、错误态）
│
├── data/                        # 数据层
│   ├── models/                 # 数据模型（Topic、User、Post 等）
│   ├── local/                  # 本地数据源（Room 数据库、DataStore）
│   ├── remote/                 # 远程数据源（Discourse API 适配）
│   └── repository/             # 仓库层（数据聚合）
│
├── di/                          # 依赖注入模块
│   ├── AppModule.kt            # 应用级单例（ImageLoader、WebViewBridge）
│   ├── NetworkModule.kt        # 网络相关（OkHttp、DoH）
│   └── DatabaseModule.kt       # 数据库相关（Room）
│
├── navigation/                  # 导航定义
│   ├── ArkDoNavHost.kt         # 导航宿主
│   └── Routes.kt               # 路由常量
│
├── services/                    # 14 个服务子域（对齐 HarmonyOS 版本）
│   ├── network/                # 网络层
│   │   ├── WebViewNetworkBridge.kt      # WebView 网络桥接（核心）
│   │   ├── DohManager.kt                # DoH DNS 管理器
│   │   └── AvatarImageCache.kt          # 头像缓存（LRU + 并发限流）
│   │
│   ├── challenge/              # Cloudflare 挑战处理
│   │   ├── ChallengeRecoveryCoordinator.kt  # 静默恢复协调器
│   │   └── ChallengeDetector.kt             # 挑战检测器
│   │
│   ├── content/                # 内容解析与渲染
│   │   ├── CookedHtmlParser.kt          # Cooked HTML → Compose 渲染
│   │   ├── MathRenderService.kt         # LaTeX 公式渲染（MathJax）
│   │   ├── LinkRouter.kt                # 链接路由（站内跳转）
│   │   └── DownloadService.kt           # 附件下载
│   │
│   ├── topic/                  # 话题服务
│   │   ├── TopicService.kt             # 话题列表、详情 API
│   │   ├── TopicPagingSource.kt        # 分页加载
│   │   └── ReactionMutation.kt         # 表情回应
│   │
│   ├── session/                # 会话管理
│   │   ├── LoginGate.kt                # 登录态拦截器
│   │   └── SessionStore.kt             # 会话持久化
│   │
│   ├── push/                   # 系统推送
│   │   ├── ArkDoFirebaseMessagingService.kt  # FCM 接收
│   │   ├── PushSubscriptionService.kt        # 推送订阅（中继注册）
│   │   └── PushNotificationNavigation.kt     # 推送深链接导航
│   │
│   ├── auth/                   # 认证服务
│   ├── bookmark/               # 书签服务
│   ├── connect/                # Connect（等级进度）
│   ├── draft/                  # 草稿服务
│   ├── logging/                # 日志服务
│   ├── notification/           # 通知服务
│   ├── reading/                # 阅读进度追踪
│   ├── site/                   # 站点服务
│   ├── user/                   # 用户服务
│   └── web/                    # Web 相关（WebView 管理）
│
└── ui/                          # UI 层
    ├── theme/                  # 主题定义（Theme.kt、Type.kt、Color.kt）
    │
    ├── screens/                # 页面（15 个，对齐 HarmonyOS 版本）
    │   ├── RootTabScreen.kt            # 根 Tab 容器（首页、通知、个人中心）
    │   ├── HomeScreen.kt               # 首页（最新、热门、未读）
    │   ├── NotificationScreen.kt       # 通知中心
    │   ├── ProfileScreen.kt            # 个人主页
    │   ├── TopicDetailScreen.kt        # 话题详情
    │   ├── LoginScreen.kt              # 登录页
    │   ├── CfChallengeScreen.kt        # Cloudflare 挑战页
    │   ├── SettingsScreen.kt           # 设置
    │   ├── DohSettingsScreen.kt        # DoH 设置
    │   ├── PushSettingsScreen.kt       # 推送设置
    │   ├── BookmarksScreen.kt          # 书签
    │   ├── DraftsScreen.kt             # 草稿
    │   ├── LevelProgressScreen.kt      # 等级进度
    │   ├── BadgesScreen.kt             # 徽章墙
    │   └── WebViewScreen.kt            # 内嵌 WebView（私信等）
    │
    └── components/             # 组件（40 个，对齐 HarmonyOS 版本）
        ├── TopicCard.kt                # 话题卡片
        ├── PostItem.kt                 # 帖子项
        ├── UserAvatar.kt               # 用户头像
        ├── ReactionBar.kt              # 表情回应栏
        ├── CommentInputSheet.kt        # 回复输入框（Bottom Sheet）
        ├── ImageViewer.kt              # 图片预览（缩放、下拉关闭）
        ├── SearchBar.kt                # 搜索栏
        ├── CategoryDrawer.kt           # 版块抽屉
        ├── FaIcon.kt                   # FontAwesome 图标
        ├── EmojiIcon.kt                # Emoji 图标
        └── ...                         # 其他 30+ 组件
```

## 核心服务说明

### 1. WebViewNetworkBridge（网络桥接）

**问题**：LINUX DO 有 Cloudflare 防护，原生 HTTP 栈发的请求会被拦截。

**解决方案**：
- 常驻一个隐藏的 WebView 承载会话（Cookie 由 WebView 管理）
- 所有 API 请求通过 `evaluateJavascript` 注入 `fetch` 调用
- JSON 结果通过 JS 回调返回给 Kotlin
- 原生界面由 Compose 渲染，WebView 仅用作网络层

### 2. ChallengeRecoveryCoordinator（挑战恢复）

**流程**：
1. 检测到挑战时，先在后台重载宿主页（让挑战脚本自己跑通）
2. 每 2 秒用 `/latest.json` 探测是否放行
3. ~12 秒还不通才弹出可见页面让用户手动点一下

### 3. DohManager（DNS over HTTPS）

应对 DNS 污染，通过 OkHttp 的 `DnsOverHttps` 实现。

### 4. PushSubscriptionService（推送订阅）

**架构**：
```
LINUX DO ──Web Push（加密）──▶ 自建中继 ──解密──▶ FCM ──▶ 设备通知
```

需自建中继并配置在 `app/src/main/assets/relay-config.json`。

## 数据流

```
UI (Compose) 
  ↓ ViewModel 发起请求
  ↓ Repository 聚合数据源
  ↓ WebViewNetworkBridge 发起 fetch
  ↓ WebView 内执行 JS fetch
  ↓ JSON 回传给 Kotlin
  ↓ 数据模型解析（kotlinx.serialization）
  ↓ StateFlow 通知 UI 更新
  ↓ Compose 重组渲染
```

## 待实现功能清单

### 高优先级（对齐 HarmonyOS 版本核心功能）
- [ ] WebView 网络桥接完整实现
- [ ] Cloudflare 挑战检测与静默恢复
- [ ] 话题列表 API 适配与渲染
- [ ] 话题详情页（分页加载、楼层跳转）
- [ ] Cooked HTML 解析器（→ Compose AnnotatedString）
- [ ] 登录流程（WebView OAuth）
- [ ] 会话持久化（Cookie 镜像）

### 中优先级（完善交互体验）
- [ ] 回复评论（Bottom Sheet + 输入框）
- [ ] 表情回应、Boost、投票
- [ ] 书签管理
- [ ] 通知中心（30+ 种通知类型）
- [ ] 个人主页（用户动态、徽章墙）
- [ ] 搜索功能
- [ ] 版块筛选抽屉

### 低优先级（增强功能）
- [ ] LaTeX 公式渲染
- [ ] 图片预览（缩放、下拉关闭）
- [ ] 音视频播放
- [ ] 附件下载
- [ ] 系统推送（需自建中继）
- [ ] DoH 设置与测速
- [ ] 阅读进度追踪
- [ ] 草稿管理
- [ ] 等级进度可视化

## 开发注意事项

1. **WebView 操作必须在主线程**：所有 `evaluateJavascript` 调用需在主线程执行
2. **Cookie 同步**：退后台时调用 `CookieManager.flush()` 确保 Cookie 落盘
3. **图片加载**：头像等静态图需走 WebView 桥接（绕过 SNI 阻断）
4. **深色模式**：Material3 自动适配，注意 WebView 内容的深色模式同步
5. **平板适配**：使用 `WindowSizeClass` 判断是否启用双栏布局

## 测试策略

- **单元测试**：核心服务（WebViewBridge、ChallengeRecovery、HTML Parser）
- **集成测试**：API 适配层（TopicService、UserService）
- **UI 测试**：关键流程（登录、发帖、回复）

## 构建与运行

```bash
# 克隆仓库
git clone <repo-url>
cd ArkDO-Android

# 配置签名（local.properties）
# 配置 Firebase（google-services.json）
# 配置推送中继（app/src/main/assets/relay-config.json，可选）

# 构建 Debug 版本
./gradlew assembleDebug

# 运行到设备
./gradlew installDebug
```

## License

MIT License
