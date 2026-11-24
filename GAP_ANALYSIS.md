# JetDo-Android 功能缺口分析

> 对标对象：鸿蒙版 JetDo（ArkTS · main@`7680996` · 28 页面 / 47 组件 / 25 服务子域）
> 本项目：`feat/migrate-vertical-slice` @ `43cc9e7`（工作区干净，零未提交改动）
> 复核日期：2026-10-12（快检：参考仓库 HEAD 仍在 `7680996` 零漂移，剩余各项证据逐一复核；上次全量重扫 2026-10-02）
> 规则：只记「现在还没做的」。做完删一条 —— 不记版本号、不记修复历史、不做已修项记账。

---

## 一、结论先行

**还剩 5 项。**

### 1.1 缺口分布

| 分类 | 数量 | 判据 |
|---|---|---|
| P0 | 1 | 用户一眼能看出没做 |
| P1 | 1 | 功能不完整，用起来别扭 |
| 半截 | 1 | 差一次接通（H1 搜索一镜到底接线）|
| 能力缺失 | 0 | 目录级对比看不出来的缺失（N1/N5/N6 已全部落地）|
| 新平台能力 | 1 | 站点微应用（mapp）宿主引擎 —— 见 §二·二（**非鸿蒙对标**，站点新增的平台能力）|
| P2 | 1 | 工程化与发布 |
| **合计** | **5** | 上表逐项相加（1+1+1+1+0+1）|
| 死代码 | 0 | 4 组已全部清理（逐符号复核后删除）|
| 微清理 | 0 | M1/M2/M3 已全部清理 |

结构基线（逐文件数出）：服务子域 **25 vs 23**（真缺 `push` 一个子域，`avatar` 已随 N1 追平两侧各 1 文件；`site` 子域缺 6 文件随**多站点取消**不再补）｜页面 **28 vs 30**（差的 3 页全是多站点页，**不迁**）｜组件 **47 vs 46 + 约 10 个内联私有 composable**（组件级真缺失 0）。

模块对齐度：首页 92% / 搜索 98%（差的 2% = H1 一镜到底接线，功能层全对齐）/ 通知 95% / 详情 85% / 发帖 92% / 回帖 86% / 表情 85% / 音视频 92% / 我的 88% / 用户主页 88% / **实验性功能 100%**（6/6，动态头像开关已补）/ **多站点：不迁移（用户 2026-10-03 决策）** / **系统推送 15%**（本地通知渠道 + 权限就位，投递链路未迁）/ 平板适配 100%（P1-13 已清：桌面姿态媒体分区 + 拍照入口落地）。

### 1.2 隐性缺口存量（0 条）

「看着有、其实没接上」的隐性缺口已全部清零（N1/N5/N6 落地、S14 接通、M2 修复）。

---

## 二、P0 · 一眼能看出没做（1 项）

| # | 要做什么 | 现在是什么样 | 关键证据 |
|---|---|---|---|
| 1 | **系统推送 + 本地通知投递链路** | `services/push/` 仍不存在（鸿蒙 12 文件：`PushCoordinator` / `PushRelayClient` / `PushRelayConfig` / `PushSubscriptionService` / `PushSubscriptionStore` / `PushRefreshGate` / `PushNotificationNavigation(EventBus)` / `PushTokenUpdateEventBus` / `HarmonyPushTokenProvider`（→ FCM 对应物）/ `PushNotificationPermissionService` / `PushTokenDebugLog`）。`PushSettingsScreen` 只做了第一块（权限 + 测试通知），推送统计与订阅管理两块缺。**已就位**：通知渠道（`SystemNotifier`）、`POST_NOTIFICATIONS` 三态处理（`NotificationPermission`）、App 活着时点通知的续接（`onNewIntent` + `DeepLinkEventBus`，`MainActivity.kt:228-245`）| `PushSettingsScreen.kt:60-64` 注释自认「那一整套尚未迁移。这里不摆空壳充数」；Android 全库 `PushRelay\|PushSubscription\|PushToken` 仅命中文档与该注释 |

---

