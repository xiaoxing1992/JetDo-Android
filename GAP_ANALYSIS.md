# ArkDO-Android 待实现清单

> 基准：鸿蒙版 ArkDO（ArkTS · 28 页面 / 47 组件 / 25 服务子域）
> 核查点：`feat/migrate-vertical-slice` @ `eb5da45`（本轮完成帖内音视频与谁回应了弹层，见 §一 第 2 项 / §二 第 8 项）
> 规则：只记「现在还没做的」，做完一条删一条。不带版本号，不记修复历史。

## 总览

**还剩 36 项 + 7 组死代码。**

| 分类 | 数量 | 判据 |
|---|---|---|
| P0 | 3 | 用户一眼能看出没做 |
| P1 | 11 | 功能不完整，用起来别扭 |
| 半截 | 10 | 数据 / 组件都在，只差一次接通（用户会当 bug 报） |
| P2 | 12 | 工程化与发布 |
| 死代码 | 7 组 | 看着有实现，其实是死的，会误导判断 |

模块对齐度参考：首页 92% / 搜索 98% / 通知 95% / 详情 85% / 发帖 88% / 回帖 80% / **表情 85%** / **音视频 90%** / 我的 88% / 用户主页 88% / 设置 48% / 多站点 30% / 系统推送 0%。

---

## 一、P0 · 一眼能看出没做（4 项）

| # | 要做什么 | 现在是什么样 | 关键证据 |
|---|---|---|---|
| 1 | **系统推送 + 本地通知** | `services/push/` 不存在；无 FCM / HMS 依赖；全库 0 处 `NotificationManager`；`POST_NOTIFICATIONS` 已声明但**无运行时申请**；App 活着时点通知也接不到（**无 `onNewIntent`**） | Manifest `:9`；`MainActivity.kt:212-217` 深链只打日志 + `// TODO: 通过 Navigation 跳转到详情页` |
| ~~2~~ | ~~**帖内音视频播放**~~ → **已做** | Media3 ExoPlayer 音频条/视频卡（状态机对齐鸿蒙），MediaSource 同域分流，下载桥结算链补完（完成广播/真实落盘路径/同源 Cookie），全局互斥 + 离页即停；texture_view 修黑屏闪 | 新增 `AudioPlayer` / `VideoPlayer` / `PostMediaEngine` / `MediaSource` / `MediaPlaybackCoordinator`；`ArkWebNetworkBridge.downloadMedia` 结算接回 |
| ~~3~~ | ~~**回帖链路残废**~~ → **已做** | 紧凑输入条加「展开」进全屏编辑器（Markdown 工具栏 / 预览 / 图片上传，与发帖同一套实现）；回帖草稿 2s 防抖自动落盘、与编辑草稿共用 key 与序号 | 新增 `ui/composer/ReplyComposerOverlay.kt`、`ComposerCommon.kt`、`ComposerUpload.kt`；`DraftService.saveReplyDraft/saveEditDraft`；`TopicDetailViewModel` 的 `scheduleReplyDraftSave` 一族 |
| ~~4~~ | ~~**表情体系整体缺失**~~ → **已做** | 新增按站点拉取的目录服务（`EmojiCatalogService` + `EmojiCatalogRegistry`，磁盘缓存 1 天）；表情选择器接进回复输入条（分组 tab + 7 列网格 + 整段退格）；发帖 / 回帖**预览**里的已知短码转成图片；楼层与用户主页的**状态表情**由短名出图 | 新增 `services/content/EmojiCatalogRegistry.kt`、`EmojiCatalogService.kt`、`EmojiPreviewHtml.kt`、`EmojiShortcodeEdit.kt`、`ui/components/EmojiPicker.kt`；`RootTabScreen` 宿主就绪后加载；死资源 `assets/linuxdo_emojis.json` 已删 |
| 5 | **设置 4 入口未迁移** | 「网络与连接 → DoH」已落地；**论坛 / 通知 / 我的站点**仍弹「尚未迁移」；`SETTINGS_PUSH` 仍悬空未注册 | `SettingsScreen.kt:88`；`ArkDoNavHost.kt:587`；对照鸿蒙 `ForumsPage` / `PushSettingsPage` / `SitesPage` |
| 6 | **多站点 / 论坛切换** | `AppConstants.BASE_URL` **硬编码 `https://linux.do`**（约 15 处引用）；`CurrentSite` / `ForumPrefs` / `SiteCapabilities` / `SiteScope` / `SiteUrl` / `SiteSwitchCoordinator` / `SiteSettingsService` 全缺；Android `services/site/` 只有 5 文件 vs 鸿蒙 12 | `AppConstants.kt:8`、`:21` |

