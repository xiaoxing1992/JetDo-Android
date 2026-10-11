# 首页 UI 对齐鸿蒙版 —— 进度

对照基准：`/Users/tongtong/JetDo` 的 `views/pages/HomePage.ets`（2217 行）
与 `views/components/TopicListItem.ets` 及其徽章组件。

> 上一版文档声称「1/8 任务完成」，与代码实际状态不符，已按 2026-09-02 的实测重写。

## 已对齐

### 配色地基
- `JetDoColors.kt`：新增，逐条搬鸿蒙 `resources/{base,dark}/element/color.json` 的语义 token
  （`textBody` / `surfaceMuted` / `border` / `ldAccent` / `infoSoftBg` …），走 CompositionLocal。
- `Theme.kt`：**关掉了 `dynamicColor`**。之前默认开启，Android 12+ 上壁纸取色会覆盖
  `onSurface` / `surfaceVariant` / `outlineVariant` / `primaryContainer` 等几乎所有角色色，
  任何配色对齐都无从谈起。同时把「温暖土色调（赤陶橙）」换成鸿蒙实际使用的蓝灰体系
  —— 原注释写着"对齐 HarmonyOS"，但那套配色与鸿蒙侧没有任何关系。

### 图标基础设施
- `FaIcon.kt`：新增。`assets/fa_icons/` 下 423 个 FA SVG（与鸿蒙同一份）经 Coil 的
  SvgDecoder 解码 + ColorFilter 染色。
- 由此删除了 `TagIconRegistry.mapIconIdToMaterialIcon()` —— 那是 48 条手写的
  FA → Material Icons 近似映射，表外图标一律不显示，且多个 FA 图标会撞到同一个 Material 图标。
- `TopicBadgeUtils.kt`：新增，对齐鸿蒙同名文件的 `normalizeIconName`（剥 `fa-/fas/far/fab` 前缀）
  与 9 组同义词映射（`file-code`→`code`、`award/medal/trophy`→`trophy` …），
  以及图标/logo 取不到时回退父版块的逻辑。

### 版块徽章链路（原先整个断着）
- `HomeScreen.kt` 过去给每张卡片传 `category = null`（源码里挂着 TODO），
  所以**版块徽章在任何情况下都渲染不出来**。
- `HomeViewModel.loadSiteMetadataIfNeeded()`：新增，对齐鸿蒙 `HomePage.loadSiteMetadataIfNeeded()`。
  `CategoryIndex` / `SiteService` 在 Android 侧本就已完整移植，只是从来没有人调用。
- 卡片改为经 `CategoryIndex.find/findParent` 解析，对齐 `HomePage.ets:707-708`。

### 卡片（TopicListItem）
| 项 | 改前 | 改后（= 鸿蒙） |
|---|---|---|
| 行上下内边距 | 12 | 8 |
| 左右内边距 | 16 | 12 |
| 头像直径 / 间距 | 40 / 12 | 48 / 8 |
| 标题 | `titleMedium` 16sp，2 行截断 | 17sp / 字重 500 / 行高 23 / **不限行** |
| 标题色 | `onSurface` | `textBody` |
| 书签 · 已解决图标 | emoji `📑` `✅` | 真 SVG（`bookmark-gray` / `far-square-check-green`）15dp |
| 未读药丸 | 直角色块，11sp | 圆角 9 的胶囊，12sp，用 `InlineTextContent` 实现 |
| 未读药丸换行 | 普通空格，可能被从中间劈开 | 内部不换行空格，整体不可拆 |
| 未读小蓝点 | 10sp | 8sp |
| meta 行间距 | 8 | 3 |
| 次要文字 | `bodySmall` 12sp | 13sp |
| 分割线 | 0.5dp / `outlineVariant` | 1dp / `border` |
| 搜索命中色 | 硬编码赤陶橙 | `brand`，随明暗切换 |
| 高亮动画 | `animateDpAsState` 声明了但从未被使用 | `animateColorAsState` 600ms，淡出到同色 alpha=0 |

