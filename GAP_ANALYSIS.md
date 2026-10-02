# ArkDO-Android 功能缺口分析

> 对标对象：鸿蒙版 ArkDO（ArkTS · main@`7680996` · 28 页面 / 47 组件 / 25 服务子域）
> 本项目：`feat/migrate-vertical-slice` @ `b731be8`（工作区干净，零未提交改动）
> 复核日期：2026-10-02（全量重扫：目录级逐文件对比 + 隐性缺口五判据 + 工程化 + 文档债，三路独立扫描后逐条交叉复核）
> 规则：只记「现在还没做的」。做完删一条 —— 不记版本号、不记修复历史、不做已修项记账。

---

## 一、结论先行

**还剩 14 项 + 4 组死代码 + 2 条微清理。**

### 1.1 缺口分布

| 分类 | 数量 | 判据 |
|---|---|---|
| P0 | 3 | 用户一眼能看出没做 |
| P1 | 1 | 功能不完整，用起来别扭 |
| 半截 | 1 | 数据 / 组件都在，只差一次接通（用户会当 bug 报）|
| 能力缺失 | 0 | 目录级对比看不出来的缺失（N1/N5/N6 已全部落地）|
| P2 | 9 | 工程化与发布 |
| **合计** | **14** | 上表逐项相加（3+1+1+0+9）|
| 死代码 | 4 组 | 看着有实现，其实是死的，会误导判断 |
| 微清理 | 2 条 | 低危，不阻塞发布 |

结构基线（逐文件数出）：服务子域 **25 vs 23**（真缺 `avatar`、`push` 两个子域；`site` 子域在但缺 6 文件）｜页面 **28 vs 30**（真缺 3 页、部分迁移 1 页）｜组件 **47 vs 46 + 约 10 个内联私有 composable**（组件级真缺失 0）。

模块对齐度：首页 92% / 搜索 98% / 通知 95% / 详情 85% / 发帖 92% / 回帖 86% / 表情 85% / 音视频 92% / 我的 88% / 用户主页 88% / **实验性功能 100%**（6/6，动态头像开关已补）/ **设置 70%**（通知/网络/DoH/推送入口已接，论坛/我的站点缺）/ **多站点 40%**（6/12 文件，`BASE_URL` 硬编码）/ **系统推送 15%**（本地通知渠道 + 权限就位，投递链路未迁）/ 平板适配 100%（P1-13 已清：桌面姿态媒体分区 + 拍照入口落地）。

### 1.2 本轮新抓到的隐性缺口（最值钱的两条）

纯目录对比发现不了这两条，都是「看着有、其实没接上」（原第三条 N6 详情已读态回写已在本轮接通）：

1. **notification 死链路四件套**（§六 组1）——整个类 + API + 死 Provider + 整个模型文件都在编译，但活链路早被 `core/data` 接管，纯误导性遗留
2. **Boost 本地刷新静默成功**（§七 M2）——服务端操作成功但本地楼层气泡不更新，UI 却提示成功

---

## 二、P0 · 一眼能看出没做（3 项）

