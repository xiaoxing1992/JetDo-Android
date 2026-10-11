# 全形态自适应适配路线（折叠 / 三折叠 / 小平板 / 大平板）

> 依据：Google 官方 Adaptive Apps 指南、大屏应用质量指南（三层级）、Android 16/17 行为变更、Compose Material 3 Adaptive 与 Jetpack WindowManager 参考文档。调研日期 2026-10-01。
>
> 本文回答两件事：**官方是怎么做的**，以及**我方要做到什么程度、怎么验收**。
>
> **落地进度**：路线第 1 ~ 4 步（高度闸门 / 折叠感知接入 / `configChanges` / 5 档分组）以及附带项
> （宽屏转场、铰链对齐、展开态尺寸兜底）**均已完成并在真实 AVD 上实测**；验收手段与模拟器行为差异见 §5。

---

## 一、官方权威口径

### 1.1 窗口尺寸类别（Window Size Classes）—— 已经是 5 档宽度 + 3 档高度

这是最容易过时的一条：老教程里 WSC 只有 compact/medium/expanded 三档。**当前官方定义已扩到 5 档宽度**，多出来的「大 / 超大」是为桌面与连接显示器准备的。

**宽度类别**（决定布局形态的主要维度）：

| 类别 | 断点 | 官方给出的设备代表 |
|---|---|---|
| 紧凑 compact | 宽度 < 600dp | 99.96% 的纵向手机 |
| 中等 medium | 600 ≤ 宽 < 840dp | 93.73% 的纵向平板、**大多数纵向大折叠内屏** |
| 扩展 expanded | 840 ≤ 宽 < 1200dp | 97.22% 的横向平板、大多数横向大折叠内屏 |
| 大 large | 1200 ≤ 宽 < 1600dp | 大型平板显示屏 |
| 超大 extra large | 宽 ≥ 1600dp | 桌面显示器 |

**高度类别**（做闸门用，见 §1.5）：

| 类别 | 断点 | 设备代表 |
|---|---|---|
| 紧凑 compact | 高 < 480dp | 99.78% 的横向手机 |
| 中等 medium | 480 ≤ 高 < 900dp | 96.56% 的横向平板、97.59% 的纵向手机 |
| 扩展 expanded | 高 ≥ 900dp | 94.25% 的纵向平板 |

两条**官方明确写着、但极易被忽略**的前提：

1. **WSC 描述的是「应用窗口」而不是「设备」。** 同一个物理设备在不同窗口形态下可以是不同类别 —— 分屏、桌面窗口化、折叠/展开都会改变类别。所以**不能用 `isTablet` 这类设备逻辑替代 WSC**，也不能读屏幕宽度当窗口宽度（这正是我方 `BoxWithConstraints` 那条设计的由来）。
2. **WSC 在运行时是动态的。** 折叠、展开、旋转、拖拽窗口都会让类别瞬时跳变，UI 必须能随时重排。

取法（新代码用 V2，它额外带回 `windowPosture`，一个调用同时拿到尺寸与姿态）：

```kotlin
val adaptiveInfo = currentWindowAdaptiveInfoV2()
val wsc = adaptiveInfo.windowSizeClass
when {
    wsc.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND) -> // Expanded
    wsc.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)   -> // Medium
    else -> // Compact
}
```

artifact：`androidx.compose.material3.adaptive:adaptive`（`currentWindowAdaptiveInfoV2` / `collectFoldingFeaturesAsState` / `Posture` / `HingeInfo` 都在这里）。

### 1.2 Android 16 / 17：限制方向与尺寸的能力已被平台收走

| 项 | 结论 |
|---|---|
| 生效范围 | sw ≥ 600dp 的屏幕（大折叠内屏、平板、桌面窗口、Chromebook） |
| 被忽略的清单属性 | `screenOrientation`、`resizeableActivity`、`minAspectRatio`、`maxAspectRatio` |
| 被忽略的运行时 API | `setRequestedOrientation()` / `getRequestedOrientation()`（含 portrait/landscape/sensor*/user*/reverse* 全系） |
| 例外 | 游戏（按 `android:appCategory` 判定）、sw < 600dp 的屏幕、用户在系统设置里显式选择沿用应用默认行为 |
| 过渡退路 | `PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY` 清单属性可临时 opt-out |
| **退路寿命** | **API 37 起 opt-out 能力被移除**，限制永远被忽略 |
| 复制条件测试 | app compat framework 开 `UNIVERSAL_RESIZABLE_BY_DEFAULT` |

**对本项目直接成立**：`compileSdk = 37` / `targetSdk = 37`，也就是说——

- opt-out 那条退路在我方**已经不存在**；
- 平板上一旦旋转、分屏、自由窗口缩放，Activity 被重建，**必须靠状态保存扛住**，不能靠锁方向躲；
- 任何「假设竖屏」的布局假设（拉伸、越界动画、按宽高比算尺寸）在平板上都会暴露。

对应官方点名的三类常见翻车：**拉伸的 UI 元素**（给组件加最大宽度）、**相机/取景器方向错乱**（用窗口 metrics 而非屏幕尺寸）、**窗口尺寸变化时丢状态**（`Save UI states`）。

### 1.3 折叠感知：FoldingFeature 的四个属性

`WindowLayoutInfo.displayFeatures` 里筛 `FoldingFeature`，可读：

| 属性 | 取值 | 用途 |
|---|---|---|
| `state` | `FLAT` / `HALF_OPENED` | 是否半折 |
| `orientation` | `HORIZONTAL` / `VERTICAL` | 折痕横竖 |
| `occlusionType` | `NONE` / `FULL` | 折痕是否**遮挡**内容（遮挡时必须避让） |
| `isSeparating` | true / false | 是否把显示区切成两块 |
| `bounds` | Rect | 折痕矩形，用来算避让 |

