# ArkDO-Android 功能缺口分析（v6 · 累计修复记账）

> 对标对象：鸿蒙版 [`ArkDO`](../ArkDO)（ArkTS · 28 页面 / 47 组件 / 25 服务子域）@ `main 7680996`
> 复核分支：`feat/migrate-vertical-slice`
> 复核日期：2026-09-26
> 版本链：v1 @ `f78d3e5` → v2 @ `cf223b7` → v3 @ `7f5a6b2` → v4 @ `6175f0a` → v5（DoH 配置页 + 5 项接通）→ **v6（S3 草稿角标真源，见 §〇）**

---

## 〇、最近两轮做了什么

### v6（本轮）· S3 草稿角标事件通路

**现象**：「我的」页快捷入口「我的草稿」的角标只在登录那一刻取一次快照，之后删草稿、登出都不动，长期陈旧甚至挂着上一个账号的数。

**根因**：`ProfileScreen` 直接读登录时的 `User.draftCount`，全 App 没有任何一处会更新它；`StorageKeys.PROFILE_DRAFT_COUNT_KEY` 定义后零引用（死常量）。

**修法**：按鸿蒙 `AppStorage[PROFILE_DRAFT_COUNT_KEY]` 的语义补一个内存真源 `DraftCountStore`（`StateFlow<Int?>`，`null` = 尚未被权威结果写过），把鸿蒙的**写入点一个不落地**搬过来：

| 写入点 | 鸿蒙 | Android |
|---|---|---|
| 会话复查成功覆盖 | `RootTabPage.ets:1836` | `SessionChecker.checkCurrentUser()` → `set(user?.draftCount)` |
| 登出归零 | `RootTabPage.ets:1798` `applyLoggedOut` | `ProfileViewModel.logout()` → `reset()` |
| 草稿页删除 → -1 | `DraftsPage.ets:392` | `DraftsViewModel.delete()` → `decrement()` |
| 发帖成功后删草稿 → -1 | `ComposerPage.ets:444` | `NewTopicViewModel.deleteDraftAfterPublish()` → `decrement()` |
| 续写草稿发表后删 → -1 | `TopicDetailPage.ets:1305` | `TopicDetailViewModel.deleteResumedDraftIfAny()` → `decrement()` |

**两处刻意的实现差异**：
- **不建 `DraftEventBus`**。鸿蒙需要一个总线把「草稿删了」广播给 `RootTabPage`，是因为 ArkUI 的 `AppStorage` 不会跨页面推送；Android 的 `DraftCountStore` 本身就是 `StateFlow` 单例，写入即广播，再加一层总线是空转。**保留的是语义（谁写、写什么、什么时候写），不是机制**。
- **`decrement` 带 `fallback` 参数**。鸿蒙那行是 `storedCount ?? currentUser.draftCount` 再减 1，即 store 未被写过时回退到登录快照。正常路径上会话复查早已写过，这条分支只在「会话检查因过盾/断网失败、而草稿接口仍可用」时才走到，Android 侧保留同样语义。

**没有搬的 1 个删除点**：鸿蒙 `TopicDetailPage.ets:1285` 的 `deleteReplyDraft` 删的是**回帖自动草稿**（key = `topic_<id>`），Android 的回复框只有内存暂存、不落盘（属 P0-3 回帖链路），所以没有对应物——这不是 S3 的遗漏，是 P0-3 的连带项。

**顺带**：删掉死常量 `PROFILE_DRAFT_COUNT_KEY`。另查出同批死常量 6 个（`DRAFTS_REFRESH_TICK_KEY` / `DRAFTS_COMPOSER_VISIBLE_KEY` / `DRAFTS_COMPOSER_BACK_TICK_KEY` / `SITES_TICK` / `SETTINGS_DOH_TICK` / `APP_IS_WIDE_STORAGE_KEY`，均只在定义处出现），**本轮未动**，记入 D6 备查。

验证：`:app:compileDebugKotlin` + `:app:testDebugUnitTest` 均 BUILD SUCCESSFUL，新增 `DraftCountStoreTest` 8 个用例全绿。

### v5（上一轮）· DoH 配置页 + 5 项接通

按 v4 的 §九「建议实施顺序」，落地了**第 0 步（捡地上的钱）**与**第一步的一部分**：