**性价比排序**：`多站点` > `音视频` > `系统推送`。

---

## 二、P1 · 功能不完整（12 项）

| # | 要做什么 | 鸿蒙版 | 现在是什么样 | 证据 |
|---|---|---|---|---|
| 7 | **版本公告 / 升级提示** | `announce/VersionAnnouncement.ets`（扫帖 `2590051`，按 `[v<code>]` 匹配版本，启动时弹） | 完全不存在 | 全仓唯一命中是测试里的分类名 |
| ~~8~~ | ~~**「谁回应了」列表**~~ → **已做** | 弹层五态（chips 过滤/用户行/四态 footer）+ 胶囊接通 + 触底自动加载；真实响应为扁平结构，DTO 解码映射 | 新增 `ReactionUsersSheet` / `ReactionUsersPager`（含单测）；`TopicService.getReactionUsers` |
| 9 | **正在回复的人 / 输入中** | `services/presence/` + `ReplyingPresenceBar` + `TypingDots` | 不存在；`MessageBusEventBus` 已解析 `enteringUsers` 但**无任何消费者** | `MessageBusEventBus.kt:17`、`:34-35` |
| 10 | **帖内实时新回复** | `RealtimePostsPrefs` | 只有首页新话题提醒；两个 extract 方法**零调用**（仅测试用）；且 `RealtimeTopicsPrefs` **默认值反了**（鸿蒙默认关、Android 默认开） | `MessageBusEventBus.kt:87`、`:96`；`RealtimeTopicsPrefs.kt:17` |
| 11 | **待审核帖子卡片** | `PendingPostCard.ets`（标签 / 时间 / 正文 / 删除） | **无 UI**：模型（`PendingPost`）与提交路径都在，用户被审后只看到一句 Toast，永远找不到也撤不回 | `TopicDetailViewModel.kt:831-839`；`TopicModels.kt:345` |
| 12 | **抽奖帖提醒卡** | `LotteryNoticeCard.ets` + `LotteryTopicRule` + `LotteryTipPrefs` | 无 | `TopicDetailViewModel.kt:586` 自述「LotteryTopicRule 未迁移」 |
| 13 | **附件 / 视频上传 + 下载分享** | `UploadService` + `VideoTranscoder`（视频压缩、附件、HEIC-alpha、短码预览）+ `DownloadService` + `ImageShareService` | **只能传图**（仅 JPEG / 1920，gif 原样）；非图片附件无落地；图片分享缺失 | `NewTopicViewModel.kt:46`、`UploadMarkdown.kt:8` 自述 |
| 14 | **Markdown 工具栏缺 2 钮** | `MarkdownToolbar.ets:19-32` 共 12 钮（含 `video` / `file-arrow-up`）+ 按站点能力过滤 | **9 钮**，缺 `video`、`file-arrow-up` | `NewTopicScreen.kt:925-937` |
| 15 | **多图批量上传 + 进度** | 多图选择 + `N/M` 进度 | 单图 `PickVisualMedia`（已核实无 `PickMultipleVisualMedia`） | `NewTopicScreen.kt:155`、`:176` |
| 16 | **平板双栏布局** | 横屏双栏 / 竖屏单栏自适应 | 0 处窗口尺寸类（`WindowSizeClass` 0 命中） | 全库 |
| 17 | **分类抽屉缺分组** | 「我的版块 / 所有版块」分组 + 组内计数 + 「我的标签 / 热门标签」分组 + 「我的站点」快捷条 | 平铺 top-level；热门标签数据有但不分组；无站点快捷条 | `HomeDrawer.kt:427-454`、`:656-736` |
| 18 | ~~**回帖草稿落盘**~~ → **已做** | 服务端草稿 + 定时存 + 序号续存 | 回帖/编辑草稿 2s 防抖存 `topic_<id>`，与编辑草稿共用序号；发表成功后删除并乐观 -1 | `TopicDetailViewModel.saveReplyDraftNow` / `deleteReplyDraftIfAny`；`DraftService.saveReplyDraft` |
| 19 | **零散小项** | — | 「我的」页活动行不可点已修 / 他人页简介链接已修 / 取关确认已修；**仍缺**：称号无全息特效、详情页「在网页中打开」菜单、抽屉 chip 搜索转场动画 | 见 §三 |