两条官方注解值得抄进注释：

- `HALF_OPENED` **必然** `isSeparating == true`；双屏设备跨屏时也恒为 true。
- **铰链角度不是 API。** 官方明确不给角度值（各设备上报范围与精度不一），任何基于精确角度的动画/逻辑都得按机型调 —— 所以别在此基础上做花活。

**姿态判定**（官方给的原始写法）：

```kotlin
// 桌面姿态：半折 + 折痕水平
fun isTableTopPosture(f: FoldingFeature?) =
    f?.state == FoldingFeature.State.HALF_OPENED &&
    f.orientation == FoldingFeature.Orientation.HORIZONTAL

// 书本姿态：半折 + 折痕垂直
fun isBookPosture(f: FoldingFeature?) =
    f?.state == FoldingFeature.State.HALF_OPENED &&
    f.orientation == FoldingFeature.Orientation.VERTICAL
```

**先问设备支不支持，再谈适配**（Android 15+，需 WindowManager Extensions v6）：

```kotlin
if (WindowSdkExtensions.getInstance().extensionVersion >= 6) {
    val postures = WindowInfoTracker.getOrCreate(context).supportedPostures
    if (postures.contains(SupportedPosture.TABLETOP)) { /* 再走桌面姿态布局 */ }
}
```

**新 API 更省事**：`Posture` + `HingeInfo`（`androidx.compose.material3.adaptive:adaptive`）。

```
Posture
├─ isTabletop                     // 直接给你结论
├─ hingeList: List<HingeInfo>     // 三折叠有 2 个铰链，不是 1 个
│   ├─ bounds        // 铰链矩形
│   ├─ isOccluding   // 是否遮挡
│   ├─ isFlat        // 是否完全展开
│   ├─ isSeparating  // 是否分离
│   └─ isVertical    // 铰链方向
└─ 扩展：all / occluding / separating × Horizontal / Vertical HingeBounds
```

⚠️ **`hingeList` 是列表而不是单值**，这是三折叠带来的结构性变化：双折 1 个铰链，三折 2 个。任何「取第一个铰链」的写法在三折叠上都是错的。

### 1.4 三折叠专项（官方单独开了一篇文档）

官方把「三折叠 + 横向折叠」合并成一篇，因为它们的坑同源。**核心结论：**

> **三折叠屏不支持桌面（tabletop）姿态，也用不到 `HALF_OPENED` 状态。** 它靠「完全展开时提供更大的屏幕」来提供独特体验。

这条推翻了「所有折叠屏都要接 `HALF_OPENED`」的惯性认知 —— 三折叠的形态阶梯是 **单屏 → 双屏 → 三屏**（尺寸阶梯），不是 **折叠 ↔ 半折 ↔ 摊平**（姿态阶梯）。

其余三条硬骨头：

**① 自然方向（rotation_0）陷阱**
横向折叠设备（Pixel Fold）与三折叠（Samsung TriFold）**展开后 rotation_0 = 横向**，而外屏是竖向。同一个 App 在折叠/展开切换时会经历「自然方向翻转」，任何 `rotation_0 == 竖屏` 的假设都会在这里炸（表现为布局错乱、闪烁循环）。

**正解**：判方向永远基于**应用实际占用的窗口**，不看物理旋转：

- `Configuration.orientation`，或
- `WindowMetrics#getBounds()` 比宽高。

**② 密度（density）配置变更**
三折叠与横向折叠的**外屏与内屏像素密度不同**。density 变化默认会重启 Activity → 丢状态。官方给的解法是清单声明自行处理 + 在 `onConfigurationChanged()` 里重新计算：

```xml
<activity android:configChanges="density|screenSize|smallestScreenSize|screenLayout|orientation|fontScale|keyboard|keyboardHidden" ... />
```

回调里要更新的东西：特定密度的位图/可绘制对象、dp→px 换算出的尺寸与边距、sp 文本尺寸、自定义 View/Canvas 的绘制像素值。

**③ 相机预览**
折叠设备能在**设备旋转不变**的前提下改变宽高比（展开就是一次宽高比突变）。假设「宽高比 ↔ 设备旋转」或「传感器方向 ↔ 竖屏」的代码会得到拉伸/侧向/裁剪/旋转的预览。

官方给的优先级：**CameraX `PreviewView`（首选，自动处理） > `CameraViewfinder`（Camera2 老代码兼容，API 21+） > 手动算 Camera2**。手动那条必须记住：**取景器尺寸要用窗口 metrics，不能用屏幕尺寸**（App 可能只占屏幕一部分）。

**④ 传感器重映射**（游戏/XR 类才需要）：按 `Display.getRotation()` 重映射坐标轴，旋转矢量用 `SensorManager.remapCoordinateSystem()`。

**⑤ 锁自然方向**：`android:screenOrientation="nosensor"` 可强制跟随设备自然方向（外屏竖、内屏横）。官方**明说不推荐** —— 违背自适应指南。且注意 API 37 下它在 sw≥600dp 屏幕上本来就被忽略。

### 1.5 高度闸门：官方专门提醒的「中等宽 + 紧凑高」

官方在 WSC 文档里有一句独立注意：

> 对于手机或横向折叠设备等场景，**窗口宽度通常为中等，但窗口高度为紧凑，此时双窗格布局并不实用**。

这是判断「该不该双栏」时**仅次于宽度**的第二根支柱。我方已按官方值（480dp）落成闸门，见 §3.1。

