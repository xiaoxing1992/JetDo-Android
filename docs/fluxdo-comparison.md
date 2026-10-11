# FluxDO 参照系：它有而 JetDo 没有的

> 参照对象：`github.com/Lingyan000/fluxdo`（Flutter · linux.do 第三方客户端，多端）
> 本文件是**第二参照系**对比，独立于 `GAP_ANALYSIS.md`（那里只管鸿蒙 ArkDo 对标，仍是唯一权威）。
> 规则同台账：只记「现在还没做的」，做完删一条。已核实过的等价物不再列（见文末防误报表）。
> FluxDO 源码取证基于浅克隆（/tmp/fluxdo），端点与文件规模逐个 grep 实测，非读 README 抄的。

---

## 结论先行

**真缺口 8 项。** 其中成体系的大活只有 1 个（私信+Chat，约等于再迁一个通知模块的量），其余都是单页面级。

| # | 功能 | 量级 | 建议 |
|---|---|---|---|
| F1 | 私信收件箱（收/发/归档） | 大 | ⭐ 最先考虑 |
| F2 | Discourse Chat 频道聊天 | 特大 | 随 F1 一起评估 |
| F3 | 浏览历史时间线 | 小 | ⭐ 性价比最高 |
| F4 | 信任等级逐条要求清单 | 中 | 建议做 |
| F5 | 数据管理（缓存清理/备份） | 中 | 国内商店场景加分 |
| F6 | 关注列表页 | 小 | 按需 |
| F7 | 邀请链接 | 中 | 增长功能，按需 |
| F8 | mermaid 图渲染 | 小 | 按需 |

---

## 逐项明细

### F1 · 私信收件箱（收件箱 / 已发送 / 归档）

- **FluxDO 实现**：Discourse 私信本质是 topic，走主题接口族——
  `/topics/private-messages/{username}.json`（收件箱）、`/topics/private-messages-sent/{username}.json`（已发送）、`/topics/private-messages-archive/{username}.json`（归档），配合既有 `/topics/bulk.json`（批量已读/归档操作）。
- **JetDo 现状**：零。`services/message/` 只有 `MessageBusEventBus`（实时通道，勿混淆）。
- **接入要点**：列表页三态（收/发/归档）复用 HomeTopic 流；阅读/回复直接进现有 `TOPIC_DETAIL`（PM 就是 topic，composer 无需新通道）；入口放 Profile 或抽屉；实时未读数挂在既有 message-bus 通道上。

### F2 · Discourse Chat 频道聊天

- **FluxDO 实现**：39 个 dart 文件，全走 chat 插件 API——`/chat/api/channels`（频道列表）、`.../memberships`（加入/退出）、`.../drafts`（草稿）、`/chat/{id}/new-messages`（拉增量）、`/chat/{id}/react/{messageId}`（表情回应）、`/chat/{id}/thread/{threadId}`（话题串）。
- **JetDo 现状**：零。linux.do 已装 chat 插件，数据在，端上没人消费。
- **接入要点**：与 F1 共享 message-bus 订阅层与「会话流 UI」骨架；先只读后可写（频道浏览 + 增量拉取先行，发送/回应二期）。

### F3 · 浏览历史时间线

- **FluxDO 实现**：`browsing_history_page.dart`（294 行）+ 独立导出页；本地记录「何时点开过什么」，支持清空与导出。
- **JetDo 现状**：有「最近阅读」（`READ_TOPICS`），但那是**已读集合**语义（reading tracker 按 `topics/timings` 结算）；不是访问时间线，不可清空、不可导出。
- **接入要点**：纯本地——`TopicDetailController` 进入详情时落一条 `(topicId, visitedAt)`，Room 表 + 按天分组 + 清空/导出；与阅读进度数据零耦合。

### F4 · 信任等级逐条要求清单

- **FluxDO 实现**：`trust_level_requirements_page.dart` 1374 行，按官方 TL 要求逐条列达标状态（访问天数/已读话题数/送出赞等），环形进度可视化；数据纯端上算（用户 stats 即可），无专用接口。
- **JetDo 现状**：`LEVEL_PROGRESS` 只展示当前等级与进度环，无逐条要求清单。
- **接入要点**：一个页面；取 `/u/{username}.json` 的用户统计字段逐条比对官方阈值（阈值可做成常量表，对齐 linux.do 实配）。

### F5 · 数据管理（缓存清理 / 备份）

- **FluxDO 实现**：`data_management_page.dart`——缓存按类目（图片/网页/日志）统计体积、多选清理、数据备份导出。
- **JetDo 现状**：零（只有调试日志页）。
- **接入要点**：扫 cache 目录 + Coil 磁盘缓存 + WebView 缓存分桶算体积；清空按钮分桶给。纯端上，无接口。

### F6 · 关注列表页

- **FluxDO 实现**：`follow_list_page.dart`（161 行）——「我关注的人」独立列表。
- **JetDo 现状**：关注/取关在用户主页上可操作，但没有一个「我关注了谁」的列表页。
- **接入要点**：一个列表页，接口与用户主页粉丝列表同源。

### F7 · 邀请链接

- **FluxDO 实现**：`invite_links_page.dart`（808 行），走 `/invites` 生成/枚举邀请链接。
- **JetDo 现状**：零。
- **接入要点**：单接口页面（生成/复制/删除/过期时间）；是否做取决于要不要增长功能。

### F8 · mermaid 图渲染

- **FluxDO 实现**：`mermaid_viewer_page.dart`，帖内 mermaid 代码块点开渲染成图。
- **JetDo 现状**：数学公式有（MathJax），mermaid 代码块按普通代码块渲染。
- **接入要点**：markdown 渲染层（既有 markdown-it 管线）加 mermaid 插件，点击放大复用图片查看器容器。

---

## 已核实等价物（防误报，勿再报）

| FluxDO 功能 | JetDo 已有 |
|---|---|
| 我的话题 / 我的草稿 | Profile 入口 + DRAFTS 页 |
| 徽章（my_badges） | BadgesPage + 徽章筛选 |
| 书签 | BookmarksPage |
| 投票 | PollMutation 全套 |
| MessageBus 实时 | services/realtime + message |
| DOH | DohService + 设置页（追平鸿蒙） |
| 内置浏览器 / WebView 登录 | WEBVIEW + 过盾登录链路 |
| 代码高亮 / HTML 分块渲染 | markdown-it 管线既有 |
| 新手引导 | ForumOnboarding 语义已并入首启流 |
| 悬浮预览 / 弹幕化 Boots / 待看帖悬浮窗 | 交互形态差异，非功能缺失（鸿蒙侧亦无） |
| 通知 / 搜索 / 主题详情等核心 | 已对齐鸿蒙（见 GAP_ANALYSIS） |