## 二·半、半截 · 差一次接通（1 项）

| # | 要做什么 | 现在是什么样 | 关键证据 |
|---|---|---|---|
| H1 | **搜索「放大镜一镜到底」共享元素转场接线** | 基建已写好未挂载：`SharedSearchTransition.kt`（CompositionLocal + `SEARCH_ICON_SHARED_KEY` + `Modifier.searchIconSharedElement()`，注释连两端路径都写明），但三个关键符号全库零引用。鸿蒙两端已接：首页标题栏放大镜（`HomePage.ets:1276`）与搜索页搜索条放大镜（`SearchOverlay.ets:201`）共用同一 `geometryTransition` id。Android 要接三处：NavHost 包 `SharedTransitionLayout` + `rememberSharedContentState` + 下发 `LocalSharedSearchTransition`；源端 `HomeBarButton` 放大镜、目的端 `SearchScreen` 搜索条放大镜各挂 `Modifier.searchIconSharedElement()`。装饰器空值退化已设计好，未接线不影响功能，纯视觉打磨 | `core/designsystem/SharedSearchTransition.kt` 全文件；反查 `searchIconSharedElement\|SEARCH_ICON_SHARED_KEY\|LocalSharedSearchTransition` 零命中 |

---

## 二·二、新平台能力 · 站点微应用（mapp）宿主引擎（1 项）

> **不属于鸿蒙对标范畴**——鸿蒙版无此功能。LINUX DO 站点 2026-11 新增「微应用（mapp）」平台，
> 官方明确「本来这个功能是给 app 做的，想给 app 里塞个小程序引擎」，网页版先行、App 侧引擎未提供。
> 本项目要**自建原生宿主引擎**（用户 2026-11-20 决策：路线 B + 底部第 4 Tab）。

| # | 要做什么 | 现在是什么样 | 关键证据 |
|---|---|---|---|
| M1 | **微应用宿主引擎**：双层 iframe 沙箱 + 包解密 + `globalThis.miniApp` 宿主桥 + 底部第 4 Tab 广场 | **已完成 + 真机验证通过**（2026-11-20）。领域模型 `core/model/MiniAppModels.kt`、数据层 `core/data/miniapp/*`、宿主页面 `app/src/main/assets/miniapp/host.html`、原生运行时 `app/src/main/kotlin/com/jetdo/android/services/miniapp/MiniAppHostRuntime.kt`、广场页与运行页 `app/src/main/kotlin/com/jetdo/android/ui/miniapp/*`、第 4 Tab 与路由 `RootTabScreen.kt` / `JetDoNavHost.kt`。真机（vivo V2241A）实测：广场列表**分页加载全部 177 应用（5 页，page=0~4）** → 点入「清明上河图」→ DMA 解密 `package ready files=6` → 完整渲染并可交互（开场卷轴 → 虹桥渡口漫游场景）。广场列表接口已固化为 `GET /mini-app-api/apps.json?page=N` | 站点协议：广场 `GET /mini-app-api/apps.json?page=N` → `{apps, page, total=177, per_page=40, sort, query, favorite, tag, untagged}`，**0-based 分页，服务端把越界 page clamp 到末页**；hasMore 必须以 `total` 判定（末页 17 个不足 per_page，按条数判断会误判成还有下一页）。早期只读 `apps` 数组漏了分页元数据，仅显示第一页 40 个，已修复：`MiniAppListPage` 承载分页元数据 + ViewModel `loadMore()` 上拉加载 + footer + `PullToRefreshBox` 下拉刷新（重置到第一页）；启动 `GET /mini-app-api/production/<uuid>/launch` → `{release, runtime_token, runtime_context}`；`release` 含 `manifest` / `permissions` / `package_url` / `package_password` / `package_size` / `zip_sha256` / `public_key`；包为自定义 **DMA 容器**（magic `44 4D 41 01`，非 ZIP），解密/验签在论坛下发的 Worker 内完成。**Worker URL 的 hash 是站点插件全局固定值**（出处：`/apps/<id>` 页内嵌 `runtimeAssets.packageWorker`），**不是** `zip_sha256`（那是解密后 zip 的摘要，早期误用导致 404）。**Worker 用包内嵌元数据做 `RELEASE_MISMATCH` 精确校验**，故 launch 原始 `release` JSON 必须原文透传（`MiniAppLaunch.releaseRawJson`），不能按结构化字段重建。**权威依据已从逆向升级为官方开发者指南**：`github.com/linux-do/mapp-developer-guide`（`sdk/index.d.ts` = 完整 API 面，`sdk/pack.mjs` = 模块包注册契约） |