| # | 要做什么 | 现在是什么样 | 关键证据 |
|---|---|---|---|
| 1 | **系统推送 + 本地通知投递链路** | `services/push/` 仍不存在（鸿蒙 12 文件：`PushCoordinator` / `PushRelayClient` / `PushRelayConfig` / `PushSubscriptionService` / `PushSubscriptionStore` / `PushRefreshGate` / `PushNotificationNavigation(EventBus)` / `PushTokenUpdateEventBus` / `HarmonyPushTokenProvider`（→ FCM 对应物）/ `PushNotificationPermissionService` / `PushTokenDebugLog`）。`PushSettingsScreen` 只做了第一块（权限 + 测试通知），推送统计与订阅管理两块缺。**已就位**：通知渠道（`SystemNotifier`）、`POST_NOTIFICATIONS` 三态处理（`NotificationPermission`）、App 活着时点通知的续接（`onNewIntent` + `DeepLinkEventBus`，`MainActivity.kt:228-245`）| `PushSettingsScreen.kt:60-64` 注释自认「那一整套尚未迁移。这里不摆空壳充数」；Android 全库 `PushRelay\|PushSubscription\|PushToken` 仅命中文档与该注释 |
| 2 | **多站点 / 论坛切换** | `AppConstants.BASE_URL` **硬编码 `https://linux.do`**；Android `services/site/` **6 文件** vs 鸿蒙 **12**，缺 `CurrentSite` / `ForumPrefs` / `SiteCapabilities` / `SiteScope` / `SiteSwitchCoordinator` / `SiteUrl`。缺 **3 页**：`ForumsPage`（RootTabPage.ets:62 引用）、`SitesPage`（RootTabPage.ets:67 引用，站点增删/拖动排序/编辑）、`ForumOnboardingPage`（pages/Index.ets:24 首启真实渲染）。**落地时两个配套钩子必须一起接**：①`EmojiCatalogService.onSiteChanged()` 现全库零调用（`EmojiCatalogService.kt:66`，切站清表情目录缓存）；②`UploadService.mediaUploadEnabled()` 硬写 `true`（`UploadService.kt:229`，注释自认「等多站点落地后改走 CurrentSite 判定」）| `AppConstants.kt`；两侧 `services/site/` 目录实测；`SitePrefs.kt:13` 注释称「数组组装在站点管理页里做」但该页不存在 |
| 3 | **设置 2 入口未迁移** | 「网络与连接 → DoH」「通知」已落地（`SETTINGS_PUSH` 已注册 `ArkDoNavHost.kt:697` + navigate `:680`）；**论坛 / 我的站点** 仍弹 Toast。两者都卡在 P0-2 的 `CurrentSite` 上——论坛入口要显示当前站点 host，我的站点要列出可选站点 | `SettingsScreen.kt` 两处 `toastNotMigrated`（`NOT_MIGRATED = "尚未迁移"`）|

**依赖关系**：`多站点（P0-2）` 是「论坛 / 我的站点」两个设置子页的前置。做 P0-3 前必须先有 `CurrentSite`。

---

## 三、P1 · 功能不完整（1 项）

| # | 要做什么 | 鸿蒙版 | 现在是什么样 | 证据 |
|---|---|---|---|---|
| 12 | **版本公告源未定** | `announce/VersionAnnouncement.ets` | 代码完整，但 `ANNOUNCE_PROMPT_ENABLED = false` → **不扫描、不弹层**。当前源是鸿蒙版发布日志帖（`2590051` / `joeyyin`），`[v900008+]` 体系与 Android `versionCode` 对不上，开着会每次冷启动误弹鸿蒙公告。**链路全部就绪，只差决定 Android 自己的公告帖与账号** | `services/announce/VersionAnnouncement.kt:15/17/22`；`RootTabScreen.kt:160`（`if (!ANNOUNCE_PROMPT_ENABLED) return@LaunchedEffect`）|
---

## 四、半截 · 差一次接通（1 项）

| # | 现象 | 根因 | 证据 |
|---|---|---|---|
| S14 | 过盾快捷入口的用户开关缺失 | 快捷入口会渲染、订阅（`ChallengeShortcutEventBus` → `TopicDetailViewModel:161`）与 `reset()` 都已接通，但无 `ChallengeShortcutPrefs` 开关与网络设置页开关项；鸿蒙该开关默认**关** | `TopicDetailViewModel.kt:128` 自述；`NetworkSettingsScreen.kt:52` 自述；参考 `NetworkSettingsPage.ets:199-231` |

---

## 五、P2 · 工程化与发布（9 项）

