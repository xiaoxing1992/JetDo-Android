# ArkDO-Android 功能缺口分析（v3 · 提交后复核）

> 对标对象：鸿蒙版 [`ArkDO`](../ArkDO)（ArkTS，228 个 .ets / 28 页面 / 47 组件 / 25 服务子域）@ `main 7680996`
> 复核分支：`feat/migrate-vertical-slice` @ `7f5a6b2`（工作区已全部提交）
> 复核日期：2026-09-26
> 版本链：v1 @ `f78d3e5` → v2 @ `cf223b7` → **v3 @ `7f5a6b2`**

---

## 一、结论先行

**主链路对齐度约 90%；功能点级对齐度约 84%。本轮缺口总数 36 项（P0 3 / P1 8 / 隐性 12 / P2 12）。**

### 与 v2 相比的 delta

| 类别 | v2 | v3 | 变化 |
|---|---|---|---|
| P0 | 4（含 Scrubber） | **3 项真缺** | Scrubber 已落地并复核在位，P0 由 4 降为 3 |
| P1 | 8 | 8 | **全部未动** |
| 隐性缺口 | 5 | **12** | 本轮新抓 7 项（首页 4 + 详情页 3） |
| P2 | 12 | 12 | 全部未动 |

**v2 → v3 期间唯一的实质推进是楼层 Scrubber**（`ui/topic/TopicScrubber.kt`，`7f5a6b2`）。
**v2 列出的 P0/P1/P2 其余各项零推进**——深链不导航、`POST_NOTIFICATIONS` 空转、设置 4 入口仍弹 Toast、路由常量仍悬空。

**本轮增量价值**：对首页（2217 行）与详情页（2304 + 1529 行）做了**逐功能点**比对（v1/v2 只做到目录/服务级），
新抓到 **7 项半截链路**——其中 3 项是「数据已下发、组件已写好、只差一行参数转发」的纯漏接，修复成本极低但用户长期看不到功能。

### ✅ 修复批次 2026-09-26（本条为 v3 之后追加，`:app:compileDebugKotlin` 通过、零新增警告）

一次修掉 **9 项**，其中 8 项来自上表的隐性缺口 + 1 项 UI 细节补齐。改动 8 个文件：

| # | 修的是什么 | 改动 |
|---|---|---|
| H1 | **火箭 boost 入口**终于会出现 | `TopicPostItem.kt` PostFooter 调用点补传 `canAddBoost = post.canBoost == true`（鸿蒙 `canAddBoost()` @ TopicPostItem.ets:1167） |
| H1' | 操作区顺序对齐鸿蒙 | 由 `点赞→更多→火箭→回复` 改为 `点赞→火箭→更多→回复`（鸿蒙 :495-511） |
| H2 | **「俺也一样」**终于会渲染 | 同调用点补传 `sharedIssue*` 五个参数。⚠️ **但这只是第一步**——它真正不显示的原因在数据层（`mapTopicDetail` 从未搬这四个字段），见下方**第三轮修复** |
| H3 | **书签能取消** | `TopicService.kt` 的 `DiscoursePost` 补 `bookmarked`/`bookmark_id` 两字段并映射（原先硬写 null）；VM `toggleBookmark` 重写对齐鸿蒙 `PostActions.ets:136-180`：无 id 时走 add 而非静默成功，成功后把 id 回填本楼 |
| H5 | **「上次访问」红线**不再是灰线 | VM 新增 `lastVisitedAnchorPostId`（按进入时快照水位算一次、锚定后不变，鸿蒙 :2114-2132）+ Screen 传 `showLastVisitedLine` |
| H6 | **「回复别人」指示器能点了** | `PostAuthorHeader` 收 `onJumpToPost`，clickable 从空注释改为真正跳楼；补 4dp 图标间距（鸿蒙 Row space 4） |
| H7 | **首页新话题拉取失败有提示** | `HomeRoute` 以 `state.error` 为 key 弹 toast（鸿蒙 :1845-1856），VM 加 `consumeError()` + 开拉先清 error（否则同一句文案第二次不触发） |
| H8 | 会话检查转圈不再是死分支 | `SessionViewModel` 加 `isChecking` 状态流，`RootTabScreen` 由恒传 false 改为订阅真源 |
| H9 | **抽屉受限项点了有反馈** | 排序 chip / 受限版块由「默默关抽屉 / 完全不可点」改为 toast「登录后可查看该入口」（鸿蒙 `CategoryDrawer.ets:257`） |
| H12 | **顶栏铃铛有未读角标** | 新增红色数字角标（15dp、99+ 封顶）+ 间歇式阻尼摇铃（14°→-12°→9°→-6°→3°→0°，每轮间隔 3.2s），对齐鸿蒙 `NotificationBell.ets`；`RootTabScreen → HomeFeatureScreen → HomeRoute → HomeScreen` 四层透传 `unreadCount` |