**架构决策（2026-11-20）**：

| 决策点 | 选择 | 理由 |
|---|---|---|
| 技术路线 | **B. 自建原生宿主引擎** | 用户决策。路线 A（WebView 复用网页版）仅作对照与调试参考 |
| 入口 | **底部 Tab 第 4 页** | 用户决策 |
| 包解密 | 复用论坛下发的 Worker 脚本，**不自己逆向实现加密算法** | 协议未公开；自己实现既有合规风险又会随论坛迭代碎掉。Worker 走同源加载，JetDo 只负责喂 `postMessage({buffer, release})` |
| Worker URL | 站点插件**全局固定 hash**（`PACKAGE_WORKER_PATH` 常量），非 `zip_sha256` | 真机实测：URL 里的 hash 来自 `/apps/<id>` 页内嵌 `runtimeAssets.packageWorker`，与具体包无关；早期按 `zip_sha256` 拼装必然 404。站点升插件后更新常量即可 |
| release 透传 | 把 launch 响应的**原始 release JSON 字符串**喂给 Worker | Worker 用包内嵌元数据做 `RELEASE_MISMATCH` 精确校验；按结构化字段重建会丢 `description`/`tags`/`icon_url`/manifest 的 `tags`/`assets`，导致校验失败 |
| iframe 尺寸 | 显式像素高度（`100vw/100vh` + mount 时按视口设 px） | srcdoc + sandbox 下 `height:100%` 会解析成 0，内层 `innerHeight=0` → 微应用离屏 canvas 宽高为 0，`drawImage` 抛 `InvalidStateError` 白屏 |
| 横竖屏 / 视口变化 | 外层监听 `resize`（rAF）+ `orientationchange`（100/400ms 兜底），统一走 `syncFrameSize()` 重设 iframe px；内层注入 `resize` 日志上报 | Activity 声明 `configChanges=orientation\|screenSize`，旋转**不重建**——外层 WebView 变成 800x360 但 iframe 若停在 mount 时写死的 360x800，内层一直拿到竖屏 viewport，表现为「竖屏提示要横屏、转横屏后布局仍竖屏 / canvas 尺寸错」。改 iframe px 会让内层 window 自动收到 `resize`，微应用据此重排。真机验证双向切换 `360x800 ↔ 800x360` 内外层同步 |
| 宿主桥范围 | 按官方 SDK 契约实现核心面 + 其余显式 `API_UNSUPPORTED` | 官方 `sdk/index.d.ts` 是权威契约，形状猜错微应用直接跑不起来；未实现的能力显式报错让应用降级，不假装成功 |
| 宿主页面加载方式 | **`loadDataWithBaseURL(base=https://linux.do/, html=宿主页面)`** | 解密 Worker 必须与宿主页面同源（Worker 是论坛下发的 https URL）。走「引导页 + document.write」会被论坛页面 CSP 拦掉内联脚本、也会被论坛自身脚本干扰；`loadDataWithBaseURL` 既拿到正确 origin 又不带任何响应头策略。页面加载后自报 origin，原生侧校验，不匹配则退化到引导页路径并明确报错 |
| 权限模型 | 以 `release.permissions`（审核结果）为准，**同步**下发 | 官方 `permissions.list()` 返回数组、`has()` 返回布尔，**不是** Promise。宿主必须在注入 SDK 前就知道批准结果，因此权限快照随启动信息一次性下发 |
| 平台 JWT | 用启动接口下发的 `runtime_token` 承载 | 官方：`auth:"platform"` 端点从 `Authorization: Bearer` 读 JWT，WS 从 `mini_app_token` query 读；JWT 由平台签发、有效期 15 分钟 |