| # | 问题 | 证据 |
|---|---|---|
| 20 | release 无签名配置 | `app/build.gradle.kts` 全文无 `signingConfigs`，所有 `*.kts` 零命中 |
| 21 | 无 androidTest | `**/src/androidTest/**` 0 文件（仅 build.gradle.kts:173-176 声明了依赖）|
| 22 | 无 CI | 无 `.github/` / `.gitlab-ci.yml` / `Jenkinsfile` |
| 23 | `core:datastore` 空模块 | `settings.gradle.kts:32` include，但模块无 `src/main`、无任何 `.kt`；且**全库零模块依赖它**（app 直依赖 `androidx.datastore.preferences`，`app/build.gradle.kts:116`）→ 可直接摘除 |
| 24 | `core:common` 近空壳 | 仅 `RelativeTime.kt` + `Placeholder.kt` 两文件 |
| 26 | 无崩溃上报 | Crashlytics / Bugly / Sentry 全库 0 命中，仅本地 `DebugLogScreen` |
| 27 | 无隐私政策 / 用户协议 / 商店素材 | `TODO.md:163-165` 未勾选 |
| 28 | 无多语言、无 `values-night` | `values/` 下仅 `strings.xml` + `themes.xml`（深色目前靠 Compose 运行时，无资源级兜底）|
| 31 | **三份文档严重过期** | ①`TODO.md:171` 末行「下一步：实现 WebViewNetworkBridge」——实际 `ArkWebNetworkBridge.kt` 是全 App 网络层唯一委托（`di/AppModule.kt:90`）；DohManager/登录/ImageViewer/搜索/草稿等 `[ ]` 均已完成，只有签名那条是真缺口。②`MIGRATION.md`「里程碑二+」整章几乎全部完成却仍标待办；但 4 条仍准确：push 15 文件、AvatarImageCache、ChallengeShortcutPrefs、TopicReadStateEventBus 确实未迁。③`README.md`：L65 文件名 `WebViewNetworkBridge`（实际 `ArkWebNetworkBridge`）、L162「只支持回复，不支持发新主题/上传图片」（实际 `NewTopicScreen` + `ComposerUpload` + `VideoTranscoder` 齐全）、L125「SDK 34+」（实际 compileSdk/targetSdk 37）、L98-118 结构图仍是单模块旧布局 |

> **31 单独提请注意**：三份文档都失去跟踪价值，照它们排期会大量重复劳动。**唯一权威是本文。** 建议整体重写或直接删除。
> **已做对、不是缺口**：R8 已开（`isMinifyEnabled = true` + `isShrinkResources`，`app/build.gradle.kts:45-46`）；明文 HTTP 已关（`networkSecurityConfig`，默认禁 + 仅 localhost 白名单）。

---

## 六、死代码与死资源（4 组）

| # | 对象 | 规模 / 证据 | 说明 |
|---|---|---|---|
| 组1 | **notification 死链路四件套** | ①`services/notification/NotificationService.kt` 整个类：全库仅定义 + 注释命中（`AppStateEventBus.kt:12` / `NotificationEventBus.kt:7` 是注释）②`services/notification/NotificationApi.kt`：唯一消费者是死类 ③`di/AppModule.kt:113-114` 死 Provider：定义后无任何注入点 ④`data/models/NotificationModels.kt` **整文件**：`RawNotification*` 四类型仅被死链路引用 | 活链路早由 `core/data/DefaultNotificationRepository.kt`（自带私有副本 ：67-93）+ `core/model/NotificationItem.kt` 接管。四件一起删，删 Provider 时注意保留 `AppModule` 里其他注入 |
| 组2 | 6 个死 Prefs 常量 | `StorageKeys.kt:37,38,39,44,47,52`：`DRAFTS_REFRESH_TICK_KEY` / `DRAFTS_COMPOSER_VISIBLE_KEY` / `DRAFTS_COMPOSER_BACK_TICK_KEY` / `SITES_TICK` / `SETTINGS_DOH_TICK` / `APP_IS_WIDE_STORAGE_KEY`，均仅定义处命中 | `PREFS_ANIMATED_AVATAR` 已随 N1 动态头像落地启用，不再是死键 |
| 组3 | 2 个悬空路由常量 | `Routes.CF_CHALLENGE`（`ArkDoNavHost.kt:741`）与 `Routes.LOGIN`（`:740`）均零注册零 navigate | **多余路由，删常量即可、页面不要动**：过盾页 `CfChallengeScreen`（313 行活代码）由 `MainActivity.kt:188-205` 内联托管；`LoginScreen` 由 `LoginGate` 弹层托管（`ArkDoNavHost.kt:714-720`）。照「预留位」去补页面就是造重复实现 |
| 组4 | 解析后零消费的 2 个字段 | `RichBlock.mediaType`（`RichContentTypes.kt:108`，解析 `CookedHtmlParser.kt:732/744`）、`RichBlock.unsupportedKind`（`:100`，解析 `:500`）；ui/ 层零读取 | 先判断是否该有 UI 行为（如音视频块降级提示）——有则补消费，无则连字段带解析一起删 |