| 项 | 内容 | 涉及文件 |
|---|---|---|
| ✅ 半截 S1 | 「我的」页最近活动行接 `onOpenTopic`，整行进详情页并定位楼层 | `ProfileScreen.kt`、`ArkDoNavHost.kt`、`RootTabScreen.kt` |
| ✅ 半截 S2 | 他人主页简介（bio）行内链接接 `onOpenLink`，四类分流复用 `LinkRouter` | `UserProfileScreen.kt` |
| ✅ 半截 S4 | 他人主页「网站」行补 UI（`website` / `website_name` 解析层早就有，整块没渲染） | `UserProfileModels.kt`、`UserProfileService.kt`、`UserProfileScreen.kt` |
| ✅ 半截 S6 | 取关加二次确认（此前误触一下就把关注取消了） | `UserProfileScreen.kt` |
| ✅ 半截 S8 | 徽章页末行等分（`Spacer(width(0.dp))` 不参与宽度分配，两个徽章被拉成各半宽） | `BadgesScreen.kt` |
| ✅ P0-5 部分 | **DoH 配置页落地**：`SETTINGS_NETWORK` / `SETTINGS_DOH` 两个悬空路由激活，含服务器增删选、逐条与一键测速、解析模式切换 | 新增 `ui/network/NetworkSettingsScreen.kt`、`ui/network/DohSettingsScreen.kt`、`services/network/DohSpeedTester.kt`、`DohSpeedTesterTest.kt`；改 `SettingsScreen.kt`、`ArkDoNavHost.kt` |
| ✅ 附带修 | `DohManager.init()` 此前**零调用**（首次启动种子数据没落盘，只靠 getter 默认值兜着）；现补进 `ArkDoApplication.onCreate` | `ArkDoApplication.kt` |

验证：`:app:compileDebugKotlin` + `:app:testDebugUnitTest` 均 BUILD SUCCESSFUL（含新增 5 个 DoH 报文用例）。
**未做真机验证**：同 v4，见 §十 的验证边界。

### 本轮明确「不做」和为什么

| 项 | 决定 | 理由 |
|---|---|---|
| 半截 S5（他人主页昵称旁状态表情） | **推迟**，并进 P0-4 表情体系一起做 | 它需要「短名 → CDN url」的表情目录服务（鸿蒙走 `EmojiUrlRegistry`），Android 只有 10 个 reaction 的 assets 映射。这不是"一行接通"，单独硬编码 URL 模板会造出一个和 P0-4 冲突的半成品 |
| 半截 S7（圆形头像先方后圆闪一下） | **不做，待实机复核** | 代码判据是 `user.id == 1L` 走方形、其余圆形；种子用户 `id = 0` 恒为圆形，**推不出报告里描述的"先方后圆"**。现象与代码不符时盲改会掩盖真因，需要真机复现后再定 |
| P0-5 的另外 3 个入口（论坛 / 通知 / 我的站点） | 推迟 | 三者都依赖 P0-6 多站点（`BASE_URL` 硬编码），先做入口是给空壳 |

---

## 一、结论先行

**主链路对齐度约 90%；功能点级对齐度约 82%。缺口 42 项（P0 6 / P1 13 / 半截 11 / P2 12）。**

> v5 相对 v4 净修 5 项半截链路（S1 / S2 / S4 / S6 / S8），并落地 DoH 配置页（P0-5 的 1/4）。
> **v6 再修 1 项（S3 草稿角标），并把鸿蒙的删除广播点核对到齐（4 个里 Android 有 3 个，缺的那个属 P0-3 回帖链路）**。
> 修得不多是因为本轮**刻意只做「数据与组件都在、只差接通」那一类**：改动小、风险低，
> 但用户会当成 bug 来报（点了没反应 / 东西没出来）。根因存疑的（S7）与依赖其它大项的（S5）先不动，理由见 §〇。

### 与 v3 相比的 delta（v4 时点，保留备查）

| 类别 | v3 | v4 | 变化 |
|---|---|---|---|
| P0 | 3 | **6** | 新增 3：回帖链路残废、表情体系缺失、多站点/论坛切换 |
| P1 | 8 | **13** | 新增 5：版本公告、附件上传与分享、抽屉分组、我的页活动行跳转、Markdown 工具栏缺钮 |
| 半截链路 | 12 | **17** | v3 的 12 项**已修 8 项**；本轮在新模块又抓出 13 项 |
| P2 工程化 | 12 | 12 | 全部未动（本轮复核确认） |
| 死代码/死资源 | 4 | **8** | 新抓 4 组：两个通知服务类、两个死 Prefs 常量、一个死资源 |

### v3 → v4 期间已完成的修复（共 13 项，三轮批次）

| 批次 | 内容 |
|---|---|
| 第一批（10 项） | 火箭 boost 入口 + 顺序对齐、书签可取消、上次访问红线、回复指示器可点、首页错误提示、会话检查死分支、抽屉受限项提示、顶栏铃铛未读角标 + 摇铃 |
| 第二批（2 项） | 火箭**回调**漏传（上一批只补了可见性）、`TopicPost` 类体字段被 `copy()` 抹掉导致「回复某人」头像消失 |
| 第三批（1 项大修） | `mapTopicDetail` 补 13 个漏搬字段——一次修好「俺也一样」、红线、采纳答案面板、首楼书签初始态、未读水位 |

验证：`:app:compileDebugKotlin` + `:app:testDebugUnitTest` 均 BUILD SUCCESSFUL。

---

## 二、模块覆盖率总览（v4 新增）

本轮逐模块核查的范围与结论，便于判断"哪里查过、哪里还没查"：

