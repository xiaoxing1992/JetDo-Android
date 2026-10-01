# ArkDO-Android 功能缺口分析

> 对标对象：鸿蒙版 ArkDO（ArkTS · `@7680996` · 28 页面 / 47 组件 / 25 服务子域）
> 本项目：`feat/migrate-vertical-slice`
> 规则：只记「现在还没做的」。做完删一条 —— 不记版本号、不记修复历史、不做已修项记账。

---

## 一、结论先行

**还剩 26 项 + 6 组死代码。**

### 1.1 缺口分布

| 分类 | 数量 | 判据 |
|---|---|---|
| P0 | 3 | 用户一眼能看出没做 |
| P1 | 1 | 功能不完整，用起来别扭 |
| 半截 | 6 | 数据 / 组件都在，只差一次接通（用户会当 bug 报）|
| 能力缺失 | 6 | 目录级对比看不出来的缺失 |
| P2 | 10 | 工程化与发布 |
| 死代码 | 6 组 | 看着有实现，其实是死的，会误导判断 |
| **合计** | **26** | 上表各项之和，与逐行点数一致 |

模块对齐度：首页 92% / 搜索 98% / 通知 95% / 详情 85% / 发帖 92% / 回帖 86% / 表情 85% / 音视频 92% / 我的 88% / 用户主页 88% / **实验性功能 50%** / **设置 65%** / **多站点 30%** / **系统推送 15%**（仅本地通知渠道 + 权限申请，投递链路未迁）/ 平板适配 90%（平板横屏双栏已落地，折叠屏展开态缺检测源）。

### 1.2 隐性缺口（最值钱的两个）

纯目录对比只能得到「少了哪几个文件」。真正值钱的是**看着有、其实没接上**的那类：

1. **徽章点击的缺口比清单上记的更广**——不是「某一行没传回调」，而是 **11 个调用点无一传**（§四 S11）
2. **动态头像不是「开关没做」，是整个能力没做**（§五 N1）

---

## 二、P0 · 一眼能看出没做（3 项）

| # | 要做什么 | 现在是什么样 | 关键证据 |
|---|---|---|---|
| 1 | **系统推送 + 本地通知**（权限与渠道已就位，缺投递链路） | `services/push/` 仍不存在（鸿蒙 12 文件：`PushCoordinator` / `PushRelayClient` / `PushRelayConfig` / `PushSubscriptionService` / `PushSubscriptionStore` / `PushToken` …）——「服务端把通知推下来」这一整条还缺。**已就位**：通知渠道创建（`SystemNotifier`，API 26 起无渠道通知会被静默丢弃）、`POST_NOTIFICATIONS` 运行时申请与三态处理、App 活着时点通知的续接（`onNewIntent` + `DeepLinkEventBus`）| 鸿蒙 `services/push/` 12 文件；Android 侧已就位件见 §九 第 3 步 |
| 2 | **设置 2 入口未迁移** | 「网络与连接 → DoH」「通知」已落地；**论坛 / 我的站点** 仍弹 Toast。两者都卡在 P0-3 的 `CurrentSite` 上——论坛入口要显示当前站点 host，我的站点要列出可选站点 | `SettingsScreen.kt`（`NOT_MIGRATED = "尚未迁移"`）两处 `toastNotMigrated` |
| 3 | **多站点 / 论坛切换** | `AppConstants.BASE_URL` **硬编码 `https://linux.do`**；Android `services/site/` **5 文件** vs 鸿蒙 **12**，缺 `CurrentSite` / `ForumPrefs` / `SiteCapabilities` / `SiteScope` / `SiteUrl` / `SiteSwitchCoordinator` / `SiteSettingsService`；鸿蒙的 `ForumsPage` / `SitesPage` / `ForumOnboardingPage` 三页无对应。**首页抽屉的「我的站点」快捷条也挂在这里**——它受 `SiteCapabilities.ecosystemSites`（复用主站 OAuth 免登录进生态站点，换站后无从谈起）与设置页开关双重门控，两个前置在 Android 都不存在 | `AppConstants.kt:8`、`:21`；两侧 `services/site/` 目录实测 |

**依赖关系**：`多站点` 是 `设置` 里「论坛 / 我的站点」两个子页的前置。做 P0-2 的两个入口前必须先有 `CurrentSite`。