### 1.6 三种规范布局与三层级质量

官方把大屏布局收敛到三种 canonical layout：

| 规范布局 | 形态 | 我方对应 |
|---|---|---|
| **list-detail** | 列表 + 详情并排 | 平板双栏（P1-9）就是这一种 |
| **feed** | 等宽多列网格 | 话题列表多列（未做） |
| **supporting pane** | 主内容 + 辅助面板 | 未做 |

质量分三层，**官方建议先做 Tier 2，再挑 Tier 1**：

| 层级 | 名称 | 要求摘要 |
|---|---|---|
| Tier 3 | adaptive ready（就绪） | 填满窗口不出现黑边、handler 配置变更并保住状态、多窗口可用、外接键鼠基础支持 |
| **Tier 2** | adaptive optimized（优化） | **响应式/自适应布局 + 侧边导航栏/抽屉 + 网格与列布局 + 双栏 + 表单类组件不成全宽 + 触控目标 ≥48dp + 键盘/鼠标/触控板增强** |
| Tier 1 | adaptive differentiated（差异化） | 折叠姿态（桌面/书本/双屏）、拖放、多实例、完整快捷键、悬停态、自定义光标、触控笔压感/倾斜 |

Tier 2 的 do / don't（直接抄）：

- ✅ 双栏布局、网格与多列、用侧边导航栏替换底部导航栏、多 Activity 应用用 activity embedding
- ❌ 把文本框/按钮/对话框拉伸成全宽、拉伸或裁剪图片、让面板或 sheet 占满全宽

---

## 二、四种目标形态的适配矩阵

官方给出的测试机型基线（含 dp 尺寸，直接当验收用例）：

| 官方基线 | 尺寸 | 宽度类别 | 高度类别 |
|---|---|---|---|
| 可折叠设备 | 841 × 701 dp | expanded | medium |
| 8 英寸平板 | 1024 × 640 dp | expanded | medium |
| 10.5 英寸平板 | 1280 × 800 dp | large | medium |
| 13 英寸 Chromebook | 1600 × 900 dp | extra large | expanded |

叠上本项目代码注释里已存档的真实机型数据：

| 形态 | 典型窗口（dp） | 宽度类别 | 期望布局 |
|---|---|---|---|
| 手机竖屏 | 360 ~ 480 × 800+ | compact | 单栏 |
| **手机横屏** | **997 × 448**（Pixel 8 Pro） | expanded | **单栏**（高度 compact，见 §1.5） |
| 竖折叠内屏·竖持 | 741 × 832 | medium/expanded | **双栏**（近方形） |
| 阔折叠展开·横持 | 939 × 664 | expanded | 双栏 |
| 三折叠·单屏 | ≈ 360 级 | compact | 单栏 |
| 三折叠·双屏 | 待实测 | medium | 待定 |
| 三折叠·三屏 | 待实测 | expanded/large | 双栏（或 2 铰链三栏） |
| 小平板 8" 横屏 | 1024 × 640 | expanded | 双栏 |
| 大平板 10.5" 横屏 | 1280 × 800 | large | 双栏 + 左栏可加宽 |
| 大平板/桌面竖屏 | 800 × 1280 | medium | 单栏 |
| 左右分屏（半宽） | ≈ 640 | medium | 单栏 |

**三折叠的 dp 需要实测补齐** —— 官方文档只给了「不支持 tabletop」这类结论，没给具体尺寸；各厂形态差异也大（三屏展开后的可用宽高、外屏与内屏的密度落差）。按项目「不做无法验证的空壳」的规矩，这一格应该等真机/模拟器量完再填。

---

## 三、我方现状对照

### 3.1 已达成

| 项 | 状态 |
|---|---|
| 读窗口实际约束而非屏幕尺寸 | ✅ `WideLayoutHost` 用 `BoxWithConstraints` |
| 左栏作为 NavHost 兄弟常驻 | ✅ 所有 `navigate()` 调用点零改动 |
| NavHost 单一调用点 | ✅ 宽窄切换不重建导航栈/WebView/滚动位置 |
| 双栏左栏宽度策略（夹取 [320,420]，展开态豁免上限压在折痕） | ✅ `WindowBreakpoints` 纯函数 |
| 断点值与国际口径对齐 | ✅ 840 = expanded 下界，600 = medium 下界 |
| 平板横屏双栏 | ✅ 已用真实 AVD（1280×800dp）复验 |
| 自由窗口缩放判定 | ✅ 已验证读的是窗口约束 |
| 物理旋转往返保状态 | ✅ 已验证返回横屏后右栏停在原详情 |
| **高度闸门**（原缺口 A） | ✅ `h < 480dp` 一律单栏；997×448dp 实测落回单栏，平板 640/800dp 不受影响 |
| **折叠感知接入**（原缺口 B） | ✅ `collectFoldingFeaturesAsState()` 喂进 `WideLayoutHost` 内部，收敛为「左栏上限豁免 + 铰链对齐 + 书本姿态判定」三处用途；折叠 AVD 实测 `HALF_OPENED + VERTICAL` → `FoldHingeSpan(437.33, 437.71)` |
| **`configChanges`**（原缺口 D） | ✅ 已声明密度那组；折叠/旋转切换实测 `onCreate` 恒为 1 次，Activity 不重建 |
| 双栏核心交互 | ✅ 折叠 AVD 实测：点左栏帖子 → 右栏出详情，**左栏原地保留**（滚动位置不丢） |
| **宽屏转场关闭** | ✅ 双栏下右栏内容直接替换（`if (isWide) EnterTransition.None`）；窄屏保持横向 push/pop，左栏内部 tab 切换仍是淡入。**平板 AVD 横竖屏对照实测**（§5.3） |
| **展开态尺寸兜底**（原缺口 H） | ✅ 判定改为纯尺寸驱动，`isWideLayout` 里已无折叠参数；折叠 AVD 完全展开（系统报空特征）实测 **838×945dp → 双栏**（§5.2） |
| **铰链对齐 + 留白**（原缺口 C） | ✅ 有竖直分隔铰链时按铰链位置分栏，并把铰链自身占宽留成空隙；折叠 AVD 实测左栏右缘 = 1148、右栏起点 = 1149，**正好框住铰链**（§5.2） |
| **5 档 WSC 分组**（原缺口 E） | ✅ `WindowWidthSizeClass` / `WindowHeightSizeClass` 落全；左栏宽度上限按档给（expanded 420 / large 480 / extra-large 560）；平板 AVD 实测 1280dp 档左栏 420 → **480dp** |
| **书本姿态双页阅读**（Tier 1 的一半） | ✅ 开关控制、默认关。折叠 AVD 实测：半折后左页「摘要 + 一楼」、右页「回复流」，分栏线压在折痕（1148px）上；同一姿态下关掉开关即完全复原（§5.4） |