| 模块 | 鸿蒙侧 | Android 侧 | 对齐度 | 主要缺口 |
|---|---|---|---|---|
| 首页 / 分类抽屉 | `HomePage` 2217 行 + `CategoryDrawer` | `feature/home` 完整 | **~92%** | 徽章点按筛选、抽屉分组标题、我的站点快捷条 |
| 帖子详情 / 楼层 | `TopicDetailPage` 2304 + `TopicPostItem` 1529 | `ui/topic` + `TopicPostItem` | **~85%** | 待审帖卡片、引用卡跳楼、谁回应了、presence |
| **创作链路（发帖）** | `ComposerPage` | `ui/composer` | **~88%** | 工具栏缺 video/附件钮、多图批量、预览表情短码 |
| **创作链路（回帖）** | 复用 `ComposerPage` | `CommentInputBar` 单行 | **~45%** ⚠️ | 全屏编辑器、表情面板、草稿落盘全线缺失 |
| **表情体系** | `EmojiPicker` + `EmojiCatalogService` | 长按 10 个固定表情 | **~15%** ⚠️ | 目录服务、选择面板、短码渲染、URL 注册表 |
| 通知 | `NotificationPage` + `Bell` | `feature/notification` 完整 | **~95%** | 仅系统推送缺失（列表本身已完整） |
| 消息 / 私信 | `services/message` | 有 `MessageBusEventBus` | ~80% | presence 数据无消费者 |
| 搜索 | `SearchOverlay` + `SearchFilterPanel` | `feature/search` | **~98%** | 仅缺一镜到底转场动画 |
| 个人中心「我的」 | `ProfilePage` | `ui/screens/ProfileScreen` | ~88% | 状态表情（并进 P0-4）、头像方/圆闪白待复核 |
| 用户主页 | `UserProfilePage` | `ui/user/UserProfileScreen` | ~80% | 简介链接不可点、网站行未渲染、取关无确认 |
| 徽章 / 活动 / 等级 / 已解决 / 阅读记录 / 书签 / 积分 / 草稿 | 8 个页面 | `ui/user`、`ui/level`、`ui/bookmarks`、`ui/credit`、`ui/drafts` | **~92%** | 徽章末行等分占位、称号全息特效 |
| **设置全家桶** | 11 个页面 | 4 个页面 + 3 个 Toast 入口 | **~48%** | 论坛 / 推送 / 站点三页未迁移 |
| 网络 / DoH | `DohSettingsPage` + `DohSpeedTester` + `NetworkSettingsPage` | 配置页与测速已迁（v5） | **~78%** | 仅差 `NetworkSettingsPage` 的过盾入口开关（S14） |
| 多站点 | `services/site` 12 文件 | 5 文件、`BASE_URL` 硬编码 | **~30%** ⚠️ | 站点切换、能力门控、站点设置 |
| 音视频 | `AudioPlayer` + `VideoPlayer` + 协调器 | 纯文本占位 | **0%** ⚠️ | 整条缺失 |
| 系统推送 | `services/push` 12 文件 | 不存在 | **0%** ⚠️ | 整条缺失（连本地通知都没有） |
| 版本公告 | `services/announce` | 不存在 | **0%** | 启动时取版本说明与升级提示 |

---

## 三、P0 缺口（用户可直接感知，6 项）

| # | 缺失功能 | 鸿蒙版 | Android 现状 | 证据 |
|---|---|---|---|---|
| 1 | **系统推送 + 本地通知** | `services/push/` **12 文件**（`PushCoordinator` / `PushRelayClient` / `PushSubscriptionService` / `PushNotificationNavigation` / `PushRefreshGate`）+ `server/relay.py` | `services/push/` **不存在**；无 FCM/HMS 依赖；全库 0 处 `NotificationManager`；`POST_NOTIFICATIONS` 已声明但**无运行时申请** | Manifest `:9`；`libs.versions.toml` 无 firebase；`MainActivity.kt:210-217` 深链只打日志 + `// TODO`，**全程无 `onNewIntent`**（只有 `onCreate` @:52）→ App 活着时点通知接不到 |
| 2 | **帖内音视频播放** | `AudioPlayer.ets`(350) + `VideoPlayer.ets`(131) + `MediaPlaybackCoordinator`（全局互斥播放） | 媒体块**已解析但退化为纯文本**：`CookedHtmlParser` 产出 AUDIO/VIDEO，`PostRichContent.kt:211-212` 只输出「音频：URL」 | `PostRichContent.kt:67` 自述「音视频占位链接」；全库 `exoplayer\|media3\|ExoPlayer` → 0 |
| 3 | **回帖链路残废**（与发帖严重不对等） | 内联回复条一个「展开」即进全屏 `ComposerPage`：完整 Markdown 工具栏 + 图片/视频/附件 + 预览 + 表情；草稿走服务端 + 序号续存 | 只有**纯文本单行条**；无展开、无工具栏、无草稿落盘 | `CommentInputBar.kt:74` 自述「展开全屏编辑器属后续切片」；草稿仅内存 `TopicDetailViewModel.kt:835`；鸿蒙 `TopicDetailPage.ets:1201-1271` |
| 4 | **表情体系整体缺失** | `EmojiPicker.ets`（7 列分组面板）+ `EmojiCatalogService`（按站拉 `/emojis.json`，内存+磁盘+网络三级）+ `EmojiUrlRegistry`（短名→URL） | 只有长按回应的 **10 个固定表情**；发帖预览不解析短码；**`assets/linuxdo_emojis.json` 全项目零引用（死资源）** | `CommentInputBar.kt:77-90` 无表情钮；`EmojiRegistry.kt:10-28`；`NewTopicScreen.kt:861` 预览只编译 markdown |
| 5 | **设置 4 入口未迁移**（含 DoH 配置页）| `ForumsPage` / `PushSettingsPage` / `NetworkSettingsPage` / `DohSettingsPage` / `SitesPage` | ✅ **网络与连接 → DoH 配置已落地（v5）**；论坛 / 通知 / 我的站点仍弹 Toast「尚未迁移」 | `SETTINGS_NETWORK`/`SETTINGS_DOH` 已在 `ArkDoNavHost.kt` 注册；`SETTINGS_PUSH` 仍悬空 |
| 6 | **多站点 / 论坛切换** | `services/site/` **12 文件**（`CurrentSite` / `SiteSwitchCoordinator` / `SiteUrl` / `SiteScope` / `SiteCapabilities` / `SiteSettingsService` / `ForumPrefs`）+ `ForumsPage` + `ForumOnboardingPage` | **5 文件**，`AppConstants.BASE_URL` **硬编码 `linux.do`**；`SitePrefs` 零调用方 | 无 `ForumPrefs`/`CurrentSite`；站点能力门控（connect/credit/抽奖/mediaUpload 按域名）、上传体积上限与白名单全缺（Android 为硬编码） |

