# ArkDO-Android 功能缺口分析

> 对标对象：鸿蒙版 ArkDO（ArkTS · `@7680996` · 28 页面 / 47 组件 / 25 服务子域）
> 本项目：`feat/migrate-vertical-slice`
> 规则：只记「现在还没做的」。做完删一条 —— 不记版本号、不记修复历史、不做已修项记账。

---

## 一、结论先行

**还剩 23 项 + 5 组死代码。**

### 1.1 缺口分布

| 分类 | 数量 | 判据 |
|---|---|---|
| P0 | 3 | 用户一眼能看出没做 |
| P1 | 2 | 功能不完整，用起来别扭 |
| 半截 | 4 | 数据 / 组件都在，只差一次接通（用户会当 bug 报）|
| 能力缺失 | 5 | 目录级对比看不出来的缺失 |
| P2 | 9 | 工程化与发布 |
| 死代码 | 5 组 | 看着有实现，其实是死的，会误导判断 |
| **合计** | **23** | 上表各项之和（3+2+4+5+9），与逐行点数一致；死代码组单列不计入 |

模块对齐度：首页 92% / 搜索 98% / 通知 95% / 详情 85% / 发帖 92% / 回帖 86% / 表情 85% / 音视频 92% / 我的 88% / 用户主页 88% / **实验性功能 50%** / **设置 65%** / **多站点 40%**（6/12 文件，但仅支持单站点，`BASE_URL` 硬编码、无法切换）/ **系统推送 15%**（仅本地通知渠道 + 权限申请，投递链路未迁）/ 平板适配 99%（双栏 + 高度闸门 + 折叠感知 + `configChanges` + 宽屏转场 + 铰链对齐留白 + 展开态尺寸兜底 + 5 档 WSC 分组 + **书本姿态双页阅读**，均在真实平板/折叠 AVD 实测；残余 2 项见 P1-13）。

### 1.2 隐性缺口（最值钱的两个）

纯目录对比只能得到「少了哪几个文件」。真正值钱的是**看着有、其实没接上**的那类：

1. **动态头像不是「开关没做」，是整个能力没做**（§五 N1）

---

## 二、P0 · 一眼能看出没做（3 项）

| # | 要做什么 | 现在是什么样 | 关键证据 |
|---|---|---|---|
| 1 | **系统推送 + 本地通知**（权限与渠道已就位，缺投递链路） | `services/push/` 仍不存在（鸿蒙 12 文件：`PushCoordinator` / `PushRelayClient` / `PushRelayConfig` / `PushSubscriptionService` / `PushSubscriptionStore` / `PushToken` …）——「服务端把通知推下来」这一整条还缺。**已就位**：通知渠道创建（`SystemNotifier`，API 26 起无渠道通知会被静默丢弃）、`POST_NOTIFICATIONS` 运行时申请与三态处理、App 活着时点通知的续接（`onNewIntent` + `DeepLinkEventBus`）| 鸿蒙 `services/push/` 12 文件；Android 侧已就位件见 §九 第 3 步 |
| 2 | **设置 2 入口未迁移** | 「网络与连接 → DoH」「通知」已落地；**论坛 / 我的站点** 仍弹 Toast。两者都卡在 P0-3 的 `CurrentSite` 上——论坛入口要显示当前站点 host，我的站点要列出可选站点 | `SettingsScreen.kt`（`NOT_MIGRATED = "尚未迁移"`）两处 `toastNotMigrated` |
| 3 | **多站点 / 论坛切换** | `AppConstants.BASE_URL` **硬编码 `https://linux.do`**（`TopicService` 内 20+ 处 `"${BASE_URL}/t/topic/$id"`、`SiteSettingsService` / `DraftService` / `WebSessionManager` 同样直接读它）；Android `services/site/` **6 文件** vs 鸿蒙 **12**，缺 **6 个**：`CurrentSite` / `ForumPrefs` / `SiteCapabilities` / `SiteScope` / `SiteSwitchCoordinator` / `SiteUrl`；鸿蒙的 `ForumsPage` / `SitesPage` / `ForumOnboardingPage` 三页无对应。**首页抽屉的「我的站点」快捷条也挂在这里**——它受 `SiteCapabilities.ecosystemSites`（复用主站 OAuth 免登录进生态站点，换站后无从谈起）与设置页开关双重门控，两个前置在 Android 都不存在 | `AppConstants.kt`；`rg -n "BASE_URL" -g '*.kt'` 实测；两侧 `services/site/` 目录实测（2026-10-01 复核：Android 已有 `CategoryIndex`/`FeedPrefs`/`SiteIconStore`/`SitePrefs`/`SiteService`/`SiteSettingsService`）|