---

## 三、半截 · 差一次接通（10 项）

**这类最该先修**：骨架、数据、组件往往都已就位，只差一行转发或一次订阅，用户会当成 bug 报，而纯静态对比目录结构永远发现不了。

| # | 现象 | 根因 | 证据 |
|---|---|---|---|
| ~~S5~~ | ~~**状态表情不渲染成图**~~ → **已做** | 原实现把短名当纯文本画（楼层显示的是 "lollipop" 这种英文单词），用户主页整块没渲染。现改为查站点表情目录出图，两处都接上 | `TopicPostItem.kt`（`getEmojiUrl` + AsyncImage 16×16）；`UserProfileScreen.kt`（昵称旁 18×18） |
| S7 | **圆形头像先方后圆闪一下** | `onOpenUser` 未透传 userId，种子 `id=0`。**待实机复核**：判据是 `id == 1L` 走方形，种子恒为圆形，推不出该现象 | `UserProfileScreen.kt:99`、`UserProfileViewModel.kt:72` |
| S9 | **presence 数据无人消费** | `entering_users` / `leavingUserIds` 只解析不渲染 | `MessageBusEventBus.kt:17`、`:34-35` |
| S10 | **帖内实时新回复未接线** | 两个 extract 方法零调用（仅测试用） | `MessageBusEventBus.kt:87`、`:96` |
| S11 | **分类 / 标签徽章不可点筛选** | `TopicRow` 调 `TopicMetadataRow(...)` 时**没传点击回调**（组件本身支持 `onTagClick` / `onCategoryClick`） | `TopicRow.kt:113`；`TopicMetadataBadges.kt:67-68` |
| S12 | **引用卡不能跳楼 / 不能展开原文** | 引用卡是纯展示 | `PostRichContent.kt:277-330`；对照 `TopicDetailPage.ets:1646-1678` |
| S13 | **深链不导航 + 无 `onNewIntent`** | `arkdo://notification` 净空转；只有 `onCreate` 处理 intent，App 活着时点通知接不到 | `MainActivity.kt:212-217`；Manifest `:41-46` |
| S14 | **过盾入口的用户开关缺失** | 快捷入口会渲染，但无 `ChallengeShortcutPrefs`；鸿蒙该开关默认**关** | `TopicDetailViewModel.kt:108` 自述；对照 `NetworkSettingsPage.ets:199-231` |
| S15 | **实验性开关只有 1/6** | 仅「首页新话题提醒」一个；其余 5 项 prefs 类不存在 | `ExperimentalSettingsScreen.kt:79-101` |
| S16 | **HTTP 明文放行** | `usesCleartextTraffic="true"`（release 包同样允许） | Manifest `:25` |
| S17 | **诊断能力降级** | 缺详细日志开关、日志大小标签、清空二次确认 | `DebugLogScreen.kt:136-153`；对照 `DiagnosticsSettingsPage.ets:119-196` |

---

## 四、P2 · 工程化与发布（12 项）

| # | 问题 | 证据 |
|---|---|---|
| 20 | release 无签名配置 | `app/build.gradle.kts` 无 `signingConfigs` |
| 21 | 无 androidTest | `app/src/` 只有 `main` 与 `test` |
| 22 | 无 CI | 无 `.github/` |
| 23 | `core:datastore` 空模块 | 无 `src/main`，只有 `build.gradle.kts` |
| 24 | `core:common` 近乎空壳 | 仅 `RelativeTime.kt` + `Placeholder.kt` |
| 25 | `usesCleartextTraffic="true"` | Manifest `:25`（同 S16） |
| 26 | 无崩溃上报 | 无 Crashlytics / Bugly，仅本地 `DebugLogScreen` |
| 27 | 无隐私政策 / 用户协议 / 商店素材 | `TODO.md:163-165` 未勾选 |
| 28 | 无多语言、无 `values-night` | `res/` 只有 `drawable mipmap-* raw values xml` |
| 29 | 非图片附件下载无落地 | 同 P1-13 |
| 30 | `AppRoutes.kt` 死代码 | 同 D2 |
| 31 | **文档严重过期** | `TODO.md` 末行仍写「等待实现具体功能」；`MIGRATION.md` 把 20 个已完成页面列为「未做」 |