> 已清理：`TopicListItem.kt`（378 行）与 `AppRoutes.kt`（163 行）已于本分支删除（逐符号复核零代码引用后编译验证）。

---

## 七、微清理（2 条，低危）

| # | 问题 | 证据 |
|---|---|---|
| M1 | Manifest 声明 `WRITE/READ_EXTERNAL_STORAGE` 但全库无运行时 `checkSelfPermission/request`；实际落盘走应用专属目录 `getExternalFilesDir`（API 29+ 本就不需要） | `AndroidManifest.xml`；`ArkWebNetworkBridge.kt:723,830-860` → 删两条声明即可 |
| M2 | `createBoost` / `deleteBoost`：`dataSource.findById(post.id)` 为 null 时本地列表不更新，但仍走 `done(true)` → UI 提示「Boost 已发送/已删除」而楼层气泡不变（服务端实际成功，仅本地刷新缺失） | `TopicDetailViewModel.kt:1497-1509` / `:1525-1538` |

---

## 八、已核实非缺口（防止下次审计误报）

以下曾经/容易被当成缺口，本轮已逐条核实**不是**：

| 项 | 结论 | 证据 |
|---|---|---|
| `TopicReadController`（原 N3） | **职责并入**，不是缺失：attach/detach/started/collectAlreadyReadPostNumbers/flushPending 全套在 ViewModel 内实现，注释逐条对齐鸿蒙 | `TopicDetailViewModel.kt:119-141,1794-1878`、`TopicDetailScreen.kt:328`；鸿蒙 `RootTabPage.ets:2008` 仅注释提及，非消费方 |
| `UserRelationService` / `UserProfileCache`（原 N4） | **职责并入**：followUser/unfollowUser 在 UserProfileService 且被 ViewModel 消费；缓存并入 ViewModel | `UserProfileService.kt:508-515`、`UserProfileViewModel.kt:189-191` |
| 徽章点击 11 调用点 | 鸿蒙只有首页一个调用点接了点击，其余页面两侧都不传回调是**正确**行为（已落地，见 §十第 1 步） | `HomePage.ets:719` |
| DoH | 鸿蒙有完整对应物（`DohManager.ets` / `DohSpeedTester.ets` / `DohSettingsPage.ets`），Android 是**追平**不是反超也不是缺口 | 两侧 grep 实测 |
| `PulsingHalo` | 鸿蒙侧自身死代码（全库仅定义处命中），不移植 | `PulsingHalo.ets:10` |
| **平台专有，不适用区** | 智感握姿（`@kit.MultimodalAwarenessKit` 传感器）；鸿蒙推送通道 `HarmonyPushTokenProvider`（Android 对应物是 FCM）；`@kit.UIDesignKit`（HdsActionBar）；`LoginKeyboardBridge`。**不要记为缺口，不要试图补** | 各 `@kit.*` import |

---

## 九、不用做：Android 已反超鸿蒙（保持即可，不要「对齐」回去）