**仍未修**（已定位到行号，见各节）：H4 待审帖卡片、H10 徽章点击筛选、H11 引用卡跳楼、A 深链导航、B 本地通知、C/D 死代码与悬空常量、P0 三项、P1 八项、P2 十二项。

### ✅ 第二轮修复 2026-09-26（用户实机回测反馈 2 项，均复现）

| # | 用户报的现象 | 根因 | 修法 |
|---|---|---|---|
| H1-R | **火箭点下去没反应** | 上一轮补漏传时只补了**可见性** `canAddBoost`，漏了**回调** `onAddBoost` → `PostFooter` 取默认空 lambda，火箭渲染出来但点了无动作 | `TopicPostItem.kt` PostFooter 调用点补 `onAddBoost = { onAddBoost(post) }`；并在参数旁写明"可见性与回调是一对，必须成对传" |
| H13 | **未读上报后「回复某人」头像消失** | `TopicPost.replyToUser` / `author` / `badgeIcons` / `plainText` / `reporting` 原先写在**类体**（非主构造参数），而 data class 的 `copy()` 只搬主构造参数。已读上报 `markPostsRead()` 执行 `post.copy(read = true)` 时把它们全部清回默认值 → `if (post.replyToUser != null && replyToPostNumber != null)` 不成立 → 整行指示器消失。`markPostsReporting()` 的 `post.copy()` 同因，上报期间即闪没 | **根治**：5 个本地态字段全部提升为主构造参数并标 `@Transient`（照抄同文件 `linkClicks` 既有写法），所有 `copy()` 点自动携带。`TopicService.mapPosts` 里构造后赋值仍合法（主构造 `var`）。`carryLocalFields()` 保留，仅服务"从 DTO 重新 map 的新对象" |

验证：`:app:compileDebugKotlin` + `:app:testDebugUnitTest` 均 BUILD SUCCESSFUL，零新增警告。
副作用排查：`PostDataSource` 的所有查找按 `id`/`postNumber` 匹配、不用 `equals`，因此"本地态进入 equals"未引入去重/重组问题。
另确认小飞机点击后的登录门 `openComposerForBoost` 与鸿蒙 `TopicDetailPage.ets:1151-1161` 逐行一致，且 `ArkDoNavHost.kt:79` 有 `LoginGate.subscribe` 订阅者（未登录会弹登录半弹窗），所以"没反应"确由漏传回调导致，非登录拦截。

#### 同类隐患扫描（Kotlin data class 类体可变字段，`copy()` 会丢）

全仓扫描 `data class` 的**类体**（非主构造）可变字段，仍有 4 处**同构风险**，但**当前无实际触发路径**（不像 `TopicPost` 已在已读上报链路上踩到），故本轮不改，登记备查：

| 类型 | 类体字段 | 赋值处 | 现状 |
|---|---|---|---|
| `Topic`（`data/models/TopicModels.kt:37`） | `searchBlurb` / `author` | `TopicService.kt:1094`、`SearchRepository` | 全仓无 `Topic.copy(...)` 调用（`DefaultSearchRepository:103` 的 `copy(searchBlurb=…)` 作用于 `core:model` 的 **HomeTopic**，另有其类），暂安全 |
| `UserActivityItem`（`data/models/UserProfileModels.kt:185`） | `author` | `UserProfileService.kt:454` | 未见 copy 调用 |
| `UserProfileDetail`（`data/models/UserProfileModels.kt:15`） | `endorsementAvailable` / `endorsedCategoryIds` | — | 未见 copy 调用 |
| `SearchFilters`（`core/model/SearchModels.kt:44`） | `count` | — | 未见 copy 调用 |

**统一修法与本次 `TopicPost` 相同**：提升为主构造参数 + `@Transient`。等这些类型将来出现 `copy(...)` 调用点时一并处理。

---

