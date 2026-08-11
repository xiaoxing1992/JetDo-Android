# ArkDO → ArkDO-Android 迁移清单（1:1 覆盖跟踪）

> 基线：源项目 `ArkDO` @ main `dc50bd0`
> 规则：**不落下任何源文件**。每个源文件对等迁移后勾选 `[x]`；`-` 表示明确不做（如鸿蒙平台专有，需注明理由）。
> 勾选前提：源逻辑逐字段核对 + Android 端编译通过。

## 里程碑一：垂直链路（网络桥→挑战→会话→首页→详情）

### 1. 网络层 core
- [ ] `services/network/ArkWebNetworkBridge.ets` → `ArkWebNetworkBridge.kt`（WebView JS 桥，pendingRequest 表 + 超时 + 下载委托 + 媒体缓存）
- [ ] `services/network/ArkWebFetchScriptFactory.ets` → `ArkWebFetchScriptFactory.kt`（fetch 脚本生成）
- [ ] `services/network/NetworkTypes.ets` → `NetworkTypes.kt`（已迁，核对）
- [ ] `services/network/NetworkResults.ets` → 待定（若为纯类型/常量）
- [ ] `services/network/ChallengeDetector.ets` → `ChallengeDetector.kt`（已迁，核对）
- [ ] `services/network/DohManager.ets` → `DohManager.kt`（已迁，补全）
- [ ] `services/network/AvatarImageCache.ets` → `AvatarImageCache.kt`（待建）
- [ ] `services/network/DohSpeedTester.ets` → 待定（里程碑二）
- [ ] `services/network/webscript/CsrfScriptTemplate.ets` → `webscript/CsrfScriptTemplate.kt`（核对）
- [ ] `services/network/webscript/MessageBusScriptTemplate.ets` → `webscript/MessageBusScriptTemplate.kt`（核对）

### 2. 挑战层
- [ ] `services/challenge/ChallengeRecoveryCoordinator.ets` → 补全骨架
- [ ] `services/challenge/ChallengeResolutionProbe.ets` → 补全骨架
- [ ] `services/challenge/ChallengeCompletionDetector.ets` → 核对
- [ ] `services/challenge/ChallengeEventBus.ets` / `ChallengeRecoveryEventBus.ets` / `ChallengeShortcutEventBus.ets` → 核对
- [ ] `services/challenge/ChallengeShortcutPrefs.ets` → 待定

### 3. 会话/Auth
- [ ] `services/session/LoginGate.ets` → 补全
- [ ] `services/session/SessionEventBus.ets` → 补全
- [ ] `services/session/AppStateEventBus.ets` → 补全
- [ ] `services/session/TopicReadStateEventBus.ets` → 待定（里程碑二）
- [ ] `services/auth/AuthService.ets` → 补全
- [ ] `services/web/WebSessionManager.ets` → 补全
- [ ] `services/message/MessageBusEventBus.ets` → 核对（已迁）

### 4. WebView 宿主
- [ ] `views/components/ArkWebHost.ets` → `ui/components/ArkWebHost.kt`（新建，隐藏宿主 WebView）
- [ ] `views/components/LdcCreditWebHost.ets` → 里程碑二

### 5. 站点层
- [ ] `services/site/SiteService.ets` → 新建
- [ ] `services/site/CategoryIndex.ets` → 新建
- [ ] `services/site/SitePrefs.ets` / `FeedPrefs.ets` / `SiteIconStore.ets` → 新建

### 6. Topic 层
- [ ] `services/topic/TopicService.ets` → 新建
- [ ] `services/topic/TopicDataSource.ets` → 新建
- [ ] `services/topic/PostDataSource.ets` → 新建
- [ ] `services/topic/PostStreamPager.ets` → 新建
- [ ] `services/topic/TopicApiTypes.ets` → 新建

### 7. 首页
- [ ] `views/pages/HomePage.ets` → `HomeScreen.kt`（重写）
- [ ] `views/components/TopicListItem.ets` → `TopicListItem.kt`
- [ ] 首页依赖组件（TopicTagBadge/TopicBadges/TopicSummaryCard 等）

### 8. 详情页
- [ ] `views/pages/TopicDetailPage.ets` → `TopicDetailScreen.kt`（新建）
- [ ] `views/components/TopicPostItem.ets` → `TopicPostItem.kt`
- [ ] 详情依赖组件（CommentInputBar、PostRichContent 基础等）

### 9. 收尾
- [ ] `views/pages/RootTabPage.ets` → `RootTabScreen.kt`（对等化：宽屏双栏）
- [ ] `pages/Index.ets` → `MainActivity.kt` 核对
- [ ] `navigation/AppRoutes.ets` → 已迁，核对
- [ ] `common/theme/*` → 核对（Palette/Metrics/ThemeManager）
- [ ] `common/ui/Immersive*` → Android 沉浸状态栏
- [ ] `common/utils/*`（RelativeTime/Toast/BreakpointManager/LdcFormat/ProfileFormat）→ 核对
- [ ] `common/constants/*` → 核对

## 里程碑二+：横向铺开（不在本次）

### 剩余服务（~80 文件）
- [ ] `content/`：CookedHtmlParser、HtmlLexer、HtmlText、MarkdownRenderService、MathRenderService、InlineHtmlParser、LinkRouter、DownloadService、DownloadEventBus、MediaSource、MediaPlaybackCoordinator、RichContentTypes、HtmlContentUtils、ImageShareService、EmojiPreviewHtml
- [ ] `push/`（15）：PushCoordinator、PushRelayClient、PushSubscriptionService 等
- [ ] `search/`（3）、`draft/`（2）、`credit/`（4）、`bookmark/`（1）、`upload/`（1）、`user/`（3）、`presence/`（1）、`reading/`（2）、`realtime/`（2）、`connect/`（1）、`announce/`（1）、`avatar/`（1）、`logging/`（2）、`home/`（1）、`topic/` 剩余（CreateTopicFlow/PollMutation/PostActions/ReactionMutation/ReactionUsersPager）、`notification/` 完整化

### 剩余页面（~35）
- [ ] LoginPage、CfChallengePage、ComposerPage、SearchOverlay、UserProfilePage、SettingsPage 及全部设置子页、SitesPage、BookmarksPage、DraftsPage、LdcCreditPage、LevelProgressPage、BadgesPage、ActivityListPage、SolvedPostsPage、ReadTopicsPage、NotificationPage、WebViewPage 等

### 剩余组件（~50）
- [ ] PostRichContent、ImageViewer、SearchFlow、EmojiPicker、AudioPlayer、VideoPlayer、PollView、CommentInputBar、UserAvatar、CategoryDrawer、HomeFab 等

### 测试对等
- [ ] 源 40+ 单测 → JVM 单测（优先 TopicService/MessageBusEventBus/CookedHtmlParser）

### 平台专项（鸿蒙→Android 映射说明）
- `@rv/image-preview`（图片预览）→ Compose 原生实现
- `@ohos.web.webview` 下载委托 → `DownloadListener` + `DownloadManager`
- HarmonyOS Push Kit → FCM（需自建中继，relay.py 已迁到 `server/`）
- 沉浸光感（6.1）→ Android 沉浸式状态栏 + 玻璃拟态（Blur/Scrim）