### 3.2 缺口

> 原 **A（高度闸门）/ B（折叠感知）/ D（configChanges）** 三项已解决；后续又把 **H（展开态尺寸兜底）/ C（铰链避让）/ E（5 档 WSC）** 一并做掉（见 §3.1 与 §四）。
> 下表仅列**尚未解决**项。

| # | 缺口 | 官方依据 | 影响 |
|---|---|---|---|
| **F** | ~~Tier 1 姿态只做了书本姿态这一半~~ **桌面姿态已落成（见 §第四步桌面姿态小节）** | §1.6 | 书本姿态 → 「双页阅读」（开关控制、默认关，实测见 §5.4）；**桌面姿态 → 视频卡上下分区**（铰链划过卡片时画面在上、控制条在下，判定与几何见 `FoldPosture.isTabletopPosture` / `TabletopMedia.splitFraction`）。**未实测真机**：模拟器 `emu fold` 只产竖直折痕，桌面姿态需要水平折痕的真机（或后续镜像）复验 |
| **G** | ~~相机预览方向~~ **已消解（2026-10-02）** | §1.4 | composer 加了「拍照」入口，但走**系统相机**（`TakePicture` + 既有 FileProvider，见 `ComposerCamera`）——预览由相机 App 渲染，它自己对折叠屏方向/姿态做适配，**App 内没有预览可谈**，这个适配点随选型消失。若未来改自建 CameraX 取景器，再按 §1.4 的窗口 metrics 方案做 |

**已解决三项的落地要点**（细节见代码注释，这里只留判据）：

- **H 展开态兜底**：`isWideLayout` 现在**不含折叠参数**，判定完全由「宽 ≥ 840 且横着」或「宽 ≥ 600 且近方形」两条尺寸路径给出。原因是实测发现系统在完全展开时可能报空特征列表（§5.2），把分栏挂在折叠信号上会让 838×945 的内屏退化成单栏。
- **C 铰链对齐**：`FoldPosture.verticalSplitHinge()` 挑出「竖直 + 分隔 + 带位置」的铰链，`WindowBreakpoints.splitGeometry()` 把分栏线压到铰链左缘，并把铰链自身宽度作为留白返回，由 `JetDoNavHost` 插 `Spacer` 落实。位置不合适（左栏 < 320 或右栏 < 360）时退回对半。**三折叠只取最靠左那条铰链**，第二条目前无第三栏承接。
- **E 5 档分组**：档位判定与左栏上限挂钩，`widthSizeClassOf` 与 `WIDE_THRESHOLD_DP` 共用 840 这个断点，避免出现「判成双栏但档位还是 MEDIUM」。

---

## 四、落地路线（按依赖顺序）

> **进度**：第一 ~ 四步 **已完成并实测**（第一 ~ 三步提交见台账 P1-9，其余见 P1-13）；第五步待做。
> 第六步（宽屏转场）属附带项，已完成。

### 第一步：补高度闸门（A）—— ✅ 已完成

在 `WindowBreakpoints.isWideLayout` 的宽度条件上再加一条高度闸门：

```
宽屏 = (宽 ≥ 840 且 宽 > 高 且 高 ≥ 480)   // 480 = 官方 medium 高度下界
      或 (展开态 且 宽 ≥ 600 且 近方形)
```

理由：平板横屏（640 / 664 / 800dp 高）全部保留双栏；手机横屏（448dp 高）落回单栏。改动仅限纯函数 + 单测，**无需新依赖**。实测：997×448dp → 单栏，1280×800dp → 双栏。

### 第二步：接入折叠感知（B）—— ✅ 已完成

1. 加依赖：`androidx.compose.material3.adaptive:adaptive`（由 Compose BOM 管理，顺带拉入 `androidx.window`）。
2. 用 `collectFoldingFeaturesAsState()` 取折叠特征 —— 注意它**不再喂给分栏判定**：实测系统可能报空列表（§5.2），
   分栏改由纯尺寸路径决定（见下面的「第二步 B」）。折叠特征的用途收敛为两处：左栏宽度上限豁免、铰链对齐。
3. 折叠判定不再用 `firstOrNull()`，见「第二步 B」的铰链筛选。

> 注意：**不要**把展开态判定写成 `HALF_OPENED` —— 三折叠永远不会给这个状态（§1.4）。展开态应当由**尺寸跃变**来识别。

