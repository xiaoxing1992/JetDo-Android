# JetDo 自建服务器

服务于 JetDo Android 的两条独立链路：

1. **系统推送**：**Web Push 标准（RFC 8291）** + 自建中继 + **Firebase Cloud Messaging (FCM)**
2. **崩溃上报**：ACRA `httpSender` → `POST /crash`（本地磁盘，有配额）

两条链路互不依赖：推送的 VAPID 密钥或 Firebase 凭证缺失时，服务仍会启动，只是推送失效，
崩溃上报照常工作。

## 架构

```
推送：LINUX DO ──Web Push（加密）──▶ 自建中继 ──解密──▶ FCM ──▶ Android 设备
崩溃：JetDo + ACRA ──gzip JSON──▶ 自建中继 /crash ──▶ ./crashes/*.json（100MB 上限）
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

崩溃上报相关（全部有默认值，不配也能跑）：

```bash
# 报告存放目录
CRASH_DIR=./crashes
# 磁盘硬上限。超过就批量淘汰最旧的，删到 85% 为止
CRASH_MAX_BYTES=104857600          # 100 MB
# 单份报告上限（解压后），超了返回 413
CRASH_MAX_REPORT_BYTES=2097152     # 2 MB
# 每 IP 每分钟上报数上限，0 表示关闭
CRASH_RATE_PER_MIN=30
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

> 生产务必走 `uvicorn --proxy-headers --forwarded-allow-ips=127.0.0.1`（脚本里的 unit 已包含）。
> 否则 `request.client.host` 恒为 `127.0.0.1`，「每 IP 每分钟 N 条」会退化成一个全局桶，
> 一台设备崩溃循环就能把所有人的上报 429 掉。
>
> nginx 侧还要注意 `client_max_body_size`：默认 1m，会在 ACRA 的报告到达 relay 之前就 413 掉。

### 6. 配置客户端

推送中继（可选，崩溃上报不需要）：

将中继地址和公钥填入 `app/src/main/assets/relay-config.json`：

```json
{
  "relay_endpoint": "https://your-relay-server.com/subscribe",
  "relay_public_key": "从 vapid_public.pem 读取的 Base64 编码公钥"
}
```

崩溃上报：在项目 `local.properties` 里填端点，构建时写入 `BuildConfig.ACRA_URI`：

```properties
jetdo.acra.uri=https://relay.example.com/crash
```

留空则**完全跳过 ACRA 初始化**——F-Droid 构建机上没有 `local.properties`，
拿到的就是不联网上报的产物。

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

### POST /crash

接收 ACRA 崩溃报告（客户端 `httpSender`）。默认 gzip + JSON，服务端不做格式强耦合——
能解析成 JSON 就内嵌到信封里，解析不了就存原文。

**请求体：** ACRA 报告，`Content-Encoding: gzip` 可选

**响应：**

```json
{
  "status": "ok",
  "file": "crash-20261026T065411-5e3baba3.json",
  "size": 18422,
  "evicted": 0,
  "total_bytes": 5242880,
  "total_files": 51,
  "max_bytes": 104857600
}
```

**状态码：**

| 码 | 含义 |
|---|---|
| 200 | 已落盘 |
| 400 | 空体，或 Content-Encoding 解不开 |
| 413 | 单份报告超过 `CRASH_MAX_REPORT_BYTES`，或超过配额的 1/2 |
| 429 | 该 IP 超过 `CRASH_RATE_PER_MIN` |

**磁盘保护是三层叠加的：**

1. **每 IP 每分钟限流**（`RateLimiter`，滑动窗口）——崩溃循环时一台设备一分钟能发几十条，
   客户端 ACRA 的 `limiter` 只是礼貌，服务端这份才是硬约束。
2. **单份大小上限** —— 且不超过配额的 1/2。这条不只是防大包：单份报告若大于淘汰目标，
   会触发「为了腾空间把其他报告全删掉」的极端情况，所以从入口就拒掉。
3. **总量配额 + 批量淘汰** —— 见下。

### GET /crash/stats

查看占用情况，用来确认配额和淘汰是否按预期工作。

```json
{
  "files": 51,
  "total_bytes": 5242880,
  "max_bytes": 104857600,
  "trim_target_bytes": 89128960,
  "usage_ratio": 0.05,
  "oldest": "crash-20261020T110203-ab12cd34.json",
  "evicted_since_start": 0
}
```

## 崩溃报告的磁盘策略

淘汰逻辑在 `crash_store.py`，不依赖任何第三方库。

**为什么不用数据库**：崩溃报告是「一次写入、偶尔翻阅、按时间淘汰」的日志型数据。
放文件系统里，出问题时 `ls` / `grep` / `scp` 直接拿走，不用先把服务跑起来查库。
几千份以内没有索引不是问题。

**弛豫区间**：配额 100MB，但淘汰目标是 85MB。

```
写入直到 100MB ──▶ 触发淘汰 ──▶ 批量删最旧的直到 ≤85MB ──▶ 再攒 15MB 才触发下一次
```

没有这个区间的话，配额一满就会变成「每来一份新报告都要删一批」，淘汰从例外变成常态，
每次写入都附带一次 IO 风暴。

**批量删**：每轮删掉当前文件数的 10%（至少 1 个），而不是一份一份 unlink。
1000 份报告时一轮删 100 份。

**永远不淘汰最新那份**：循环带 `len(entries) > 1` 的硬保护。
否则单份报告一旦大于淘汰目标，循环会把目录删空——那不是腾空间，是把现场全丢了。

进程重启会重新扫描目录重建索引，配额照样生效（启动时若已超限会先纠一次）。

## 客户端配置崩溃上报

崩溃上报端点在构建期从 `local.properties` 注入（该文件不入库）：

```properties
# local.properties
jetdo.acra.uri=https://your-relay-server.com/crash
```

不配这一行 → `BuildConfig.ACRA_URI` 为空串 → 客户端整体跳过 ACRA 初始化，
产物不带任何远程上报。F-Droid 构建机没有 `local.properties`，正是靠这一点
天然得到合规产物。

代码见 `JetDoApplication.initCrashReportingIfAllowed()`：端点为空、用户未同意隐私政策、
已初始化过——三个条件任一命中就静默跳过。

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