**官方契约要点（与逆向结论的差异，已全部对齐）**：

| 项 | 官方契约 | 早期实现的问题 | 现状 |
|---|---|---|---|
| `permissions.list/has` | **同步** | 返回 Promise → 应用拿到的是 Promise，`has()` 恒真 | 已改为同步，权限随启动信息下发 |
| `miniApp.modules.__registerBundle` | 必须**不可写、不可配置、且为函数**；`miniApp` 必须冻结 | 缺失 → 官方模块包直接抛 `SDK_UNAVAILABLE` | 已按 `sdk/pack.mjs` 的校验逻辑实现 |
| `http.request` | 返回 `{status, headers, body}`；`body` 默认 JSON、可为 text/bytes | 未支持 bytes/流 | 已支持 `request/stream/events` |
| `webSocket.connect` | 返回 `MiniAppSocket`：`id/readyState/ready/send/sendText/close/on` | 完全未实现 | 已实现，外层代理真 WS，消息按 `textMode` 自动 JSON 解析 |
| `network.*` | `reconnectSocket/reconnectEvents/uploadChunks` | 缺失 | 已实现（基于 http/webSocket 组合） |
| manifest `endpoints` | `query` 是**允许的动态 query 键白名单**；`auth` 三态；`headers` 常量头 | 未校验 query 键 → 应用可往开发者服务塞任意参数 | 已做白名单校验，未声明键一律 `PERMISSION_DENIED` |
| 其它 | `media/location/usb/bluetooth` 为设备能力；`video.*` 含直播时间线 | 形状不符 | 未接通者显式 `API_UNSUPPORTED` |

**协议层验证方式（2026-11-20）**：在真实 Chromium（headless + CDP）里加载真实 `host.html`，仅把原生侧换成夹具，跑 **26 项断言全绿**，覆盖冻结属性、权限同步语义、`__registerBundle` 注册、storage 往返、HTTP 端点白名单、WS 消息解析、权限拒绝与不支持能力的显式报错。

---

## 三、P1 · 功能不完整（1 项）

| # | 要做什么 | 鸿蒙版 | 现在是什么样 | 证据 |
|---|---|---|---|---|
| 12 | **版本公告源未定** | `announce/VersionAnnouncement.ets` | 代码完整，但 `ANNOUNCE_PROMPT_ENABLED = false` → **不扫描、不弹层**。当前源是鸿蒙版发布日志帖（`2590051` / `joeyyin`），`[v900008+]` 体系与 Android `versionCode` 对不上，开着会每次冷启动误弹鸿蒙公告。**链路全部就绪，只差决定 Android 自己的公告帖与账号** | `services/announce/VersionAnnouncement.kt:15/17/22`；`RootTabScreen.kt:160`（`if (!ANNOUNCE_PROMPT_ENABLED) return@LaunchedEffect`）|
---

## 四、半截 · 差一次接通（0 项，已清）

~~S14 过盾快捷入口的用户开关缺失~~ **已完成**（`services/challenge/ChallengeShortcutPrefs`（默认关，复用预留键 `PREFS_CHALLENGE_SHORTCUT`）+ 网络设置页第二张卡开关行；入口可见 = 开关 ∧ bus 被拦，两条件在 `TopicDetailViewModel.challengeBlocked` 的 combine 里 AND）。

---

## 五、P2 · 工程化与发布（1 项）

| # | 问题 | 证据 |
|---|---|---|
| 20 | release 无签名配置 | `app/build.gradle.kts` 全文无 `signingConfigs`，所有 `*.kts` 零命中 |

