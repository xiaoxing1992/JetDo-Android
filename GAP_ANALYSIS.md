# ArkDO-Android 功能缺口分析

> 对标对象：鸿蒙版 ArkDO（ArkTS · `@7680996` · 28 页面 / 47 组件 / 25 服务子域）
> 本项目：`feat/migrate-vertical-slice`
> 规则：只记「现在还没做的」。做完删一条 —— 不记版本号、不记修复历史、不做已修项记账。

---

## 一、结论先行

**还剩 33 项 + 7 组死代码。**

### 1.1 缺口分布

| 分类 | 数量 | 判据 |
|---|---|---|
| P0 | 3 | 用户一眼能看出没做 |
| P1 | 3 | 功能不完整，用起来别扭 |
| 半截 | 10 | 数据 / 组件都在，只差一次接通（用户会当 bug 报）|
| 能力缺失 | 6 | 目录级对比看不出来的缺失 |
| P2 | 11 | 工程化与发布 |
| 死代码 | 7 组 | 看着有实现，其实是死的，会误导判断 |
| **合计** | **33** | 上表各项之和，与逐行点数一致 |

模块对齐度：首页 92% / 搜索 98% / 通知 95% / 详情 85% / 发帖 92% / 回帖 86% / 表情 85% / 音视频 92% / 我的 88% / 用户主页 88% / **实验性功能 50%** / **设置 48%** / **多站点 30%** / **系统推送 0%**。

### 1.2 隐性缺口（最值钱的三个）

纯目录对比只能得到「少了哪几个文件」。真正值钱的是**看着有、其实没接上**的那类：

1. **引用卡是「半截链路」**——三个定位字段解析齐全、单测全绿，**UI 层零消费**，还配了一句谎称已实现的注释（§四 S12）
2. **徽章点击的缺口比清单上记的更广**——不是「某一行没传回调」，而是 **11 个调用点无一传**（§四 S11）
3. **动态头像不是「开关没做」，是整个能力没做**（§五）

---

## 二、P0 · 一眼能看出没做（3 项）

| # | 要做什么 | 现在是什么样 | 关键证据 |
|---|---|---|---|
| 1 | **系统推送 + 本地通知** | `services/push/` 不存在（鸿蒙 12 文件：`PushCoordinator` / `PushRelayClient` / `PushRelayConfig` / `PushSubscriptionService` / `PushSubscriptionStore` / `PushToken` …）；全库 **0 处** `NotificationManager` / `NotificationCompat` / `NotificationChannel`；`POST_NOTIFICATIONS` 已声明但**无运行时申请**；App 活着时点通知也接不到（`onNewIntent` **0 命中**） | Manifest `:9`；`MainActivity.kt:210` `handleNotificationIntent` 只打日志；`:201` 仅 `onCreate` 调用 |
| 2 | **设置 4 入口未迁移** | 「网络与连接 → DoH」已落地；**论坛 / 通知 / 我的站点** 仍弹 Toast；`SETTINGS_PUSH` 常量零引用（悬空） | `SettingsScreen.kt:88`（`NOT_MIGRATED = "尚未迁移"`）、`:163` / `:169` / `:187`（三处 `toastNotMigrated`）；`ArkDoNavHost.kt:587` |
| 3 | **多站点 / 论坛切换** | `AppConstants.BASE_URL` **硬编码 `https://linux.do`**；Android `services/site/` **5 文件** vs 鸿蒙 **12**，缺 `CurrentSite` / `ForumPrefs` / `SiteCapabilities` / `SiteScope` / `SiteUrl` / `SiteSwitchCoordinator` / `SiteSettingsService`；鸿蒙的 `ForumsPage` / `SitesPage` / `ForumOnboardingPage` 三页无对应。**首页抽屉的「我的站点」快捷条也挂在这里**——它受 `SiteCapabilities.ecosystemSites`（复用主站 OAuth 免登录进生态站点，换站后无从谈起）与设置页开关双重门控，两个前置在 Android 都不存在 | `AppConstants.kt:8`、`:21`；两侧 `services/site/` 目录实测 |

**依赖关系**：`多站点` 是 `设置 4 入口` 里「论坛 / 我的站点」两个子页的前置。做 P0-2 的两个入口前必须先有 `CurrentSite`。

---

## 三、P1 · 功能不完整（3 项）