1. **图片长按菜单**（保存相册 / 分享 / 拷贝链接）——`ui/components/ImageViewer.kt:94/343/489`（鸿蒙注释自认没有）
2. **私有 DNS 引导页** `PrivateDnsGuideScreen` + `NetworkAccessibilityChecker`——鸿蒙侧 `PrivateDns` 0 命中
3. **书本姿态双页阅读** `TwoPageReading` + 开关——鸿蒙 `TwoPage|双页` 0 命中，Android 独有
4. **折叠姿态感知层** `FoldPosture`（铰链几何 / 书本姿态 / 展开判定）——鸿蒙只有 `BreakpointManager.ets` 宽度断点，无姿态感知
5. **模块化**——core×6 + feature×3 共 9 个 Gradle 模块，feature 垂直切片
6. **单元测试 73 个测试类 / 541 用例零失败**（app 54 + core/data 7 + core/model 3 + designsystem 3 + home 4 + notification 1 + search 1，鸿蒙无对应 JVM 单测）
7. **通知列表分页**——`loadMore` + hasMore（`NotificationViewModel.kt:46,105-111`）
8. **表情目录可观察刷新**——`emojiCatalogRevision: StateFlow<Int>`（`EmojiCatalogRegistry.kt:70`），表一到选择器自动刷新；鸿蒙 `aboutToAppear` 读一次、拉到晚就一直空白
9. **视频播放走 TextureView**——`arkdo_player_view.xml` + `VideoPlayer.kt:158`，避开 SurfaceView 与 Compose 合成的黑帧交替（实测黑帧率 46% → 0%）
10. **上传前视频转码** `VideoTranscoder`（media3-transformer）——鸿蒙上传链路无对应转码实现
11. **LdcCredit 拆分更细**——credit 子域 6 文件 vs 鸿蒙 4
12. **composer 拍照入口** `ComposerCamera`（TakePicture + 既有 FileProvider，成图走图片上传通道）——鸿蒙 `camera` 0 命中，选图只走 photoAccessHelper

> 载体差异、不算独有：`FabGlassStore`（鸿蒙有 `FabGlassPrefs.ets`）、`TopicListStyleSettingsScreen`（鸿蒙有 `TopicListStylePrefs.ets`）。

---

## 十、建议实施顺序

**第 0 步 · 清台面（半天内，全是删除）**
1. 死代码组1/2/3（notification 四件套、6 个死常量、2 个悬空路由常量）+ 微清理 M1（Manifest 存储权限）——每批删完编译 + 全量单测
2. 组4（`mediaType`/`unsupportedKind`）先判断该不该有 UI 行为，再决定补消费还是删
3. 重写或删除 `TODO.md` / `MIGRATION.md` / 修 `README.md` 三处失真（P2-31）

**第 1 步 · 差一次接通（改动小）**
4. ~~N6 详情已读态回写首页~~ **已完成**（`TopicReadStateSource` 单例：详情页 `onDetailDetach` 结算、首页订阅更新对应行，不搬 bus；feature/home 单测覆盖）
5. **S14 过盾快捷入口开关**：`ChallengeShortcutPrefs`（默认关）+ 网络设置页开关项，入口/订阅/reset 均已就位

**第 2 步 · 用户一眼能看到**
6. **多站点**（P0-2）：`CurrentSite` + `ForumPrefs` 让 `BASE_URL` 可变 + 三页（Forums/Sites/ForumOnboarding）；**顺带接上两个钩子**（`EmojiCatalogService.onSiteChanged()`、`UploadService.mediaUploadEnabled()` 走站点判定）——一并解锁 P0-3 的「论坛 / 我的站点」设置入口
7. **版本公告定源**（P1-12）：决定 Android 自己的公告帖与账号，`ANNOUNCE_PROMPT_ENABLED` 置 `true`，链路已就绪