> **31 单独提请注意**：`MIGRATION.md` 已失去跟踪价值，照它排期会重复劳动，建议整体重写或删除。

---

## 五、死代码与死资源（7 组）

不是「缺功能」，但会**严重误导后续判断**（看着有实现、其实是死的）：

| # | 对象 | 规模 / 证据 | 说明 |
|---|---|---|---|
| D1 | `ui/components/TopicListItem.kt` | 378 行，`TopicListItem(` 仅定义处命中（已核实） | 已被 `core/designsystem/TopicRow.kt` 取代；`:51` 注释仍写「Android 尚未移植设置页」（**已过期**） |
| D2 | `navigation/AppRoutes.kt` | 163 行，零引用（已核实） | 真实路由在 `ArkDoNavHost.kt` 的 `Routes` |
| D3 | `services/notification/NotificationService.kt` 整个类 | 无实例化、无 Provider（已核实） | 含 `markPushNotificationRead` 死钩子；列表实际走 `DefaultNotificationRepository` |
| D4 | `services/notification/NotificationApi.kt` 整个类 | 仅被 D3 与未被注入的 `di/AppModule.kt:107` 引用（已核实） | `load` / `markRead` / `markAllRead` 无真实调用方 |
| D5 | 悬空路由常量 `SETTINGS_PUSH` | `ArkDoNavHost.kt:587`（已核实仍悬空） | 做 P0-5 时直接复用，不要另起名字 |
| D6 | 死 Prefs 常量 | `StorageKeys.kt:59` `PREFS_ANIMATED_AVATAR`（已核实零引用）；另有同批 6 个：`DRAFTS_REFRESH_TICK_KEY` / `DRAFTS_COMPOSER_VISIBLE_KEY` / `DRAFTS_COMPOSER_BACK_TICK_KEY` / `SITES_TICK` / `SETTINGS_DOH_TICK` / `APP_IS_WIDE_STORAGE_KEY` | 均只在定义处出现 |
| ~~D7~~ | ~~死资源 `assets/linuxdo_emojis.json`~~ → **已删** | 284KB，全项目零引用（已核实） | 内置的那份只对 linux.do 成立，别的论坛自定义表情完全不同；目录已改为按站点拉取（`EmojiCatalogService`），留着只会让人以为"有内置兜底" |
| D8 | `core:datastore` 空模块 | 无 `src/main` | 同 P2-23 |

**顺带一个「活代码里的过期注释」**：`TopicPostItem.kt:79` 仍写「待后续切片：boost 行与火箭、shared issue『俺也一样』、采纳答案面板」，但这三项**早已修完**；只剩「拷贝画勾动画（暂 Toast）、全息称号（暂普通文字）、回应人数弹层」未做。下次读到这条注释会被误导。

---

## 六、不用做：Android 已反超鸿蒙（10 条）

以下 Android 侧强于鸿蒙，**保持即可，不要"对齐"回去**：

1. **图片长按菜单**（保存相册 / 分享 / 拷贝链接）——`ui/components/ImageViewer.kt`
2. **首页悬浮按钮液态玻璃**，磨砂强度可调——`core/designsystem/FabGlassStore.kt`
3. **帖子列表样式设置**（字号 / 行高 / 内边距 / 头像 / 字重）——`TopicListStyleSettingsScreen`
4. **搜索独立垂直切片**——`feature:search`，`SearchFilters` 字段与鸿蒙全量对齐
5. **私有 DNS 引导页** `PrivateDnsGuideScreen`（鸿蒙无对应页面）
6. **Now in Android 风格模块化**——10 个 Gradle 模块，feature 垂直切片
7. **单元测试 28 个测试类**（`CookedHtmlParser` / `LinkRouter` / `PostStreamPager` / `PollMutation` / `MessageBusEventBus` / `PostDataSource` / `TopicDetailParsing` 等，鸿蒙无对应单测）
8. **LdcCredit 拆分更细**——credit 子域 6 文件 vs 鸿蒙 4
9. **通知列表实现完整**——30/页分页、下拉刷新、上拉加载、全部已读、乐观已读、未读轮询 + 实时频道
10. **DoH 诊断能力更强**——`NetworkAccessibilityChecker`（鸿蒙无）；DoH 配置页与测速也已补齐
11. **表情目录可观察刷新**——鸿蒙在 `aboutToAppear` 读一次分组快照，表情表拉到得比面板打开晚就一直空白、要重开；Android 用 `emojiCatalogRevision: StateFlow<Int>`，表一到选择器自动刷新

