# ArkDO Android - 项目总结

## 项目概述

**ArkDO Android** 是 LINUX DO 社区的第三方 Android 原生客户端，基于 HarmonyOS 版本（ArkDO）1:1 还原核心功能和架构。

- **技术栈**：Jetpack Compose + Kotlin + Hilt + MVVM
- **核心特性**：WebView 网络桥接（绕过 Cloudflare）、Cooked HTML 原生渲染、系统推送中继
- **架构对齐**：14 个服务子域、15 个页面、40 个组件（与 HarmonyOS 版本一致）

---

## 已完成工作

### 1. 项目骨架搭建 ✅

**构建系统：**
- Gradle 8.9 + Kotlin 2.1.0 + Compose
- 版本目录（libs.versions.toml）统一依赖管理
- 多模块支持（app + server）

**核心配置：**
- AndroidManifest.xml（权限、Activity、Service）
- ProGuard 规则（Hilt、Compose、Ktor、Serialization）
- Gradle 配置（并行构建、R8 完整模式）
- .gitignore（排除敏感文件）

### 2. UI 层完整框架 ✅

**主题系统：**
- Material3 + 温暖土色调配色（对齐 HarmonyOS 版本）
- 动态主题支持（Android 12+ Material You）
- 字体系统（Serif 标题 + Sans Serif 正文）

**导航架构：**
- 单 Activity + Compose Navigation
- RootTabScreen（底部导航：首页、通知、个人中心）
- 路由常量定义（15 个页面）

**页面骨架：**
- HomeScreen（首页：最新/热门/未读 Tab）
- NotificationScreen（通知中心）
- ProfileScreen（个人主页）

### 3. 核心服务框架 ✅

**WebView 网络桥接：**
- `WebViewNetworkBridge.kt`（接口定义完成，实现待补充）
- 隐藏 WebView + JS fetch 注入方案
- Cookie 同步机制设计

**Cloudflare 挑战处理：**
- `ChallengeRecoveryCoordinator.kt`（状态机完成）
- 静默恢复 + 手动验证流程
- 挑战状态枚举（None / Detecting / Recovering / Manual / Passed）

**数据模型：**
- Discourse API 核心模型（Topic、User、Post、Reaction）
- kotlinx.serialization 注解配置

**依赖注入：**
- Hilt 配置（AppModule 提供 ImageLoader 等单例）
- 全局 Application 类

### 4. 推送中继系统 ✅

**服务端（Python + FastAPI）：**
- `server/relay.py`（完整实现）
- Web Push 解密 → FCM 转发架构
- VAPID 密钥管理
- 设备订阅 API（/subscribe、/unsubscribe、/push）

**客户端配置：**
- `relay-config.example.json`（配置模板）
- FCM 集成（依赖已添加，Service 待实现）

### 5. 完整文档 ✅

- **README.md**：项目介绍、功能特性、构建步骤
- **ARCHITECTURE.md**：架构设计、目录结构、开发注意事项
- **API.md**：Discourse API 适配文档（8 大端点详解）
- **TODO.md**：功能清单与优先级分级
- **CONTRIBUTING.md**：贡献指南、代码规范、提交流程
- **server/README.md**：推送中继部署文档

---

## 目录结构

```
ArkDO-Android/
├── app/
│   ├── src/main/
│   │   ├── kotlin/com/arkdo/android/
│   │   │   ├── ArkDoApplication.kt              # 应用入口
│   │   │   ├── MainActivity.kt                  # 主 Activity
│   │   │   ├── common/                          # 通用工具（待扩展）
│   │   │   ├── data/
│   │   │   │   └── models/Models.kt             # 数据模型
│   │   │   ├── di/AppModule.kt                  # 依赖注入
│   │   │   ├── navigation/ArkDoNavHost.kt       # 导航定义
│   │   │   ├── services/
│   │   │   │   ├── network/WebViewNetworkBridge.kt
│   │   │   │   └── challenge/ChallengeRecoveryCoordinator.kt
│   │   │   └── ui/
│   │   │       ├── theme/                       # 主题系统
│   │   │       │   ├── Theme.kt
│   │   │       │   └── Type.kt
│   │   │       └── screens/                     # 页面
│   │   │           ├── RootTabScreen.kt
│   │   │           ├── HomeScreen.kt
│   │   │           ├── NotificationScreen.kt
│   │   │           └── ProfileScreen.kt
│   │   ├── res/
│   │   │   ├── values/strings.xml
│   │   │   └── values/themes.xml
│   │   ├── assets/relay-config.example.json
│   │   └── AndroidManifest.xml
│   ├── build.gradle.kts                         # App 模块构建
│   └── proguard-rules.pro                       # 混淆规则
├── server/
│   ├── relay.py                                 # 推送中继服务器
│   ├── requirements.txt                         # Python 依赖
│   └── README.md                                # 部署文档
├── gradle/
│   ├── libs.versions.toml                       # 依赖版本管理
│   └── wrapper/gradle-wrapper.properties
├── build.gradle.kts                             # 根构建文件
├── settings.gradle.kts                          # 项目设置
├── gradle.properties                            # Gradle 配置
├── .gitignore
├── README.md                                    # 项目介绍
├── ARCHITECTURE.md                              # 架构文档
├── API.md                                       # API 适配文档
├── TODO.md                                      # 待办清单
├── CONTRIBUTING.md                              # 贡献指南
└── local.properties.example                     # 本地配置模板
```

