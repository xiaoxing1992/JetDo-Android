# Home Feature 模块化设计

## 目标

将首页核心话题列表迁移到 Now in Android 风格的 `feature:home` 垂直切片，覆盖最新、热门、未读三个 feed，保留现有请求路径、分页、刷新和话题跳转行为。

## 范围

本轮包含：

- `core:model` 中与首页列表相关的稳定模型
- `core:data` 中的 `HomeRepository` 契约
- app 中现有 WebView 网络桥接到新 Repository 的 Hilt 绑定
- `feature:home` 的 ViewModel、Route 和 Compose 列表页面
- RootTab 首页入口切换到新 feature
- Repository、ViewModel 和核心 UI 状态测试

本轮不包含：

- 分类筛选抽屉和标签联想
- MessageBus 实时新话题提示
- 版本公告、复杂 FAB 动画和首页设置
- TopicDetail、发帖和搜索迁移

这些能力继续由现有 app 首页代码维护，待核心列表稳定后单独迁移。新 feature 已完成基础实现，但当前暂不替换 RootTab 首页入口，以保持现有 UI 和交互不变。

## 模块边界

```text
app -> feature:home -> core:data -> core:model/core:network
```

`feature:home` 不引用 `app` 包。`core:data` 通过 `core:network` 的 `JsonNetworkClient` 访问接口，app 只提供旧 WebView Bridge 的适配器和 `HomeRepository` Hilt 绑定。

## 状态与错误

Repository 返回稳定的 `HomePageResult`，不泄漏旧网络类型。ViewModel 管理首载、刷新、加载更多、当前 feed、错误和去重。请求失败保留已有列表，首载失败展示重试状态。

## 验证

- Repository 测试覆盖成功映射、网络失败和分页参数
- ViewModel 测试覆盖首载、刷新、追加分页、失败重试和重复话题去重
- Compose 页面覆盖首载、刷新、分页、空态和错误态；UI 像素级测试留到设计系统稳定后补充
- `:app:assembleDebug` 验证完整集成