**依赖关系**：`多站点` 是 `设置` 里「论坛 / 我的站点」两个子页的前置。做 P0-2 的两个入口前必须先有 `CurrentSite`。

---

## 三、P1 · 功能不完整（1 项）

| # | 要做什么 | 鸿蒙版 | 现在是什么样 | 证据 |
|---|---|---|---|---|
| 12 | **版本公告源未定** | `announce/VersionAnnouncement.ets` | 代码完整，但 `ANNOUNCE_PROMPT_ENABLED = false` → **不扫描、不弹层**。当前源是鸿蒙版发布日志帖（`2590051` / `joeyyin`），`[v900008+]` 体系与 Android `versionCode` 对不上，开着会每次冷启动误弹鸿蒙公告 | `services/announce/VersionAnnouncement.kt:15/17/22`；`RootTabScreen.kt:160`（`if (!ANNOUNCE_PROMPT_ENABLED) return@LaunchedEffect`）|
| 13 | **大屏适配残余 2 项** | `common/utils/BreakpointManager.ets` 等 | 主线（双栏 / 高度闸门 / 折叠感知 / `configChanges` / 宽屏转场 / 铰链对齐留白 / 展开态尺寸兜底 / 5 档 WSC 分组）**已全部落地实测**，**并已加做 Tier 1 姿态里的书本姿态**；残余两项：**①桌面姿态的媒体上下分区**（Tier 1 的另一半，等视频播放页需要时再做）；②**相机预览方向**（当前无拍照/发图入口，等真加拍照时按 CameraX `PreviewView` + 窗口 metrics 实现，别用屏幕尺寸） | 书本姿态已落成**详情页「双页阅读」**（`TwoPageReading` + `TwoPageReadingPrefs`，**开关控制、默认关**，见 `docs/adaptive-devices.md` §3.2 F 行与 §5.4 实测）；桌面姿态与相机见同文 §四 |