### ✅ 第三轮修复 2026-09-26（用户回测追问「俺也一样是不是需要什么条件」）

#### 先订正上一轮报告里的一处错误结论

本轮排查中途曾得出"鸿蒙版没有这个功能"——**那是假的**。原因：macOS 自带的 BSD `grep` **不支持 `\|`**（GNU 扩展），写了 `grep "a\|b\|c"` 会当成字面量匹配，整条搜索静默返回空，连着骗了我三次。
> 教训（已写进项目记忆）：**macOS 上不要用 `grep "a\|b"`，改用 `grep -E "a|b"` 或 ripgrep。**

用 ripgrep 复查后确认：**鸿蒙有完整实现**（`0b20a1c` 起，当前 HEAD 落在 `TopicApiTypes.ets:75-79`、`TopicService.ets:1120-1123`、`TopicPostItem.ets:774` 的 `MeTooButton`）。

#### 真根因：`mapTopicDetail` 是「简化版映射」，整批话题级字段没搬

`getTopicDetail` 走 `decodeFromString<DiscourseTopicDetail>` → `mapTopicDetail(payload)` **手工**映射。这个映射函数源码里就自述"简化版"，实测**13 个字段一个都没搬，且中间 DTO 里连定义都没有** → 下游一律拿到 null / 默认值。与之前 `bookmarkId` 完全同型，但这次是一整批。

| 被漏字段 | 用户可见后果 | 处置 |
|---|---|---|
| `shared_issue_visible` / `can_create_shared_issue` / `user_created_shared_issue` / `shared_issue_count` | **「俺也一样」永不显示**（本轮用户报的） | ✅ 补 DTO + 映射 |
| `last_read_post_number` | **「上次访问」红线永不出现**（VM `updateLastVisitedAnchor` 第一行 `?: 0` 就直接 return）；已读预置退化成只看 `read` 字段 | ✅ 补 |
| `highest_post_number` / `unread_posts` | 未读水位（目前无消费方） | ✅ 补 |
| `bookmarked` | 详情页首楼书签初始态恒为「未收藏」 | ✅ 补 |
| `accepted_answers` | **采纳答案面板永不出现 + solved 标记恒 false**（`TopicDetailScreen.kt:457`） | ✅ 补 |
| `polls_votes` / `can_vote` | 无影响：投票态实际由 `post.can_vote` / `polls` 下发（`TopicService.kt:1220`） | 无需补 |
| `pending_posts` / `message_bus_last_id` | 无消费方（待审帖卡片、实时新回复本就未接） | 登记备查 |

#### 「俺也一样」的显示条件（取自鸿蒙源码注释，非推测）

- `shared_issue_visible == true` 是**唯一**渲染条件，且**只在 1 楼**（页面只对一楼下发）；
- `can_create_shared_issue`：当前用户能否点。**未登录、或自己是楼主 → false，但按钮照样显示**，只是「已登录 + false」时置灰禁用（透明度 0.45）、未登录时可点以走登录拦截；
- `user_created_shared_issue`：已点 → 图标染 accent 色 + 文案带括号计数；
- `shared_issue_count`：计数。
- ⚠️ 鸿蒙注释特别警告：**别改用 topic-voting 的 `can_vote` / `user_voted` / `vote_count`** —— 本站恒为 false/0，历史上就是这么踩的。

#### ✅ linux.do 上的实际触发条件（2026-09-26 线上实测，非文档推测）

用户反馈「点了 20 多个帖子都没找到」。抓 linux.do 真实接口比对后，判据是**标签**，不是分类：

| 组 | 样本量 | `shared_issue_visible` |
|---|---|---|
| 带 `快问快答` 标签 | 9 个（横跨 cat 4 开发调优 / cat 11 搞七捻三） | **9 / 9 = true** |
| 不带该标签 | 12 个（cat 2 / 4 / 11 / 34 / 36） | **0 / 12 = true**，且连 `shared_issue_count` 键都不下发 |

**同分类对照**：cat 4 里带标签为 true、不带为 false ⇒ **与分类无关，只看标签**。
（官方 Discourse 文档写的是「unsolved topic in a **support category**」；linux.do 运营方把这条落到了 `快问快答` 标签上。）

