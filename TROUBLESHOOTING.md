# ArkDO Android 故障排查指南

## 问题：首页加载失败

### 症状
- 应用启动后首页一直加载
- 30 秒后弹出 "Private DNS 配置引导" 页面
- 日志显示：`net::ERR_CONNECTION_CLOSED`

### 根本原因
**DNS 污染**：`linux.do` 在中国大陆受到 DNS 污染，导致 WebView 无法连接。

### 解决方案

#### 方案 1：配置 Private DNS（推荐给最终用户）

1. **打开 Android 系统设置**
   - 进入「设置」→「网络和互联网」→「Private DNS」
   - 或直接搜索「Private DNS」

2. **选择 Private DNS 模式**
   - 点击「Private DNS 提供商主机名」

3. **输入 DNS 服务器**（任选其一）
   - `dns.alidns.com` - 阿里云公共 DNS（推荐）
   - `dot.pub` - 腾讯 DNSPod
   - `dns.google` - Google DNS

4. **保存并重启应用**

#### 方案 2：使用 VPN 或代理（开发测试）

- 开启系统级 VPN 或代理
- WebView 会自动使用代理连接

#### 方案 3：修改 hosts（开发调试）

```bash
# 需要 root 权限
adb root
adb remount
adb shell "echo '172.67.177.165 linux.do' >> /etc/hosts"
adb shell "echo '172.67.177.165 cdn.ldstatic.com' >> /etc/hosts"
adb reboot
```

### 验证是否成功

配置完成后，打开应用并查看日志：

```bash
adb logcat -s "ArkDO:*" | grep -E "onPageFinished|canFetch=true|Bridge ready"
```

**成功的日志**应该包含：
```
I ArkWebNetworkBridge: attachHost: onPageFinished url=https://linux.do/session/csrf.json
I ArkWebNetworkBridge: attachController: after setExecutor
V ArkWebNetworkBridge: canFetch: executor=...WebviewJsonExecutor... result=true
I ArkWebHost: ✅ Bridge ready! Notifying onReady()
```

**失败的日志**会显示：
```
E ArkWebNetworkBridge: onReceivedError url=https://linux.do/session/csrf.json code=-6 msg=net::ERR_CONNECTION_CLOSED
E ArkWebHost: ❌ Timeout: canFetch() still false after 30s
```

---

## 问题：与 fluxdo 的架构差异

### fluxdo（Flutter）
- 使用 `flutter_inappwebview` 加载完整的 linux.do 网站
- 应用本身就是一个 WebView 容器
- 本地运行 DoH 代理服务器，WebView 通过代理访问

### ArkDO-Android（原生 Kotlin）
- 使用隐藏的 1x1 WebView 作为网络桥接
- 所有网络请求通过 WebView 执行 JS fetch
- 主界面是原生 Jetpack Compose UI

### 为什么不能直接对比
两个项目的架构完全不同：
- fluxdo 的首页**就是** WebView
- 你的首页是**原生** Compose UI，只有网络层用 WebView

---

## 长期解决方案（待实现）

参考 fluxdo 的做法，在应用内实现：

1. **本地 DoH 代理服务器**
   - 监听 `localhost:8080` 等端口
   - 接收 HTTP 请求
   - 使用 `DohService` 解析域名
   - 转发请求到真实 IP

2. **配置 WebView 使用本地代理**
   ```kotlin
   val proxyInfo = ProxyInfo.buildDirectProxy("127.0.0.1", 8080)
   ProxyController.getInstance().setProxyOverride(proxyInfo, ...)
   ```

3. **自动启动和管理代理生命周期**

这需要大量工作，当前阶段建议：
- **开发测试**：使用 VPN 或配置 Private DNS
- **用户使用**：引导配置 Private DNS（已实现）

---

## 常见问题

### Q: 为什么不在代码层面解决 DNS 问题？
A: WebView 使用 Chromium 内核，无法直接拦截其 DNS 查询。需要实现本地代理服务器，工作量较大。

### Q: 配置 Private DNS 后还是无法连接怎么办？
A: 
1. 确认 Private DNS 设置生效（设置页面应显示"已连接"）
2. 尝试切换其他 DNS 服务器
3. 检查网络连接是否正常
4. 清除应用数据后重试

### Q: 为什么 fluxdo 没有这个问题？
A: fluxdo 内置了本地 DoH 代理服务器，自动处理 DNS 污染问题。

---

## 开发建议

### 当前阶段（MVP）
- ✅ 引导用户配置 Private DNS
- ✅ 提供详细的配置说明
- ✅ 检测连接状态并给出反馈

### 下一阶段
- ⬜ 实现本地 DoH 代理服务器
- ⬜ 自动配置 WebView 代理
- ⬜ 提供应用内一键配置方案

### 技术栈建议
如果要实现本地代理，可以考虑：
- 使用 OkHttp + DoH 实现简单的 HTTP 代理
- 参考 clash 或 v2ray 的代理实现
- 或者集成现有的代理库
