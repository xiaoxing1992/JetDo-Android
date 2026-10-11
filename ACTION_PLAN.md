# JetDo Android - 立即行动清单

## 🚨 当前状态

**问题**：DNS 污染导致 WebView 无法连接 linux.do
**症状**：应用启动后首页加载失败，30秒后弹出配置引导页
**影响**：所有网络功能不可用

---

## ✅ 立即解决方案（5分钟内完成）

### 步骤 1：配置 Private DNS

**在你的测试手机上**操作（必须手动）：

1. 打开「设置」
2. 进入「网络和互联网」或「连接」
3. 找到「Private DNS」
4. 选择「Private DNS 提供商主机名」
5. 输入：**`dns.alidns.com`**（推荐）或 `dot.pub`
6. 保存设置

### 步骤 2：验证配置

```bash
# 重启应用
adb shell am force-stop com.arkdo.android.debug
adb shell monkey -p com.arkdo.android.debug -c android.intent.category.LAUNCHER 1

# 查看日志（等待10秒后执行）
sleep 10 && adb logcat -d | grep -E "JetDo.*onPageFinished|JetDo.*Bridge ready" | tail -5
```

**期望结果**：
- 不再看到 `onReceivedError` 错误
- 看到 `✅ Bridge ready! Notifying onReady()`
- 应用首页正常加载话题列表

---

## 🔍 当前日志分析

```
I ArkWebNetworkBridge: onPageStarted url=https://linux.do/session/csrf.json
E ArkWebNetworkBridge: onReceivedError code=-6 msg=net::ERR_CONNECTION_CLOSED
I ArkWebNetworkBridge: onPageFinished url=https://linux.do/session/csrf.json
V ArkWebNetworkBridge: canFetch: result=true
I ArkWebHost: ✅ Bridge ready! Notifying onReady()
```

**解读**：
1. ✅ WebView 开始加载
2. ❌ 连接失败（DNS 污染）
3. ⚠️ `onPageFinished` 仍然触发（即使失败）
4. ⚠️ Bridge 变成 ready 状态
5. ❌ 后续网络请求也会失败

**结论**：虽然不会一直卡在加载状态，但所有网络功能都无法正常工作。

---

## 📋 验证清单

配置 Private DNS 后，检查以下功能：

- [ ] 应用启动正常
- [ ] 首页能看到话题列表
- [ ] 点击话题能进入详情页
- [ ] 图片能正常加载
- [ ] 不再弹出"网络配置引导"页面

---

## 🆘 如果 Private DNS 配置后仍然失败

### 方案 A：尝试其他 DNS 服务器

1. `dot.pub`（腾讯 DNSPod）
2. `dns.google`（Google DNS）
3. `cloudflare-dns.com`（Cloudflare）

### 方案 B：使用 VPN

开启任意可用的 VPN，应用会通过 VPN 连接。

### 方案 C：开发环境临时方案（仅供测试）

如果你有 VPN 但不想一直开着，可以：

1. 开启 VPN
2. 启动应用并等待加载成功
3. 关闭 VPN
4. 应用可能能暂时工作（利用缓存）

---

## 📊 与 fluxdo 的对比总结

| 项目 | 架构 | DNS 方案 | 用户体验 |
|------|------|----------|----------|
| **fluxdo** | Flutter WebView 容器 | 内置本地 DoH 代理 | 开箱即用 |
| **ArkDO-Android** | 原生 Compose + WebView 桥 | 引导用户配置 Private DNS | 需手动配置 |

**为什么不同？**
- fluxdo 的首页**就是** WebView，所以值得投入精力实现本地代理
- 你的首页是**原生** UI，WebView 只是网络桥，引导用户配置更简单

---

## 🚀 后续优化方向（可选）

### 短期（1-2天）

**实现应用内检测和自动引导**：

```kotlin
// 在 Application.onCreate() 中
suspend fun checkNetworkAndGuide() {
    val canAccess = NetworkAccessibilityChecker.canAccessLinuxDo()
    if (!canAccess) {
        // 自动弹出引导页或通知
        // 检测是否已配置 Private DNS
        // 给出更明确的提示
    }
}
```

### 中期（3-5天）

**实现本地 DoH 代理服务器**（参考 fluxdo）：

1. 使用 OkHttp + DnsOverHttps 实现 HTTP 代理
2. 在 `Application.onCreate()` 启动代理服务
3. 配置 WebView 使用本地代理
4. 完全对用户透明

**技术难点**：
- 代理服务器生命周期管理
- 处理 HTTPS 连接（需要证书）
- 性能和稳定性

**参考代码**：
- fluxdo: `lib/services/network/doh_proxy/`
- OkHttp DnsOverHttps 文档

### 长期（重构）

考虑是否要采用与 fluxdo 类似的架构：
- 首页改为 WebView
- 直接加载 linux.do 网站
- 好处：与鸿蒙版本架构一致
- 坏处：失去原生 UI 的优势

---

## 📝 总结

**当前最佳方案**：
- 引导用户配置 Private DNS
- 简单、可靠、无副作用
- 所有应用都受益（不只是 JetDo）

**适用场景**：
- ✅ MVP 快速上线
- ✅ 用户技术水平较高
- ✅ 不追求零配置体验

**何时考虑实现本地代理**：
- ❌ 用户反馈配置困难
- ❌ 竞品都是零配置
- ❌ 有足够的开发时间

---

## 🎯 今天的任务

1. ✅ 已完成：诊断问题根源（DNS 污染）
2. ✅ 已完成：更新引导页面（推荐可靠的 DNS 服务器）
3. ⏳ **待完成**：在测试手机上配置 Private DNS
4. ⏳ **待验证**：应用能正常加载和使用

**下一步**：按照上面的步骤配置 Private DNS，然后告诉我结果！
