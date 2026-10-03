# ArkDO Android — 开发待办

> 功能缺口的**唯一权威**是 [`GAP_ANALYSIS.md`](GAP_ANALYSIS.md)（做完删一条）。
> 本文件只保留发布准备相关的工程项，不重复记账。

## 发布准备

- [ ] 签名配置（`app/build.gradle.kts` 尚无 `signingConfigs`）
- [x] CI（GitHub Actions：`.github/workflows/ci.yml`，push/PR 跑 assembleDebug + 全模块单测）
- [x] androidTest 骨架（app/src/androidTest 3 类 7 用例，connectedDebugAndroidTest 模拟器 7/7 通过）
- [ ] 崩溃上报（Crashlytics / Bugly / Sentry 选型）
- [x] 隐私政策 / 用户协议 / 商店素材（docs/publish/ 三份；联系方式占位待补，隐私政策需发布为网页链接）
- [x] 多语言（UI 文案抽取进 strings.xml 并翻译；679 条 + values-en 全量，locales_config 声明 zh-CN / en-US）
- [ ] 真机回归：折叠屏桌面姿态媒体分区、composer 拍照入口

## 已知缺口摘要（以 GAP_ANALYSIS.md 为准）

- P0：系统推送投递链路 / 多站点与论坛切换 / 设置「论坛 / 我的站点」两入口
- P1：版本公告定源
- 其余：S14 过盾开关、P2 工程化项、死代码 4 组与微清理 2 条 —— 逐项状态见 GAP_ANALYSIS.md