>
> ~~21 无 androidTest~~ **已完成（2026-10-03）**：骨架 3 类 7 用例（设备冒烟=包名+AppContextHolder 真机取串、FaIconSet 资产目录守卫、Compose 测试基建），`connectedDebugAndroidTest` 模拟器 7/7 通过。
>
> ~~27 无隐私政策 / 用户协议 / 商店素材~~ **已完成（2026-10-03）**：`docs/publish/` 三份——privacy-policy.md（按实际行为如实编写：权限仅 INTERNET/ACCESS_NETWORK_STATE/POST_NOTIFICATIONS，数据只发 linux.do 与 cdn.ldstatic.com，会话走系统 CookieManager，无任何第三方 SDK）、user-agreement.md、store-listing.md（简介/完整介绍/权限说明表/截图清单/图标规格/上架 checklist）。⚠️ 两份文档的联系方式占位待补，隐私政策需发布为网页链接后商店才可引用。
> **已做对、不是缺口**：R8 已开（`isMinifyEnabled = true` + `isShrinkResources`，`app/build.gradle.kts:45-46`）；明文 HTTP 已关（`networkSecurityConfig`，默认禁 + 仅 localhost 白名单）；CI 已建（`.github/workflows/ci.yml`，push/PR 跑 assembleDebug + 全模块单测）；`core:datastore` 空模块与 `core:common` 空壳已摘除（`RelativeTime` 落位 core:model，8 模块收成 6+feature3）。
>
> ~~28 多语言 strings 抽取与翻译~~ **已完成（2026-10-03）**：679 条 UI 文案抽取进资源（app 607 / feature:home 39 / feature:search 39 / core:designsystem 8），values-en 全量英文对照 + `locales_config` 加 en-US。替换分三路：组合期 `stringResource`、服务层 object 单例走 `AppContextHolder`（core:data，Application.onCreate 初始化）、VM 消息同 holder；36 处插值模板转位置格式参数。刻意不本地化的边界（勿再报）：注释 113 处、Timber/Log 日志 31 处、逻辑位字面量 5 处（`CategoryIndex`/`HomeDrawer` 的 `未分类` 比较、`HolographicTitle` 标题匹配集合、`DebugLogStore.RESTORE_SEPARATOR` 持久化格式标记、`DebugLogScreen` 的 `contains("未捕获异常")`）、异常消息（DohService UnknownHostException）、core:data/core:model 内部错误文案（纯 JVM 语义层）。e2e 已验：en-US 冷启动首页/设置页全英文（含相对时间、公告条、底栏），zh-CN 切回无损；全库 541 单测 0 失败。

---

## 六、死代码与死资源（0 组，已全部清理）

四组死代码已全部删除（逐符号复核零活引用后编译 + 全量单测验证）：notification 死链路四件套、6 个死 Prefs 常量、2 个悬空路由常量（`Routes.CF_CHALLENGE`/`Routes.LOGIN`，页面未动）、`RichBlock.mediaType`/`unsupportedKind` 两字段（鸿蒙侧零命中，系 Android 自加字段；`unsupportedKind` 在 `hasBlockContent` 的半句判定冗余，UNSUPPORTED 块 text 有兜底恒非空）。连同更早删除的 `TopicListItem.kt`（378 行）与 `AppRoutes.kt`（163 行）。

---

## 七、微清理（0 条，已全部清理）

- ~~M1~~ Manifest 两条存储权限声明删除（落盘走应用专属目录无需权限）。
- ~~M2~~ Boost 本地刷新静默成功：实况是 `createBoost` 零调用方（唯一活链路 `submitBoost` 发请求前已捕获 target，无此问题）→ 死代码删除；`deleteBoost` 把 `boostDeleteLoadingId`/`boostDeleteTargetId` 复位与成功提示提到 `findById?.let` 外（楼不在窗口时只跳过气泡摘除，状态必复位；对齐鸿蒙 `if (target)` 只包数组替换的写法）。
- ~~M3~~ 设置页「论坛」「我的站点」两个死入口 + `toastNotMigrated`/`NOT_MIGRATED`，随多站点取消。

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
| **多站点 / 论坛切换** | **用户明确取消（2026-10-03）**：不做 `CurrentSite`/站点管理 3 页/`BASE_URL` 可变；`services/site/` 只保留现状 6 文件，`EmojiCatalogService.onSiteChanged()` 永久零调用，`UploadService.mediaUploadEnabled()` 保持硬写 `true`。**不要再报缺口** | 用户原话「就不要多站点功能了」；单站点定位（仅 linux.do） |

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
9. **视频播放走 TextureView**——`jetdo_player_view.xml` + `VideoPlayer.kt:158`，避开 SurfaceView 与 Compose 合成的黑帧交替（实测黑帧率 46% → 0%）
10. **上传前视频转码** `VideoTranscoder`（media3-transformer）——鸿蒙上传链路无对应转码实现
11. **LdcCredit 拆分更细**——credit 子域 6 文件 vs 鸿蒙 4
12. **composer 拍照入口** `ComposerCamera`（TakePicture + 既有 FileProvider，成图走图片上传通道）——鸿蒙 `camera` 0 命中，选图只走 photoAccessHelper