其他实测要点：
- **已解决的帖子按钮依然显示**（`t/2952044` 有采纳答案，按钮仍在），所以「未解决」不是 linux.do 的判据；
- linux.do 的计数**从 0 起**（不把楼主算进去），与 Discourse 官方 demo「起始为 1」不同 —— 验客户端时别拿「是否等于 1」当断言；
- 字段只在**话题级**（`shared_issue_visible` / `shared_issue_count` / `can_create_shared_issue`），
  `post_stream.posts[]` 里**没有** shared 相关键。

**实机验证目标**（未登录即可见，可直接拿来跑 UI）：

| 用途 | 链接 | 期望按钮文案 |
|---|---|---|
| **验 UI 形态（带计数）** | `https://linux.do/t/topic/2952044` | **俺也一样 (3)** |
| 带计数 | `https://linux.do/t/topic/2851783`、`/2948430` | 俺也一样 (2) |
| 无计数（count=0） | `https://linux.do/t/topic/2952700` | 俺也一样（无括号） |
| 浏览入口 | `https://linux.do/tag/快问快答` | — |

截图存档：`artifacts/metoo-linuxdo-topic.png`（2952700 整页）、`artifacts/metoo-linuxdo-count3.png`
（2952044 整页，含「俺也一样 (3)」）、`artifacts/metoo-linuxdo-count3-closeup.png`（按钮特写）、
`artifacts/metoo-c.png`（Discourse 官方 `Me too (2)` 实拍，用于对照文案形态）。

#### 顺带加的诊断日志

`TopicDetailViewModel.applyDetail` 里打一条：
`[ArkDO][TopicDetail] topicId=… sharedIssue(visible=… canCreate=… created=… count=…) lastRead=… accepted=…`
实机若不显示，看这条即可区分「服务端没下发（正常，该话题未开启共享问题）」与「客户端没接住（bug）」。

验证：`compileDebugKotlin` + `testDebugUnitTest` 均 BUILD SUCCESSFUL，零新增警告。


---

## 二、P0 缺口（用户可直接感知，3 项）

| # | 缺失功能 | 鸿蒙版 | Android 现状 | 证据 |
|---|---|---|---|---|
| 1 | **系统推送 + 本地通知** | `services/push/` **12 文件** + `server/relay.py` | `services/push/` **不存在**；无 FCM 依赖；全库 0 处 `NotificationManager` | `services/` 子域清单无 push<br>`gradle/libs.versions.toml` 无 firebase<br>Manifest `:9` `POST_NOTIFICATIONS` 空转 |
| 2 | **音视频播放** | `AudioPlayer.ets`(350) + `VideoPlayer.ets`(131) + `MediaPlaybackCoordinator` + `MediaSource` | 只渲染一行占位文本链接，点击跳出去 | `ui/components/PostRichContent.kt:67` 自述「音视频占位链接」<br>全库检索 `exoplayer\|media3\|ExoPlayer` → 0 |
| 3 | **设置 4 个入口未迁移** | `ForumsPage` / `PushSettingsPage` / `NetworkSettingsPage` + `DohSettingsPage` / `SitesPage` | 点击弹 Toast「尚未迁移」 | `ui/screens/SettingsScreen.kt:88`(常量)<br>:162 论坛 / :168 通知 / :174 网络与连接 / ~:186 我的站点 |

> DoH **引擎已就绪**（`DohManager` / `DohService` / `WebViewDohInterceptor`），缺的只是 UI 与测速——这是 4 个入口里性价比最高的一个。

---

## 三、P1 缺口（体验完整性，8 项）