---

## 七、建议顺序

**第 1 步 · 差一次接通的（改动小、风险低）**
1. S11 分类 / 标签徽章接 `onTagClick` / `onCategoryClick`（`TopicRow.kt:113` 补两个参数）
2. S13 深链导航 + `onNewIntent`（`MainActivity.kt`）
3. S15 补齐实验性开关 5 项 + S14 过盾开关
4. P1-11 `PendingPostCard`、P1-8 `ReactionUsersSheet`（模型都已在）
5. S17 诊断能力补齐、S16 关明文 HTTP

**第 2 步 · 用户一眼能看到的**
6. **多站点 / 论坛切换**（P0-6）：`ForumPrefs` + `CurrentSite`，让 `BASE_URL` 可变；顺带解锁 P0-5 的论坛 / 站点两个入口
7. ✅ **回帖链路对齐发帖**（P0-3 / P1-18）：紧凑条展开成全屏编辑器（工具栏 / 预览 / 图片）+ 回帖草稿落盘（本轮完成）
8. ✅ **表情体系**（P0-4 / S5 / D7）：目录服务（按站点拉取 + 磁盘缓存）→ 选择器接回复条（分组 + 整段退格）→ 预览短码转图 → 状态表情出图，死资源已删（本轮完成）
9. ✅ **音视频播放**（P0-2，本轮完成）
10. Markdown 工具栏补 `video` / `file-arrow-up`（P1-14）+ 多图批量上传（P1-15）

**第 3 步 · 横向铺开**
11. 系统推送全链路（P0-1）
12. 版本公告（P1-7）/ presence（P1-9）/ 帖内实时新回复（P1-10）/ 抽奖卡（P1-12）
13. 平板双栏（P1-16）/ 抽屉分组（P1-17）/ 附件上传与分享（P1-13）

**第 4 步 · 发布准备**
14. 签名配置 + CI + androidTest 骨架（P2-20/21/22）
15. 崩溃上报、隐私政策与商店素材（P2-26/27）
16. **清理死代码 D1–D8**，重写或删除 `TODO.md` / `MIGRATION.md`（P2-31）

---

## 附：核查命令（可复跑）

```bash
# 能力关键词命中（0 = 缺失）
rg -l "exoplayer|media3"                  -g '*.{kt,toml}'        # 音视频
rg -l "NotificationCompat|NotificationManager" -g '*.kt'          # 本地通知
rg -l "EmojiPicker|EmojiCatalogRegistry" -g '*.kt'                # 表情体系（现应有 5 处命中）
rg -l "CurrentSite|ForumPrefs|SiteCapabilities|WindowSizeClass" -g '*.kt'
rg -l "presence|Lottery" -g '*.kt'                                # presence / 抽奖

# 半截链路：参数有没有往下传（定义处 vs 调用点）
rg -n "onTagClick|onCategoryClick" core/designsystem/src/main/kotlin/com/arkdo/android/core/designsystem/TopicRow.kt
rg -n "onNewIntent|getIntent" app/src/main/kotlin/com/arkdo/android/MainActivity.kt

# 死代码：定义有、调用无
rg -n "TopicListItem\(" -g '*.kt'
rg -l "AppRoutes|NotificationService|NotificationApi" -g '*.kt'
rg -n "PREFS_ANIMATED_AVATAR|linuxdo_emojis" -g '*.kt'
rg -n "SETTINGS_PUSH" app/src/main/kotlin/com/arkdo/android/navigation/ArkDoNavHost.kt

# 自述未完成标记
rg -n "尚未迁移|后续切片|未迁移|暂不支持" -g '*.kt'

# 工程化
grep -n "signingConfig" app/build.gradle.kts ; ls -a | grep -E "^\.github" ; ls app/src/main/res/
```

> **工具提醒**：macOS 自带 BSD `grep` **不支持 `\|`**（GNU 扩展），`grep "a\|b"` 会当字面量、静默返回空。一律用 `grep -E "a|b"` 或 ripgrep（`rg`），否则会得出「鸿蒙没有这个功能」这类假结论。