### 徽章（TopicMetadataBadges）
- **删掉了父版块徽章** —— 鸿蒙从不把 `parentCategory` 单独渲染成徽章，它只是图标/logo 的回退数据源。
- 版块徽章由「实心彩色药丸」改回鸿蒙的形态：透明底、无圆角、无内边距，
  前导 10dp 图元（FA 图标 → uploaded_logo → 受限锁 → 版块色圆点）+ 14sp 灰字；
  不使用 `category.textColor`。
- 标签徽章：高 20、左右内边距 4、圆角 2、`surfaceMuted` 底、图标 10dp、文字 14sp。
- 间距按鸿蒙的非等宽值（版块右 8 / 标签右 7），不再用统一的 `spacedBy`。
- 按下反馈改为整体 `opacity 0.6`，不是 Material ripple。
- 横向滚动改为**仅在真的装不下时启用**，否则会把列表的横向手势截胡。

### 头像（UserAvatar）
- 底色 `primaryContainer` → `surfaceMuted`；首字母取 `username` 而非 `displayName`；
  字号改为随尺寸缩放 `max(12, floor(size*0.4))`；颜色 `avatarFg`。
- 首字母只在无候选/加载失败时露出，不再恒在底层垫着。
- 补上 1dp `#4D00AEFF` 描边；去掉 flair 角标多出来的白色描边。
- 头像请求尺寸固定 96（对齐鸿蒙，跨尺寸共用同一份缓存）。
- flair 支持图标型（`flairUrl` 是 FA 图标 id 时染色渲染），此前一律当图片 URL 处理。

### 页面骨架（HomeScreen）
- 顶栏：三枚 42×42 图标按钮 + 汉堡（三条 18×2、间距 4）；默认条件下标题 26sp Bold；
  搜索与铃铛改用 FA 图标（铃铛 `#F5A623`）。
- 新话题提示条：`infoSoftBg` 底 + 底部 1dp 分隔线，文案改为「查看 N 个新的或更新的话题」
  （原为「有 N 条新话题」），色 `#0088CC`，动画时长接上 `HomeConstants` 的 240/180ms。
- FAB：52×52，图标 22dp，双态用 `pen-to-square` / `arrow-up`；仅在有数据时挂载。
- 列表页脚：高 54，18dp 指示器，13sp 文案「上拉加载更多 / 正在加载更多 / 没有更多了」。
- 空/加载/错误态：按鸿蒙 `LoadStateView` 的字号与文案重写
  （30dp 指示器 + 15sp 文案、「正在加载{标题}」、「加载失败，请检查网络后重试」+ 胶囊重试按钮）。

## 未对齐（仍是缺口）

按鸿蒙侧的体量排序，都是独立组件级的工作：

1. **侧边版块筛选抽屉** `CategoryDrawer.ets` —— 288 宽、跟手手势、版块树展开/折叠、
   标签页签、站点区、底部确认栏。`HomeConstants` 里的抽屉常量目前全是摆设。
2. **发帖浮层** `ComposerPage.ets`。
3. **沉浸式标题栏** —— 列表穿到标题栏下方 + 滚动渐变模糊。当前是普通 `Scaffold` topBar。
4. **增量插入动画** —— 新话题插入时的位移 + 淡入，以及行高亮倒计时状态机。
5. **版本公告弹层** `bindSheet`。
6. **SmartReach 单手模式** —— FAB 左右手换边。
7. **顶栏账号入口的已登录态** —— 需要 `HomeUiState` 带上会话用户才能渲染 `UserAvatar(34)`。
8. **标签图标的站点开关** —— 鸿蒙有 `SiteCapabilities.tagIcons` 闸门，Android 无对应物。

## 备注

- 全部改动通过 `./gradlew :app:assembleDebug`。
- 未做实机验证（本机无连接设备），像素级差异需要真机对比截图确认。
- `feature:home` 已自带版块索引:`HomeViewModel` 经 `core:data` 的 `CategoryRepository` 加载 `/site.json`(解析含 `uploaded_logo` 的 string/object 双形态),不再从 app 层旧首页 ViewModel 取 categories。
