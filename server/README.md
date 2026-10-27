# JetDo 自建服务器

服务于 JetDo Android 的系统推送：**Web Push 标准（RFC 8291）** + 自建中继 + **Firebase Cloud Messaging (FCM)**。

> 这里**没有**崩溃上报。客户端不做任何远程上报，崩溃现场只写本机日志
> （「设置 → 诊断日志」里可自行查看/导出）。此前曾接过 ACRA + `/crash` 自建接收端，
> 因为要把中继域名写进隐私政策与 APK，等于把基础设施和 AI 网关放在同一个域名区里，
> 已整体移除。

## 架构

```
推送：LINUX DO ──Web Push（加密）──▶ 自建中继 ──解密──▶ FCM ──▶ Android 设备
```

**推送为什么需要中继？**

1. Discourse 的 Web Push 订阅需要提供 `endpoint`（接收推送的 URL）
2. Android 原生无法直接接收 Web Push（只有浏览器支持）
3. 中继接收 Web Push → 解密 → 转发给 FCM → 设备收到通知

## 部署中继

### 1. 环境要求

- Python 3.9+
- Firebase Admin SDK 账号（从 Firebase Console 下载 `service-account.json`）
- 公网可访问的服务器（需要 HTTPS）

### 2. 安装依赖

```bash
cd server
pip install -r requirements.txt
```

### 3. 生成 VAPID 密钥对

```bash
python generate_vapid_keys.py
```

会生成：
- `vapid_private.pem`（私钥，服务端持有，解密用）
- `vapid_public.pem`（公钥，客户端提交给 Discourse）

### 4. 配置环境变量

创建 `.env` 文件：

```bash
# Firebase Admin SDK 凭证路径
FIREBASE_CREDENTIALS_PATH=./service-account.json

# VAPID 密钥路径
VAPID_PRIVATE_KEY_PATH=./vapid_private.pem
VAPID_PUBLIC_KEY_PATH=./vapid_public.pem

# 中继服务端口
PORT=8080

# 允许的来源（Discourse 服务器）
ALLOWED_ORIGINS=https://linux.do
```

### 5. 启动中继

开发机直接跑：

```bash
python relay.py
```

**生产环境用一键部署脚本**（Debian/Ubuntu + nginx，自动装 systemd unit、证书、反代）：

```bash
sudo bash deploy/deploy.sh relay.example.com
```

它只做加法：新增 `/opt/jetdo-relay`、一个 systemd unit、一个 `conf.d/jetdo-relay.conf`，
不动任何已有站点，重复执行安全。域名 A 记录与安全组需要提前配好。

> 生产务必走 `uvicorn --proxy-headers --forwarded-allow-ips=*`（脚本里的 unit 已包含），
> 否则 `request.client.host` 恒为反代地址，日志里的来源 IP 全失真。
> 安全边界靠两层兜住：ufw 只放行容器网段进端口；nginx 侧用
> `X-Forwarded-For $remote_addr` **覆盖**而非追加，客户端伪造不了。

### 6. 配置客户端

将中继地址和公钥填入 `app/src/main/assets/relay-config.json`：

```json
{
  "relay_endpoint": "https://your-relay-server.com/subscribe",
  "relay_public_key": "从 vapid_public.pem 读取的 Base64 编码公钥"
}
```

不配置则客户端不启用推送，其余功能不受影响。

## API 接口

### POST /subscribe

注册设备推送订阅

**请求体：**

```json
{
  "fcm_token": "设备 FCM Token",
  "user_id": 123,
  "username": "用户名"
}
```

**响应：**

```json
{
  "endpoint": "https://your-relay-server.com/push/{device_id}",
  "keys": {
    "p256dh": "客户端公钥（Base64）",
    "auth": "客户端认证密钥（Base64）"
  }
}
```

客户端需要将 `endpoint` 和 `keys` 提交给 Discourse 的 `/push_notifications/subscribe.json`。

### POST /push/{device_id}

接收 LINUX DO 的 Web Push 推送（Discourse 服务器调用）

**请求体：** 加密的 Web Push 载荷（二进制）

**响应：** `200 OK`

中继会：
1. 用 VAPID 私钥解密推送内容
2. 提取通知标题、正文、跳转链接
3. 通过 FCM 发送到对应设备

### DELETE /unsubscribe

取消设备推送订阅

**请求体：**

```json
{
  "fcm_token": "设备 FCM Token"
}
```

### GET /health

健康检查，也是这个域名唯一对外暴露的端点（其余路径一律 404）。

```json
{
  "status": "ok",
  "registered_devices": 3
}
```

## 安全注意事项

1. **HTTPS 必须**：Web Push 标准要求 endpoint 必须是 HTTPS
2. **私钥保密**：`vapid_private.pem` 泄露会导致推送可被伪造
3. **来源校验**：配置 `ALLOWED_ORIGINS` 限制推送来源
4. **限流**：生产环境建议对 `/push` 端点做限流

## 故障排查

**推送没收到？**

1. 检查中继日志：`tail -f relay.log`
2. 检查 FCM Token 是否有效（在 Firebase Console 查看）
3. 检查 Discourse 推送订阅状态（用户设置 → 通知）
4. 检查客户端 `relay-config.json` 配置是否正确

**推送解密失败？**

- 确认 VAPID 公钥与 Discourse 订阅时提交的一致
- 确认私钥文件完整且格式正确

## 开发与测试

**本地测试（使用 ngrok 暴露端口）：**

```bash
# 启动中继
python relay.py

# 另一个终端启动 ngrok
ngrok http 8080

# 将 ngrok 提供的 HTTPS URL 填入客户端配置
```

**模拟推送（用于测试解密逻辑）：**

```bash
python test_push.py --device-id <device_id> --title "测试标题" --body "测试内容"
```

## 依赖项

见 `requirements.txt`：

```
fastapi==0.109.0
uvicorn[standard]==0.27.0
firebase-admin==6.4.0
cryptography==42.0.0
py-vapid==1.9.0
python-dotenv==1.0.0
```

## License

MIT License
