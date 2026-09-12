# Now in Android 风格架构迁移设计

## 目标

在保留 ArkDO Android 现有功能、WebView 网络桥接、Cloudflare 处理和未提交业务改动的前提下，将单模块 MVVM 项目渐进式迁移为接近 Google Now in Android 的多模块、单向数据流架构。

首期范围是建立可持续扩展的模块边界，并迁移通知垂直切片。首期完成后，通知页面仍然保持现有用户行为，但其 UI、ViewModel、Repository、API 依赖和模型不再依赖 `app` 内的横向服务目录。

## 现状与约束

- 当前项目只有 `:app` 模块。
- 当前已经使用 Jetpack Compose、Hilt、Coroutines、Flow、Room、DataStore 和 Navigation Compose。
- 当前业务代码主要位于 `data/`、`services/`、`ui/` 三个横向目录。
- `ArkWebNetworkBridge` 是访问社区 API 和处理 Cloudflare 会话的关键基础设施，首期只抽象其接口，不重写实现。
- 当前工作树存在用户未提交修改；迁移不得覆盖或回滚这些修改。
- 首期不引入新的业务功能，不迁移首页、登录、主题详情和设置页面。

## 目标架构

```text
app/
  Application, MainActivity, top-level navigation, DI assembly

core/
  common/       shared Kotlin and coroutine utilities
  model/        stable business models shared by data and features
  network/      network abstractions and WebView bridge implementation
  data/         repositories and data-source composition
  datastore/    persistent preferences and session settings
  database/     Room database and DAOs when offline cache is introduced
  designsystem/ shared Compose theme and components
  ui/           shared UI state helpers and app-level UI primitives

feature/
  notification/ notification screen, ViewModel, feature contracts
  home/         future migration target
  auth/         future migration target
  topic/        future migration target
  profile/      future migration target
  settings/     future migration target
```

首期只创建真正需要的模块：`:core:common`、`:core:model`、`:core:network`、`:core:data`、`:core:datastore`、`:core:designsystem` 和 `:feature:notification`。空的未来 feature 目录不作为本次代码改动创建。

依赖方向固定为：

```text
:app -> :feature:notification -> :core:data -> :core:network
                                      |          -> :core:model
                                      -> :core:datastore
:core:designsystem -> :core:common / :core:model
```

Feature 模块不得依赖 `:app` 或其他 feature 模块。`app` 可以依赖多个 feature，并负责把 feature 的导航入口注册到顶层 NavHost。

## 组件职责

### `:core:model`

存放跨模块稳定的业务模型。通知模型从当前 `data/models/NotificationModels.kt` 中拆出为不依赖 API DTO 和 Compose 的模型。网络响应解析所需的 DTO 留在 `:core:network` 或 `:core:data`，并通过 mapper 转换为业务模型。

### `:core:network`

提供 `JsonNetworkClient` 等最小接口，以及现有 `ArkWebNetworkBridge`、Challenge 和 WebView 相关实现。上层只能依赖接口，不直接调用全局 bridge 单例。首期允许通过 Hilt 绑定现有实现，确保运行行为不变。

### `:core:data`

提供 Repository 接口和实现，负责 API 调用、响应解析、错误转换以及未来的本地/远程数据源组合。通知功能暴露 `NotificationRepository`，ViewModel 不直接依赖 `NotificationApi`。

### `:core:datastore`

保留已有偏好和会话持久化能力的迁移位置。首期只迁移通知 Repository 所需的最小依赖，不将所有现有 SharedPreferences/DataStore 一次性搬迁。

### `:core:designsystem`

承载跨 feature 的主题、颜色、排版和共享 Compose 组件。首期只迁移通知页面实际使用的共享 UI 依赖；页面私有组件留在 feature 内，避免建立无意义的公共 API。

### `:feature:notification`

包含通知页面、`NotificationViewModel`、通知 UI state、用户事件和 feature 导航入口。页面只消费 `NotificationUiState` 并向 ViewModel 发送事件。ViewModel 通过 `StateFlow` 暴露状态，并使用 `SavedStateHandle` 读取导航参数（若页面需要）。

## 数据流

```text
NotificationRoute
  -> NotificationViewModel
  -> NotificationRepository
  -> JsonNetworkClient
  -> ArkWebNetworkBridge
  -> DTO / mapper
  -> NotificationUiState
  -> Compose UI
```

状态至少区分初始加载、加载成功、空数据和错误；刷新操作不得让 UI 自己发起网络请求。单次导航、Toast 等一次性效果通过明确的 effect 流或回调表达，不把新的全局事件总线引入 feature。

## 错误处理

- 网络层将 HTTP、Cloudflare、解析和连接失败转换为稳定错误类型。
- Repository 不把底层异常类型泄漏给 UI。
- ViewModel 将错误映射为用户可展示的状态，并支持重试。
- Challenge 仍由应用级协调器处理；通知 feature 只接收 Repository 返回的可处理错误。
- 日志继续使用现有日志设施，首期不改变日志落盘行为。

## 测试策略

- `:core:data`：使用假的 `JsonNetworkClient` 测试通知响应解析、成功、空响应和网络错误转换。
- `:feature:notification`：使用假的 `NotificationRepository` 测试 ViewModel 的初始状态、加载成功、加载失败和重试行为。
- `:feature:notification`：保留现有 Compose UI 测试基础，至少验证页面在成功状态展示通知标题，在错误状态展示重试入口。
- 每个迁移步骤先新增能失败的测试，再写最小实现。
- 每个阶段运行对应单元测试和 `:app:compileDebugKotlin`；最终运行完整 `:app:testDebugUnitTest` 和 Debug 构建。

## 分阶段迁移

1. 建立模块骨架、统一版本依赖和基础模块依赖方向。
2. 抽取稳定模型、网络接口和通知 Repository。
3. 迁移通知 ViewModel 与 Compose 页面。
4. 将 app 顶层导航改为调用通知 feature 的入口，删除旧通知实现的直接依赖。
5. 验证完整构建和测试，记录下一阶段首页迁移的边界。

首期不做全量目录重命名，不引入额外 UseCase 层，不重写 WebView bridge，不将所有 service 强制拆成模块。

## 验收标准

- `:app`、`:core:*` 和 `:feature:notification` 可独立解析并成功编译。
- 通知页面的加载、空态、错误、重试、打开主题和打开用户行为与迁移前一致。
- 通知 feature 不直接依赖 `ArkWebNetworkBridge`、`NotificationApi` 或 `app`。
- 单元测试覆盖 Repository 和 ViewModel 的主要状态转换。
- 现有未提交改动不被覆盖，所有迁移变更可通过 Git diff 清晰审查。