| # | 缺失功能 | 鸿蒙版 | Android 现状 | 证据 |
|---|---|---|---|---|
| 4 | **回复全屏编辑器** | `ComposerPage.ets` | 回复条仍是单行内联，无展开/全屏 | `ui/components/CommentInputBar.kt:74` 自述「展开全屏编辑器…属后续切片」 |
| 5 | **表情面板** | `EmojiPicker.ets` + `EmojiCatalogService` | 回复/发帖均无面板；只有渲染侧 `EmojiRegistry` | 同上 `CommentInputBar.kt:74`；`services/content/` 无 `EmojiCatalogService` |
| 6 | **「谁回应了」列表** | `ReactionUsersSheet.ets` + `ReactionUsersPager` | 回应汇总胶囊**看着可点但不可点** | `ui/components/TopicPostItem.kt:555-558` 无 clickable；无 `ReactionUsersSheet` |
| 7 | **正在回复的人 / 输入中** | `services/presence/` + `ReplyingPresenceBar` + `TypingDots` | `services/presence/` 不存在 | 全库检索 `presence` → 0 命中 |
| 8 | **帖子实时新回复** | `RealtimePostsPrefs` | 只有首页新话题提醒，无帖子级 | `services/realtime/` 仅 `RealtimeTopicsPrefs.kt` 一个文件 |
| 9 | **版本公告 / 抽奖卡** | `services/announce/` + `LotteryNoticeCard` + `HolographicText` | 均无 | `ui/topic/TopicDetailViewModel.kt:559` 自述「LotteryTopicRule 未迁移」 |
| 10 | **多站点 / 站点切换** | `services/site/` **12 文件**（`CurrentSite`/`SiteSwitchCoordinator`/`SiteUrl`/`SiteScope`/`SiteCapabilities`/`SiteSettingsService`/`ForumPrefs`） | **5 文件**，单站点硬编码 | 缺 7 个；`AppConstants.BASE_URL` 单值 |
| 11 | **平板双栏布局** | 横屏双栏 / 竖屏单栏自适应 | 0 处窗口尺寸类 | 全库检索 `WindowSizeClass` → 0 |
| 12 | **回帖草稿落盘** | `DraftService` 服务端草稿 + 定时存 | 仅内存暂存，切走即丢 | `ui/topic/TopicDetailViewModel.kt:807` 自述「磁盘草稿后续切片接」（发帖侧已落盘，两边不一致） |

---

## 四、本轮新发现：隐性缺口（12 项）

这一类是 v3 的主要增量：**骨架在了，但没接上**。用户会当成 bug 报，静态对比目录永远发现不了。

### 🔴 一行就能修的（数据 + 组件都在，只差转发）

| # | 现象 | 根因 | 证据 |
|---|---|---|---|
| H1 | **火箭（boost）入口永不出现** | `TopicPostItem` 收了 `onAddBoost`，但调用 `PostFooter` 时**未转发 `canAddBoost`** → 默认 false → `if (canAddBoost && boosts.isNullOrEmpty())` 恒不成立 | 调用点 `TopicPostItem.kt:224-283`（无 canAddBoost）<br>参数默认 `TopicPostItem.kt:475`<br>渲染条件 `TopicPostItem.kt:706` |
| H2 | **「俺也一样」按钮永不渲染** | 同上：`sharedIssueVisible/Created/Count/CanCreate/onToggleSharedIssue` 五个参数**都没传给 PostFooter** → 恒 false。VM 已下发真实值 | VM 下发 `TopicDetailViewModel.kt:988-991`<br>传入 TopicPostItem `TopicDetailScreen.kt:703`<br>未转发给 PostFooter `TopicPostItem.kt:224-283`<br>渲染条件 `TopicPostItem.kt:510` |
| H3 | **书签永远删不掉** | `TopicService` 映射时把 `bookmarkId` **硬写为 null** → VM 拿不到 id → 走 `done(true)` 静默成功，服务端从未执行删除 | `services/topic/TopicService.kt:1153`<br>`ui/topic/TopicDetailViewModel.kt:482-486` |

> H1/H2 是同一个调用点的同一处漏传，**一个工单一起修**。

### 🟠 半截链路（有数据、有分支，但 UI 不消费）

| # | 现象 | 证据 |
|---|---|---|
| H4 | **待审核帖无处可找**：模型 + 服务齐备，用户提交被审后只看到一句 Toast，永远找不到也撤不回 | 模型 `TopicModels.kt:300`；服务 `TopicService.kt:1548/1590`；VM 只吐 Toast `TopicDetailViewModel.kt:831-839` |
| H5 | **「上次访问」红线恒为灰线**：`LastVisitedDivider` 组件已实现，但详情页调用处**从不传 `showLastVisitedLine`**（默认 false） | `TopicPostItem.kt:100`(默认 false) / `:316`(分支)<br>`TopicDetailScreen.kt:685-717`(调用处无该参数) |
| H6 | **「回复别人」指示器点了没反应**：有涟漪、有图标，clickable 里是空注释 | `TopicPostItem.kt:402` `.clickable { /* onJumpToPost 接线后定位 */ }` |
| H7 | **新话题拉取失败提示永不可见**：VM 写入 `error`，但 UI 只在列表为空时读它 → 有列表时失败静默 | `feature/home/.../HomeScreen.kt:251`（仅 `state.topics.isEmpty()` 分支传 error）<br>`HomeViewModel.kt:330/351` 写 error |
| H8 | **会话检查转圈是死分支**：`RootTabScreen` 恒传 `isCheckingSession = false`，头像 loading 永不出现 | `RootTabScreen.kt:259`；消费处 `HomeScreen.kt:405` |
| H9 | **抽屉受限项点了直接关抽屉**：未登录点击版块/标签 chip 只 `onBlockedTap = onClose`，没有「请先登录」提示 | `HomeDrawer.kt:298-306`（chip）、`:572-574`（注释写"走登录提示"却写成不可点） |