**性价比排序**：`DoH 配置页` > `多站点` > `回帖链路` > `表情体系` > `音视频` > `系统推送`。
DoH 后端（`DohManager` / `DohService` / `WebViewDohInterceptor`）**已完整且默认 `SECURE_ONLY` 开启**，只差一个 UI —— 是 6 项里最快见效的。

---

## 四、P1 缺口（体验完整性，13 项）

| # | 缺失功能 | 鸿蒙版 | Android 现状 | 证据 |
|---|---|---|---|---|
| 7 | **版本公告 / 升级提示** | `announce/VersionAnnouncement.ets`（扫帖 `2590051`，按 `[v<code>]` 匹配当前版本，启动时弹） | **完全不存在** | 全仓唯一命中是分类名「公告」（测试文件里） |
| 8 | **「谁回应了」列表** | `ReactionUsersSheet.ets`（可切表情、分页） | 数据模型已在（`TopicModels.kt:135 ReactionUsersPage`），**无弹层**；汇总胶囊看着可点但不可点 | `TopicPostItem.kt:555-558` 无 clickable |
| 9 | **正在回复的人 / 输入中** | `services/presence/` + `ReplyingPresenceBar` + `TypingDots` | `presence/` 不存在；`MessageBusEventBus` 已解析 `enteringUsers`（`:34-35`）但**无任何消费者** | 全库 `presence` → 0 命中（除模型） |
| 10 | **帖内实时新回复** | `RealtimePostsPrefs` | 只有首页新话题提醒；`extractCreatedPostId`（`:96`）/`extractMessageBusTopicId`（`:87`）**零调用** | 且 `RealtimeTopicsPrefs.kt:17` **默认值反了**（鸿蒙默认关、Android 默认开） |
| 11 | **待审核帖子卡片** | `PendingPostCard.ets`（标签/时间/正文/删除） | **无 UI**：模型（`TopicModels.kt:345`）与提交路径都在，用户被审后只看到一句 Toast，永远找不到也撤不回 | `TopicDetailViewModel.kt:831-839` |
| 12 | **抽奖帖提醒卡** | `LotteryNoticeCard.ets` + `LotteryTopicRule` + `LotteryTipPrefs` | 无 | `TopicDetailViewModel.kt:579` 自述「LotteryTopicRule 未迁移」 |
| 13 | **附件 / 视频上传 + 下载分享** | `upload/UploadService` + `VideoTranscoder`（视频压缩、附件、HEIC-alpha、`lookup-urls` 短码预览）+ `content/DownloadService` + `ImageShareService` | **只能传图**（仅 JPEG/1920，gif 原样）；非图片附件无落地；图片分享缺失 | `NewTopicViewModel.kt:44`、`UploadMarkdown.kt:8` 自述后续切片 |
| 14 | **Markdown 工具栏缺 2 钮** | `MarkdownToolbar.ets:19-32` 共 **12 钮**（含 `video` / `file-arrow-up` 附件）+ 按站点能力过滤 | **9 钮**，缺 `video`、`file-arrow-up` | `NewTopicScreen.kt:925-937` 与鸿蒙逐钮对比 |
| 15 | **多图批量上传 + 进度** | 多图选择 + `N/M` 进度 | 单图 `PickVisualMedia` | `NewTopicScreen.kt:175` |
| 16 | **平板双栏布局** | 横屏双栏 / 竖屏单栏自适应 | 0 处窗口尺寸类 | 全库 `WindowSizeClass` → 0 |
| 17 | **分类抽屉缺分组** | 「我的版块 / 所有版块」分组 + 组内计数 + 「我的标签 / 热门标签」分组 + 「我的站点」快捷条 | 平铺 top-level；热门标签数据有但不分组；无站点快捷条 | `HomeDrawer.kt:427-454`、`:656-736` |
| 18 | **回帖草稿落盘** | 服务端草稿 + 定时存 + 序号续存 | 仅内存，切走即丢（发帖侧已落盘，**两边能力不对等**） | `TopicDetailViewModel.kt:835` |
| 19 | **其他小项** | — | 「我的」页最近活动行不可点 / 他人页简介链接不可点 / 取关无二次确认 / 称号无全息特效 / 详情页「在网页中打开」菜单 / 抽屉 chip 搜索转场动画 | 见 §五 |