| # | 要做什么 | 鸿蒙版 | 现在是什么样 | 证据 |
|---|---|---|---|---|
| 9 | **平板双栏布局** | 横屏双栏 / 竖屏单栏自适应 | `WindowSizeClass` **0 命中** | 全库 |
| 11 | **顶栏「放大镜 → 搜索页」共享元素转场** | 顶栏放大镜与搜索页之间做共享元素转场 | 只剩这一条：「我的」页活动行、他人页简介链接、取关二次确认、**称号全息特效**（`HolographicTitle` + `UserTitleText`）、用户主页**「在网页中打开」**菜单**均已落地**。共享元素转场未做 —— 它要求起止两端页面同时在场、各自出一份起止快照，改造面大，建议降优先级 | `ui/components/HolographicTitle.kt`（白名单 `Premium` / `种子用户` / `破界者`）；`UserProfileScreen.kt:180`；`TopicPostItem.kt:418`、`UserProfileScreen.kt:431` |
| 12 | **版本公告源未定** | `announce/VersionAnnouncement.ets` | 代码完整，但 `ANNOUNCE_PROMPT_ENABLED = false` → **不扫描、不弹层**。当前源是鸿蒙版发布日志帖（`2590051` / `joeyyin`），`[v900008+]` 体系与 Android `versionCode` 对不上，开着会每次冷启动误弹鸿蒙公告 | `services/announce/VersionAnnouncement.kt:15/17/22`；`RootTabScreen.kt:139`（`if (!ANNOUNCE_PROMPT_ENABLED) return@LaunchedEffect`）|

---

## 四、半截 · 差一次接通（11 项）

**这类最该先修**：骨架、数据、组件往往都已就位，只差一行转发或一次订阅。

| # | 现象 | 根因 | 证据 |
|---|---|---|---|
| S7 | 圆形头像先方后圆闪一下 | `onOpenUser` 未透传 userId，种子 `id=0`。**待实机复核**：判据是 `id == 1L` 走方形，种子恒为圆形，静态推不出该现象 | `UserProfileScreen.kt:99`、`UserProfileViewModel.kt:72` |
| S11 | **分类 / 标签徽章不可点筛选** | `TopicMetadataBadges` **11 个调用点，无一传** `onTagClick` / `onCategoryClick`（组件本身支持这两个参数；全库唯二命中在死代码 `TopicListItem.kt` 与组件定义处） | `TopicMetadataBadges.kt:67-68`（定义）；调用点见 `TopicRow.kt:113`、`BookmarksScreen.kt:226`、`UserProfileScreen.kt:1104/1178/1238`、`SolvedPostsScreen.kt:212`、`ActivityScreen.kt:209`、`TopicDetailScreen.kt:1022/1075`、`ProfileScreen.kt:788` |
| S13 | 深链不导航 + 无 `onNewIntent` | `arkdo://notification` 净空转；只有 `onCreate` 处理 intent，App 活着时点通知接不到 | `MainActivity.kt:201/210`；Manifest `:41-46`；全库 `onNewIntent` **0 命中** |
| S14 | 过盾入口的用户开关缺失 | 快捷入口会渲染、订阅与 `reset()` 都已接通，但无 `ChallengeShortcutPrefs`；鸿蒙该开关默认**关** | `TopicDetailViewModel.kt:128` 自述；`NetworkSettingsScreen.kt:52` 自述；参考 `NetworkSettingsPage.ets:199-231` |
| S15 | 实验性开关只有 3/6 | 有：首页新话题提醒（**默认值反着**，见 S20）、抽奖帖提醒、帖子内新回复提醒（默认关，对齐鸿蒙）。缺 3：动态头像、点分类/标签快速筛选、智感握姿（**后者鸿蒙专有，见 §五**） | `ExperimentalSettingsScreen.kt:79-101`；参考 `ExperimentalSettingsPage.ets:40-45`（6 项）|
| S16 | HTTP 明文放行 | `usesCleartextTraffic="true"`（release 同样允许） | Manifest `:25` |
| S17 | 诊断能力降级 | 缺详细日志开关、日志大小标签、清空二次确认（仅一个「清空日志」图标，无确认） | `DebugLogScreen.kt:152`；对照 `DiagnosticsSettingsPage.ets:119-196` |
| S18 | 版本公告「源未定」 | 见 P1-12。功能写好但**默认不生效**，属于典型的「看着有、用起来没有」 | 同 P1-12 |
| S19 | **文件日志器缺失** | 鸿蒙 `services/logging/` 有 `FileLogger` + `Log`；Android 只有内存态 `DebugLogStore`（无落盘）。这直接影响线上问题取证能力 | 两侧 `services/logging/` 目录实测 |
| S20 | **`RealtimeTopicsPrefs` 默认值两侧反着** | Android 默认 **开**、鸿蒙默认 **关**（鸿蒙注释：该功能常驻后台长轮询，并非所有用户都需要）。新加的 `RealtimePostsPrefs` 已对齐鸿蒙（默认关），两者相互独立 | `RealtimeTopicsPrefs.kt:17/24`；参考 `realtime/RealtimeTopicsPrefs.ets:9` |