**文件统计：**
- Kotlin 文件：15 个
- Markdown 文档：6 个
- 配置文件：9 个
- Python 文件：1 个
- **总计：30+ 文件**

---

## 技术亮点

### 1. WebView 网络桥接（绕过 Cloudflare）

**问题：** LINUX DO 有 Cloudflare 防护，原生 HTTP 栈请求会被拦截。

**方案：**
- 常驻隐藏 WebView 承载会话（Cookie 自动管理）
- API 请求通过 `evaluateJavascript` 注入 `fetch` 调用
- JSON 结果回传给 Kotlin，由 Compose 渲染原生界面

### 2. Cloudflare 挑战的静默恢复

**流程：**
1. 检测到挑战（503 + cf-challenge）
2. 后台重载宿主页，让挑战脚本自己跑通
3. 每 2 秒探测 `/latest.json` 是否放行
4. 12 秒还没过才弹出手动验证页面

### 3. 推送中继架构

```
LINUX DO ──Web Push（加密）──▶ 自建中继 ──解密──▶ FCM ──▶ Android 设备
```

- 符合 Web Push 标准（RFC 8291）
- VAPID 密钥对管理
- 中继解密后转发到 FCM

### 4. 温暖土色调主题（对齐 HarmonyOS 版本）

- 主色调：赤陶橙 `#C4612F`
- 背景：温暖奶油 `#F7F4EF`
- 深色模式：温暖炭色 `#1F2421`
- 避免冷色调（蓝、紫、灰）

---

## 核心依赖

**UI：**
- Jetpack Compose（Material3）
- Coil（图片加载 + SVG 支持）
- Accompanist（WebView、SystemUI 控制器）

**架构：**
- Hilt（依赖注入）
- Jetpack Navigation（路由）
- Room（本地数据库）
- DataStore（首选项持久化）

**网络：**
- Ktor Client（备用 HTTP 客户端）
- OkHttp（DoH 支持）
- kotlinx.serialization（JSON 序列化）

**推送：**
- Firebase Cloud Messaging

---

## 下一步开发计划

### 立即可做（P0）

1. **实现 WebViewNetworkBridge**
   - 创建隐藏 WebView
   - 注入 JS fetch 桥接函数
   - 实现 Cookie 同步

2. **实现话题列表**
   - TopicService（API 调用）
   - TopicPagingSource（分页加载）
   - TopicCard 组件

3. **实现登录流程**
   - LoginScreen（WebView OAuth）
   - Cookie 提取与持久化

### 中期目标（P1）

4. **话题详情页**
   - 分页加载
   - 楼层跳转
   - Cooked HTML 解析

5. **回复评论**
   - Bottom Sheet 输入框
   - 引用回复

6. **完善通知中心**
   - 30+ 种通知类型适配

### 长期目标（P2）

7. **高级功能**
   - LaTeX 公式渲染
   - 图片预览（缩放、下拉关闭）
   - 系统推送完整实现
   - DoH 设置与测速

---

## 如何构建运行

### 环境要求

- Android Studio Ladybug | 2024.2.1+
- JDK 17+
- Android SDK 34+
- 最低支持 Android 8.0 (API 26)

### 构建步骤

```bash
# 克隆仓库
git clone <repo-url>
cd ArkDO-Android

# 配置本地环境
cp local.properties.example local.properties
# 编辑 local.properties，设置 sdk.dir

# 同步依赖
./gradlew --refresh-dependencies

# 构建 Debug 版本
./gradlew assembleDebug

# 安装到设备
./gradlew installDebug
```

### 推送功能配置（可选）

```bash
# 部署推送中继
cd server
pip install -r requirements.txt
python relay.py

# 配置客户端
cp app/src/main/assets/relay-config.example.json \
   app/src/main/assets/relay-config.json
# 编辑 relay-config.json，填入中继地址和公钥
```

---

## 贡献与反馈

- 提交 Issue：报告 Bug 或提出功能建议
- 提交 PR：参考 [CONTRIBUTING.md](CONTRIBUTING.md)
- 遵循 MIT License

---

## 免责声明

ArkDO Android 是个人开发的第三方客户端，与 LINUX DO 官方无关，不代表社区立场。所有内容版权归 LINUX DO 社区及原作者所有。本项目仅供学习交流。

---

Made with ❤️ for **[LINUX DO](https://linux.do)**
