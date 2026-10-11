# JetDo → JetDo-Android 迁移现状

> 对标基线：鸿蒙 `JetDo` @ main（28 页面 / 47 组件 / 25 服务子域）。
> 历史里程碑（垂直链路 → 横向铺开）已全部走完，逐文件勾选清单已废弃 —— **当前缺口与完成度的唯一权威是 [`GAP_ANALYSIS.md`](GAP_ANALYSIS.md)**（全量重扫于 2026-10-02，做完删一条）。

## 当前结构

```
app/                    Hilt 组装、导航宿主、页面与组件（ui/）、服务实现（services/）
core/model/             数据模型 + RelativeTime 等纯逻辑
core/network/           网络契约
core/data/              Repository 层（含会话 / 读态 / 通知等共享数据源）
core/designsystem/      主题、通用组件（TopicRow / UserAvatar / FaIcon 等）
feature/home|search|notification/   垂直切片：ViewModel + Route
server/                 推送中继（Python）
```

## 对齐度概览（详见 GAP_ANALYSIS.md）

- 服务子域 25 vs 23（真缺 `avatar` 已补、`push` 投递链路未迁）
- 页面 28 vs 30；组件级真缺失 0
- 未迁真缺口集中在：系统推送、多站点、设置 2 入口、版本公告定源

## 平台差异，明确不迁（勿再记为缺口）

- 智感握姿（`@kit.MultimodalAwarenessKit` 传感器）
- 鸿蒙推送通道 `HarmonyPushTokenProvider`（Android 对应物为 FCM + 自建中继）
- `@kit.UIDesignKit`（HdsActionBar）、`LoginKeyboardBridge`
- `PulsingHalo`（鸿蒙侧自身死代码）