---

## 五、能力缺失：目录级对比看不出来（6 项）

| # | 缺口 | 参考实现 | Android 实况 | 证据 |
|---|---|---|---|---|
| N1 | **动态头像（整条能力）** | `services/avatar/AnimatedAvatarPrefs.ets`（默认**开**）：控制头像与 flair 是否播动图；关闭时改走 webview 桥取首帧 | **完全不存在**。`StorageKeys.kt:63` 有个 `PREFS_ANIMATED_AVATAR` 键，但**零引用**（死键）。注意：引用卡等处已在传 `animatedAvatar` 参数，但无开关与取帧路径 | `StorageKeys.kt:63`；参考 `avatar/AnimatedAvatarPrefs.ets` |
| N2 | **点分类/标签快速筛选（Prefs）** | `services/home/BadgeFilterPrefs.ets`（默认**关**）| 类不存在。与 §四 S11 同族——**S11 补入口时必须一起做**，否则徽章可点却没有开关约束（参考实现刻意默认关，因为误触代价不对称） | 参考 `home/BadgeFilterPrefs.ets` |
| N3 | **`TopicReadController`** | `services/reading/TopicReadController.ets` | Android `services/reading/` 只有 `ReadingTracker.kt` | 两侧目录实测 |
| N4 | **`UserProfileCache` / `UserRelationService`** | `services/user/` 3 文件 | Android `services/user/` 只有 `UserProfileService.kt` | 同上 |
| N5 | **图片失效黑名单** | `services/network/BlockedImageHosts.ets` 等 4 文件（`AvatarImageCache` / `ContentImageCache` / `BridgeImageLoader`）| 全库 0 命中 | 同上 |
| N6 | **`TopicReadStateEventBus`** | `services/session/TopicReadStateEventBus.ets` | 不存在（`TopicReadState` 只出现在 ViewModel 内）| 同上 |

> **不算缺口的**：`PulsingHalo.ets` 在**参考实现自身也是死代码**（全库仅定义处命中）——鸿蒙侧的遗留物，Android 不必补。
> **鸿蒙专有、Android 不适用**：`智感握姿`（`HoldingHandManager`）依赖 `@kit.MultimodalAwarenessKit` 传感器能力，Android 无对应标准 API。**不要记为缺口**，建议在设置页说明或直接不列。

---

## 六、P2 · 工程化与发布（12 项）

| # | 问题 | 证据 |
|---|---|---|
| 20 | release 无签名配置 | `app/build.gradle.kts` 无 `signingConfigs` |
| 21 | 无 androidTest | `**/src/androidTest/**` 实测 0 文件 |
| 22 | 无 CI | 无 `.github/` |
| 23 | `core:datastore` 空模块 | 有 `build.gradle.kts`，无 `src/main` |
| 24 | `core:common` 近乎空壳 | 仅 `RelativeTime.kt` + `Placeholder.kt` |
| 25 | `usesCleartextTraffic="true"` | Manifest `:25`（同 S16）|
| 26 | 无崩溃上报 | 无 Crashlytics / Bugly，仅本地 `DebugLogScreen` |
| 27 | 无隐私政策 / 用户协议 / 商店素材 | `TODO.md:163-165` 未勾选 |
| 28 | 无多语言、无 `values-night` | `res/` 只有 `drawable mipmap-* raw values xml` |
| 30 | `AppRoutes.kt` 死代码 | 同 D2 |
| 31 | **文档严重过期** | `TODO.md` 末行仍写「等待实现具体功能」；`MIGRATION.md` 把 20 个已完成页面列为「未做」|

> **31 单独提请注意**：`MIGRATION.md` 已失去跟踪价值，照它排期会重复劳动，建议整体重写或删除。

---

## 七、死代码与死资源（7 组）