### 🟡 降级（有替代，但弱于鸿蒙）

| # | 现象 | 证据 |
|---|---|---|
| H10 | **分类/标签徽章不可点筛选**：列表行无 `onTagClick`/`onCategoryClick`，鸿蒙点徽章直接筛选 | `TopicRow.kt:77-88`（对比 `HomePage.ets:719-725`） |
| H11 | **引用卡不能跳楼 / 不能展开原文**：整卡只接了 `onLinkClick`，无 UP 箭头、无全文展开 | `PostRichContent.kt:277-330`（对比 `TopicDetailPage.ets:1646-1678`） |
| H12 | **首页顶栏铃铛无未读角标**：未读数只喂给底部 tab，顶栏是静态铃铛 | `HomeScreen.kt:399-402` 无 badge；`RootTabScreen.kt:207` 底栏有角标 |

### （v2 已发现、本轮复核仍未修）

| # | 项 | 现状 |
|---|---|---|
| A | 推送深链解析了但不导航 | `MainActivity.kt:210-217` 只打日志 + `// TODO`；**全程无 `onNewIntent`**（全文件只有 `onCreate` @:52）→ App 活着时点通知接不到。Manifest `:41-46` 的 `arkdo://notification` 净空转 |
| B | `POST_NOTIFICATIONS` 声明但零使用 | Manifest `:9`；全库 0 处 `NotificationCompat`/`NotificationManager`/`NotificationChannel` → **连本地通知都没有** |
| C | `TopicListItem.kt` 死代码（378 行） | `TopicListItem.kt:82` 零调用方，已被 `core/designsystem/TopicRow.kt` 取代；`:51` 注释仍写"Android 尚未移植设置页"（已过期） |
| D | 设置路由常量悬空 ×3 | `ArkDoNavHost.kt:560-562` `SETTINGS_DOH` / `SETTINGS_PUSH` / `SETTINGS_NETWORK` 定义但无 `composable` 注册。**做 P0-3 时直接复用这三个常量，不要另起名字** |

---

## 五、P2 缺口（工程化与发布，12 项 · 与 v2 一致，全部未动）

| # | 问题 | 证据 |
|---|---|---|
| 13 | release 无签名配置 | `app/build.gradle.kts:45/53` 只有 `isMinifyEnabled`，无 `signingConfigs` |
| 14 | 无 androidTest | `find -path "*androidTest*"` → 0 文件（单测 45 个，UI 测试 0） |
| 15 | 无 CI | 无 `.github/` |
| 16 | `core:datastore` 空模块 | 目录下无 `src/main`（只有 build.gradle.kts） |
| 17 | `core:common` 近乎空壳 | 仅 `RelativeTime.kt` + `Placeholder.kt` |
| 18 | `usesCleartextTraffic="true"` | Manifest `:25`（release 包允许明文 HTTP） |
| 19 | 无崩溃上报 | 无 Crashlytics / Bugly，仅本地 `DebugLogScreen` |
| 20 | 无隐私政策 / 用户协议 / 商店素材 | `TODO.md:163-165` 未勾选 |
| 21 | 无多语言、无 `values-night` | `app/src/main/res/` 只有 `values/` |
| 22 | 非图片附件下载无落地 | 只有 `services/content/DownloadEventBus.kt`（纯事件总线） |
| 23 | `AppRoutes.kt` 163 行死代码 | 零调用方；真实路由是 `ArkDoNavHost.kt:542+` 的 `Routes` |
| 24 | **文档严重过期** | `TODO.md` 末行仍写"等待实现具体功能"；`MIGRATION.md` 里程碑二把 20 个**已完成**的页面/组件列为"未做" |

> **P2-24 单独提请注意**：`MIGRATION.md` 已失去跟踪价值，继续照它排期会重复劳动。建议整体重写（见 §七）。

