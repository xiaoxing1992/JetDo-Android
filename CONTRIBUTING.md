# 贡献指南

感谢考虑为 JetDo Android 贡献代码！

## 开发环境设置

1. **克隆仓库**
   ```bash
   git clone https://github.com/your-username/ArkDO-Android.git
   cd ArkDO-Android
   ```

2. **安装依赖**
   - Android Studio Ladybug | 2024.2.1+
   - JDK 17+
   - Android SDK 34+

3. **配置本地环境**
   - 复制 `local.properties.example` 为 `local.properties`
   - 配置 `sdk.dir` 指向你的 Android SDK 路径
   - （可选）配置签名信息用于 Release 构建

4. **同步 Gradle**
   ```bash
   ./gradlew --refresh-dependencies
   ```

5. **运行项目**
   - 连接设备或启动模拟器
   - Run `app` configuration

## 代码风格

- 使用 Kotlin 官方代码风格（`kotlin.code.style=official`）
- 遵循 [Android Kotlin 风格指南](https://developer.android.com/kotlin/style-guide)
- 函数名使用驼峰命名，常量使用 UPPER_SNAKE_CASE
- Composable 函数使用 PascalCase

## 提交规范

遵循 [Conventional Commits](https://www.conventionalcommits.org/)：

```
<type>(<scope>): <subject>

<body>

<footer>
```

**Type：**
- `feat`: 新功能
- `fix`: 修复 Bug
- `refactor`: 重构
- `perf`: 性能优化
- `style`: 代码格式调整（不影响逻辑）
- `test`: 添加或修改测试
- `docs`: 文档更新
- `chore`: 构建/工具链配置

**示例：**
```
feat(topic): 实现话题详情页分页加载

- 添加 TopicPagingSource
- 支持窗口式加载
- 实现楼层跳转 Scrubber

Closes #42
```

## Pull Request 流程

1. Fork 仓库到你的账号
2. 创建特性分支：`git checkout -b feat/your-feature`
3. 提交代码：`git commit -m "feat: your feature"`
4. 推送到 Fork：`git push origin feat/your-feature`
5. 提交 Pull Request 到 `main` 分支

**PR 检查清单：**
- [ ] 代码通过 Lint 检查（`./gradlew lint`）
- [ ] 添加了必要的测试
- [ ] 更新了相关文档（README、ARCHITECTURE、API 文档等）
- [ ] Commit 信息遵循规范
- [ ] PR 描述清晰（做了什么、为什么、如何测试）

## 目录结构约定

新增功能时，请遵循以下目录结构：

- **UI 组件**：`ui/components/YourComponent.kt`
- **页面**：`ui/screens/YourScreen.kt`
- **服务**：`services/<domain>/YourService.kt`
- **数据模型**：`data/models/YourModel.kt`
- **Repository**：`data/repository/YourRepository.kt`
- **ViewModel**：`ui/screens/YourViewModel.kt`

## 架构原则

1. **单向数据流**：UI → ViewModel → Repository → DataSource
2. **依赖注入**：所有依赖通过 Hilt 注入，不使用静态单例
3. **响应式**：使用 Flow/StateFlow 暴露状态，不使用 LiveData
4. **组合优于继承**：Compose 组件使用组合模式
5. **关注点分离**：UI 只负责渲染，业务逻辑在 ViewModel/Repository

## 测试策略

- **单元测试**：核心业务逻辑（Service、Repository、ViewModel）
- **集成测试**：API 适配层、数据库操作
- **UI 测试**：关键用户流程（登录、发帖、回复）

运行测试：
```bash
./gradlew test               # 单元测试
./gradlew connectedAndroidTest  # 集成测试（需连接设备）
```

## 常见问题

**Q: WebView 相关代码如何测试？**
A: WebView 操作需要在 Instrumented Test 中测试（`androidTest` 目录），单元测试中可以 Mock WebViewNetworkBridge。

**Q: 如何调试 Cloudflare 挑战？**
A: 在 `ChallengeRecoveryCoordinator` 中添加日志，使用 Charles/Proxyman 抓包查看请求。

**Q: 推送功能需要配置什么？**
A: 需要自建中继服务器（见 `server/README.md`），并在 `app/src/main/assets/relay-config.json` 配置中继地址。

## 行为准则

- 尊重所有贡献者
- 建设性地讨论问题
- 遵守开源社区礼仪

## 许可证

贡献的代码将以 MIT License 发布。

---

有任何问题？欢迎在 Issue 中提问或加入讨论！