| # | 对象 | 规模 / 证据 | 说明 |
|---|---|---|---|
| D1 | `ui/components/TopicListItem.kt` | 378 行，`TopicListItem(` 仅定义处 + 文件内自调用（已核实）| 已被 `core/designsystem/TopicRow.kt` 取代；`:51` 注释仍写「Android 尚未移植设置页」（**已过期**）|
| D2 | `navigation/AppRoutes.kt` | 163 行，除自身注释外零引用（已核实）| 真实路由在 `ArkDoNavHost.kt` 的 `Routes` |
| D3 | `services/notification/NotificationService.kt` 整个类 | 无实例化、无 Provider（已核实）| 列表实际走 `DefaultNotificationRepository` |
| D4 | `services/notification/NotificationApi.kt` 整个类 | 仅被 D3 与 `di/AppModule.kt:107` 未被注入的 Provider 引用 | `load` / `markRead` / `markAllRead` 无真实调用方 |
| D5 | 悬空路由常量 `SETTINGS_PUSH` | `ArkDoNavHost.kt:587`，全库零引用（已核实）| 做 P0-2 时直接复用，**不要另起名字** |
| D6 | 死 Prefs 常量 | `StorageKeys.kt:63` `PREFS_ANIMATED_AVATAR`（零引用，**做 N1 时复用**）；同批另有 6 个：`DRAFTS_REFRESH_TICK_KEY` / `DRAFTS_COMPOSER_VISIBLE_KEY` / `DRAFTS_COMPOSER_BACK_TICK_KEY` / `SITES_TICK` / `SETTINGS_DOH_TICK` / `APP_IS_WIDE_STORAGE_KEY` | 均只在定义处出现 |
| D7 | `core:datastore` 空模块 | 无 `src/main` | 同 P2-23 |

---

## 八、不用做：Android 已反超鸿蒙（12 条）

以下 Android 侧强于鸿蒙，**保持即可，不要「对齐」回去**：

1. **图片长按菜单**（保存相册 / 分享 / 拷贝链接）——`ui/components/ImageViewer.kt`
2. **首页悬浮按钮液态玻璃**，磨砂强度可调——`core/designsystem/FabGlassStore.kt`
3. **帖子列表样式设置**（字号 / 行高 / 内边距 / 头像 / 字重）——`TopicListStyleSettingsScreen`
4. **搜索独立垂直切片**——`feature:search`，`SearchFilters` 字段与鸿蒙全量对齐
5. **私有 DNS 引导页** `PrivateDnsGuideScreen`（鸿蒙无对应页面）
6. **Now in Android 风格模块化**——10 个 Gradle 模块，feature 垂直切片
7. **单元测试 28+ 测试类**（`CookedHtmlParser` / `LinkRouter` / `PostStreamPager` / `PollMutation` / `MessageBusEventBus` / `PostDataSource` / `TopicDetailParsing` / `LotteryTopicRule` 等，鸿蒙无对应单测）
8. **LdcCredit 拆分更细**——credit 子域 6 文件 vs 鸿蒙 4
9. **通知列表实现完整**——30/页分页、下拉刷新、上拉加载、全部已读、乐观已读、未读轮询 + 实时频道
10. **DoH 诊断能力更强**——`NetworkAccessibilityChecker`（鸿蒙无）；DoH 配置页与测速也已补齐
11. **表情目录可观察刷新**——鸿蒙在 `aboutToAppear` 读一次分组快照，表情表拉到得比面板打开晚就一直空白、要重开；Android 用 `emojiCatalogRevision: StateFlow<Int>`，表一到选择器自动刷新
12. **视频播放走 TextureView**——`res/layout/arkdo_player_view.xml` 设 `surface_type="texture_view"`，避开 SurfaceView 独立图层与 Compose 合成的黑帧交替（实测黑帧率 46% → 0%）

---

## 九、建议实施顺序

**第 1 步 · 差一次接通的（改动小、风险低）**
1. **S11 + N2 一起做**：11 个调用点接 `onTagClick` / `onCategoryClick`，同时补 `BadgeFilterPrefs`（默认关）——入口与开关是**一对**
2. **S13 深链导航 + `onNewIntent`**（`MainActivity.kt`），补 `POST_NOTIFICATIONS` 运行时申请
3. **S15 补齐其余开关**（动态头像、点分类/标签筛选；智感握姿不补）+ **S20 订正 `RealtimeTopicsPrefs` 默认值**为 `false`（属产品决策，需先确认）
4. **S19 文件日志器** + S17 诊断能力补齐
5. **S16 关明文 HTTP**（注意 DoH 生效后仍可能有 http 兜底，先确认再关）