---

## 六、Android 已反超鸿蒙的地方（保持，不用回补）

1. **图片长按菜单**（保存相册 / 分享 / 拷贝链接）——`ui/components/ImageViewer.kt`
2. **首页悬浮按钮液态玻璃**，磨砂强度可调——`core/designsystem/FabGlassStore.kt`
3. **帖子列表样式设置**（字号/行高/内边距/头像/字重）——`TopicListStyleSettingsScreen`
4. **搜索独立垂直切片**——`feature:search`（`SearchFilterPanel` / `SearchHistoryStore` / `SearchVisualTokens`）
5. **私有 DNS 引导页** `PrivateDnsGuideScreen`（鸿蒙无对应页面）
6. **Now in Android 风格模块化**——10 个 Gradle 模块
7. **单元测试 45 个**（覆盖 `CookedHtmlParser` / `LinkRouter` / `PostStreamPager` / `PollMutation` / `MessageBusEventBus` / `BookmarkRepository` / `LdcCredit`）
8. **LdcCredit 拆分更细**——credit 子域 6 文件 vs 鸿蒙 4（`LdcCreditFetch` / `LdcEntry` / `LdcFormat`）

---

## 七、建议实施顺序

**第 0 步 · 捡地上的钱（半天以内，3 项纯漏接）**
1. `TopicPostItem.kt:224-283` 补转 `canAddBoost` + `sharedIssue*` 五个参数 → 火箭与「俺也一样」立刻出现（H1+H2）
2. `TopicService.kt:1153` 补 `bookmarkId` 映射 → 书签能删（H3）
3. `TopicPostItem.kt:402` 接上 `onJumpToPost` → 回复指示器可用（H6）

**第一步 · 把「声明了没接上」收干净**
4. 深链导航 + `onNewIntent`（A）、本地通知通道（B）—— 后者不依赖 FCM
5. 设置 4 入口：先做**网络与连接**（DoH 引擎现成），复用悬空常量 `SETTINGS_DOH/NETWORK`（D）
6. H4 待审帖卡片 / H5 上次访问红线 / H7 首页错误提示 / H8 死分支 / H9 抽屉登录提示

**第二步 · 用户一眼能看到的**
7. 回复全屏编辑器 + 表情面板（发帖侧 `MarkdownToolbar` 可复用）
8. 回帖草稿落盘（对齐发帖侧 `DraftService`）
9. 「谁回应了」列表（模型契约已齐）
10. 音视频播放（引入 Media3，替换 `PostRichContent.kt:67` 占位）

**第三步 · 横向铺开**
11. 系统推送全链路（FCM + `server/relay.py`）
12. 多站点 / 平板双栏 / presence / 实时新回复 / 公告与抽奖卡

**第四步 · 发布准备**
13. 签名配置 + CI + androidTest 骨架
14. 关闭明文 HTTP、崩溃上报、隐私政策与商店素材
15. **重写 `TODO.md` / `MIGRATION.md`**（当前版本会误导排期）

---

## 八、实施进展

### 2026-09-26 修复批次（9 项，`:app:compileDebugKotlin` BUILD SUCCESSFUL · 零新增警告）

| 文件 | 改动 |
|---|---|
| `ui/components/TopicPostItem.kt` | PostFooter 调用点补传 `canAddBoost` + `sharedIssue*` 五参数（H1/H2）；火箭移到「更多」之前对齐鸿蒙顺序；`PostAuthorHeader` 收 `onJumpToPost` 并接线回复指示器 + 4dp 间距（H6） |
| `services/topic/TopicService.kt` | `DiscoursePost` 补 `bookmarked`/`bookmark_id`，`mapPosts` 不再硬写 null（H3 根因） |
| `ui/topic/TopicDetailViewModel.kt` | `toggleBookmark` 重写（无 id 走 add、成功回填 id、不再静默成功）；新增红线锚楼计算 `updateLastVisitedAnchor` + `lastVisitedAnchorPostId` 入 uiState（H3/H5） |
| `ui/topic/TopicDetailScreen.kt` | `TopicDetailContent` 收 `lastVisitedAnchorPostId` 并逐楼传 `showLastVisitedLine`（H5） |
| `feature/home/.../HomeRoute.kt` | 列表非空时 toast 提示 error（H7）；透传 `unreadCount` |
| `feature/home/.../HomeViewModel.kt` | 新增 `consumeError()`；开拉新话题前先清 error（H7） |
| `feature/home/.../HomeDrawer.kt` | 受限 chip / 受限版块点击给登录提示，不再静默关抽屉或不可点（H9） |
| `feature/home/.../HomeScreen.kt` | 顶栏铃铛加红色数字角标 + 间歇阻尼摇铃（H12） |
| `ui/screens/SessionViewModel.kt` | 新增 `isChecking` 状态流（H8） |
| `ui/screens/RootTabScreen.kt` | `isCheckingSession` 接真源；`unreadCount` 下发首页（H8/H12） |

