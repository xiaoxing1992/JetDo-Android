# ArkDO Android - 开发清单

## 项目初始化 ✅

- [x] 项目结构搭建
- [x] Gradle 配置（Kotlin、Compose、Hilt）
- [x] 依赖管理（libs.versions.toml）
- [x] 主题系统（Material3 + 温暖土色调）
- [x] 导航框架（Compose Navigation）
- [x] 依赖注入（Hilt）
- [x] 资源文件（strings.xml、themes.xml）
- [x] ProGuard 规则
- [x] .gitignore 配置

## 核心架构 ✅

- [x] ArkDoApplication（应用入口）
- [x] MainActivity（单 Activity）
- [x] RootTabScreen（底部导航栏）
- [x] HomeScreen（首页框架）
- [x] NotificationScreen（通知中心框架）
- [x] ProfileScreen（个人主页框架）
- [x] 数据模型定义（Topic、User、Post）
- [x] ARCHITECTURE.md 文档

## 核心服务（骨架） ⏳

- [x] WebViewNetworkBridge（网络桥接 - 待实现）
- [x] ChallengeRecoveryCoordinator（挑战恢复 - 待实现）
- [x] 推送中继服务器代码（relay.py）
- [ ] DohManager（DoH DNS 管理器）
- [ ] AvatarImageCache（头像缓存）
- [ ] MathRenderService（LaTeX 公式渲染）

## 待实现功能（按优先级）

### P0 - 核心功能

- [ ] **WebView 网络桥接完整实现**
  - [ ] 创建隐藏 WebView
  - [ ] 注入 JS fetch 桥接函数
  - [ ] 实现 Cookie 同步（退后台刷盘）
  - [ ] 错误重试与超时处理

- [ ] **Cloudflare 挑战处理**
  - [ ] 挑战检测（503 + cf-challenge）
  - [ ] 静默恢复（后台重载 + 探测）
  - [ ] 手动验证页面

- [ ] **话题列表 API 适配**
  - [ ] TopicService（latest / hot / unread）
  - [ ] TopicPagingSource（分页加载）
  - [ ] TopicCard 组件

- [ ] **登录流程**
  - [ ] LoginScreen（WebView OAuth）
  - [ ] Cookie 提取与持久化
  - [ ] LoginGate 拦截器

### P1 - 核心交互

- [ ] **话题详情页**
  - [ ] TopicDetailScreen
  - [ ] 帖子列表渲染
  - [ ] 分页加载（窗口式）
  - [ ] 楼层跳转 Scrubber

- [ ] **Cooked HTML 解析器**
  - [ ] HTML → AnnotatedString
  - [ ] 支持 `<p>` `<a>` `<blockquote>` `<code>` 等标签
  - [ ] 站内链接路由

- [ ] **回复评论**
  - [ ] CommentInputSheet（Bottom Sheet）
  - [ ] 键盘自适应
  - [ ] 引用回复

- [ ] **表情回应与互动**
  - [ ] ReactionBar 组件
  - [ ] Boost / 投票 / "俺也一样"

### P2 - 完善体验

- [ ] **书签管理**
  - [ ] BookmarksScreen
  - [ ] 书签增删 API
  - [ ] 置顶功能

- [ ] **通知中心**
  - [ ] 30+ 种通知类型适配
  - [ ] 未读角标管理
  - [ ] 深链接跳转

- [ ] **个人主页**
  - [ ] ProfileScreen 完整实现
  - [ ] 用户动态（话题/回复/点赞）
  - [ ] 徽章墙

- [ ] **搜索功能**
  - [ ] SearchBar 组件
  - [ ] 搜索结果页

- [ ] **版块筛选**
  - [ ] CategoryDrawer（双层抽屉）

### P3 - 高级功能

- [ ] **LaTeX 公式渲染**
  - [ ] MathRenderService 实现
  - [ ] 离屏 WebView + MathJax

- [ ] **图片预览**
  - [ ] ImageViewer 组件
  - [ ] 双击缩放、下拉关闭

- [ ] **音视频播放**
  - [ ] ExoPlayer 集成

- [ ] **附件下载**
  - [ ] DownloadService

- [ ] **系统推送**
  - [ ] FCM 集成
  - [ ] 推送中继部署
  - [ ] 深链接导航

- [ ] **DoH 设置**
  - [ ] DohSettingsScreen
  - [ ] 服务器测速

- [ ] **阅读进度追踪**
  - [ ] ReadingProgressTracker
  - [ ] 蓝点淡出、红线标记

- [ ] **草稿管理**
  - [ ] DraftsScreen
  - [ ] 草稿自动保存

- [ ] **等级进度**
  - [ ] LevelProgressScreen
  - [ ] 连接 connect.linux.do

## 测试

- [ ] WebViewBridge 单元测试
- [ ] HTML Parser 单元测试
- [ ] 登录流程集成测试
- [ ] 话题列表 UI 测试

## 文档

- [x] README.md
- [x] ARCHITECTURE.md
- [x] server/README.md
- [ ] API.md（Discourse API 适配文档）
- [ ] CONTRIBUTING.md

## 发布准备

- [ ] 签名配置
- [ ] ProGuard 规则完善
- [ ] 性能优化（启动速度、内存占用）
- [ ] 无障碍适配（TalkBack 测试）
- [ ] 隐私政策与用户协议
- [ ] Google Play 应用商店素材

---

**当前状态**：项目骨架搭建完成，核心服务框架就绪，等待实现具体功能。

**下一步**：实现 WebViewNetworkBridge，打通网络层。