---

## 五、半截链路与降级（隐性缺口 · v6 后剩 11 项）

**这一类是报告里最该先修的**：骨架、数据、组件往往都已就位，只差一行转发或一次订阅，
用户会当成 bug 报，而纯静态对比目录结构永远发现不了。

### 5.1 一行就能修（数据 + 组件都在，只差接通）

> ✅ 标记的 6 项已在 v5 / v6 修掉（见 §〇）。保留在原位是为了对照原始诊断与修法。

| # | 现象 | 根因 | 证据 |
|---|---|---|---|
| ✅ S1 | ~~「我的」页最近活动行点了没反应~~ → **v5 已修**：整行接 `clickable` → `onOpenTopic` | `ProfileActivityRow` 无 `clickable`，`LoggedInContent` 签名也没接 `onOpenTopic` | `ProfileScreen.kt`、`ArkDoNavHost.kt`、`RootTabScreen.kt` |
| ✅ S2 | ~~他人主页简介里的链接点了没反应~~ → **v5 已修**：接 `onOpenLink`，四类分流复用 `LinkRouter` | 调 `InlineText` 未传 `onLinkClick`，走默认空实现；页面无 `onOpenLink` 参数 | `UserProfileScreen.kt`（对比 `UserProfilePage.ets:597-602`） |
| ✅ S3 | ~~草稿角标长期陈旧~~ → **v6 已修**：补内存真源 `DraftCountStore`，把鸿蒙的写入点搬齐（会话复查覆盖 / 登出归零 / 三处删草稿 -1）；**未建 `DraftEventBus`**（`StateFlow` 本身即总线，理由见 §〇） | 角标只读登录那一刻快照，全 App 无更新通路；`PROFILE_DRAFT_COUNT_KEY` 定义后零引用 | 新增 `services/draft/DraftCountStore.kt`；改 `SessionChecker` / `ProfileViewModel` / `DraftsViewModel` / `NewTopicViewModel` / `TopicDetailViewModel` / `ProfileScreen`（对比 `ProfilePage.ets:71` + `RootTabPage.ets:1778-1837`） |
| ✅ S4 | ~~他人主页「网站」行不渲染~~ → **v5 已修**：补 `website` 字段解析 + 整行 UI | 字段已解析但 UI 整块缺失 | `UserProfileModels.kt`、`UserProfileService.kt`、`UserProfileScreen.kt`（对比 `UserProfilePage.ets:531-548`） |
| S5 | **他人主页昵称旁状态表情不渲染** | 模型有 `statusEmoji`，仅楼层在用；**缺「短名 → CDN url」的表情目录服务，并进 P0-4 一起做** | `UserProfileModels.kt:46`（对比 `UserProfilePage.ets:441-448`） |
| ✅ S6 | ~~取关无二次确认~~ → **v5 已修**：「再想想 / 取消关注」确认弹窗，警示色只在后者 | 直接调 `toggleFollow()`，误触即取关 | `UserProfileScreen.kt`（对比 `UserProfilePage.ets:1241-1260`） |
| S7 | **圆形头像先方后圆闪一下** | `onOpenUser` 未透传 userId，种子 `id=0`。**待实机复核**：`UserAvatar` 判据是 `id == 1L` 走方形，种子恒为圆形，推不出报告描述的现象 | `UserProfileScreen.kt:99`、`UserProfileViewModel.kt:72` |
| ✅ S8 | ~~徽章末行被拉宽~~ → **v5 已修**：`Spacer(width(0.dp))` → `weight(1f)` | 用 `Spacer(width(0.dp))` 占位，它不参与宽度分配 | `BadgesScreen.kt` |

### 5.2 事件有发无收 / 有骨架无 UI