**验证边界**：只做了编译验证，**未做真机交互验证**。以下需实机确认：火箭/「俺也一样」在真实帖子上的显示条件（`canBoost` / `sharedIssueVisible` 由服务端下发，本机环境可能被 CF 拦导致拿不到）；书签取消后刷新是否真的不回来；红线画在第几楼；铃铛摇铃观感。

### 2026-09-26 第二轮修复（用户回测反馈 2 项，`compileDebugKotlin` + `testDebugUnitTest` 均 SUCCESSFUL）

| 文件 | 改动 |
|---|---|
| `data/models/TopicModels.kt` | **根治 H13**：`plainText`/`reporting`/`replyToUser`/`author`/`badgeIcons` 由类体提升为主构造参数并标 `@Transient`（与既有 `linkClicks` 同写法），使 `copy()` 不再丢本地态；`statusEmoji` 派生属性留在类体 |
| `ui/components/TopicPostItem.kt` | PostFooter 调用点补漏传的 **回调** `onAddBoost`（H1-R；上一轮只补了可见性 `canAddBoost`）；注释写明"可见性与回调是一对" |
| `ui/topic/TopicDetailViewModel.kt` | `markPostsReporting` 由 `copy().also{}` 简化为 `copy(reporting = ...)`；`carryLocalFields` 注释更新为"仅服务 DTO 重新映射" |
| `services/topic/TopicService.kt` | `mapPosts` 里 `replyToUser` 赋值处注释更新（不再是"类体可变字段"） |

### P0-3 楼层拖动 Scrubber ✅（2026-09-25 完成，v3 复核在位）

`ui/topic/TopicScrubber.kt` + 详情页接线，复用原有 `jumpToPostNumber`。
与鸿蒙 `TopicScrubber.ets` 逐行对照修掉 5 处差异（钉底条件缺 `!hasMore`、跳楼缺 `ScrollAlign.AUTO`、空闲透明度、气泡间隙 36→42dp、挂载条件缺 `topic != null`）。
**未迁移**：鸿蒙 SmartReach 智感握姿（依赖 motion 传感器 + 实验性开关），Android 整条缺失。
**验证边界**：`:app:compileDebugKotlin` 通过；**未做真机交互验证**（linux.do 的字段结构已线上实测确认，见第三轮「实际触发条件」）。

---

## 附：核对方法（可复跑）

```bash
# 服务子域对比（一眼看出少哪几块）
ls app/src/main/kotlin/com/arkdo/android/services/
ls /Users/tongtong/ArkDO/entry/src/main/ets/services/

# 能力关键词命中（0 = 缺失）
grep -rl "exoplayer\|media3" --include="*.kt" --include="*.toml" app core feature gradle
grep -rl "NotificationCompat\|NotificationManager" --include="*.kt" app/src/main
grep -rl "WindowSizeClass\|presence\|Lottery\|CurrentSite\|SiteSwitch" --include="*.kt" app core feature

# 半截链路：参数有没有往下传
grep -n "canAddBoost\|sharedIssueVisible" app/src/main/kotlin/com/arkdo/android/ui/components/TopicPostItem.kt
# 定义处(475/510) 与 TopicPostItem→PostFooter 调用点(224-283) 对比：调用点无 = 漏传

# 死代码核查：定义有、调用无
grep -rn "TopicListItem(" --include="*.kt" app core feature
grep -rln "AppRoutes" --include="*.kt" app core feature

# 路由常量悬空
grep -n "SETTINGS_DOH\|SETTINGS_PUSH\|SETTINGS_NETWORK" app/src/main/kotlin/com/arkdo/android/navigation/ArkDoNavHost.kt

# 自述未完成标记
grep -rn "尚未迁移\|后续切片\|未迁移\|暂不支持" --include="*.kt" app core feature
```