---

## 三、P1 · 功能不完整（1 项）

| # | 要做什么 | 鸿蒙版 | 现在是什么样 | 证据 |
|---|---|---|---|---|
| 12 | **版本公告源未定** | `announce/VersionAnnouncement.ets` | 代码完整，但 `ANNOUNCE_PROMPT_ENABLED = false` → **不扫描、不弹层**。当前源是鸿蒙版发布日志帖（`2590051` / `joeyyin`），`[v900008+]` 体系与 Android `versionCode` 对不上，开着会每次冷启动误弹鸿蒙公告 | `services/announce/VersionAnnouncement.kt:15/17/22`；`RootTabScreen.kt:139`（`if (!ANNOUNCE_PROMPT_ENABLED) return@LaunchedEffect`）|

> **P1-9 平板双栏已落地**（原「`WindowSizeClass` 0 命中」那条）：
> `common/layout/WindowBreakpoints.kt` 判定 + `common/layout/WindowLayout.kt` 的 `WideLayoutHost`
> + `ArkDoNavHost` 左右分栏（左栏常驻 `RootTabScreen`、右栏内容栈，栈底 `ContentEmptyPane` 空态）。
> 验证分两层：① `wm size/density` 伪造形态，四种全过（平板横屏双栏 / 点左栏换帖右栏跟随 / 返回回空态 / 平板竖屏回落单栏）；
> ② 另建**真实平板 AVD**（`medium_tablet`，2560×1600@320dpi = 1280×800dp）复验，补上伪造法原理上测不到的三项 ——
> 真实物理旋转横↔竖往返（**导航栈未重建**，转回横屏后右栏仍停在原详情）、真实系统栏 insets、
> **自由窗口缩放的宽窄判定**（窗口 640dp → 单栏，1070dp → 双栏，而屏幕恒为 1280dp：证明读的是窗口实际约束
> 而非屏幕 Configuration，用 `LocalConfiguration.screenWidthDp` 在这里会误判成双栏）。
>
> **残留（不计入上表）**：折叠屏展开态那条路径（`isWideLayout(..., unfolded = true)`）**已实现且有单测覆盖**，
> 但**没有检测源** —— Android 侧要拿 `androidx.window` 的 `FoldingFeature.isSeparating`。按「不做无法验证的
> 空壳」的规矩先不接；接入只需给 `WideLayoutHost` 传一个非默认的 `unfolded`，判定与布局无需改动。
>
> **订正**：此前记为「无折叠设备、也没有折叠形态的 AVD 可验证」，**该理由不成立** —— `avdmanager list device`
> 里有 `8in Fold-out`、`6.7in Fold-in`、`7.6in Fold-in with outer display` 三个折叠档案，配合已有的
> android-35 镜像可直接建 AVD（无需下载）。不过这些 AVD 是否真把折叠姿态经 `FoldingFeature` 上报给应用
> **尚未实测**；至少「没有验证手段」这一条是错的。真正的缺口是 `androidx.window` 依赖与姿态检测代码。
> 影响面：竖折叠内屏竖持（如 741×832）会走单栏。展开横持（939×664）不受影响 —— 它由「≥840 且宽 > 高」那条判为双栏。

---

## 四、半截 · 差一次接通（6 项）

**这类最该先修**：骨架、数据、组件往往都已就位，只差一行转发或一次订阅。