| # | 现象 | 证据 |
|---|---|---|
| S9 | **presence 数据无人消费**：`entering_users` / `leavingUserIds` 只解析不渲染 | `MessageBusEventBus.kt:17/34-35`；无消费者 |
| S10 | **帖内实时新回复未接线**：两个 extract 方法零调用（仅测试用） | `MessageBusEventBus.kt:87/96` |
| S11 | **分类/标签徽章不可点筛选**（v3 H10，仍缺） | `TopicRow.kt:76-121` 无 `onTagClick`/`onCategoryClick`（对比 `HomePage.ets:719-724`） |
| S12 | **引用卡不能跳楼 / 不能展开原文**（v3 H11，仍缺） | `PostRichContent.kt:277-330`（对比 `TopicDetailPage.ets:1646-1678`） |
| S13 | **深链不导航 + 无 `onNewIntent`**（v3 A，仍缺） | `MainActivity.kt:210-217`；Manifest `:41-46` 的 `arkdo://notification` 净空转 |
| S14 | **过盾入口的用户开关缺失** | 快捷入口会渲染（`TopicDetailScreen.kt:771`），但无 `ChallengeShortcutPrefs`；鸿蒙该开关在原状态**默认关**（`NetworkSettingsPage.ets:199-231`） |
| S15 | **实验性开关只有 1/6** | 仅「首页新话题」；其余 5 项 prefs 类不存在（`ExperimentalSettingsScreen.kt:88-100` vs `ExperimentalSettingsPage.ets:88-101`） |

### 5.3 降级（有替代，但弱于鸿蒙）

| # | 现象 | 证据 |
|---|---|---|
| S16 | **HTTP 明文放行** | Manifest `:25` `usesCleartextTraffic="true"`（release 包同样允许） |
| S17 | **诊断能力降级**：缺详细日志开关、日志大小标签、清空二次确认 | `DebugLogScreen.kt:136-153` vs `DiagnosticsSettingsPage.ets:119-196` |

---

## 六、死代码与死资源（专项 · 9 组）

这类不是"缺功能"，但会**严重误导后续判断**（看着有实现、其实是死的），建议单独清理。

| # | 对象 | 规模/证据 | 说明 |
|---|---|---|---|
| D1 | `ui/components/TopicListItem.kt` | 378 行，`TopicListItem(` 仅定义处命中 | 已被 `core/designsystem/TopicRow.kt` 取代；`:51` 注释仍写「Android 尚未移植设置页」（**已过期**） |
| D2 | `navigation/AppRoutes.kt` | 163 行，零引用 | 真实路由在 `ArkDoNavHost.kt:542+` 的 `Routes` |
| D3 | **`services/notification/NotificationService.kt` 整个类** | 无实例化、无 Provider | 含 `markPushNotificationRead:57` 死钩子；列表实际走 `core/data` 的 `DefaultNotificationRepository` |
| D4 | **`services/notification/NotificationApi.kt` 整个类** | 仅被 D3 与未被注入的 `di/AppModule.kt:107` 引用 | `load/markRead/markAllRead` 无真实调用方 |
| D5 | 悬空路由常量 ×3 | `ArkDoNavHost.kt:560-562` | `SETTINGS_DOH`/`SETTINGS_PUSH`/`SETTINGS_NETWORK`。**做 P0-5 时直接复用，不要另起名字** |
| D6 | 死 Prefs 常量（v6 后剩 1 主项 + 6 待查） | `StorageKeys.kt:58`（动图头像开关）**未修**；草稿角标那个 **v6 已删**（改为内存真源 `DraftCountStore`） | 定义后零引用。v6 顺带查出同批死常量 6 个：`DRAFTS_REFRESH_TICK_KEY` / `DRAFTS_COMPOSER_VISIBLE_KEY` / `DRAFTS_COMPOSER_BACK_TICK_KEY` / `SITES_TICK` / `SETTINGS_DOH_TICK` / `APP_IS_WIDE_STORAGE_KEY`，均只在定义处出现，**本轮未动** |
| D7 | 死资源 `assets/linuxdo_emojis.json` | 全项目零引用 | 表情目录的半个骨架，配套的 `EmojiCatalogService` 未迁移（见 P0-4） |
| D8 | `core:datastore` 空模块 | 无 `src/main` | 只有 `build.gradle.kts` |
| D9 | `DohManager.init()` | 零调用（v5 新增发现） | 首次启动的种子数据从没落盘过，一直靠 `getServers`/`isEnabled`/`getMode` 各自的默认值兜着（默认值恰好与种子一致，所以没暴露）。**v5 已接进 `ArkDoApplication.onCreate`** |

---

## 七、P2 缺口（工程化与发布，12 项 · 与 v3 一致，本轮复核全部仍未动）

| # | 问题 | 证据 |
|---|---|---|
| 20 | release 无签名配置 | `app/build.gradle.kts` 无 `signingConfigs` |
| 21 | 无 androidTest | `app/src/` 只有 `main` 与 `test` |
| 22 | 无 CI | 无 `.github/` |
| 23 | `core:datastore` 空模块 | 同 D8 |
| 24 | `core:common` 近乎空壳 | 仅 `RelativeTime.kt` + `Placeholder.kt` |
| 25 | `usesCleartextTraffic="true"` | Manifest `:25`（同 S16） |
| 26 | 无崩溃上报 | 无 Crashlytics / Bugly，仅本地 `DebugLogScreen` |
| 27 | 无隐私政策 / 用户协议 / 商店素材 | `TODO.md:163-165` 未勾选 |
| 28 | 无多语言、无 `values-night` | `res/` 只有 `drawable mipmap-* raw values xml` |
| 29 | 非图片附件下载无落地 | 同 P1-13 |
| 30 | `AppRoutes.kt` 死代码 | 同 D2 |
| 31 | **文档严重过期** | `TODO.md` 末行仍写「等待实现具体功能」；`MIGRATION.md` 里程碑二把 **20 个已完成页面**列为「未做」 |

