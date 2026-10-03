# ArkDO Android 商店上架素材

> 各应用商店字段长短不一，按需裁剪。隐私政策与用户协议见同目录 [privacy-policy.md](privacy-policy.md) / [user-agreement.md](user-agreement.md)，发布前须补齐两份文档中的联系方式，并把隐私政策挂到可达的网页链接（商店「隐私政策」栏必填）。

## 一、基本信息

| 项 | 内容 |
|---|---|
| 应用名 | ArkDO |
| 包名 | com.arkdo.android |
| 当前版本 | 1.0.1 (versionCode 2) |
| 分类 | 社交 / 通讯类（论坛阅读）或「阅读」 |
| 标签 | LINUX DO、论坛、社区、Discourse |
| 支持语言 | 简体中文、English（locales_config：zh-CN、en-US） |
| 最低系统 | Android 8.0（minSdk 26） |

## 二、一句话简介（≤ 30 字）

> LINUX DO 社区的非官方 Android 客户端

英文（≤ 30 chars）：

> Unofficial Android client for LINUX DO

## 三、完整介绍（约 400 字，可直接粘贴）

> ArkDO 是 LINUX DO 社区的非官方第三方 Android 客户端，基于 Material 3 与 Jetpack Compose 全新构建。
>
> 【浏览与阅读】首页多标签（热门/最新/顶置）流式浏览；话题详情支持 Markdown 完整渲染、代码高亮、楼层跳转与 Scrubber 快速拖动定位；内嵌音视频播放。
>
> 【参与互动】回复、引用、点赞、收藏、话题解决标记；本地草稿自动保存，编辑与回帖状态互不干扰；拍照或相册选图直传社区。
>
> 【消息提醒】站内通知、实时消息、@提及与引用提醒，支持系统通知。
>
> 【本地能力】书签、草稿、已读状态、浏览历史全部本地化；支持 DoH（DNS over HTTPS）抗污染；深色模式跟随系统。
>
> 【大屏与折叠屏】平板双栏布局、折叠屏/三折叠展开态自适应、桌面姿态媒体上下分区、书本姿态双页阅读（实验性）。
>
> 本应用无广告、无埋点统计；崩溃信息经腾讯 Bugly 上报用于稳定性改进（不包含社区账号与浏览记录）。登录通过官方页面完成，隐私政策见应用发布页。
>
> ArkDO 与 LINUX DO 官方无隶属关系，社区内容版权归社区及原作者所有。

英文版（英文商店可用，与 values-en 文案口径一致）：

> ArkDO is an unofficial Android client for the LINUX DO community, built with Material 3 and Jetpack Compose: full Markdown rendering with code highlight, a floor scrubber for quick navigation in long topics, built-in media playback, local drafts, bookmarks, read-state sync, DoH support, dark mode, and adaptive layouts for tablets and foldables. No ads, no analytics SDK. ArkDO is not affiliated with LINUX DO.

## 四、更新说明模板（1.0.1）

```
· 全量界面文案双语化（中/英随系统切换）
· 新增过盾快捷开关、看图模式等设置项
· 大屏适配：桌面姿态媒体分区、书本姿态双页阅读
· 稳定性修复：Boost 刷新、楼层定位等
```

## 五、权限用途说明（国内商店「权限说明」栏）

| 权限 | 用途说明 |
|---|---|
| 网络（INTERNET） | 访问 LINUX DO 社区获取内容，应用核心功能必需 |
| 网络状态（ACCESS_NETWORK_STATE） | 检测联网状态以提示离线，不采集任何信息 |
| 通知（POST_NOTIFICATIONS） | 在你授权后推送新消息提醒，拒绝不影响浏览 |

## 六、截图清单（建议 6 张，设备 1080×2400 起）

| # | 画面 | 要点 |
|---|---|---|
| 1 | 首页（亮色） | 多标签话题流，展示头像/徽章/角标 |
| 2 | 话题详情 | Markdown 渲染 + 代码高亮，楼层锚点 |
| 3 | 楼层 Scrubber | 右缘拖动定位浮层（差异化亮点） |
| 4 | 回帖编辑器 | 工具栏 + 图片上传 + 草稿 |
| 5 | 设置页 | 展示 DoH、通知、主题等选项密度 |
| 6 | 平板/折叠屏双栏 | 强调大屏自适应（商店加分项） |

拍摄注意：使用已登录的演示账号、造一批无隐私风险的测试内容；深色模式可另出 2 张替换 1/2。

## 七、图标与图材规格

- **应用图标**：512×512 PNG（圆角按商店规范）；源文件待定稿
- **功能宣传图**（Google Play 需要）：1024×500
- **内容分级问卷建议**：无暴力/赌博/敏感内容；支持用户生成内容（社区），按商店 UGC 类目如实勾选「提供举报/屏蔽途径」（依托社区侧规则与客户端忽略用户功能）

## 八、上架前 checklist

- [ ] 隐私政策 / 用户协议补联系方式并发布为网页（GitHub Pages / 仓库渲染页均可）
- [ ] 商店「隐私政策」链接指向网页版 privacy-policy.md
- [ ] **到 bugly.qq.com 创建产品，替换 `app/build.gradle.kts` 里的演示 AppId（`BuildConfig.BUGLY_APP_ID`）**，并验证一次真实崩溃上报
- [x] 首启隐私同意弹窗：已实现（MainActivity 模态弹窗 + `PrivacyConsentStore` 落盘版本号；未同意前 Bugly 不初始化，政策实质变更递增 `POLICY_VERSION` 自动重弹）
- [ ] 截图 6 张按上表拍摄
- [ ] 图标 512×512 定稿
- [ ] release 签名（GAP P2-20）后出正式包；release 混淆后如需还原堆栈，用 Bugly 符号表工具上传 mapping.txt