**第 3 步 · 横向铺开**
8. **系统推送全链路**（P0-1）：`services/push/` 移植（鸿蒙 12 文件）+ FCM 对应物替代 `HarmonyPushTokenProvider` + Relay 同意流程；**前置已全部就位**（渠道/权限/深链），推送到达直接调 `SystemNotifier` 即可；`PushSettingsScreen` 补推送统计与订阅管理两块
9. ~~动态头像（N1）~~ **已完成**（`services/avatar/AnimatedAvatarPrefs`（默认开，复用死键 `PREFS_ANIMATED_AVATAR`）+ `LocalAnimatedAvatarEnabled`/`rememberAvatarImageModel` 首帧解码 + 实验页第 6 开关，6/6）
10. ~~图片失效黑名单（N5）~~ **已完成**（`BlockedImageHosts` + Coil `BlockedImageHostInterceptor` 挂全局 ImageLoader：网络层失败记证据、2 张不同图判域名走不通、命中短路；鸿蒙「桥也取不到就撤销」对冲无取图桥，不搬）

**第 4 步 · 大屏深化（P1-13 已全清）**
11. ~~桌面姿态媒体上下分区~~ **已完成**（`TabletopMedia` 几何 + `VideoPlayer` 分区接线，单测覆盖；真机水平折痕待复验）；~~相机预览方向~~ **已消解**（拍照入口走系统相机 TakePicture，预览由系统自理，App 内无预览可适配）

**第 5 步 · 发布准备**
12. 签名配置 + CI + androidTest 骨架（P2-20/21/22）
13. 崩溃上报、隐私政策与商店素材（P2-26/27）；多语言与 `values-night`（P2-28）；`core:datastore` 摘除 + `core:common` 处置（P2-23/24）

---

## 附：核查方法（可复跑）

```bash
# ── 结构对比 ──
ls entry/src/main/ets/services/                    # 鸿蒙 25 子域
ls app/src/main/kotlin/com/arkdo/android/services/ # Android 20 + core/data 2 + feature/home 1
ls /Users/tongtong/ArkDO/entry/src/main/ets/views/pages/ | wc -l   # 28 页

# ── 判据 D：路由常量 vs 注册 ──
rg -n "composable\(Routes|Routes\.CF_CHALLENGE|Routes\.LOGIN" -g '*.kt'   # 只命中定义处 = 悬空

# ── 判据 A：声明 → 反查调用 ──
grep -nE "uses-permission|scheme=" app/src/main/AndroidManifest.xml
rg -n "checkSelfPermission" -g '*.kt'             # 存储两条权限应零命中（M1）

# ── 判据 B：解析了但没消费 ──
rg -n "mediaType|unsupportedKind" -g '*.kt' app/src/main/kotlin/com/arkdo/android/ui   # 应 0 命中（组4）
rg -n "quotePostNumber|quoteTopicId|quoteIsReply" -g '*.kt'  # 应命中 PostRichContent（已消费）

# ── 判据 C：零调用方 ──
rg -ln "NotificationService|NotificationApi|RawNotification" -g '*.kt'    # 组1，注意排除注释与 core/data 私有副本
rg -n "DRAFTS_REFRESH_TICK_KEY|APP_IS_WIDE_STORAGE_KEY" -g '*.kt'

# ── 判据 E：有发无收 ──
rg -rn "EventBus" -g '*.kt' --files-with-matches  # 逐条对账发送方/订阅方
rg -n "onSiteChanged" -g '*.kt'                   # 应只命中定义处（P0-2 钩子）

# ── 工程化 ──
grep -n "signingConfig" app/build.gradle.kts; ls -a | grep github; ls app/src/main/res/values/
```

> **工具提醒**：macOS BSD `grep` 对 `\|` 行为不可靠，一律 `grep -E` 或 `rg`；长 `find | wc` 组合会被沙箱杀（exit 137），统计用 Glob/Grep 工具；grep 命中里混注释是常态，命中行以 `//` 或 `*` 开头的不算引用，必须逐行看。