> 载体差异、不算独有：`FabGlassStore`（鸿蒙有 `FabGlassPrefs.ets`）、`TopicListStyleSettingsScreen`（鸿蒙有 `TopicListStylePrefs.ets`）。

---

## 十、建议实施顺序

**第 0 步 · 清台面**
1. ~~死代码组1/2/3 + 微清理 M1~~ **已完成**（notification 四件套 / 6 个死常量 / 2 个悬空路由常量 / Manifest 存储权限，编译 + 全量单测验证）
2. ~~组4（`mediaType`/`unsupportedKind`）~~ **已完成**（鸿蒙零命中，两字段连解析一并删）
3. ~~重写或删除 `TODO.md` / `MIGRATION.md` / 修 `README.md` 三处失真（P2-31）~~ **已完成**（TODO 收敛为发布准备清单、MIGRATION 重写为现状+不迁清单、README 修 6 处失真；三份均指向 GAP_ANALYSIS 为唯一权威）
4. ~~微清理 M2（Boost 本地刷新静默成功）~~ **已完成**（死 `createBoost` 删除 + `deleteBoost` 状态复位提出 `let`，对齐鸿蒙语义）

**第 1 步 · 差一次接通（改动小）**
4. ~~N6 详情已读态回写首页~~ **已完成**（`TopicReadStateSource` 单例：详情页 `onDetailDetach` 结算、首页订阅更新对应行，不搬 bus；feature/home 单测覆盖）
5. ~~S14 过盾快捷入口开关~~ **已完成**（`ChallengeShortcutPrefs` 默认关 + 网络设置页开关项，入口/订阅/reset 原已就位，combine AND 门控）

**第 2 步 · 用户一眼能看到**
6. **版本公告定源**（P1-12）：决定 Android 自己的公告帖与账号，`ANNOUNCE_PROMPT_ENABLED` 置 `true`，链路已就绪

**第 3 步 · 横向铺开**
8. **系统推送全链路**（P0-1）：`services/push/` 移植（鸿蒙 12 文件）+ FCM 对应物替代 `HarmonyPushTokenProvider` + Relay 同意流程；**前置已全部就位**（渠道/权限/深链），推送到达直接调 `SystemNotifier` 即可；`PushSettingsScreen` 补推送统计与订阅管理两块
9. ~~动态头像（N1）~~ **已完成**（`services/avatar/AnimatedAvatarPrefs`（默认开，复用死键 `PREFS_ANIMATED_AVATAR`）+ `LocalAnimatedAvatarEnabled`/`rememberAvatarImageModel` 首帧解码 + 实验页第 6 开关，6/6）
10. ~~图片失效黑名单（N5）~~ **已完成**（`BlockedImageHosts` + Coil `BlockedImageHostInterceptor` 挂全局 ImageLoader：网络层失败记证据、2 张不同图判域名走不通、命中短路；鸿蒙「桥也取不到就撤销」对冲无取图桥，不搬）

**第 4 步 · 大屏深化（P1-13 已全清）**
11. ~~桌面姿态媒体上下分区~~ **已完成**（`TabletopMedia` 几何 + `VideoPlayer` 分区接线，单测覆盖；真机水平折痕待复验）；~~相机预览方向~~ **已消解**（拍照入口走系统相机 TakePicture，预览由系统自理，App 内无预览可适配）