**实现取舍**：判定逻辑抽成纯函数 `FoldPosture.isUnfolded`（入参是自有的 `FoldFeature` 投影，不含 `android.graphics.Rect`），映射只发生在 Composable 一步 —— 这样纯 JVM 单测能覆盖各形态，不踩 `Stub!` 异常。

### 第二步 B：铰链对齐（C）+ 展开态尺寸兜底（H）—— ✅ 已完成

这两项是一起做的，因为它们都指向同一件事：**分栏这件事不该依赖折叠信号，但分栏线的位置该依赖它**。

**H（先做，因为它改变了判定本身）**：把 `isWideLayout` 的入参里的 `unfolded` 去掉，判定只剩两条纯尺寸路径。
这不是简化，而是实测倒逼出来的 —— 折叠 AVD 完全展开时系统报 `folding features=[]`，
裸的 838×945dp 内屏连 840 都够不着，依赖折叠信号的写法会让这块屏退化成单栏（§5.2 实测）。
那条「宽 ≥ 600 且近方形」的路径本来就是为折叠内屏写的，去掉 `unfolded &&` 之后它自己就能兜住。

**C**：`FoldPosture.verticalSplitHinge()` 从特征列表里挑出「竖直 + `isSeparating` + 带 `bounds`」的铰链
（多条时取最靠左的一条），交给 `WindowBreakpoints.splitGeometry()`：
分栏线压到**铰链左缘**而不是几何中线，并把铰链自身宽度作为 `hingeGapWidthDp` 返回，
`JetDoNavHost` 在左右两栏之间插一个同宽的 `Spacer`。位置不合适（左栏 < 320dp 或右栏 < 360dp）时退回对半。
可折叠屏的折痕只有 1px，留白换算后约 0.4dp，肉眼等同无缝。

**为什么不能用「对半」代替**：折痕在设备坐标系里**不保证落在中线**。折叠 AVD 的铰链在 1148/2200 = 52.2%，
对半算出 1100px，分栏线比铰链偏左 48px —— 右栏内容会压着铰链画。

### 第三步：清单补 `configChanges`（D）—— ✅ 已完成

加上 `density|screenSize|smallestScreenSize|screenLayout|orientation|fontScale|keyboard|keyboardHidden`，并在 `onConfigurationChanged` 处理密度重算。**这一步要格外小心**：自行处理配置变更意味着失去系统的自动重建兜底，需确认 Compose 侧不会因此漏刷新（Compose 通常靠 `LocalConfiguration` 感知，声明后仍会更新，但自定义 View / WebView 需手动处理）。

实测：冷启后连续切 `posture 4 → unfold → posture 4`，`onCreate` 恒为 1 次 —— 姿态变化不重建 Activity。

### 第四步：形态阶梯细化（E）—— ✅ 已完成

落成 `WindowWidthSizeClass`（5 档）+ `WindowHeightSizeClass`（3 档）两个枚举与对应判定函数，
左栏宽度上限从「一个常量」改为 `maxNavBarWidthDp(widthDp, unfolded)` 按档取值：

| 档位 | 窗口宽 | 左栏上限 | 依据 |
|---|---|---|---|
| MEDIUM 及更窄 | < 840 | 420（走下限 320 居多） | 这一档根本不会分栏，取什么都不影响 |
| EXPANDED | 840 ~ 1200 | 420 | 沿用原值：1138dp 对半 569 太宽，右栏读正文用不了 |
| LARGE | 1200 ~ 1600 | **480** | 10.5" ~ 12" 平板是主力大屏形态，420 会让列表卡片挤成一条 |
| EXTRA_LARGE | ≥ 1600 | **560** | 桌面显示器，对半会有 800dp+ 的列表栏，官方 Tier 2 明确要求别拉伸成全宽 |

折叠屏展开态仍豁免上限（返回 `Float.MAX_VALUE`）：内屏折痕必在中线，封顶会让分割线偏离折痕。

**内容密度与多列网格没有一起做**：官方 Tier 2 还要求「网格与多列」，那属于列表组件的改动，
与布局断点是两件事，单独立项。

### 第五步：Tier 1 差异化（F）—— 书本姿态 ✅ / 桌面姿态 ✅

#### 书本姿态（双页阅读）—— ✅ 已完成，开关控制、默认关

**字面意义的「同一份内容从左页续排到右页」在本工程里做不了**，原因不是难，而是它和详情页既有结构冲突：
`TopicDetailContent` 的 `listState` 承载着跳楼、楼层 scrubber、已读上报、未读行跳转、闪目标楼五条链路，
全部依赖「index 0 是摘要、楼层从 index 1 起」的下标算术；按像素高度切富文本就得把这五条一起重写，
而且图片是异步加载的，高度变化会让分页跳页。

落地的形态改成**按内容语义切**而不是按高度切：左页「摘要 + 一楼（楼主正文）」，右页「其余楼层（讨论）」。
切分点在**数据层**（`leadPosts` / `flowPosts`），右页因此仍是一个普通 `LazyColumn`，上面五条链路一行都不用改，
只需把「楼层下标 ↔ 列表下标」的换算收敛成 `headerSlots` 一个变量。

三条必须记住的判据：

1. **是否生效要按详情页自己的宽度量**，不能按窗口宽度。分栏时详情页只占右栏 —— 实测那台 838×945dp 的折叠 AVD
   里它只有 419dp，按窗口判会在一栏里硬塞两页（每页不到 210dp）。所以调用点包了一层 `BoxWithConstraints`，
   阈值取 `TwoPageReading.MIN_PANE_WIDTH_DP = 600`。