> **P2-31 单独提请注意**：`MIGRATION.md` 已失去跟踪价值，照它排期会重复劳动。建议整体重写。

---

## 八、Android 已反超鸿蒙的地方（保持，不用回补）

1. **图片长按菜单**（保存相册 / 分享 / 拷贝链接）——`ui/components/ImageViewer.kt`
2. **首页悬浮按钮液态玻璃**，磨砂强度可调——`core/designsystem/FabGlassStore.kt`
3. **帖子列表样式设置**（字号/行高/内边距/头像/字重）——`TopicListStyleSettingsScreen`
4. **搜索独立垂直切片**——`feature:search`，`SearchFilters` 字段与鸿蒙**全量对齐**
5. **私有 DNS 引导页** `PrivateDnsGuideScreen`（鸿蒙无对应页面）
6. **Now in Android 风格模块化**——10 个 Gradle 模块，feature 垂直切片
7. **单元测试 28 个测试类**（覆盖 `CookedHtmlParser` / `LinkRouter` / `PostStreamPager` / `PollMutation` / `MessageBusEventBus` / `PostDataSource` / `TopicDetailParsing` 等，鸿蒙无对应单测）
8. **LdcCredit 拆分更细**——credit 子域 6 文件 vs 鸿蒙 4
9. **通知列表实现完整**——分页 30/页、下拉刷新、上拉加载、全部已读、乐观已读、未读轮询 + 实时频道，与鸿蒙逐项对齐（v3 未覆盖，本轮核实）
10. **DoH 诊断能力更强**——`NetworkAccessibilityChecker`（鸿蒙无）；DoH 配置 UI 自 v5 起也已补齐（见 §〇）

---

## 九、建议实施顺序

> ✅ 表示 v5 已完成。

**第 0 步 · 捡地上的钱（纯接通，见 §5.1）**
1. ✅ `ProfileScreen` 活动行接 `onOpenTopic`（S1）
2. ✅ `UserProfileScreen` 补 `onOpenLink` 并接通 bio 链接（S2）
3. 🟡 `UserProfileScreen` 渲染「网站」行（S4 ✅）+ 状态表情（S5 ⏸ 并进 P0-4）
4. ✅ 取关加二次确认对话框（S6）
5. ✅ 徽章页末行等分（S8）

**第一步 · 把「声明了没接上」收干净**
6. ✅ **DoH 配置页**：`SETTINGS_NETWORK` / `SETTINGS_DOH` 已注册，含服务器增删选 + 测速 + 模式切换（D5 + P0-5 的 1/4）
7. ✅ 草稿角标事件通路（S3）：补内存真源 `DraftCountStore` + 5 个写入点；**不建 `DraftEventBus`**（理由见 §〇）；D6 里草稿角标那个死常量一并删除
8. 深链导航 + `onNewIntent`（S13）、本地通知通道（不依赖 FCM）
9. 恢复 `PendingPostCard`（P1-11）、`ReactionUsersSheet`（P1-8）—— 两者模型都已在

**第二步 · 用户一眼能看到的**
10. **回帖链路对齐发帖**：展开全屏编辑器 + 草稿落盘（P0-3 / P1-18）—— 先做这两项，表情面板随后
11. **表情体系**（P0-4）：`EmojiCatalogService` → `EmojiPicker` → 短码渲染，顺手删掉死资源 D7 + 带上 S5 的状态表情
12. **多站点 / 论坛切换**（P0-6）：`ForumPrefs` + `CurrentSite`，让 `BASE_URL` 可变
13. 音视频播放（引入 Media3，替换 `PostRichContent.kt:67` 占位）
14. Markdown 工具栏补 `video` / `file-arrow-up`（P1-14）+ 多图批量上传（P1-15）

**第三步 · 横向铺开**
15. 系统推送全链路（FCM/HMS + `server/relay.py`）
16. 版本公告（P1-7）/ presence（P1-9）/ 帖内实时新回复（P1-10）/ 抽奖卡（P1-12）
17. 平板双栏（P1-16）/ 抽屉分组（P1-17）/ 附件上传与分享（P1-13）

**第四步 · 发布准备**
18. 签名配置 + CI + androidTest 骨架
19. 关闭明文 HTTP、崩溃上报、隐私政策与商店素材
20. **清理死代码 D1–D9**，重写 `TODO.md` / `MIGRATION.md`

---

## 十、修复历史（v3 @ `7f5a6b2` → v4 @ `6175f0a`）