**第 5 步 · 发布准备**
12. ~~签名配置 + androidTest 骨架（P2-20/21）~~ **P2-21 已完成**（2026-10-03）；P2-20 签名待密钥；CI 已建（P2-22 完成）
13. ~~崩溃上报（P2-26）~~ **已完成（2026-10-03，腾讯 Bugly 4.1.9.3）**：crashreport 走 mavenCentral，`BuildConfig.BUGLY_APP_ID`（现为官方演示值 900001573，正式启用前到 bugly.qq.com 创建产品替换）；init 装在 DebugLogStore/FileLoggerDaemon 崩溃链之后（后装在外层，两端现场都保住）；混淆 keep 规则已加；模拟器实测 v4.1.9.3 初始化完成 + native SO 加载 + Java/Native/ANR 监视注册。**首启隐私同意弹窗已补（2026-10-04）**：`PrivacyConsentStore`（POLICY_VERSION 落盘）+ MainActivity 模态弹窗 + `initCrashReportIfAllowed()` 幂等闸门——未同意前 Bugly 不装，e2e 六步全闭环（弹窗→拒绝→二次确认退出→重启重现→同意初始化→重启不再弹）。隐私政策已同步修订（Bugly 采集范围如实声明）；~~隐私政策与商店素材（P2-27）~~ **已完成**（2026-10-03，docs/publish/ 三份，联系方式待补）；~~多语言 strings 抽取与翻译（P2-28）~~ **已完成**（679 条抽取 + values-en 全量翻译 + AppContextHolder 三路替换，e2e 双语验证）；~~`core:datastore` 摘除 + `core:common` 处置（P2-23/24）~~ **已完成**（RelativeTime 落位 core:model，两模块删除，模块数 9→7）

---

## 附：核查方法（可复跑）

```bash
# ── 结构对比 ──
ls entry/src/main/ets/services/                    # 鸿蒙 25 子域
ls app/src/main/kotlin/com/jetdo/android/services/ # Android 20 + core/data 2 + feature/home 1
ls ~/ArkDO/entry/src/main/ets/views/pages/ | wc -l   # 28 页

# ── 判据 D：路由常量 vs 注册 ──
rg -n "composable\(Routes|Routes\.CF_CHALLENGE|Routes\.LOGIN" -g '*.kt'   # 只命中定义处 = 悬空

# ── 判据 A：声明 → 反查调用 ──
grep -nE "uses-permission|scheme=" app/src/main/AndroidManifest.xml
rg -n "checkSelfPermission" -g '*.kt'             # 存储权限声明已删（M1 完成），应零命中

# ── 判据 B：解析了但没消费 ──
rg -n "mediaType|unsupportedKind" -g '*.kt' app/src/main/kotlin/com/jetdo/android/ui   # 已全删（组4 完成），应 0 命中
rg -n "quotePostNumber|quoteTopicId|quoteIsReply" -g '*.kt'  # 应命中 PostRichContent（已消费）

# ── 判据 C：零调用方 ──
rg -ln "NotificationService|NotificationApi|RawNotification" -g '*.kt'    # 已清空，只剩 core/data 私有副本与注释
rg -n "DRAFTS_REFRESH_TICK_KEY|APP_IS_WIDE_STORAGE_KEY" -g '*.kt'

# ── 判据 E：有发无收 ──
rg -rn "EventBus" -g '*.kt' --files-with-matches  # 逐条对账发送方/订阅方
rg -n "onSiteChanged" -g '*.kt'                   # 永久只命中定义处（多站点已取消，勿当缺口）

# ── 工程化 ──
grep -n "signingConfig" app/build.gradle.kts; ls -a | grep github; ls app/src/main/res/values/
```

> **工具提醒**：macOS BSD `grep` 对 `\|` 行为不可靠，一律 `grep -E` 或 `rg`；长 `find | wc` 组合会被沙箱杀（exit 137），统计用 Glob/Grep 工具；grep 命中里混注释是常态，命中行以 `//` 或 `*` 开头的不算引用，必须逐行看。