2. **图书姿态下外层要强制单栏**（`WideLayoutHost(forceSinglePane = …)`）：整块屏得交给详情页去分两页，
   外层再分一次（列表 | 详情）就没地方放了。
3. **分栏线要压在折痕上，不能对半**：折痕不保证在中线（实测 1148/2200 = 52.2%），对半会让右页开头一条内容
   压在铰链上（修复前 1127 < 1148）。折痕位置经 `LocalBookSpineCenter` 下发。

#### 桌面姿态（媒体上下分区）—— ✅ 已完成（待真机复验）

与双页阅读不同的关键差异：**内联视频卡在滚动列表里、铰链在窗口坐标系里**，两者的相对位置随滚动
一直变 —— 不像详情页那样「整页独占屏幕、折痕相对静止」。所以桌面姿态没有姿态开关（不会全局改变
布局形态，铰链划不进卡片时就是普通布局），切点必须**按卡片在窗口里的实时位置现场求交**：

- **判定**：`FoldPosture.isTabletopPosture()`（水平折痕 + `HALF_OPENED`，与书本姿态竖直折痕互斥），
  `WindowLayout.rememberTabletopPosture()` 包装；铰链中心经 `LocalTabletopHingeCenter` 下发
  （窗口坐标，同一基准才能求交）。
- **几何**：`TabletopMedia.splitFraction(cardTop, cardHeight, hingeY)` 纯函数 —— `(hingeY - cardTop) / height`
  求交，铰链不划过卡片（≤0 或 ≥1）返回 null 退回普通布局；贴边时 clamp 到 `[0.35, 0.75]`，
  宁可不严格贴铰链也不让某一区剩一条缝。滚动中每帧重算，划进/划出自然切换。
- **消费点**：`VideoPlayer` 桌面姿态 ∧ 已开播时切 `Column` 两区（画面 `weight(splitFraction)` 在上、
  `TabletopControlBar` 在下）。**分区时必须关掉 PlayerView 内置控制条换自绘条**：内置条只能渲染在
  自己 bounds 底部，画面被裁在铰链上方后它会悬在画面中间。
- ⚠️ **验证缺口**：模拟器 `emu fold`/`posture` 只产竖直折痕（`8in Foldable` AVD 实测），桌面姿态的
  水平折痕只有真机能给。单测覆盖了几何矩阵（`TabletopMediaTest` 7 例）与姿态判定
  （`FoldPostureTest` 桌面 2 例），真机到手后过一遍「视频播放中翻成桌面姿态 → 分区跟随滚动切换」。

### 第六步：宽屏转场策略（附带，已完成）

横向 push 表达的是「一层压一层」的空间关系，前提是**页面占满整个可视区**。切成双栏后前提不成立：左栏原地不动，位移只发生在右栏内部 —— 读到的不是层级变化，而是「右边自己抽走一块再塞回来」。加上右栏只有半屏宽，滑入距离减半，观感更跳。

做法：`JetDoNavHost` 的四个转场按 `isWide` 切换（`noneEnterTransition` / `noneExitTransition` / `slide*`），宽屏走 `EnterTransition.None`。这也是 list-detail 规范布局的惯例 —— 右侧详情区切换不做位移，层级由左栏选中态体现。

两个实现要点：

1. 两个转场常量都是**顶层 `val`**（稳定引用），`if (isWide)` 的结果只可能是其中之一，不会每次重组产生新 lambda，NavHost 参数保持稳定。
2. 只影响**路由转场**。左栏内部 tab 切换走的是 `tabEnterTransition`（淡入），不受影响。

---

## 五、验收方法

| 层次 | 手段 | 能证明什么 |
|---|---|---|
| 单元测试 | `WindowBreakpoints` / `FoldPosture` 纯函数入参矩阵 | 每种形态的判定结果，成本最低 |
| 形态伪造 | `wm size / wm density` | 尺寸驱动的布局分支 |
| 真实 AVD | `medium_tablet`（1280×800dp）等 | 真实 insets、真实密度 |
| 物理旋转 | `adb shell settings put system user_rotation` | 旋转往返是否保住状态 |
| **自由窗口缩放** | 拖动窗口边界 | **证明读的是窗口约束而非屏幕**（唯一能证伪 `LocalConfiguration` 方案的手段） |
| **折叠姿态（已跑通）** | `adb emu fold` / `unfold` / `posture N` | **真实驱动 `FoldingFeature` 上报**，见下方实测记录 |
| 三折叠 | 暂无现成 AVD 档案 | ⚠️ 缺口，需真机或后续镜像 |

### 5.1 折叠姿态实测法（已跑通，`8in Foldable` AVD）

模拟器控制台**能直接驱动铰链姿态**，应用侧会收到真实的 `FoldingFeature` —— 这意味着折叠感知代码不需要真机即可验证：

```bash
adb -s emulator-5558 emu fold        # 折起 → HALF_OPENED + 竖直折痕
adb -s emulator-5558 emu unfold      # 展开 → 特征列表为空
adb -s emulator-5558 emu posture 3   # opened  → mDeviceState=REAR,       特征为空
adb -s emulator-5558 emu posture 4   # flipped → mDeviceState=HALF_FOLDED, HALF_OPENED
```

`posture` 取值为 `1:closed 2:half-opened 3:opened 4:flipped 5:tent`，但**具体哪个值可用取决于设备形态**：本次的 `8in Foldable`（外折）只接受 `3` 和 `4`，`1/2/5` 一律返回 `KO: Failed to set posture`。

实测记录（`8in Foldable`，2200×2480 @420dpi = **838×945dp**）：