| 批次 | 提交 | 内容 |
|---|---|---|
| 楼层 Scrubber | `7f5a6b2` | `ui/topic/TopicScrubber.kt` + 详情页接线，复用 `jumpToPostNumber`；与鸿蒙逐行对照修掉 5 处差异。**未迁移**：鸿蒙 SmartReach 智感握姿（依赖 motion 传感器 + 实验开关） |
| 第一批（10 项） | `a01e875` 前 | 火箭 boost 入口 + 操作区顺序对齐、书签可取消、上次访问红线、回复指示器可点、首页错误提示、会话检查死分支、抽屉受限项提示、顶栏铃铛未读角标 + 阻尼摇铃 |
| 第二批（2 项） | `a01e875` | 火箭**回调** `onAddBoost` 漏传；`TopicPost` 的 `replyToUser`/`author`/`badgeIcons`/`plainText`/`reporting` 由类体提升为主构造参数，根治 `copy()` 抹掉本地态导致的「回复某人头像消失」 |
| 第三批（1 项大修） | `a01e875` | `TopicService.mapTopicDetail` 补 13 个漏搬字段（`shared_issue_*` 四项、`last_read_post_number`、`bookmarked`、`accepted_answers`、`highest_post_number`、`unread_posts`…）—— 一次性修好「俺也一样」、上次访问红线、采纳答案面板、首楼书签初始态、已读水位 |
| 挑战恢复 | `598ba39` | 总线订阅改 `DisposableEffect`（修 Activity 重建重复挂载 + 旧协调器轮询泄漏）；接上手动验证入口 + `notifySheetDismissed()` 复位 |
| 首页/抽屉 | `3c931ab` | 铃铛角标与摇铃、错误 toast、会话检查真源、抽屉登录提示 |
| 文档/工程 | `21c99ae` `6175f0a` | 本报告 v3；`.gitignore` 忽略 `.tmp` / `.workbuddy` |
| **本轮（v4 → v5）** | 见 §〇 | 半截链路 5 项接通（S1 / S2 / S4 / S6 / S8）＋ DoH 配置页与测速（P0-5 的 1/4）＋ `DohManager.init` 接进启动流程 |
| **本轮（v5 → v6）** | 见 §〇 | 草稿角标真源 `DraftCountStore` + 5 个写入点（会话复查 / 登出 / 三处删草稿）；删死常量 `PROFILE_DRAFT_COUNT_KEY`；新增 `DraftCountStoreTest`（8 用例） |

**验证边界**：以上只做了编译与单测验证，**未做真机交互验证**。待实机确认：火箭 / 「俺也一样」在真实帖子上的显示（`canBoost` / `sharedIssueVisible` 由服务端下发）、书签取消后刷新是否真的不回来、红线画在第几楼、铃铛摇铃观感。

**「俺也一样」的实机验证目标**（linux.do 线上实测，未登录即可见）：
判据是话题标签**「快问快答」**（与分类无关：带标签 9/9 命中、不带 12/12 未命中，含同分类对照）。
`https://linux.do/t/topic/2952044` 应显示「俺也一样 (3)」；`/2952700` 显示无计数版；
计数**从 0 起**（不含楼主），别拿「是否等于 1」当断言；已解决的帖子按钮照样显示。

---

## 附：核对方法（可复跑）

```bash
# 结构对比
ls app/src/main/kotlin/com/arkdo/android/services/          # 17 个 vs 鸿蒙 25 个
ls /Users/tongtong/ArkDO/entry/src/main/ets/services/
ls /Users/tongtong/ArkDO/entry/src/main/ets/views/components/   # 47 个待逐个对照

# 能力关键词命中（0 = 缺失）
grep -rl "exoplayer\|media3" --include="*.kt" --include="*.toml" app core feature gradle   # 音视频
grep -rl "NotificationCompat\|NotificationManager" --include="*.kt" app/src/main           # 本地通知
grep -rl "WindowSizeClass\|presence\|Lottery\|CurrentSite\|SiteSwitch" --include="*.kt" app core feature
grep -rl "EmojiPicker\|EmojiCatalog" --include="*.kt" app core feature                     # 表情体系

# 半截链路：参数有没有往下传（定义处 vs 调用点）
grep -n "canAddBoost\|sharedIssueVisible" app/src/main/kotlin/com/arkdo/android/ui/components/TopicPostItem.kt

# 死代码：定义有、调用无
grep -rn "TopicListItem(" --include="*.kt" app core feature
grep -rln "AppRoutes\|NotificationService\|NotificationApi" --include="*.kt" app core feature
grep -rn "PREFS_ANIMATED_AVATAR\|PROFILE_DRAFT_COUNT_KEY" --include="*.kt" app core feature
grep -rn "linuxdo_emojis" -r app core feature

# 路由常量悬空
grep -n "SETTINGS_DOH\|SETTINGS_PUSH\|SETTINGS_NETWORK" app/src/main/kotlin/com/arkdo/android/navigation/ArkDoNavHost.kt

# 自述未完成标记
grep -rn "尚未迁移\|后续切片\|未迁移\|暂不支持" --include="*.kt" app core feature

# 工程化
grep -n "signingConfig" app/build.gradle.kts ; ls -a | grep -E "^\.github" ; ls app/src/main/res/
```

> **工具提醒**：macOS 自带 BSD `grep` **不支持 `\|`**（GNU 扩展），`grep "a\|b"` 会当字面量、
> 静默返回空。一律用 `grep -E "a|b"` 或 ripgrep，否则会得出「鸿蒙没有这个功能」这类假结论。