> **P1-9 平板双栏已落地**（原「`WindowSizeClass` 0 命中」那条）：
> `common/layout/WindowBreakpoints.kt` 判定 + `common/layout/WindowLayout.kt` 的 `WideLayoutHost`
> + `ArkDoNavHost` 左右分栏（左栏常驻 `RootTabScreen`、右栏内容栈，栈底 `ContentEmptyPane` 空态）。
> 验证分两层：① `wm size/density` 伪造形态，四种全过（平板横屏双栏 / 点左栏换帖右栏跟随 / 返回回空态 / 平板竖屏回落单栏）；
> ② 另建**真实平板 AVD**（`medium_tablet`，2560×1600@320dpi = 1280×800dp）复验，补上伪造法原理上测不到的三项 ——
> 真实物理旋转横↔竖往返（**导航栈未重建**，转回横屏后右栏仍停在原详情）、真实系统栏 insets、
> **自由窗口缩放的宽窄判定**（窗口 640dp → 单栏，1070dp → 双栏，而屏幕恒为 1280dp：证明读的是窗口实际约束
> 而非屏幕 Configuration，用 `LocalConfiguration.screenWidthDp` 在这里会误判成双栏）。
>
> **折叠感知已接入并实测**（原「残留：没有检测源」已消除）：
> 加 `androidx.compose.material3.adaptive:adaptive`（由 Compose BOM 管理），新增纯函数 `common/layout/FoldPosture.kt`
> 判定「内屏是否铺开」，`rememberUnfoldedPosture()` 用 `collectFoldingFeaturesAsState()` 取真实折叠特征，
> 喂给 `WideLayoutHost(unfolded = …)` —— 原先恒为默认 `false` 的空转参数已接上。
>
> **同时补掉一个真 bug**：`isWideLayout` 原只有「宽 ≥840 且宽 > 高」两刀，**缺高度闸门**，会把**横屏手机**
> 判成双栏（Pixel 8 Pro 横屏 997×448dp 稳定命中，而 448dp 高并排两栏没法用）。已按官方口径加
> `高度 ≥ 480dp`（`WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND`）闸门，实测 997×448 → 单栏、1280×800 → 双栏。
> Manifest 另补 `configChanges`（密度那组），实测冷启后连续切三次姿态 `onCreate` **恒为 1 次**、Activity 不重建。
>
> **实测方法（新发现，重要）**：模拟器控制台能直接驱动铰链姿态，应用侧会收到**真实 `FoldingFeature`** ——
> `adb emu fold` / `unfold` / `posture 4` 即可复现，**不需要真折叠设备**。
> 靶子选 `8in Foldable` AVD：2200×2480@420dpi = **838×945dp**，差 2dp 够不到 840 阈值，
> 尺寸路径彻底失效 —— 单栏 ↔ 双栏的每次切换都只可能来自折叠感知，**排除蒙混过关**。
> 实测：`fold`/`posture 4` → `HALF_OPENED` + 竖直折痕 → `unfolded=true` → 双栏
> （点左栏帖子，右栏出「帖子详情」且**左栏滚动位置不丢**）；`unfold`/`posture 3` → 特征为空 → 单栏。可稳定复现。
>
> **订正两处旧说法**：① 此前记「无折叠形态 AVD 可验证」**不成立** —— `8in Foldable` 档案可用（镜像现成）；
> ② 此前说「这些 AVD 是否真上报 `FoldingFeature` 尚未实测」—— **现已实测：确实上报**（见上）。
>
> **已修**：模拟器在**完全展开**时**不上报 `FLAT`**（返回空列表），真机（Pixel Fold 类）会报。
> 原先「展开态靠折叠信号」的写法在这台设备上会让 838×945dp 的内屏退化成单栏。
> 现已把分栏判定改成**纯尺寸驱动**（`isWideLayout` 里已经没有折叠参数）：同一台 AVD、同一个展开态，
> 日志仍是 `folding features=[]`，但实测已从单栏变**双栏**（左栏 1100px）。
> 折叠信号收敛到两处用途 —— 左栏宽度上限豁免、**铰链对齐**。
>
> **铰链对齐同批落地**：`FoldPosture.verticalSplitHinge()` 取「竖直 + 分隔 + 带 `bounds`」的铰链，
> 分栏线压到铰链左缘、并把铰链占宽留成空隙。折叠 AVD 实测左栏右缘 1148 / 右栏起点 1149 正好框住铰链
> （对半算法会落 1100，分栏线偏 48px，右栏内容压在铰链上）。**5 档 WSC 分组**（左栏上限按档 420/480/560）
> 也在同一批：平板 AVD 1280dp 档实测左栏 420 → **480dp**。
>
> 剩余只有**桌面姿态**与**相机预览方向**两项，均属「有需求再做」。**详见 P1-13**。

---

## 四、半截 · 差一次接通（4 项）

**这类最该先修**：骨架、数据、组件往往都已就位，只差一行转发或一次订阅。

| # | 现象 | 根因 | 证据 |
|---|---|---|---|
| S7 | 圆形头像先方后圆闪一下 | `onOpenUser` 未透传 userId，种子 `id=0`。**待实机复核**：判据是 `id == 1L` 走方形，种子恒为圆形，静态推不出该现象 | `UserProfileScreen.kt:99`、`UserProfileViewModel.kt:72` |
| S14 | 过盾入口的用户开关缺失 | 快捷入口会渲染、订阅与 `reset()` 都已接通，但无 `ChallengeShortcutPrefs`；鸿蒙该开关默认**关** | `TopicDetailViewModel.kt:128` 自述；`NetworkSettingsScreen.kt:52` 自述；参考 `NetworkSettingsPage.ets:199-231` |
| S15 | 实验性开关 5/6 | 有：首页新话题提醒、抽奖帖提醒、帖子内新回复提醒、点分类/标签快速筛选（S11 落地时补上，默认关）。缺 1：动态头像（随 N1 一起做）。智感握姿鸿蒙专有不移植（见 §五） | `ExperimentalSettingsScreen.kt`；参考 `ExperimentalSettingsPage.ets:40-45`（6 项）|
| S18 | 版本公告「源未定」 | 见 P1-12。功能写好但**默认不生效**，属于典型的「看着有、用起来没有」 | 同 P1-12 |