| 命令 | 系统态 | 应用侧 `FoldingFeature` | `unfolded` | UI |
|---|---|---|---|---|
| 初始 / `unfold` / `posture 3` | `REAR` | `[]` | `false` | 单栏 |
| `fold` / `posture 4` | `HALF_FOLDED` | `HINGE, bounds=[1148,0,1149,2480], state=HALF_OPENED` | `true` | **双栏** |

**这台 AVD 是验证折叠感知最理想的靶子**：展开态宽度 **838dp 差 2dp 够不到 840 阈值**，第一路径彻底失效 —— 只有 `unfolded=true` 走第二路径才会分栏。因此上面"单栏 ↔ 双栏"的每一次切换，都是折叠感知链路真实的开与关，不可能靠尺寸路径蒙混过关。

### 5.2 两条模拟器与真机的行为差异（重要）

1. **模拟器不上报 `FLAT`**。完全展开时返回**空列表**，而真机（Pixel Fold 类）展开时会报 `FoldingFeature{state=FLAT}`。后果：
   - `FoldPosture` 里 `FLAT → true` 那条分支**在模拟器上无法验证**，只能靠单测钉死；
   - 修复前，模拟器"展开态"会显示单栏（838dp < 840 且无折叠特征）。**这不是 bug，是模拟器行为** ——
     但它把一个真问题暴露了出来：**只要分栏判定依赖折叠信号，碰上不上报的设备就会退化**。
   - 这正是缺口 **H** 的由来，现已修掉：判定改成纯尺寸驱动后，同一台 AVD、同一个展开态实测为
     **双栏**（日志仍是 `folding features=[]`，见下方对照表）。**这个"模拟器 bug"反而成了 H 的回归靶子**
     —— 它稳定复现了「系统不上报、界面仍须正确」这一最难凑的场景。

2. **`fold` 不改变屏幕物理尺寸**。`wm size` 恒为 2200×2480，变化的是铰链姿态与 `mDeviceState`。别指望用 `fold` 制造"折叠成小屏"的尺寸变化。

### 5.2.1 缺口 C / H 的实测对照（`8in Foldable`，2200×2480 @420dpi = 838×945dp）

同一台设备、同一份 APK，只用 `adb emu` 改姿态：

| 姿态 | 应用侧 `FoldingFeature` | `unfolded` | 左栏宽度（实测 px → dp） | 右栏起点 |
|---|---|---|---|---|
| 展开 / `emu unfold` | `[]`（空） | false | **1100px = 419.05dp** | 1100 |
| `emu fold` | `HINGE, bounds=[1148,0,1149,2480], state=HALF_OPENED` | true | **1148px = 437.33dp** | **1149** |

读法：

- **H 的证据**：第一行。修复前这台设备在展开态是**单栏**（838 < 840 且无折叠特征），现在是**双栏**
  —— 800dp 以上的左栏真实存在（dump 里有 28 个宽 1100 的节点）。全程日志 `features=[]`，证明确实没靠折叠信号。
- **C 的证据**：第二行。左栏右缘 **1148**、右栏起点 **1149**，正好框住 `[1148, 1149]` 这条铰链。
  若仍走「对半」，左栏会是 1100px，分栏线比铰链偏左 48px —— 那 48px 的内容就压在铰链上了。
- 第二行 `hinge=FoldHingeSpan(startDp=437.33334, endDp=437.7143)` 是日志原文，
  1148 / 2.625 = 437.33、1149 / 2.625 = 437.71，留白 0.38dp（可折叠屏的折痕就是一条线）。
- 两行的 PID 相同（3153），姿态切换未重建 Activity，`configChanges` 同时得到二次验证。

### 5.3 验证「转场动画关没关」：单张相位采样法

**坑在时间尺度**：路由转场 320ms，而 `screencap` 单张耗时就接近这个量级，直接采集到的早已是终态。

**更关键的是：连续 `screencap` 会让渲染管线 stall** —— 截屏要等 GPU 合成完当前帧再读回，连续调用时 Compose 的帧被持续推迟，而动画走的是系统时钟，恢复渲染时直接跳到后面的时间点。**一次转场连拍多张，只有第 1 张可信，第 2 张起已是落定态**，而且它们看起来像正常的中后期帧，不会报错，极易误判。（本仓库就据此误判过一次。）

正确做法是**把时长临时放大 + 每轮只采一张**：

```bash
# 1) 临时把 SCREEN_TRANSITION_DURATION 从 320 改成 1600，编译并单独备份该 APK
# 2) 每轮独立转场：冷启动复位 → 采"点击前" → tap → sleep 相位 → 采"点击后"
adb shell am force-stop <pkg>; adb shell am start -n <pkg>/.MainActivity; sleep 11
adb shell "screencap -p /sdcard/B$phase.png; input tap X Y; sleep $phase; screencap -p /sdcard/A$phase.png"
# 3) 逐相位比对"after 帧 vs 终态帧"的差异占比
```

判据看**差异是否随相位单调收敛**（同一时长、同一操作，单栏与双栏各跑一次）：

| 相位 | 双栏（已关） | 单栏（保留 slide） |
|---|---|---|
| 0.3s | 11.325% | **59.909%** |
| 0.6s | 0.482% | **41.931%** |
| 0.9s | 0.206% | **22.129%** |
| 1.6s | 0.000%（终态基准） | 0.000%（终态基准） |

单栏**单调收敛**（越接近终态差异越小）—— 这是动画的典型特征；双栏**无趋势**、各相位都已是终态 —— 证明切换是瞬时的。