| # | 现象 | 根因 | 证据 |
|---|---|---|---|
| S7 | 圆形头像先方后圆闪一下 | `onOpenUser` 未透传 userId，种子 `id=0`。**待实机复核**：判据是 `id == 1L` 走方形，种子恒为圆形，静态推不出该现象 | `UserProfileScreen.kt:99`、`UserProfileViewModel.kt:72` |
| S11 | **分类 / 标签徽章不可点筛选** | `TopicMetadataBadges` **11 个调用点，无一传** `onTagClick` / `onCategoryClick`（组件本身支持这两个参数；全库唯二命中在死代码 `TopicListItem.kt` 与组件定义处） | `TopicMetadataBadges.kt:67-68`（定义）；调用点见 `TopicRow.kt:113`、`BookmarksScreen.kt:226`、`UserProfileScreen.kt:1104/1178/1238`、`SolvedPostsScreen.kt:212`、`ActivityScreen.kt:209`、`TopicDetailScreen.kt:1022/1075`、`ProfileScreen.kt:788` |
| S14 | 过盾入口的用户开关缺失 | 快捷入口会渲染、订阅与 `reset()` 都已接通，但无 `ChallengeShortcutPrefs`；鸿蒙该开关默认**关** | `TopicDetailViewModel.kt:128` 自述；`NetworkSettingsScreen.kt:52` 自述；参考 `NetworkSettingsPage.ets:199-231` |
| S15 | 实验性开关只有 3/6 | 有：首页新话题提醒（**默认值反着**，见 S20）、抽奖帖提醒、帖子内新回复提醒（默认关，对齐鸿蒙）。缺 3：动态头像、点分类/标签快速筛选、智感握姿（**后者鸿蒙专有，见 §五**） | `ExperimentalSettingsScreen.kt:79-101`；参考 `ExperimentalSettingsPage.ets:40-45`（6 项）|
| S18 | 版本公告「源未定」 | 见 P1-12。功能写好但**默认不生效**，属于典型的「看着有、用起来没有」 | 同 P1-12 |
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

## 六、P2 · 工程化与发布（10 项）

| # | 问题 | 证据 |
|---|---|---|
| 20 | release 无签名配置 | `app/build.gradle.kts` 无 `signingConfigs` |
| 21 | 无 androidTest | `**/src/androidTest/**` 实测 0 文件 |
| 22 | 无 CI | 无 `.github/` |
| 23 | `core:datastore` 空模块 | 有 `build.gradle.kts`，无 `src/main` |
| 24 | `core:common` 近乎空壳 | 仅 `RelativeTime.kt` + `Placeholder.kt` |
| 26 | 无崩溃上报 | 无 Crashlytics / Bugly，仅本地 `DebugLogScreen` |
| 27 | 无隐私政策 / 用户协议 / 商店素材 | `TODO.md:163-165` 未勾选 |
| 28 | 无多语言、无 `values-night` | `res/` 只有 `drawable mipmap-* raw values xml` |
| 30 | `AppRoutes.kt` 死代码 | 同 D2 |
| 31 | **文档严重过期** | `TODO.md` 末行仍写「等待实现具体功能」；`MIGRATION.md` 把 20 个已完成页面列为「未做」|

> **31 单独提请注意**：`MIGRATION.md` 已失去跟踪价值，照它排期会重复劳动，建议整体重写或删除。

---

## 七、死代码与死资源（6 组）

| # | 对象 | 规模 / 证据 | 说明 |
|---|---|---|---|
| D1 | `ui/components/TopicListItem.kt` | 378 行，`TopicListItem(` 仅定义处 + 文件内自调用（已核实）| 已被 `core/designsystem/TopicRow.kt` 取代；`:51` 注释仍写「Android 尚未移植设置页」（**已过期**）|
| D2 | `navigation/AppRoutes.kt` | 163 行，除自身注释外零引用（已核实）| 真实路由在 `ArkDoNavHost.kt` 的 `Routes` |
| D3 | `services/notification/NotificationService.kt` 整个类 | 无实例化、无 Provider（已核实）| 列表实际走 `DefaultNotificationRepository` |
| D4 | `services/notification/NotificationApi.kt` 整个类 | 仅被 D3 与 `di/AppModule.kt:107` 未被注入的 Provider 引用 | `load` / `markRead` / `markAllRead` 无真实调用方 |
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
2. **S15 补齐其余开关**（动态头像、点分类/标签筛选；智感握姿不补）+ **S20 订正 `RealtimeTopicsPrefs` 默认值**为 `false`（属产品决策，需先确认）

**第 2 步 · 用户一眼能看到的**
3. **多站点 / 论坛切换**（P0-3）：`CurrentSite` + `ForumPrefs`，让 `BASE_URL` 可变；顺带解锁 P0-2 的「论坛 / 我的站点」两个入口。`UploadService.mediaUploadEnabled()` 现在硬写 `true` 并留了注释「等多站点落地后改走 `CurrentSite` 判定」——**落地时顺手把它接上，判据只留那一处**
4. **版本公告定源**（P1-12 / S18）：决定 Android 自己的公告帖与账号，把 `ANNOUNCE_PROMPT_ENABLED` 置 `true`——链路已全部就绪，只差这一个决定