**第 2 步 · 用户一眼能看到的**
6. **多站点 / 论坛切换**（P0-3）：`CurrentSite` + `ForumPrefs`，让 `BASE_URL` 可变；顺带解锁 P0-2 的「论坛 / 我的站点」两个入口（复用 D5 的 `SETTINGS_PUSH` 常量）
7. **版本公告定源**（P1-12 / S18）：决定 Android 自己的公告帖与账号，把 `ANNOUNCE_PROMPT_ENABLED` 置 `true`——链路已全部就绪，只差这一个决定

**第 3 步 · 横向铺开**
8. **系统推送全链路**（P0-1）：含 `services/push/` 移植、Relay 配置与同意流程、本地通知渠道、`onNewIntent` 接续
9. **平板双栏**（P1-9）/ **动态头像**（N1）

**第 4 步 · 发布准备**
10. 签名配置 + CI + androidTest 骨架（P2-20/21/22）
11. 崩溃上报、隐私政策与商店素材（P2-26/27）
12. **清理死代码 D1–D7**，重写或删除 `TODO.md` / `MIGRATION.md`（P2-31）

---

## 附：核查方法（可复跑）

```bash
# ── 路由常量 vs 注册（判据 D）──
rg -n "Routes\.SETTINGS_PUSH|Routes\.LOGIN|Routes\.CF_CHALLENGE" -g '*.kt'   # 只命中定义处 = 悬空

# ── 声明的权限/深链 → 反查调用（判据 A）──
grep -nE "uses-permission|scheme=" app/src/main/AndroidManifest.xml
rg -l "NotificationManager|NotificationCompat|NotificationChannel" -g '*.kt'   # 0 命中 = 无本地通知
rg -n "onNewIntent" -g '*.kt'                                                  # 0 命中 = 前台接不到通知

# ── 半截链路：解析了但没消费（判据 B，S12 引用卡就是这么抓到的）──
rg -n "quotePostNumber|quoteTopicId|quoteIsReply" -g '*.kt'
#   → S12 当年：只命中 RichContentTypes(定义) / CookedHtmlParser(填充) / *Test(断言)，
#     ui/ 下 0 命中 = UI 从不消费；现已由 PostRichContent.TopicQuoteBlock 消费
#   → 同一手法可复用到其它「解析齐全但没人读」的字段

# ── 零调用方（判据 C）──
rg -n "TopicListItem\(" -g '*.kt'
rg -l "AppRoutes|NotificationService|NotificationApi" -g '*.kt'
rg -n "PREFS_ANIMATED_AVATAR" -g '*.kt'

# ── 有发无收 / 反向方法零调用（判据 E）──
rg -n "enteringUsers|leavingUserIds" -g '*.kt'          # 只命中 bus = 无订阅方
rg -n "extractMessageBusTopicId" -g '*.kt'              # 只命中 test = 未接线
rg -n "onTagClick|onCategoryClick" -g '*.kt'            # 只命中组件定义 + 死代码

# ── 能力关键词命中（0 = 缺失）──
rg -l "WindowSizeClass" -g '*.kt'                       # 平板双栏
rg -l "PickMultipleVisualMedia" -g '*.kt'               # 多图上传
rg -l "Transcod" -g '*.kt'                              # 视频转码
rg -l "AnimatedAvatar|BadgeFilter" -g '*.kt'            # N1 / N2
rg -l "CurrentSite|ForumPrefs|SiteCapabilities" -g '*.kt'

# ── 工程化 ──
grep -n "signingConfig" app/build.gradle.kts ; ls -a | grep -E "^\.github" ; ls app/src/main/res/
```

> **工具提醒（又踩一次）**：macOS 自带 BSD `grep` **不支持 `\|`**（GNU 扩展）。用 `grep -rn "尚未迁移\|未迁移"` 查设置页会**静默返回空**，差点把「设置 4 入口未迁移」误判成已修。一律用 `grep -E "a|b"` 或 ripgrep（`rg`）。
> 另：`find . -path ... | wc -l` 这类长组合会被沙箱 `SIGTERM` 杀掉（exit 137），统计类任务改用 Glob/Grep 工具。