**同一手法在平板 AVD 上复现过一遍**（`jetdo_tablet`，2560×1600@320）。这一组的变量隔离比折叠 AVD 更干净：**同一台设备、同一个点击目标（左栏顶栏「个人中心」）、同一份 APK，唯一差别是横竖屏** —— 竖屏 `w800dp` 落单栏、横屏 `w1280dp` 落双栏：

| 相位 | 横屏 1280×800dp（`isWide=true`） | 竖屏 800×1280dp（`isWide=false`） |
|---|---|---|
| 0.3s | 0.145% | **65.915%** |
| 0.6s | 0.146% | **33.357%** |
| 0.9s | 0.008% | **14.807%** |
| 1.2s | 0.003% | **5.772%** |
| 1.6s | 0.000%（终态基准） | 0.000%（终态基准） |

宽屏从第一帧就已就位（0.145% ≈ 内容加载噪声），竖屏稳定递减五个数量级 —— 与折叠 AVD 结论一致。

**四个必须避开的坑**（都踩过）：

1. **别用连拍**，理由见上。
2. **`BACK` 在 ROOT 会退出 App**。若拿 `BACK` 当"返回上页"，某轮起点已在首页时会把 App 退到桌面，随后的 `tap` 就落到桌面图标上（实测点开了 Google 日历）。改用**冷启动复位**（`force-stop` + `start`）最稳。
3. **每轮 SOP 必须一致**：冷启动后固定等待时间、同一坐标、同一相位序列。跨轮直接比画面会被内容加载差异污染，**要用轮内的「before→after」或「各 after 帧 vs 终态」做对照**。
4. **别忘了正在跑的是哪个版本**。改动回退后重新编译装包，后续实验就是在正式版（320ms）上做的 —— 采样延迟大于动画时长，各相位当然全一样。实验期把长动画 APK 单独备份成另一个文件名，避免装错。

验完把时长改回 320 并重新编译装包（判据：`git diff` 该文件为空）。

**单测优先**：判定已抽成纯函数（`WindowBreakpoints` / `FoldPosture` / `TwoPageReading`），
形态矩阵可以直接钉死在 JVM 单测里 —— 这比凑设备可靠得多。

### 5.4 双页阅读实测（`8in Foldable`，书本姿态）

判据不靠截图，靠 `uiautomator dump` 的**几何特征**：Compose 默认不导 `resource-id`，
认「哪个节点在哪一页」只能看 `bounds`。

| 形态 | 开关 | 外层（`WideLayoutHost`） | 详情页 |
|---|---|---|---|
| 未折起（`features=[]`） | 开 | 双栏（838×945 命中近方形兜底） | 占右栏 **419dp** < 600 → **单栏**（正确：没地方摊两页） |
| `emu fold`（`HALF_OPENED` + 竖直铰链） | 开 | **强制单栏** | 占满 838dp → **摊开两页** |
| `emu fold` | **关** | 双栏 | 单栏 |

**摊开的证据**（开关开 + 已折起）：

- 左页：标题 `[26,274][1123,436]` · 摘要 `[26,531][487,594]` · 一楼正文 `[26,837][1123,895]`
- 右页：回复 `[1178,419][2174,477]`、`[1178,830][2137,1004]`，回帖人 `[1315,633][1506,735]`
- 顶栏「帖子详情」在 `x=158`（左上角）→ 反证外层没有再分栏

**书脊对齐**：铰链在 `1148`px，右页内容最小左缘 `1178`px，不压折痕。
修复前按窗口对半，左页宽 1100px，右页内容从 `1127` 起 —— **正压在 1148 的铰链上**。

**开关可控性对照**：同一设备、同一姿态，只改 `twoPageReadingEnabled` 一个布尔 ——
开时首页列表项宽 1989px（单栏），关时 937px（左栏）。关掉后主布局完全回到改动前。

---

## 六、一句话总结

官方体系的骨架是 **「尺寸类别（5 档宽 + 3 档高）决定布局形态」+「折叠特征决定避让与姿态」+「Android 16 起不允许你再靠锁方向躲」**。

我方落地的形态可以压成一条分工：

> **分不分栏看尺寸，分栏线画在哪看折痕。**

前半句是缺口 H 修完后的结论 —— `isWideLayout(widthDp, heightDp)` 里已经没有任何折叠参数。
理由不是"简化"，而是实测：系统完全展开时可能报空特征列表，把布局形态押在它身上会退化。
后半句是缺口 C 的结论 —— 折痕位置（`FoldingFeature.bounds`）反过来**必须**信设备，
因为它不保证落在中线（折叠 AVD 实测 52.2%）。

补第三句 —— 它是双页阅读逼出来的：

> **分不分栏看尺寸，分栏线画在哪看折痕，能不能摊开两页看详情页自己有多宽。**

窗口够宽不等于详情页够宽：分栏之后它可能只剩一半。按窗口宽度判断，就会在一栏里硬塞两页。

已落地：**读窗口约束、左栏提为 NavHost 兄弟**（最难的两处）+ **高度闸门**（横屏手机不再误判双栏）
+ **`configChanges`**（姿态切换不重建）+ **宽屏转场关闭** + **铰链对齐与留白** + **展开态尺寸兜底**
+ **5 档 WSC 分组** + **书本姿态双页阅读**（开关控制、默认关）+ **桌面姿态媒体上下分区**
（几何单测覆盖，真机水平折痕待复验）。前八项全部在真实 AVD 上验证
（平板 `1280×800dp` / 折叠 `838×945dp`）。

**大屏适配至此全部收口**：书本姿态（双页阅读开关）+ 桌面姿态（媒体上下分区，真机待复验）
+ 相机方向（选型系统相机，适配点消解）。Tier 1/2 官方清单再无未做项。