**第 3 步 · 横向铺开**
5. **系统推送全链路**（P0-1）：只剩 `services/push/` 移植（`PushCoordinator` / `PushRelayClient` / `PushRelayConfig` / `PushSubscriptionService` / `PushSubscriptionStore` / `PushToken` …）与 Relay 同意流程。**前置已全部就位**——通知渠道（`services/notification/SystemNotifier.kt`）、`POST_NOTIFICATIONS` 申请与三态处理（`services/notification/NotificationPermission.kt` + `ui/screens/PushSettingsScreen.kt`，入口在 设置 → 通知）、深链接续（`onNewIntent` + `DeepLinkEventBus`）。推送到达后直接调 `SystemNotifier` 即可，不必再动权限与渠道
6. **动态头像**（N1）

**第 4 步 · 发布准备**
7. 签名配置 + CI + androidTest 骨架（P2-20/21/22）
8. 崩溃上报、隐私政策与商店素材（P2-26/27）
9. **清理死代码 D1–D7**，重写或删除 `TODO.md` / `MIGRATION.md`（P2-31）

---

## 附：核查方法（可复跑）

```bash
# ── 路由常量 vs 注册（判据 D）──
rg -n "Routes\.SETTINGS_PUSH|Routes\.LOGIN|Routes\.CF_CHALLENGE" -g '*.kt'   # 只命中定义处 = 悬空

# ── 声明的权限/深链 → 反查调用（判据 A）──
grep -nE "uses-permission|scheme=" app/src/main/AndroidManifest.xml
rg -l "NotificationChannel|NotificationManagerCompat" -g '*.kt'                # 命中 SystemNotifier = 本地通知渠道就位
rg -n "POST_NOTIFICATIONS" -g '*.kt'                                           # 命中 NotificationPermission = 运行时申请就位
rg -n "SETTINGS_PUSH" -g '*.kt'                                                # 应命中 Routes 定义 + 注册处 + 设置页导航
rg -n "onNewIntent" -g '*.kt'                                                  # 命中 MainActivity = 前台能接通知
rg -n "usesCleartextTraffic" app/src/main/AndroidManifest.xml                  # 0 命中 = 明文已关（改走 @xml/network_security_config）
rg -n "DeepLinkEventBus|FileLogger" -g '*.kt'                                  # 深链接续 / 日志落盘

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
rg -n "WindowBreakpoints|WideLayoutHost|ContentEmptyPane" -g '*.kt'   # 平板双栏（P1-9）：判定 + 宿主 + 右栏空态
rg -l "WindowSizeClass" -g '*.kt'                       # 平板双栏的另一条路（本侧刻意不用它，0 命中属正常：改用 BoxWithConstraints 读实际约束，分屏才准）
rg -l "PickMultipleVisualMedia" -g '*.kt'               # 多图上传
rg -l "Transcod" -g '*.kt'                              # 视频转码
rg -l "AnimatedAvatar|BadgeFilter" -g '*.kt'            # N1 / N2
rg -l "CurrentSite|ForumPrefs|SiteCapabilities" -g '*.kt'
# 顶栏放大镜共享元素转场的接线点（P1-11 已落地；查它是否被拆掉/退化）：
#   宿主 SharedTransitionLayout → core/designsystem/SharedSearchTransition.kt → 两端 searchIconSharedElement()
rg -n "SharedTransitionLayout|searchIconSharedElement|SEARCH_ICON_SHARED_KEY" -g '*.kt'

# ── 工程化 ──
grep -n "signingConfig" app/build.gradle.kts ; ls -a | grep -E "^\.github" ; ls app/src/main/res/
```

> **工具提醒（又踩一次）**：macOS 自带 BSD `grep` **不支持 `\|`**（GNU 扩展）。用 `grep -rn "尚未迁移\|未迁移"` 查设置页会**静默返回空**，差点把「设置 4 入口未迁移」误判成已修。一律用 `grep -E "a|b"` 或 ripgrep（`rg`）。
> 另：`find . -path ... | wc -l` 这类长组合会被沙箱 `SIGTERM` 杀掉（exit 137），统计类任务改用 Glob/Grep 工具。