---

## 五、能力缺失：目录级对比看不出来（5 项）

| # | 缺口 | 参考实现 | Android 实况 | 证据 |
|---|---|---|---|---|
| N1 | **动态头像（整条能力）** | `services/avatar/AnimatedAvatarPrefs.ets`（默认**开**）：控制头像与 flair 是否播动图；关闭时改走 webview 桥取首帧 | **完全不存在**。`StorageKeys.kt:63` 有个 `PREFS_ANIMATED_AVATAR` 键，但**零引用**（死键）。注意：引用卡等处已在传 `animatedAvatar` 参数，但无开关与取帧路径 | `StorageKeys.kt:63`；参考 `avatar/AnimatedAvatarPrefs.ets` |
| N3 | **`TopicReadController`** | `services/reading/TopicReadController.ets` | Android `services/reading/` 只有 `ReadingTracker.kt` | 两侧目录实测 |
| N4 | **`UserProfileCache` / `UserRelationService`** | `services/user/` 3 文件 | Android `services/user/` 只有 `UserProfileService.kt` | 同上 |
| N5 | **图片失效黑名单** | `services/network/BlockedImageHosts.ets` 等 4 文件（`AvatarImageCache` / `ContentImageCache` / `BridgeImageLoader`）| 全库 0 命中 | 同上 |
| N6 | **`TopicReadStateEventBus`** | `services/session/TopicReadStateEventBus.ets` | 不存在（`TopicReadState` 只出现在 ViewModel 内）| 同上 |

> **不算缺口的**：`PulsingHalo.ets` 在**参考实现自身也是死代码**（全库仅定义处命中）——鸿蒙侧的遗留物，Android 不必补。
> **鸿蒙专有、Android 不适用**：`智感握姿`（`HoldingHandManager`）依赖 `@kit.MultimodalAwarenessKit` 传感器能力，Android 无对应标准 API。**不要记为缺口**，建议在设置页说明或直接不列。

---

## 六、P2 · 工程化与发布（9 项）

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
| 31 | **文档严重过期** | `TODO.md` 末行仍写「项目骨架搭建完成…等待实现具体功能」+「下一步：实现 WebViewNetworkBridge」（该桥早已落地）；`MIGRATION.md` 的「里程碑二+」整章（清单自述 **~80 服务 / ~35 页面 / ~50 组件**）**几乎全部已完成**，却仍标为待办；且其基线写 `dc50bd0`，与台账用的 `7680996` 不是同一个点 |

> **31 单独提请注意**：`MIGRATION.md` 已失去跟踪价值 —— 它的「已完成」章节停在里程碑一（`2026-08-11`），而「里程碑二+」里列的待办绝大部分已经做完；照它排期会大量重复劳动。`TODO.md` 同理（末行的「下一步」指向早已落地的 WebView 桥）。**两份都建议整体重写或直接删除**，避免误导后续判断。**唯一权威是本文。**

---

## 七、死代码与死资源（5 组）

| # | 对象 | 规模 / 证据 | 说明 |
|---|---|---|---|
| D3 | `services/notification/NotificationService.kt` 整个类 | 无实例化、无 Provider（已核实）| 列表实际走 `DefaultNotificationRepository` |
| D4 | `services/notification/NotificationApi.kt` 整个类 | 仅被 D3 与 `di/AppModule.kt:107` 未被注入的 Provider 引用 | `load` / `markRead` / `markAllRead` 无真实调用方 |
| D6 | 死 Prefs 常量 | `StorageKeys.kt:63` `PREFS_ANIMATED_AVATAR`（零引用，**做 N1 时复用**）；同批另有 6 个：`DRAFTS_REFRESH_TICK_KEY` / `DRAFTS_COMPOSER_VISIBLE_KEY` / `DRAFTS_COMPOSER_BACK_TICK_KEY` / `SITES_TICK` / `SETTINGS_DOH_TICK` / `APP_IS_WIDE_STORAGE_KEY` | 均只在定义处出现 |
| D7 | `core:datastore` 空模块 | 无 `src/main` | 同 P2-23 |
| D8 | `Routes.CF_CHALLENGE` 悬空常量 | `ArkDoNavHost.kt:712` 定义，**全库零注册零引用**（已核实）| 是「以为要走路由」的遗留物：过盾页面 `ui/screens/CfChallengeScreen.kt`（313 行，**活代码**）实际由 `MainActivity.kt:189` 内联托管，从不经 NavHost。**常量删掉即可，页面不要动** |

> 已清理：`ui/components/TopicListItem.kt`（原 D1）与 `navigation/AppRoutes.kt`（原 D2）已于 2026-10-02 删除（逐符号复核零代码引用后编译验证）。

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
1. ~~**S11 + N2 一起做**~~ **已完成**：首页行徽章可点筛选（`TopicRow` 加 `badgesTappable`/回调，
   `HomeRoute` 实现点击 → `setQuery` + toast，语义对齐鸿蒙 `handleTagClick`/`handleCategoryClick`）；
   `BadgeFilterPrefs`（feature:home，默认关）+ 实验性设置页开关。**范围订正**：鸿蒙只有首页一个
   调用点接了点击（`HomePage.ets:719`），其余页面（书签/用户主页等）两侧都不传回调是**正确**的——
   原先「11 个调用点无一传」里只有首页那一处是真缺口
2. **S15 补齐剩余开关**（只剩动态头像，随 N1 做；智感握姿不补）。~~S20 订正默认值~~ **已完成**
3. ~~**P1-13 ①「展开态尺寸兜底」**~~ **已完成**：`isWideLayout` 改为纯尺寸驱动（去掉 `unfolded` 入参），
   折叠信号收敛为「左栏上限豁免 + 铰链对齐」两处用途。顺带做掉 P1-13 ②（铰链避让）③（5 档 WSC 分组）

**第 2 步 · 用户一眼能看到的**
4. **多站点 / 论坛切换**（P0-3）：`CurrentSite` + `ForumPrefs`，让 `BASE_URL` 可变；顺带解锁 P0-2 的「论坛 / 我的站点」两个入口。`UploadService.mediaUploadEnabled()` 现在硬写 `true` 并留了注释「等多站点落地后改走 `CurrentSite` 判定」——**落地时顺手把它接上，判据只留那一处**
5. **版本公告定源**（P1-12 / S18）：决定 Android 自己的公告帖与账号，把 `ANNOUNCE_PROMPT_ENABLED` 置 `true`——链路已全部就绪，只差这一个决定

**第 3 步 · 横向铺开**
6. **系统推送全链路**（P0-1）：只剩 `services/push/` 移植（鸿蒙 12 文件：`PushCoordinator` / `PushRelayClient` / `PushRelayConfig` / `PushSubscriptionService` / `PushSubscriptionStore` / `PushRefreshGate` / `PushNotificationNavigation` / `PushNotificationNavigationEventBus` / `PushTokenUpdateEventBus` / `HarmonyPushTokenProvider`（→ FCM 对应物）/ `PushNotificationPermissionService` / `PushTokenDebugLog`）与 Relay 同意流程。**前置已全部就位**——通知渠道（`services/notification/SystemNotifier.kt`）、`POST_NOTIFICATIONS` 申请与三态处理（`services/notification/NotificationPermission.kt` + `ui/screens/PushSettingsScreen.kt`，入口在 设置 → 通知）、深链接续（`onNewIntent` + `DeepLinkEventBus`）。推送到达后直接调 `SystemNotifier` 即可，不必再动权限与渠道
7. **动态头像**（N1）

**第 4 步 · 大屏深化（P1-13 剩余 2 项：桌面姿态、相机预览方向，按需排入）**
8. Tier 1 姿态（书本姿态双页阅读对论坛类有真实价值，需先定「做不做」+ 过 `supportedPostures` 检查）；
   相机预览方向等到有拍照/发图需求再做。~~铰链避让 / 5 档 WSC 分组~~ **已完成**
   另：官方 Tier 2 还要求「网格与多列」，属列表组件改动，目前未立项

**第 5 步 · 发布准备**
9. 签名配置 + CI + androidTest 骨架（P2-20/21/22）
10. 崩溃上报、隐私政策与商店素材（P2-26/27）
11. **清理死代码 D3/D4/D6/D7/D8**，重写或删除 `TODO.md` / `MIGRATION.md`（P2-31）

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
rg -l "NotificationService|NotificationApi" -g '*.kt'
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
